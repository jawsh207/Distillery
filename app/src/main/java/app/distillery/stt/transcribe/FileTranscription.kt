// SPDX-License-Identifier: Apache-2.0
package app.distillery.stt.transcribe

import ai.moonshine.voice.Transcriber
import ai.moonshine.voice.TranscriberOption
import ai.moonshine.voice.TranscriptEvent
import ai.moonshine.voice.TranscriptLine
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import app.distillery.stt.DistilleryApplication
import app.distillery.stt.Notifications
import app.distillery.stt.engine.ModelLicensePolicy
import app.distillery.stt.engine.SttModel
import app.distillery.stt.media.MediaJobs
import app.distillery.stt.service.Punctuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.function.Consumer

/**
 * Transcribes an audio or video file with a downloaded speech model.
 *
 * The file is copied into the cache first (a shared file's permission can end
 * with the screen that received it), decoded with Android's codecs, and fed to
 * a Moonshine transcriber of its own, on a thread of its own, so a long file
 * doesn't hold up dictation in other apps. It runs as a [MediaJobs] job, so
 * it carries on when the user switches apps.
 *
 * With speaker labels on, the transcriber also runs pyannote's speaker models
 * ([app.distillery.stt.engine.SpeakerModel]) and attributes each stretch of a
 * phrase to "Speaker 1", "Speaker 2" and so on, in the order they first speak.
 * Moonshine keeps revising those labels for the last two minutes of audio, so
 * the final labels are the ones its last events carried.
 */
object FileTranscription {

    private const val TAG = "DistilleryTranscribe"

    /** A pause at least this long between phrases starts a new paragraph. */
    private const val PARAGRAPH_GAP_S = 2.0f

    /** Seconds of audio between transcription passes when labelling speakers. */
    private const val SPEAKER_UPDATE_INTERVAL_S = 2.0

    /** The usual subtitle line length. */
    private const val SRT_LINE = 42

    /**
     * One phrase Moonshine heard (or, with speaker labels, the part of one a
     * single speaker said), with its place in the recording. [speaker] counts
     * from 0 in the order speakers first appear; null without speaker labels.
     */
    data class Segment(val startS: Float, val endS: Float, val text: String, val speaker: Int? = null)

    sealed interface State {
        data object Idle : State
        data class Running(val name: String, val model: SttModel, val fraction: Float?, val segments: List<Segment>) : State
        data class Done(val name: String, val model: SttModel, val segments: List<Segment>, val seconds: Float, val tookMs: Long) : State
        data class Failed(val name: String, val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Forgets a finished or failed transcription. */
    fun clear() {
        if (_state.value !is State.Running) _state.value = State.Idle
    }

    /**
     * Starts transcribing [uri], labelling speakers if [speakers] (the speaker
     * models must be downloaded). Returns false if another transcription or export is running.
     */
    fun start(context: Context, uri: Uri, name: String, model: SttModel, speakers: Boolean = false): Boolean {
        val app = context.applicationContext as DistilleryApplication
        val screen = Intent(app, TranscribeActivity::class.java)
        return MediaJobs.start(app, "Transcribing $name", screen) { report ->
            _state.value = State.Running(name, model, null, emptyList())
            val started = SystemClock.elapsedRealtime()
            val input = File(app.cacheDir, "transcribe/input")
            try {
                input.parentFile!!.mkdirs()
                app.contentResolver.openInputStream(uri)?.use { from ->
                    input.outputStream().use { to -> from.copyTo(to, 1 shl 16) }
                } ?: throw java.io.IOException("Couldn't open $name")
                val (segments, seconds) = transcribe(app, input, model, speakers) { fraction, live ->
                    report(fraction)
                    _state.value = State.Running(name, model, fraction, live)
                }
                _state.value = State.Done(name, model, tidy(app, segments), seconds, SystemClock.elapsedRealtime() - started)
                if (!TranscribeActivity.visible) {
                    Notifications.showWorkDone(app, "Transcript ready", name, PendingIntent.getActivity(
                        app, 2, screen.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ))
                }
            } catch (e: CancellationException) {
                _state.value = State.Idle
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "Transcription of $name failed", e)
                _state.value = State.Failed(name, message(e))
            } finally {
                input.delete()
            }
        }
    }

    private suspend fun transcribe(
        app: DistilleryApplication,
        file: File,
        model: SttModel,
        speakers: Boolean,
        onUpdate: (fraction: Float?, segments: List<Segment>) -> Unit,
    ): Pair<List<Segment>, Float> {
        ModelLicensePolicy.requireAllowed(model)
        check(app.modelStore.isInstalled(model)) { "The ${model.displayName} model isn't downloaded" }
        if (speakers) check(app.speakerModel.isInstalled()) { "Speaker labels aren't downloaded" }
        val options = if (speakers) {
            listOf(
                TranscriberOption("identify_speakers", "true"),
                TranscriberOption("diarization_model_dir", app.speakerModel.dir.absolutePath),
            )
        } else {
            emptyList()
        }
        val executor = Executors.newSingleThreadExecutor { Thread(it, "distillery-transcribe") }
        try {
            return withContext(executor.asCoroutineDispatcher()) {
                val context = currentCoroutineContext()
                val transcriber = Transcriber(options).apply {
                    // Each pass with speaker labels re-checks every speaker turn, so
                    // passes are spaced out: nobody is watching live text here.
                    if (speakers) setUpdateInterval(SPEAKER_UPDATE_INTERVAL_S)
                    loadFromFiles(app.modelStore.dirFor(model).absolutePath, model.arch)
                }
                val lines = LinkedHashMap<Long, List<Segment>>()
                var lastUpdate = 0L
                var fraction: Float? = null
                var seconds = 0f
                val listener = Consumer<TranscriptEvent> { event ->
                    val line = when (event) {
                        is TranscriptEvent.LineStarted -> event.line
                        is TranscriptEvent.LineUpdated -> event.line
                        is TranscriptEvent.LineTextChanged -> event.line
                        is TranscriptEvent.LineCompleted -> event.line
                        is TranscriptEvent.LineSpeakersChanged -> event.line
                        else -> null
                    } ?: return@Consumer
                    lines[line.id] = pieces(line)
                }
                transcriber.addListener(listener)
                try {
                    transcriber.start()
                    AudioDecoder.decode(
                        file,
                        cancelled = { !context.isActive },
                        onProgress = { positionUs, durationUs ->
                            seconds = positionUs / 1e6f
                            fraction = if (durationUs > 0) (positionUs.toFloat() / durationUs).coerceIn(0f, 1f) else null
                        },
                        onAudio = { samples, rate ->
                            transcriber.addAudio(samples, rate)
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastUpdate > 500) {
                                lastUpdate = now
                                onUpdate(fraction, lines.values.flatten())
                            }
                        },
                    )
                    if (!context.isActive) throw CancellationException()
                    // Moonshine finishes the open phrase on stop().
                    transcriber.stop()
                    onUpdate(1f, lines.values.flatten())
                } finally {
                    transcriber.removeListener(listener)
                    transcriber.close()
                }
                lines.values.flatten().filter { it.text.isNotEmpty() } to seconds
            }
        } finally {
            executor.shutdown()
        }
    }

    /**
     * A line as segments: the whole line, or with speaker labels one segment per
     * speaker span. Span ends are UTF-8 byte offsets into the line's text.
     */
    private fun pieces(line: TranscriptLine): List<Segment> {
        val text = line.text.orEmpty()
        val spans = line.speakerSpans
        if (spans.isNullOrEmpty()) return listOf(Segment(line.startTime, line.startTime + line.duration, text.trim()))
        val bytes = text.toByteArray(Charsets.UTF_8)
        val pieces = spans.sortedBy { it.startChar }.mapNotNull { span ->
            val from = span.startChar.toInt().coerceIn(0, bytes.size)
            val to = span.endChar.toInt().coerceIn(from, bytes.size)
            val part = String(bytes, from, to - from, Charsets.UTF_8).trim()
            if (part.isEmpty()) null else Segment(span.startTime, span.startTime + span.duration, part, span.speakerIndex)
        }
        // Text no span covers (rare) stays with the line rather than being lost.
        return pieces.ifEmpty { listOf(Segment(line.startTime, line.startTime + line.duration, text.trim())) }
    }

    /** Applies the user's dictation settings: punctuation off, offensive words masked. */
    private fun tidy(app: DistilleryApplication, segments: List<Segment>): List<Segment> = segments.map { s ->
        var text = s.text
        if (!app.settings.punctuation) text = Punctuation.stripAll(text)
        if (app.settings.maskOffensiveWords) text = app.offensiveWords.mask(text)
        s.copy(text = text)
    }.filter { it.text.isNotEmpty() }

    /**
     * The transcript as text: a new paragraph at each long pause and, with
     * speaker labels, each change of speaker ("Speaker 2: …"); optionally each
     * paragraph with its time.
     */
    fun render(segments: List<Segment>, timestamps: Boolean): String {
        val out = StringBuilder()
        var previousEnd = Float.NEGATIVE_INFINITY
        var previousSpeaker: Int? = null
        for (s in segments) {
            val speakerChanged = s.speaker != null && s.speaker != previousSpeaker
            val newParagraph = out.isEmpty() || s.startS - previousEnd >= PARAGRAPH_GAP_S || speakerChanged
            if (out.isNotEmpty()) out.append(if (newParagraph) "\n\n" else " ")
            if (newParagraph && timestamps) out.append('[').append(clock(s.startS)).append("] ")
            if (newParagraph && s.speaker != null) out.append(speakerName(s.speaker)).append(": ")
            out.append(s.text)
            previousEnd = s.endS
            previousSpeaker = s.speaker
        }
        return out.toString()
    }

    fun speakerName(index: Int) = "Speaker ${index + 1}"

    /**
     * The transcript as SubRip subtitles (.srt): one cue per phrase, split into
     * pieces of about two lines of [SRT_LINE] characters, each timed in
     * proportion to its length. A speaker's name starts the cue where it changes.
     */
    fun srt(segments: List<Segment>): String {
        data class Cue(val start: Float, val end: Float, val text: String)
        val cues = mutableListOf<Cue>()
        var previousSpeaker: Int? = null
        for (s in segments) {
            var text = s.text
            if (s.speaker != null && s.speaker != previousSpeaker) text = "${speakerName(s.speaker)}: $text"
            previousSpeaker = s.speaker
            val parts = splitCue(text, SRT_LINE * 2)
            val total = parts.sumOf { it.length }.coerceAtLeast(1)
            var t = s.startS
            val length = (s.endS - s.startS).coerceAtLeast(0.5f)
            for (part in parts) {
                val d = length * part.length / total
                cues += Cue(t, t + d, wrap(part, SRT_LINE))
                t += d
            }
        }
        val out = StringBuilder()
        cues.forEachIndexed { i, cue ->
            // A cue never runs into the next one.
            val end = minOf(cue.end, cues.getOrNull(i + 1)?.start ?: cue.end).coerceAtLeast(cue.start + 0.1f)
            out.append(i + 1).append('\n')
                .append(srtTime(cue.start)).append(" --> ").append(srtTime(end)).append('\n')
                .append(cue.text).append("\n\n")
        }
        return out.toString()
    }

    /** Splits text at word boundaries into pieces of at most [max] characters. */
    private fun splitCue(text: String, max: Int): List<String> {
        val pieces = mutableListOf<String>()
        val current = StringBuilder()
        for (word in text.split(' ').filter { it.isNotEmpty() }) {
            if (current.isNotEmpty() && current.length + 1 + word.length > max) {
                pieces += current.toString()
                current.clear()
            }
            if (current.isNotEmpty()) current.append(' ')
            current.append(word)
        }
        if (current.isNotEmpty()) pieces += current.toString()
        return pieces
    }

    /** Breaks a cue's text into lines of at most [width] characters where it can. */
    private fun wrap(text: String, width: Int): String = splitCue(text, width).joinToString("\n")

    /** 75.25 → "00:01:15,250". */
    fun srtTime(seconds: Float): String {
        val ms = (seconds.coerceAtLeast(0f) * 1000).toLong()
        // Locale.ROOT: subtitle players expect ASCII digits whatever the phone's language.
        return String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)
    }

    /** 75.4 → "1:15"; an hour or more as "1:02:03". */
    fun clock(seconds: Float): String {
        val total = seconds.toInt().coerceAtLeast(0)
        val h = total / 3600
        val m = total / 60 % 60
        val s = total % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
    }

    private fun message(e: Throwable): String = when (e) {
        is UnsupportedAudioException -> e.message ?: "Android can't decode this file"
        is android.media.MediaCodec.CodecException -> "Android's audio decoder failed on this file. Convert it to M4A or MP3, or pick another file."
        is OutOfMemoryError -> "Ran out of memory. Try a smaller model."
        is UnsatisfiedLinkError -> "The speech engine couldn't start"
        else -> e.message?.takeIf { it.isNotBlank() } ?: e::class.java.simpleName
    }
}
