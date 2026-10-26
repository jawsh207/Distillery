// SPDX-License-Identifier: GPL-3.0-or-later
package app.distillery.stt.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.AttributionSource
import android.content.ContextParams
import android.content.Intent
import android.content.pm.PackageManager
import android.os.RemoteException
import android.speech.ModelDownloadListener
import android.speech.RecognitionService
import android.speech.RecognitionSupport
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import app.distillery.stt.Notifications
import app.distillery.stt.DistilleryApplication
import app.distillery.stt.engine.Diagnostics
import app.distillery.stt.engine.ModelPolicyException
import java.util.concurrent.ConcurrentHashMap

/**
 * Android's standard speech recognition service, backed by Moonshine on the device.
 *
 * Apps and keyboards reach it through [SpeechRecognizer.createSpeechRecognizer]
 * once Distillery is the default voice input, or directly with
 * `SpeechRecognizer.createSpeechRecognizer(context, ComponentName(...))`.
 * There's no activity or popup: results go straight back to the caller.
 * Besides live dictation from the microphone, callers can hand over a recording
 * with RecognizerIntent.EXTRA_AUDIO_SOURCE; see
 * [RecordingInput]. The service never uses the network.
 */
class DistilleryRecognitionService : RecognitionService() {

    private val app get() = application as DistilleryApplication

    /** Live sessions by the framework's per-session callback (identity). */
    private val sessions = ConcurrentHashMap<Callback, RecognitionSession>()

    override fun onCreate() {
        super.onCreate()
        // Bound means a caller is about to listen: get the model into memory.
        app.modelStore.resolveModel(app.settings.chosenModel)?.let(app.engine::preload)
    }

    @SuppressLint("MissingPermission") // RECORD_AUDIO is checked before recording
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        listener ?: return
        val request = RecognitionRequest.from(recognizerIntent ?: Intent(), app.settings)

        if (!request.isEnglish) {
            Log.i(TAG, "Unsupported language ${request.language}")
            Diagnostics.report("Language not supported", "Requested ${request.language}; Distillery supports English.")
            listener.safeError(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED)
            return
        }

        val model = app.modelStore.resolveModel(app.settings.chosenModel)
        if (model == null) {
            Diagnostics.report(
                "No speech model downloaded",
                "Chosen: ${app.settings.chosenModel.id}. Installed: none. Download one on the Models screen.",
            )
            Notifications.showNeedsModel(this)
            listener.safeError(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)
            return
        }

        // Created here, on the service's main thread, as the framework expects.
        // For the microphone it attributes recording to the caller with
        // Distillery as the proxy. For a recording the caller hands over (such
        // as an audio file) no microphone is opened, and having the
        // attribution context tells the framework Distillery handles attribution
        // itself, so the caller doesn't need RECORD_AUDIO just to transcribe a file.
        val attributionContext = createContext(
            ContextParams.Builder()
                .setNextAttributionSource(listener.callingAttributionSource)
                .build(),
        )

        val external = request.externalAudio
        val openInput: () -> AudioInput
        if (external != null) {
            openInput = { RecordingInput.open(external, cacheDir) }
        } else {
            // Recording needs both the caller's permission (checked by the
            // framework) and ours, since Distillery records on the caller's behalf.
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                Diagnostics.report("Microphone not allowed", "Distillery doesn't have microphone permission.")
                Notifications.showNeedsMicrophone(this)
                listener.safeError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
                return
            }
            openInput = { MicrophoneInput(attributionContext) }
        }

        val session = RecognitionSession(
            callback = listener,
            request = request,
            model = model,
            engine = app.engine,
            openInput = openInput,
            onFinished = { finished -> sessions.remove(listener, finished) },
        )
        sessions[listener] = session
        session.start()
    }

    override fun onStopListening(listener: Callback?) {
        listener?.let { sessions[it]?.stop() }
    }

    override fun onCancel(listener: Callback?) {
        listener?.let { sessions.remove(it)?.cancel() }
    }

    override fun onDestroy() {
        sessions.values.forEach { it.cancel() }
        sessions.clear()
        super.onDestroy()
    }

    // ---------------------------------------------------- support & models

    override fun onCheckRecognitionSupport(
        recognizerIntent: Intent,
        supportCallback: SupportCallback,
    ) {
        val installed = app.modelStore.installed.value.isNotEmpty()
        val downloading = app.isAnyModelDownloading()
        val support = RecognitionSupport.Builder()
            .setSupportedOnDeviceLanguages(SUPPORTED_LANGUAGES)
            .apply {
                when {
                    installed -> setInstalledOnDeviceLanguages(SUPPORTED_LANGUAGES)
                    downloading -> setPendingOnDeviceLanguages(SUPPORTED_LANGUAGES)
                }
            }
            .setOnlineLanguages(emptyList())
            .build()
        supportCallback.onSupportResult(support)
    }

    override fun onTriggerModelDownload(recognizerIntent: Intent) {
        startDefaultDownload()
    }

    override fun onTriggerModelDownload(
        recognizerIntent: Intent,
        attributionSource: AttributionSource,
        listener: ModelDownloadListener,
    ) {
        if (!RecognitionRequest.isEnglishTag(recognizerIntent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))) {
            listener.onError(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED)
            return
        }
        if (app.modelStore.resolveModel(app.settings.chosenModel) != null) {
            listener.onSuccess()
            return
        }
        try {
            startDefaultDownload()
            // WorkManager runs it when the network allows; progress shows in Distillery.
            listener.onScheduled()
        } catch (e: ModelPolicyException) {
            listener.onError(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)
        }
    }

    private fun startDefaultDownload() {
        if (app.modelStore.resolveModel(app.settings.chosenModel) == null) {
            app.downloads.start(app.settings.chosenModel)
        }
    }

    private fun Callback.safeError(error: Int) {
        try {
            error(error)
        } catch (e: RemoteException) {
            Log.i(TAG, "Caller went away")
        }
    }

    private companion object {
        const val TAG = "DistilleryService"

        /** Moonshine's English models aren't tied to one accent; these are the tags callers ask for. */
        val SUPPORTED_LANGUAGES = listOf("en-US", "en-GB", "en-AU", "en-CA", "en-IN", "en-IE", "en-NZ", "en-ZA")
    }
}
