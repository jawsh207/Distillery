// SPDX-License-Identifier: GPL-3.0-or-later
package app.distillery.stt.engine

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** What the Models screen shows for one model's download. */
sealed interface DownloadState {
    data object Idle : DownloadState
    data object Waiting : DownloadState
    data class Running(val fraction: Float?, val bytesDone: Long, val bytesTotal: Long) : DownloadState
    data class Failed(val message: String) : DownloadState
}

/** Starts, cancels and observes model downloads (WorkManager unique work per model). */
class ModelDownloads(context: Context, private val settings: DistillerySettings) {

    private val workManager = WorkManager.getInstance(context.applicationContext)

    fun start(model: SttModel) {
        ModelLicensePolicy.requireAllowed(model)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                if (settings.wifiOnlyDownloads) NetworkType.UNMETERED else NetworkType.CONNECTED,
            )
            .setRequiresStorageNotLow(true)
            .build()
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(workDataOf(ModelDownloadWorker.KEY_MODEL to model.id))
            .setConstraints(constraints)
            .addTag(TAG)
            .build()
        workManager.enqueueUniqueWork(workName(model), ExistingWorkPolicy.KEEP, request)
    }

    fun cancel(model: SttModel) {
        workManager.cancelUniqueWork(workName(model))
    }

    fun state(model: SttModel): Flow<DownloadState> =
        workManager.getWorkInfosForUniqueWorkFlow(workName(model)).map { infos ->
            val info = infos.lastOrNull() ?: return@map DownloadState.Idle
            when (info.state) {
                WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> DownloadState.Waiting
                WorkInfo.State.RUNNING -> {
                    val done = info.progress.getLong(ModelDownloadWorker.PROGRESS_BYTES_DONE, 0L)
                    val total = info.progress.getLong(ModelDownloadWorker.PROGRESS_BYTES_TOTAL, 0L)
                    val fraction = info.progress.getFloat(ModelDownloadWorker.PROGRESS_FRACTION, -1f)
                    DownloadState.Running(fraction.takeIf { it >= 0f }, done, total)
                }
                WorkInfo.State.FAILED -> DownloadState.Failed(
                    info.outputData.getString(ModelDownloadWorker.OUTPUT_ERROR) ?: "Download failed",
                )
                WorkInfo.State.SUCCEEDED, WorkInfo.State.CANCELLED -> DownloadState.Idle
            }
        }

    companion object {
        const val TAG = "distillery-model-download"
        fun workName(model: SttModel) = "download-${model.id}"
    }
}
