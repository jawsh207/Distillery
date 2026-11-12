// SPDX-License-Identifier: Apache-2.0
package app.distillery.stt

import android.app.Application
import androidx.work.WorkManager
import app.distillery.stt.engine.ModelDownloads
import app.distillery.stt.engine.ModelStore
import app.distillery.stt.engine.SpeechEngine
import app.distillery.stt.engine.DistillerySettings
import app.distillery.stt.service.OffensiveWords
import app.distillery.stt.tts.TtsRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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

    /** The offensive-word list, loaded the first time a result is masked. */
    internal val offensiveWords: OffensiveWords by lazy { assets.open(OffensiveWords.ASSET).use { OffensiveWords.load(it) } }

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

        appScope.launch(Dispatchers.IO) {
            val tts = TtsRuntime.get(this@DistilleryApplication)
            tts.downloads.repair(tts.store)
        }
    }

    fun isAnyModelDownloading(): Boolean = anyDownloadActive.value
}
