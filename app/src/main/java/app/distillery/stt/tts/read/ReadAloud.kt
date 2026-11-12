// SPDX-License-Identifier: Apache-2.0
package app.distillery.stt.tts.read

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import app.distillery.stt.DistilleryApplication
import app.distillery.stt.tts.KokoroSynth
import app.distillery.stt.tts.KokoroVoice
import app.distillery.stt.tts.SpeechChunks
import app.distillery.stt.tts.TextNormalizer
import app.distillery.stt.tts.TtsRuntime
import app.distillery.stt.tts.VoiceNotInstalledException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlin.coroutines.coroutineContext

/**
 * Reads a text aloud with Kokoro, sentence by sentence, in the app itself
 * rather than through Android's text-to-speech system, so it can pause, skip
 * and jump to any sentence. [ReadAloudService] keeps it playing, with
 * controls in a notification and on the lock screen, while it isn't on screen.
 */
object ReadAloud {

    private const val TAG = "DistilleryReadAloud"
    val SPEEDS = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)

    /** One sentence, and where it is in [State.text]. */
    data class Sentence(val text: String, val start: Int, val end: Int, val paragraph: Int)

    enum class Status { Ready, Preparing, Playing, Paused, Finished }

    data class State(
        val title: String?,
        val text: String,
        val sentences: List<Sentence>,
        val index: Int,
        val status: Status,
        val voice: KokoroVoice,
        val speed: Float,
        val error: String? = null,
    )

    private val _state = MutableStateFlow<State?>(null)
    /** What's loaded, or null when nothing is. */
    val state: StateFlow<State?> = _state.asStateFlow()

    private val paused = MutableStateFlow(false)
    private var job: Job? = null
    private var focus: AudioFocusRequest? = null

    // ------------------------------------------------------------- control

    /** Loads [text] in place of whatever was loaded, ready to play from the start. */
    fun load(context: Context, text: String, title: String? = null) {
        stopPlayback(context)
        val runtime = TtsRuntime.get(context)
        val installed = runtime.store.installed.value
        val voice = runtime.settings.defaultVoice.value.takeIf { it in installed } ?: installed.firstOrNull()
            ?: runtime.settings.defaultVoice.value
        val previous = _state.value
        _state.value = State(
            title = title,
            text = text,
            sentences = split(text),
            index = 0,
            status = Status.Ready,
            voice = previous?.voice?.takeIf { it in installed } ?: voice,
            speed = previous?.speed ?: 1f,
        )
    }

    fun play(context: Context, from: Int? = null) {
        val s = _state.value ?: return
        if (s.sentences.isEmpty()) return
        val start = (from ?: if (s.status == Status.Finished) 0 else s.index).coerceIn(0, s.sentences.lastIndex)
        job?.cancel()
        paused.value = false
        _state.value = s.copy(index = start, status = Status.Preparing, error = null)
        val app = context.applicationContext as DistilleryApplication
        if (!requestFocus(app)) {
            _state.update { it?.copy(status = Status.Paused) }
            return
        }
        app.startForegroundService(Intent(app, ReadAloudService::class.java))
        job = app.appScope.launch(Dispatchers.Default) { run(app, start) }
    }

    fun pause() {
        val s = _state.value ?: return
        if (s.status == Status.Playing || s.status == Status.Preparing) {
            paused.value = true
            _state.value = s.copy(status = Status.Paused)
        }
    }

    fun resume(context: Context) {
        val s = _state.value ?: return
        when {
            s.status == Status.Paused && job?.isActive == true -> {
                if (!requestFocus(context)) return
                paused.value = false
                _state.value = s.copy(status = Status.Playing)
            }
            s.status != Status.Playing && s.status != Status.Preparing -> play(context)
        }
    }

    fun toggle(context: Context) {
        when (_state.value?.status) {
            Status.Playing, Status.Preparing -> pause()
            else -> resume(context)
        }
    }

    fun next(context: Context) = jump(context, +1)
    fun previous(context: Context) = jump(context, -1)

    /** Starts reading at sentence [index]. */
    fun seek(context: Context, index: Int) = play(context, index)

    fun setSpeed(context: Context, speed: Float) = change(context) { it.copy(speed = speed) }
    fun setVoice(context: Context, voice: KokoroVoice) = change(context) { it.copy(voice = voice) }

    /** Stops playing; the text stays loaded. */
    fun stopPlayback(context: Context) {
        job?.cancel()
        job = null
        paused.value = false
        abandonFocus(context)
        _state.update { s -> s?.copy(status = if (s.status == Status.Finished) Status.Finished else Status.Ready) }
    }

    /** Stops and unloads the text. */
    fun close(context: Context) {
        stopPlayback(context)
        _state.value = null
    }

    private fun jump(context: Context, by: Int) {
        val s = _state.value ?: return
        play(context, (s.index + by).coerceIn(0, s.sentences.lastIndex))
    }

    /** Applies a setting; when playing, restarts the current sentence with it. */
    private fun change(context: Context, edit: (State) -> State) {
        val s = _state.value ?: return
        val playing = s.status == Status.Playing || s.status == Status.Preparing
        _state.value = edit(s)
        if (playing) play(context, s.index)
    }

    // ------------------------------------------------------------ playback

    private class Piece(val sentence: Int, val samples: FloatArray, val rate: Int)

    private suspend fun run(context: Context, start: Int) {
        val synth = TtsRuntime.get(context).synth
        val settings = _state.value ?: return
        try {
            coroutineScope {
                // Kokoro makes the next piece while the current one plays.
                val pieces = Channel<Piece>(capacity = 2)
                launch(Dispatchers.IO) {
                    try {
                        for (i in start until settings.sentences.size) {
                            for (piece in make(synth, settings, i)) pieces.send(piece)
                        }
                    } finally {
                        pieces.close()
                    }
                }
                playPieces(pieces)
            }
            _state.update { it?.copy(status = Status.Finished, index = it.sentences.lastIndex) }
            abandonFocus(context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "Reading aloud failed", e)
            val message = when (e) {
                is VoiceNotInstalledException -> "The ${settings.voice.displayName} voice isn't downloaded"
                else -> e.message ?: e::class.java.simpleName
            }
            _state.update { it?.copy(status = Status.Ready, error = message) }
            abandonFocus(context)
        }
    }

    /** One sentence as pieces of audio, each tidied and followed by its pause. */
    private suspend fun make(synth: KokoroSynth, settings: State, index: Int): List<Piece> {
        val spoken = TextNormalizer.normalize(settings.sentences[index].text)
        return SpeechChunks.chunks(listOf(spoken)).mapNotNull { chunk ->
            coroutineContext.ensureActive()
            val (samples, rate) = runInterruptible { synth.synthesize(settings.voice, chunk.text, settings.speed) }
            if (samples.isEmpty() || rate <= 0) null else Piece(index, SpeechChunks.tidy(samples, rate, chunk.pauseMs), rate)
        }
    }

    private suspend fun playPieces(pieces: Channel<Piece>) {
        var track: AudioTrack? = null
        var trackRate = 0
        var written = 0L
        try {
            for (piece in pieces) {
                if (track == null || trackRate != piece.rate) {
                    track?.let { drain(it, written); it.release() }
                    track = newTrack(piece.rate)
                    trackRate = piece.rate
                    written = 0
                    track.play()
                }
                _state.update { s ->
                    s?.copy(index = piece.sentence, status = if (paused.value) Status.Paused else Status.Playing)
                }
                var offset = 0
                val step = piece.rate / 10 // 100 ms at a time, so pausing is quick
                while (offset < piece.samples.size) {
                    if (paused.value) {
                        track.pause()
                        paused.first { !it }
                        track.play()
                    }
                    coroutineContext.ensureActive()
                    val n = minOf(step, piece.samples.size - offset)
                    val t = track
                    runInterruptible { t.write(piece.samples, offset, n, AudioTrack.WRITE_BLOCKING) }
                    offset += n
                    written += n
                }
            }
            track?.let { drain(it, written) }
        } finally {
            track?.let {
                runCatching { it.pause(); it.flush(); it.stop() }
                it.release()
            }
        }
    }

    /** Waits until everything written to [track] has played. */
    private suspend fun drain(track: AudioTrack, written: Long) {
        while (track.playbackHeadPosition.toLong() and 0xffffffffL < written) {
            coroutineContext.ensureActive()
            if (paused.value) {
                track.pause()
                paused.first { !it }
                track.play()
            }
            kotlinx.coroutines.delay(50)
        }
    }

    private fun newTrack(rate: Int): AudioTrack {
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        return AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(min * 2, rate / 2 * 4))
            .build()
    }

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    // --------------------------------------------------------- audio focus

    private fun requestFocus(context: Context): Boolean {
        val audio = context.getSystemService(AudioManager::class.java)
        val request = focus ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            // Spoken words shouldn't be ducked under a notification sound.
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener { change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
                    -> pause()
                }
            }
            .build()
            .also { focus = it }
        return audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus(context: Context) {
        focus?.let { context.getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
    }

    // ----------------------------------------------------------- splitting

    /** Splits [text] into paragraphs at line breaks, and those into sentences, keeping their places. */
    fun split(text: String): List<Sentence> {
        val out = mutableListOf<Sentence>()
        var paragraph = 0
        var cursor = 0
        for (line in text.split('\n')) {
            val lineStart = cursor
            cursor += line.length + 1
            if (line.isBlank()) continue
            var from = lineStart
            for (sentence in KokoroSynth.sentences(line)) {
                val piece = sentence.trim()
                if (piece.isEmpty()) continue
                val at = text.indexOf(piece, from).takeIf { it >= 0 && it < lineStart + line.length } ?: from
                val end = (at + piece.length).coerceAtMost(lineStart + line.length)
                out += Sentence(piece, at, end, paragraph)
                from = end
            }
            paragraph++
        }
        return out
    }
}
