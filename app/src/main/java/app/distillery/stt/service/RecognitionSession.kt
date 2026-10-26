// SPDX-License-Identifier: GPL-3.0-or-later
package app.distillery.stt.service

import ai.moonshine.voice.Transcriber
import ai.moonshine.voice.TranscriptEvent
import ai.moonshine.voice.TranscriptLine
import android.os.Bundle
import android.os.RemoteException
import android.os.SystemClock
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.util.Log
import app.distillery.stt.engine.SpeechEngine
import app.distillery.stt.engine.SttModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer

/**
 * One SpeechRecognizer.startListening() call, from opening the microphone to
 * the final onResults/onError.
 *
 * Two workers cooperate:
 *  - the audio reader (IO thread) pulls frames from the microphone or from a
 *    recording the caller handed over (decoded if needed), reports the level
 *    through onRmsChanged and queues the audio;
 *  - the recognizer (the engine's Moonshine thread) feeds the queue to
 *    Moonshine, turns its phrase events into onBeginningOfSpeech and
 *    onPartialResults, and asks [Endpointer] whether the speaker is done.
 *
 * Audio is queued from the moment the microphone opens, so nothing said while
 * the model is still loading is lost. Exactly one terminal callback (results,
 * error or end of segmented session) is sent, and nothing at all after [cancel].
 */
internal class RecognitionSession(
    private val callback: RecognitionService.Callback,
    private val request: RecognitionRequest,
    private val model: SttModel,
    private val engine: SpeechEngine,
    private val openInput: () -> AudioInput,
    private val onFinished: (RecognitionSession) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("distillery-session"))

    /** No more audio should be read (stopListening, endpoint, or end of session). */
    private val stopRequested = AtomicBoolean(false)

    /** A terminal callback was sent, or the session was cancelled. */
    private val finished = AtomicBoolean(false)

    @Volatile
    private var input: AudioInput? = null

    fun start() {
        scope.launch { run() }
    }

    /** SpeechRecognizer.stopListening(): recognize what was said so far, then finish. */
    fun stop() {
        stopRequested.set(true)
        input?.interrupt()
    }

    /** SpeechRecognizer.cancel(), or the caller went away: stop without callbacks. */
    fun cancel() {
        val first = finished.compareAndSet(false, true)
        stopRequested.set(true)
        input?.interrupt()
        scope.cancel()
        if (first) onFinished(this)
    }

    // ---------------------------------------------------------------- audio

    private suspend fun run() {
        val source = try {
            withContext(Dispatchers.IO) { openInput() }
        } catch (e: SecurityException) {
            Log.w(TAG, "No permission to record", e)
            fail(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
            return
        } catch (e: AudioInputException) {
            Log.w(TAG, "Audio source unavailable", e)
            fail(SpeechRecognizer.ERROR_AUDIO)
            return
        }
        input = source
        if (finished.get()) {
            // Cancelled while the microphone was opening.
            source.close()
            return
        }

        // The microphone must never wait on the recognizer; a recording is read
        // only as fast as it's recognized.
        val audio = Channel<AudioFrame>(if (source.isLive) Channel.UNLIMITED else RECORDING_QUEUE_FRAMES)
        val reader = scope.launch(Dispatchers.IO) { readAudio(source, audio) }

        deliver { callback.readyForSpeech(Bundle()) }

        try {
            withContext(engine.dispatcher) { recognize(audio) }
        } finally {
            stopRequested.set(true)
            source.interrupt()
            // Unblocks a reader waiting for queue space after an early stop.
            audio.cancel()
            reader.join()
        }
    }

    private suspend fun readAudio(source: AudioInput, audio: Channel<AudioFrame>) {
        var lastLevelAt = 0L
        var failure: Throwable? = null
        try {
            while (scope.isActive && !stopRequested.get()) {
                val frame = source.read() ?: break
                if (frame.samples.isEmpty() || frame.sampleRate <= 0) continue
                audio.send(frame)
                val now = SystemClock.elapsedRealtime()
                if (now - lastLevelAt >= RMS_INTERVAL_MS) {
                    lastLevelAt = now
                    deliver { callback.rmsChanged(rmsDb(frame.samples)) }
                }
            }
        } catch (e: AudioInputException) {
            Log.w(TAG, "Audio read failed", e)
            failure = e
        } finally {
            source.close()
            // Lets the recognizer drain what's queued, then see the end (or the error).
            audio.close(failure)
        }
    }

    // ---------------------------------------------------------- recognition

    /** Runs on the engine thread, the only thread that touches Moonshine. */
    private suspend fun recognize(audio: ReceiveChannel<AudioFrame>) {
        val transcriber: Transcriber = try {
            engine.acquire(model)
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't load ${model.id}", e)
            stopRequested.set(true)
            fail(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)
            return
        }

        val endpointer = Endpointer(
            pauseToStopMs = request.pauseToStopMs,
            noSpeechTimeoutMs = request.noSpeechTimeoutMs,
            minimumLengthMs = request.minimumLengthMs,
            maxDurationMs = request.maxDurationMs,
            stopAtMinimumLength = request.stopAtMinimumLength,
        )
        val transcript = TranscriptBuffer()
        var speechBegan = false
        var lastPartial = ""

        fun onLine(line: TranscriptLine, started: Boolean, completed: Boolean) {
            transcript.update(line.id, line.text, line.isComplete || completed)
            if (started) {
                endpointer.onLineStarted(line.id)
            }
            if (!speechBegan && (started || completed)) {
                speechBegan = true
                deliver { callback.beginningOfSpeech() }
            }
            if (completed) {
                endpointer.onLineCompleted(line.id)
                if (request.segmented) {
                    val text = transcript.textOf(line.id)
                    if (text.isNotEmpty()) deliver { callback.segmentResults(resultsBundle(text)) }
                }
            }
            if (request.partialResults && !request.segmented) {
                val text = transcript.fullText()
                if (text.isNotEmpty() && text != lastPartial) {
                    lastPartial = text
                    deliver { callback.partialResults(resultsBundle(text)) }
                }
            }
        }

        val listener = Consumer<TranscriptEvent> { event ->
            when (event) {
                is TranscriptEvent.LineStarted -> onLine(event.line, started = true, completed = false)
                is TranscriptEvent.LineUpdated -> onLine(event.line, started = false, completed = false)
                is TranscriptEvent.LineTextChanged -> onLine(event.line, started = false, completed = false)
                is TranscriptEvent.LineCompleted -> onLine(event.line, started = false, completed = true)
                is TranscriptEvent.Error -> Log.w(TAG, "Moonshine reported an error", event.cause)
                else -> Unit
            }
        }

        transcriber.addListener(listener)
        try {
            transcriber.start()

            var audioUs = 0L
            var noSpeech = false
            loop@ for (frame in audio) {
                if (finished.get()) return
                // Moonshine resamples to 16 kHz itself (8 kHz phone audio is fine).
                transcriber.addAudio(frame.samples, frame.sampleRate)

                val beforeMs = audioUs / 1000
                audioUs += frame.samples.size * 1_000_000L / frame.sampleRate
                endpointer.onAudio(audioUs / 1000 - beforeMs)

                when (endpointer.decide()) {
                    Endpointer.Decision.CONTINUE -> Unit
                    Endpointer.Decision.END_OF_SPEECH -> break@loop
                    Endpointer.Decision.NO_SPEECH_TIMEOUT -> {
                        noSpeech = true
                        break@loop
                    }
                }
            }

            // Endpoint, stopListening(), or the end of the recording. Stop reading
            // and tell the caller right away, before the final pass.
            stopRequested.set(true)
            input?.interrupt()
            if (noSpeech) {
                fail(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
                return
            }
            if (speechBegan) deliver { callback.endOfSpeech() }

            // Moonshine finishes the open phrase on stop(); its LineCompleted
            // events arrive through the listener before this returns.
            transcriber.stop()

            val text = transcript.fullText()
            when {
                request.segmented -> finish { callback.endOfSegmentedSession() }
                text.isEmpty() -> fail(SpeechRecognizer.ERROR_NO_MATCH)
                else -> finish { callback.results(resultsBundle(text)) }
            }
        } catch (e: AudioInputException) {
            fail(SpeechRecognizer.ERROR_AUDIO)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            Log.e(TAG, "Recognition failed", e)
            fail(SpeechRecognizer.ERROR_SERVER)
        } finally {
            transcriber.removeListener(listener)
            engine.releaseLater()
        }
    }

    // ------------------------------------------------------------ callbacks

    private fun resultsBundle(text: String) = Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
    }

    /** Sends a non-terminal callback unless the session is over. */
    private inline fun deliver(block: () -> Unit) {
        if (finished.get()) return
        try {
            block()
        } catch (e: RemoteException) {
            Log.i(TAG, "Caller went away; ending session")
            cancel()
        }
    }

    /** Sends the one terminal callback. */
    private inline fun finish(block: () -> Unit) {
        if (!finished.compareAndSet(false, true)) return
        stopRequested.set(true)
        input?.interrupt()
        try {
            block()
        } catch (e: RemoteException) {
            Log.i(TAG, "Caller went away before the result")
        }
        onFinished(this)
    }

    private fun fail(error: Int) = finish { callback.error(error) }

    private companion object {
        const val TAG = "DistillerySession"
        const val RMS_INTERVAL_MS = 50L

        /** About 0.6 s of 20 ms frames buffered ahead of the recognizer. */
        const val RECORDING_QUEUE_FRAMES = 32
    }
}
