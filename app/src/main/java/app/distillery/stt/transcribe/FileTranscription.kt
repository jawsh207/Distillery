// SPDX-License-Identifier: Apache-2.0
package app.distillery.stt.transcribe

import ai.moonshine.voice.Transcriber
import ai.moonshine.voice.TranscriptEvent
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
 */
object FileTranscription {

    private const val TAG = "DistilleryTranscribe"

    /** A pause at least this long between phrases starts a new paragraph. */
    private const val PARAGRAPH_GAP_S = 2.0f

    /** One phrase Moonshine heard, with its place in the recording. */
    data class Segment(val startS: Float, val endS: Float, val text: String)

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

    /** Starts transcribing [uri]. Returns false if another transcription or export is running. */
    fun start(context: Context, uri: Uri, name: String, model: SttModel): Boolean {
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
                val (segments, seconds) = transcribe(app, input, model) { fraction, live ->
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
        onUpdate: (fraction: Float?, segments: List<Segment>) -> Unit,
    ): Pair<List<Segment>, Float> {
        ModelLicensePolicy.requireAllowed(model)
        check(app.modelStore.isInstalled(model)) { "The ${model.displayName} model isn't downloaded" }
        val executor = Executors.newSingleThreadExecutor { Thread(it, "distillery-transcribe") }
        try {
            return withContext(executor.asCoroutineDispatcher()) {
                val context = currentCoroutineContext()
                val transcriber = Transcriber().apply { loadFromFiles(app.modelStore.dirFor(model).absolutePath, model.arch) }
                val lines = LinkedHashMap<Long, Segment>()
                var lastUpdate = 0L
                var fraction: Float? = null
                var seconds = 0f
                val listener = Consumer<TranscriptEvent> { event ->
                    val line = when (event) {
                        is TranscriptEvent.LineStarted -> event.line
                        is TranscriptEvent.LineUpdated -> event.line
                        is TranscriptEvent.LineTextChanged -> event.line
                        is TranscriptEvent.LineCompleted -> event.line
                        else -> null
                    } ?: return@Consumer
                    lines[line.id] = Segment(line.startTime, line.startTime + line.duration, line.text.orEmpty().trim())
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
                                onUpdate(fraction, lines.values.toList())
                            }
                        },
                    )
                    if (!context.isActive) throw CancellationException()
                    // Moonshine finishes the open phrase on stop().
                    transcriber.stop()
                    onUpdate(1f, lines.values.toList())
                } finally {
                    transcriber.removeListener(listener)
                    transcriber.close()
                }
                lines.values.filter { it.text.isNotEmpty() } to seconds
            }
        } finally {
            executor.shutdown()
        }
    }

    /** Applies the user's dictation settings: punctuation off, offensive words masked. */
    private fun tidy(app: DistilleryApplication, segments: List<Segment>): List<Segment> = segments.map { s ->
        var text = s.text
        if (!app.settings.punctuation) text = Punctuation.stripAll(text)
        if (app.settings.maskOffensiveWords) text = app.offensiveWords.mask(text)
        s.copy(text = text)
    }.filter { it.text.isNotEmpty() }

    /** The transcript as text: paragraphs at long pauses, optionally each with its time. */
    fun render(segments: List<Segment>, timestamps: Boolean): String {
        val out = StringBuilder()
        var previousEnd = Float.NEGATIVE_INFINITY
        for (s in segments) {
            val newParagraph = out.isEmpty() || s.startS - previousEnd >= PARAGRAPH_GAP_S
            if (out.isNotEmpty()) out.append(if (newParagraph) "\n\n" else " ")
            if (newParagraph && timestamps) out.append('[').append(clock(s.startS)).append("] ")
            out.append(s.text)
            previousEnd = s.endS
        }
        return out.toString()
    }

    /** 75.4 → "1:15"; an hour or more as "1:02:03". */
    fun clock(seconds: Float): String {
        val total = seconds.toInt().coerceAtLeast(0)
        val h = total / 3600
        val m = total / 60 % 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    private fun message(e: Throwable): String = when (e) {
        is UnsupportedAudioException -> e.message ?: "Android can't decode this file"
        is OutOfMemoryError -> "Ran out of memory. Try a smaller model."
        is UnsatisfiedLinkError -> "The speech engine couldn't start"
        else -> e.message?.takeIf { it.isNotBlank() } ?: e::class.java.simpleName
    }
}
