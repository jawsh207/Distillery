// SPDX-License-Identifier: GPL-3.0-or-later
package app.distillery.stt.engine

import ai.moonshine.voice.AssetDownloader
import ai.moonshine.voice.ModelSpec
import ai.moonshine.voice.Transcriber
import ai.moonshine.voice.TranscriberOption
import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.SystemClock
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.distillery.stt.R
import app.distillery.stt.Notifications
import app.distillery.stt.DistilleryApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.IOException

/**
 * Downloads one Moonshine model into [ModelStore] using Moonshine's own
 * [AssetDownloader] (resumable, atomic writes), after checking the model and
 * its file list against [ModelLicensePolicy].
 */
class ModelDownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    private val app = context.applicationContext as DistilleryApplication

    override suspend fun doWork(): Result {
        val model = SttModel.fromId(inputData.getString(KEY_MODEL))
            ?: return failure("Unknown model")

        try {
            ModelLicensePolicy.requireAllowed(model)
        } catch (e: ModelPolicyException) {
            return failure(e.message ?: "Model not allowed")
        }

        // Long downloads need to outlive the screen; fall back to plain
        // background work if the system won't allow a foreground service now.
        try {
            setForeground(foregroundInfo(model, null))
        } catch (e: Exception) {
            Log.w(TAG, "Running download without a foreground notification", e)
        }

        val store = app.modelStore
        val dir = store.dirFor(model)

        return try {
            runInterruptible(Dispatchers.IO) {
                val manifest = Transcriber.getSttDependencies(
                    model.language,
                    listOf(TranscriberOption("model_arch", model.arch.toString())),
                )
                ModelLicensePolicy.verifyManifest(model, manifest)

                dir.mkdirs()
                var lastReport = 0L
                AssetDownloader().ensureModelPresent(
                    dir,
                    ModelSpec.stt(model.language, model.arch, false),
                ) { _, fileIndex, totalFiles, bytesDone, bytesTotal ->
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastReport < PROGRESS_INTERVAL_MS) return@ensureModelPresent
                    lastReport = now
                    val withinFile = if (bytesTotal > 0) bytesDone.toFloat() / bytesTotal else 0f
                    val fraction = if (totalFiles > 0) {
                        ((fileIndex - 1) + withinFile) / totalFiles
                    } else {
                        -1f
                    }
                    setProgressAsync(
                        workDataOf(
                            PROGRESS_FRACTION to fraction,
                            PROGRESS_BYTES_DONE to bytesDone,
                            PROGRESS_BYTES_TOTAL to bytesTotal,
                        ),
                    )
                    runCatching { setForegroundAsync(foregroundInfo(model, fraction)) }
                }
            }
            store.markComplete(model)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ModelPolicyException) {
            Log.e(TAG, "Refusing download of ${model.id}", e)
            dir.deleteRecursively()
            failure(e.message ?: "Model not allowed")
        } catch (e: IOException) {
            Log.w(TAG, "Download of ${model.id} failed (attempt ${runAttemptCount + 1})", e)
            if (runAttemptCount < MAX_RETRIES) Result.retry() else failure(e.message ?: "Network error")
        } catch (e: RuntimeException) {
            Log.e(TAG, "Download of ${model.id} failed", e)
            failure(e.message ?: "Download failed")
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val model = SttModel.fromId(inputData.getString(KEY_MODEL)) ?: SttModel.SMALL
        return foregroundInfo(model, null)
    }

    private fun foregroundInfo(model: SttModel, fraction: Float?): ForegroundInfo {
        val notification: Notification = Notifications.downloadProgress(
            applicationContext,
            applicationContext.getString(R.string.notification_downloading, model.displayName),
            fraction,
            cancelIntent = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id),
        )
        return ForegroundInfo(
            Notifications.ID_DOWNLOAD,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private fun failure(message: String) = Result.failure(workDataOf(OUTPUT_ERROR to message))

    companion object {
        private const val TAG = "DistilleryDownload"
        private const val MAX_RETRIES = 3
        private const val PROGRESS_INTERVAL_MS = 300L

        const val KEY_MODEL = "model"
        const val PROGRESS_FRACTION = "fraction"
        const val PROGRESS_BYTES_DONE = "bytes_done"
        const val PROGRESS_BYTES_TOTAL = "bytes_total"
        const val OUTPUT_ERROR = "error"
    }
}
