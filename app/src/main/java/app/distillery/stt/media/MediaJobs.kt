// SPDX-License-Identifier: Apache-2.0
package app.distillery.stt.media

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import app.distillery.stt.DistilleryApplication
import app.distillery.stt.Notifications
import app.distillery.stt.ui.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Long media work (transcribing a file, saving speech as audio) that has to
 * keep going when the user switches apps. One job runs at a time, in the app's
 * scope, while [MediaJobService] holds a "media processing" foreground service
 * with its progress in a notification.
 */
object MediaJobs {

    /** The job running now: its notification title and progress (null while unknown). */
    data class Running(val title: String, val fraction: Float?, val screen: Intent)

    private val _running = MutableStateFlow<Running?>(null)
    val running: StateFlow<Running?> = _running.asStateFlow()

    private var job: Job? = null

    /**
     * Starts [work] unless another job is running (then returns false). [screen]
     * opens the job's screen from the notification. [work] reports progress
     * from 0 to 1, or null when it can't tell.
     */
    @Synchronized
    fun start(context: Context, title: String, screen: Intent, work: suspend (report: (Float?) -> Unit) -> Unit): Boolean {
        if (job?.isActive == true) return false
        val app = context.applicationContext as DistilleryApplication
        _running.value = Running(title, null, screen)
        Notifications.clearWorkDone(app)
        job = app.appScope.launch {
            try {
                work { fraction -> _running.value = _running.value?.copy(fraction = fraction) }
            } catch (e: CancellationException) {
                throw e
            } finally {
                _running.value = null
            }
        }
        app.startForegroundService(Intent(app, MediaJobService::class.java))
        return true
    }

    fun cancel() {
        job?.cancel()
    }
}

/** Keeps the app running, with a progress notification, while a [MediaJobs] job runs. */
class MediaJobService : Service() {

    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            MediaJobs.cancel()
            return START_NOT_STICKY
        }
        val running = MediaJobs.running.value
        startForeground(
            Notifications.ID_WORK,
            notification(running ?: MediaJobs.Running("Finishing…", null, Intent(this, MainActivity::class.java))),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING,
        )
        if (running == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (watcher == null) {
            val app = application as DistilleryApplication
            watcher = app.appScope.launch {
                var lastShown = -1
                MediaJobs.running.collect { now ->
                    if (now == null) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                        return@collect
                    }
                    // Only when the shown percentage changes, so updates aren't rate limited.
                    val shown = ((now.fraction ?: -0.01f) * 100).toInt()
                    if (shown != lastShown) {
                        lastShown = shown
                        getSystemService(android.app.NotificationManager::class.java)
                            .notify(Notifications.ID_WORK, notification(now))
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android limits media processing to six hours a day.
        MediaJobs.cancel()
        stopSelf()
    }

    override fun onDestroy() {
        watcher?.cancel()
        watcher = null
        super.onDestroy()
    }

    private fun notification(running: MediaJobs.Running) = Notifications.workProgress(
        this,
        running.title,
        running.fraction,
        contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(running.screen).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        ),
        cancelIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, MediaJobService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE,
        ),
    )

    companion object {
        const val ACTION_CANCEL = "app.distillery.stt.action.CANCEL_MEDIA_JOB"
    }
}
