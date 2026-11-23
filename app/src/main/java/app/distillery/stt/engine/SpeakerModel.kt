// SPDX-License-Identifier: Apache-2.0
package app.distillery.stt.engine

import ai.moonshine.voice.AssetDownloader
import ai.moonshine.voice.ModelSpec
import ai.moonshine.voice.Transcriber
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runInterruptible
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URI

/**
 * The speaker identification models Transcribe uses to label who's speaking:
 * pyannote's "community-1" segmentation and speaker-embedding models (CC BY 4.0),
 * 8.6 MB together, served by Moonshine.
 *
 * Moonshine stopped compiling them into its library so apps that never label
 * speakers don't carry them, so they're an optional download here too. The
 * clustering data that goes with them is still inside Moonshine's library.
 */
object SpeakerModel {
    const val DISPLAY_NAME = "Speaker labels"
    const val SIZE_LABEL = "8.6 MB"
    const val LICENSE = "CC BY 4.0"

    /** Exactly these files, from exactly this folder of Moonshine's site. */
    val FILES = setOf("segmentation.ort", "embedding.ort")
    private const val FOLDER = "diarization-community1"

    /**
     * Checks the manifest Moonshine's catalog returns before anything downloads:
     * HTTPS from [ModelLicensePolicy.CDN_HOST], the community-1 folder, and only
     * the two model files, so a catalog change can't swap in something else.
     */
    fun verifyManifest(manifestJson: String) {
        val groups = JSONObject(manifestJson).optJSONArray("groups")
            ?: throw ModelPolicyException("Speaker model manifest has no file groups")
        val names = mutableSetOf<String>()
        for (g in 0 until groups.length()) {
            val group = groups.getJSONObject(g)
            val uri = runCatching { URI(group.optString("base_url")) }.getOrNull()
                ?: throw ModelPolicyException("Bad speaker model URL")
            val segments = uri.path.orEmpty().split('/').filter { it.isNotEmpty() }
            if (uri.scheme != "https" || uri.host != ModelLicensePolicy.CDN_HOST ||
                segments != listOf("model", FOLDER)
            ) {
                throw ModelPolicyException("Speaker models must come from https://${ModelLicensePolicy.CDN_HOST}/model/$FOLDER")
            }
            names += ModelLicensePolicy.manifestFiles(group)
        }
        if (names != FILES) throw ModelPolicyException("Unexpected speaker model files: $names")
    }
}

/** Where the speaker models live: noBackupFilesDir/speakers, out of device backups like the speech models. */
class SpeakerModelStore(val dir: File) {

    private val _installed = MutableStateFlow(scan())
    val installed: StateFlow<Boolean> = _installed.asStateFlow()

    fun isInstalled(): Boolean = scan()

    fun markComplete() {
        File(dir, ModelStore.COMPLETE_MARKER).writeText(System.currentTimeMillis().toString())
        _installed.value = scan()
    }

    fun delete() {
        dir.deleteRecursively()
        _installed.value = scan()
    }

    fun bytesOnDisk(): Long = if (dir.isDirectory) dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0L

    private fun scan(): Boolean =
        File(dir, ModelStore.COMPLETE_MARKER).isFile && SpeakerModel.FILES.all { File(dir, it).isFile }
}

/** Starts, cancels and watches the speaker model download (one WorkManager job). */
class SpeakerModelDownloads(context: Context, private val settings: DistillerySettings) {

    private val workManager = WorkManager.getInstance(context.applicationContext)

    fun start() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (settings.wifiOnlyDownloads) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresStorageNotLow(true)
            .build()
        val request = OneTimeWorkRequestBuilder<SpeakerModelDownloadWorker>()
            .setConstraints(constraints)
            .addTag(ModelDownloads.TAG)
            .build()
        workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    fun cancel() {
        workManager.cancelUniqueWork(WORK_NAME)
    }

    fun state(): Flow<DownloadState> = workManager.getWorkInfosForUniqueWorkFlow(WORK_NAME).map { infos ->
        val info = infos.lastOrNull() ?: return@map DownloadState.Idle
        when (info.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> DownloadState.Waiting
            WorkInfo.State.RUNNING -> DownloadState.Running(
                info.progress.getFloat(ModelDownloadWorker.PROGRESS_FRACTION, -1f).takeIf { it >= 0f },
                info.progress.getLong(ModelDownloadWorker.PROGRESS_BYTES_DONE, 0L),
                info.progress.getLong(ModelDownloadWorker.PROGRESS_BYTES_TOTAL, 0L),
            )
            WorkInfo.State.FAILED -> DownloadState.Failed(
                info.outputData.getString(ModelDownloadWorker.OUTPUT_ERROR) ?: "Download failed",
            )
            WorkInfo.State.SUCCEEDED, WorkInfo.State.CANCELLED -> DownloadState.Idle
        }
    }

    companion object {
        const val WORK_NAME = "download-speakers"
    }
}

/** Downloads the speaker models with Moonshine's [AssetDownloader], after checking its manifest. */
class SpeakerModelDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val app = context.applicationContext as DistilleryApplication

    override suspend fun doWork(): Result {
        runCatching { setForeground(foregroundInfo(null)) }
            .onFailure { Log.w(TAG, "Running download without a foreground notification", it) }
        val store = app.speakerModel
        return try {
            runInterruptible(Dispatchers.IO) {
                SpeakerModel.verifyManifest(Transcriber.getDiarizationDependencies())
                store.dir.mkdirs()
                var lastReport = 0L
                AssetDownloader().ensureModelPresent(store.dir, ModelSpec.diarization()) { _, fileIndex, totalFiles, done, total ->
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastReport < 300) return@ensureModelPresent
                    lastReport = now
                    val within = if (total > 0) done.toFloat() / total else 0f
                    val fraction = if (totalFiles > 0) ((fileIndex - 1) + within) / totalFiles else -1f
                    setProgressAsync(
                        workDataOf(
                            ModelDownloadWorker.PROGRESS_FRACTION to fraction,
                            ModelDownloadWorker.PROGRESS_BYTES_DONE to done,
                            ModelDownloadWorker.PROGRESS_BYTES_TOTAL to total,
                        ),
                    )
                    runCatching { setForegroundAsync(foregroundInfo(fraction)) }
                }
            }
            // AssetDownloader may lay files out under a subfolder; Moonshine wants them side by side.
            for (name in SpeakerModel.FILES) {
                val target = File(store.dir, name)
                if (!target.isFile) store.dir.walkTopDown().firstOrNull { it.isFile && it.name == name }?.renameTo(target)
            }
            if (!SpeakerModel.FILES.all { File(store.dir, it).isFile }) {
                throw IOException("The speaker models didn't arrive complete")
            }
            store.markComplete()
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ModelPolicyException) {
            Log.e(TAG, "Refusing the speaker model download", e)
            store.delete()
            failure(e.message ?: "Not allowed")
        } catch (e: IOException) {
            Log.w(TAG, "Speaker model download failed (attempt ${runAttemptCount + 1})", e)
            if (runAttemptCount < 3) Result.retry() else failure(e.message ?: "Network error")
        } catch (e: RuntimeException) {
            Log.e(TAG, "Speaker model download failed", e)
            failure(e.message ?: "Download failed")
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(null)

    private fun foregroundInfo(fraction: Float?) = ForegroundInfo(
        Notifications.ID_DOWNLOAD,
        Notifications.downloadProgress(
            applicationContext,
            "Downloading speaker labels",
            fraction,
            cancelIntent = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id),
        ),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )

    private fun failure(message: String) = Result.failure(workDataOf(ModelDownloadWorker.OUTPUT_ERROR to message))

    companion object {
        private const val TAG = "DistillerySpeakers"
    }
}
