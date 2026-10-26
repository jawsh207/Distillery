// SPDX-License-Identifier: GPL-3.0-or-later
package app.distillery.stt

import android.app.Application
import androidx.work.WorkManager
import app.distillery.stt.engine.ModelDownloads
import app.distillery.stt.engine.ModelStore
import app.distillery.stt.engine.SpeechEngine
import app.distillery.stt.engine.DistillerySettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.io.File

/** Process-wide singletons shared by the UI, the recognition service and the download worker. */
class DistilleryApplication : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var settings: DistillerySettings
        private set
    lateinit var modelStore: ModelStore
        private set
    lateinit var engine: SpeechEngine
        private set
    lateinit var downloads: ModelDownloads
        private set

    /** True while any model download is queued or running. */
    lateinit var anyDownloadActive: StateFlow<Boolean>
        private set

    override fun onCreate() {
        super.onCreate()
        settings = DistillerySettings(this)
        modelStore = ModelStore(File(noBackupFilesDir, "models"))
        engine = SpeechEngine(modelStore)
        downloads = ModelDownloads(this, settings)
        Notifications.createChannels(this)

        anyDownloadActive = WorkManager.getInstance(this)
            .getWorkInfosByTagFlow(ModelDownloads.TAG)
            .map { infos -> infos.any { !it.state.isFinished } }
            .stateIn(appScope, SharingStarted.Eagerly, false)
    }

    fun isAnyModelDownloading(): Boolean = anyDownloadActive.value
}
