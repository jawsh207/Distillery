// SPDX-License-Identifier: GPL-3.0-or-later
package app.distillery.stt.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.distillery.stt.ui.VoiceInputSetup

/** One line in the test screen's event log. */
data class TestEvent(val atMs: Long, val name: String, val detail: String = "")

/**
 * Drives a [SpeechRecognizer] exactly the way other apps do: a keyboard
 * (startListening with EXTRA_PARTIAL_RESULTS, partials as composing text, then
 * the final result) or an app handing over a recording (EXTRA_AUDIO_SOURCE).
 * Every callback is recorded so streaming can be checked.
 *
 * Must be used from the main thread (SpeechRecognizer's requirement).
 */
class RecognizerTester(private val context: Context) {

    var listening by mutableStateOf(false)
        private set
    var committed by mutableStateOf("")
        private set
    var composing by mutableStateOf("")
        private set
    var level by mutableFloatStateOf(0f)
        private set
    var status by mutableStateOf("Tap the microphone and speak")
        private set

    /** Request partial results (as keyboards do). */
    var partialResults by mutableStateOf(true)

    private var targetDistilleryState by mutableStateOf(false)

    /** Bind Distillery by component instead of using the system default. */
    var targetDistilleryDirectly: Boolean
        get() = targetDistilleryState
        set(value) {
            if (targetDistilleryState != value) destroyRecognizer()
            targetDistilleryState = value
        }

    val events = mutableStateListOf<TestEvent>()

    var partialCount by mutableIntStateOf(0)
        private set
    var firstPartialMs by mutableStateOf<Long?>(null)
        private set
    var finalAfterEndMs by mutableStateOf<Long?>(null)
        private set

    /** Wall-clock time of the whole session; for recordings, how long transcription took. */
    var totalMs by mutableStateOf<Long?>(null)
        private set

    private var recognizer: SpeechRecognizer? = null
    private var recordingDescriptor: ParcelFileDescriptor? = null
    private var startedAt = 0L
    private var endOfSpeechAt = 0L

    /** Live dictation from the microphone. */
    fun start() {
        begin(baseIntent(), "microphone")
    }

    /**
     * Transcribes a recording the way another app would hand one over:
     * the file goes to the recognizer as EXTRA_AUDIO_SOURCE, with no encoding
     * extras, and the service decodes it.
     */
    fun transcribeRecording(uri: Uri, name: String) {
        if (listening) return
        val descriptor = try {
            context.contentResolver.openFileDescriptor(uri, "r")
        } catch (e: Exception) {
            null
        }
        if (descriptor == null) {
            status = "Couldn't open $name"
            return
        }
        recordingDescriptor = descriptor
        begin(baseIntent().putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, descriptor), "recording: $name")
    }

    private fun baseIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, partialResults)
        .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)

    private fun begin(intent: Intent, source: String) {
        if (listening) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            status = "No speech recognition service is available"
            closeRecording()
            return
        }
        val r = recognizer ?: createRecognizer().also { recognizer = it }
        events.clear()
        partialCount = 0
        firstPartialMs = null
        finalAfterEndMs = null
        totalMs = null
        composing = ""
        startedAt = SystemClock.elapsedRealtime()
        endOfSpeechAt = 0L
        log(
            "startListening",
            (if (targetDistilleryDirectly) "Distillery (by component)" else "system default") + " · $source",
        )
        listening = true
        status = if (recordingDescriptor != null) "Transcribing recording…" else "Starting…"
        r.startListening(intent)
    }

    private fun closeRecording() {
        recordingDescriptor?.let { runCatching { it.close() } }
        recordingDescriptor = null
    }

    private fun finishSession() {
        listening = false
        level = 0f
        totalMs = elapsed()
        closeRecording()
    }

    fun stop() {
        if (!listening) return
        log("stopListening")
        recognizer?.stopListening()
        status = "Finishing…"
    }

    fun cancel() {
        if (!listening) return
        log("cancel")
        recognizer?.cancel()
        finishSession()
        composing = ""
        status = "Cancelled"
    }

    fun clear() {
        committed = ""
        composing = ""
        events.clear()
    }

    fun destroyRecognizer() {
        recognizer?.destroy()
        recognizer = null
        listening = false
        level = 0f
        closeRecording()
    }

    private fun createRecognizer(): SpeechRecognizer {
        val r = if (targetDistilleryDirectly) {
            SpeechRecognizer.createSpeechRecognizer(context, VoiceInputSetup.component(context))
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        r.setRecognitionListener(listener)
        return r
    }

    private fun elapsed() = SystemClock.elapsedRealtime() - startedAt

    private fun log(name: String, detail: String = "") {
        events.add(0, TestEvent(elapsed(), name, detail))
        if (events.size > MAX_EVENTS) events.removeAt(events.lastIndex)
    }

    private fun commit(text: String) {
        if (text.isBlank()) return
        committed = if (committed.isEmpty()) text else "$committed $text"
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            log("onReadyForSpeech")
            status = "Listening — speak now"
        }

        override fun onBeginningOfSpeech() {
            log("onBeginningOfSpeech")
        }

        override fun onRmsChanged(rmsdB: Float) {
            // Too frequent to log; drives the level meter.
            level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
        }

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            endOfSpeechAt = SystemClock.elapsedRealtime()
            log("onEndOfSpeech")
            status = "Finishing…"
        }

        override fun onError(error: Int) {
            log("onError", "${errorName(error)} ($error)")
            finishSession()
            composing = ""
            status = "Error: ${errorName(error)}"
        }

        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (endOfSpeechAt > 0) finalAfterEndMs = SystemClock.elapsedRealtime() - endOfSpeechAt
            log("onResults", "“$text”")
            composing = ""
            commit(text)
            finishSession()
            status = "Done"
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            partialCount++
            if (firstPartialMs == null) firstPartialMs = elapsed()
            log("onPartialResults", "“$text”")
            composing = text
        }

        override fun onSegmentResults(segmentResults: Bundle) {
            val text = segmentResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            log("onSegmentResults", "“$text”")
            commit(text)
        }

        override fun onEndOfSegmentedSession() {
            log("onEndOfSegmentedSession")
            finishSession()
            status = "Done"
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    companion object {
        private const val MAX_EVENTS = 200

        fun errorName(error: Int): String = when (error) {
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "NETWORK_TIMEOUT"
            SpeechRecognizer.ERROR_NETWORK -> "NETWORK"
            SpeechRecognizer.ERROR_AUDIO -> "AUDIO"
            SpeechRecognizer.ERROR_SERVER -> "SERVER"
            SpeechRecognizer.ERROR_CLIENT -> "CLIENT"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "SPEECH_TIMEOUT"
            SpeechRecognizer.ERROR_NO_MATCH -> "NO_MATCH"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "RECOGNIZER_BUSY"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "INSUFFICIENT_PERMISSIONS"
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "TOO_MANY_REQUESTS"
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "SERVER_DISCONNECTED"
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "LANGUAGE_NOT_SUPPORTED"
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "LANGUAGE_UNAVAILABLE"
            else -> "UNKNOWN"
        }
    }
}
