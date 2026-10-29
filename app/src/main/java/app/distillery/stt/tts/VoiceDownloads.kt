// SPDX-License-Identifier: GPL-3.0-or-later
package app.distillery.stt.tts

import ai.moonshine.voice.AssetDownloader
import ai.moonshine.voice.ModelSpec
import ai.moonshine.voice.TextToSpeech
import ai.moonshine.voice.TranscriberOption
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.SystemClock
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.distillery.stt.DistilleryApplication
import app.distillery.stt.Notifications
import app.distillery.stt.engine.DownloadState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runInterruptible
import java.io.IOException

/** Starts, cancels and observes voice downloads (WorkManager unique work per voice). */
class VoiceDownloads(context: Context) {

    private val appContext = context.applicationContext
    private val workManager = WorkManager.getInstance(appContext)

    fun start(voice: KokoroVoice) {
        TtsLicensePolicy.requireAllowed(voice)
        val wifiOnly = (appContext as DistilleryApplication).settings.wifiOnlyDownloads
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresStorageNotLow(true)
            .build()
        val request = OneTimeWorkRequestBuilder<VoiceDownloadWorker>()
            .setInputData(workDataOf(VoiceDownloadWorker.KEY_VOICE to voice.id))
            .setConstraints(constraints)
            .addTag(TAG)
            .build()
        // One download at a time: voices share the model files, so parallel
        // downloads would fetch the same files twice.
        workManager.enqueueUniqueWork(workName(voice), ExistingWorkPolicy.KEEP, request)
    }

    fun cancel(voice: KokoroVoice) {
        workManager.cancelUniqueWork(workName(voice))
    }

    fun state(voice: KokoroVoice): Flow<DownloadState> =
        workManager.getWorkInfosForUniqueWorkFlow(workName(voice)).map { infos ->
            val info = infos.lastOrNull() ?: return@map DownloadState.Idle
            when (info.state) {
                WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> DownloadState.Waiting
                WorkInfo.State.RUNNING -> {
                    val done = info.progress.getLong(VoiceDownloadWorker.PROGRESS_BYTES_DONE, 0L)
                    val total = info.progress.getLong(VoiceDownloadWorker.PROGRESS_BYTES_TOTAL, 0L)
                    val fraction = info.progress.getFloat(VoiceDownloadWorker.PROGRESS_FRACTION, -1f)
                    DownloadState.Running(fraction.takeIf { it >= 0f }, done, total)
                }
                WorkInfo.State.FAILED -> DownloadState.Failed(
                    info.outputData.getString(VoiceDownloadWorker.OUTPUT_ERROR) ?: "Download failed",
                )
                WorkInfo.State.SUCCEEDED, WorkInfo.State.CANCELLED -> DownloadState.Idle
            }
        }

    companion object {
        const val TAG = "distillery-voice-download"
        fun workName(voice: KokoroVoice) = "voice-${voice.id}"
    }
}

/**
 * Downloads one voice (and, the first time, the Kokoro model and English
 * pronunciation data) into [VoiceStore], after checking every file against
 * [TtsLicensePolicy].
 */
class VoiceDownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val voice = KokoroVoice.fromId(inputData.getString(KEY_VOICE)) ?: return failure("Unknown voice")
        try {
            TtsLicensePolicy.requireAllowed(voice)
        } catch (e: TtsPolicyException) {
            return failure(e.message ?: "Voice not allowed")
        }
        try {
            setForeground(foregroundInfo(voice, null))
        } catch (e: Exception) {
            Log.w(TAG, "Running voice download without a foreground notification", e)
        }

        val store = TtsRuntime.get(applicationContext).store
        val root = store.root
        return try {
            runInterruptible(Dispatchers.IO) {
                root.mkdirs()
                // Stand-ins first: the downloader skips files that exist, so
                // Moonshine's own English pronunciation files are never fetched.
                StandIns.install(root, applicationContext.assets)
                val spec = ModelSpec.tts(TtsLicensePolicy.LANGUAGE, voice.moonshineId)
                // Same query AssetDownloader makes, checked before anything is fetched.
                val manifest = TextToSpeech.getTtsDependencies(
                    TtsLicensePolicy.LANGUAGE,
                    listOf(
                        TranscriberOption("g2p_root", root.absolutePath),
                        TranscriberOption("voice", voice.moonshineId),
                    ),
                )
                val files = TtsLicensePolicy.verifyManifest(voice, manifest)

                var lastReport = 0L
                AssetDownloader().ensureModelPresent(root, spec) { _, fileIndex, totalFiles, bytesDone, bytesTotal ->
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastReport < PROGRESS_INTERVAL_MS) return@ensureModelPresent
                    lastReport = now
                    val withinFile = if (bytesTotal > 0) bytesDone.toFloat() / bytesTotal else 0f
                    val fraction = if (totalFiles > 0) ((fileIndex - 1) + withinFile) / totalFiles else -1f
                    setProgressAsync(
                        workDataOf(
                            PROGRESS_FRACTION to fraction,
                            PROGRESS_BYTES_DONE to bytesDone,
                            PROGRESS_BYTES_TOTAL to bytesTotal,
                        ),
                    )
                    runCatching { setForegroundAsync(foregroundInfo(voice, fraction)) }
                }
                if (!StandIns.verify(root, applicationContext.assets)) {
                    throw TtsPolicyException("Unexpected files under en_us/; only Distillery's stand-ins belong there")
                }
                store.markComplete(voice, files)
            }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: TtsPolicyException) {
            Log.e(TAG, "Refusing download of ${voice.id}", e)
            failure(e.message ?: "Voice not allowed")
        } catch (e: IOException) {
            Log.w(TAG, "Download of ${voice.id} failed (attempt ${runAttemptCount + 1})", e)
            if (runAttemptCount < MAX_RETRIES) Result.retry() else failure(e.message ?: "Network error")
        } catch (e: RuntimeException) {
            Log.e(TAG, "Download of ${voice.id} failed", e)
            failure(e.message ?: "Download failed")
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val voice = KokoroVoice.fromId(inputData.getString(KEY_VOICE)) ?: KokoroVoice.DEFAULT
        return foregroundInfo(voice, null)
    }

    private fun foregroundInfo(voice: KokoroVoice, fraction: Float?): ForegroundInfo = ForegroundInfo(
        Notifications.ID_DOWNLOAD,
        Notifications.downloadProgress(
            applicationContext,
            "Downloading the ${voice.displayName} voice",
            fraction,
            cancelIntent = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id),
        ),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )

    private fun failure(message: String) = Result.failure(workDataOf(OUTPUT_ERROR to message))

    companion object {
        private const val TAG = "DistilleryVoiceDownload"
        private const val MAX_RETRIES = 3
        private const val PROGRESS_INTERVAL_MS = 300L

        const val KEY_VOICE = "voice"
        const val PROGRESS_FRACTION = "fraction"
        const val PROGRESS_BYTES_DONE = "bytes_done"
        const val PROGRESS_BYTES_TOTAL = "bytes_total"
        const val OUTPUT_ERROR = "error"
    }
}
