// SPDX-License-Identifier: Apache-2.0
package app.distillery.stt.tts.read

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder
import app.distillery.stt.DistilleryApplication
import app.distillery.stt.Notifications
import app.distillery.stt.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Keeps Read aloud playing while Distillery isn't on screen: a "media
 * playback" foreground service with a media session, so the notification and
 * lock screen have play, pause and skip, and headphones' buttons work.
 */
class ReadAloudService : Service() {

    private var session: MediaSession? = null
    private var watcher: Job? = null

    /** Pauses when headphones are unplugged, rather than reading out loud to the room. */
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) ReadAloud.pause()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        session = MediaSession(this, "DistilleryReadAloud").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = ReadAloud.resume(this@ReadAloudService)
                override fun onPause() = ReadAloud.pause()
                override fun onSkipToNext() = ReadAloud.next(this@ReadAloudService)
                override fun onSkipToPrevious() = ReadAloud.previous(this@ReadAloudService)
                override fun onStop() = ReadAloud.stopPlayback(this@ReadAloudService)
            })
            setSessionActivity(openScreen())
            isActive = true
        }
        registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> ReadAloud.toggle(this)
            ACTION_NEXT -> ReadAloud.next(this)
            ACTION_PREVIOUS -> ReadAloud.previous(this)
            ACTION_STOP -> ReadAloud.stopPlayback(this)
        }
        // Every start, not just the first: each startForegroundService() call expects it.
        startForeground(
            Notifications.ID_READ_ALOUD,
            notification(ReadAloud.state.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
        if (watcher == null) {
            watcher = (application as DistilleryApplication).appScope.launch {
                ReadAloud.state.collect { s -> update(s) }
            }
        }
        return START_NOT_STICKY
    }

    private fun update(state: ReadAloud.State?) {
        val status = state?.status
        if (state == null || status == ReadAloud.Status.Ready || status == ReadAloud.Status.Finished) {
            session?.setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_STOPPED, 0, 1f).build())
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        val playing = status == ReadAloud.Status.Playing || status == ReadAloud.Status.Preparing
        session?.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP,
                )
                .setState(
                    if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                    state.speed,
                )
                .build(),
        )
        session?.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title(state))
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "${state.voice.displayName} · Distillery")
                .build(),
        )
        getSystemService(NotificationManager::class.java).notify(Notifications.ID_READ_ALOUD, notification(state))
    }

    private fun notification(state: ReadAloud.State?): Notification {
        val playing = state?.status == ReadAloud.Status.Playing || state?.status == ReadAloud.Status.Preparing
        val builder = Notification.Builder(this, Notifications.CHANNEL_READ_ALOUD)
            .setSmallIcon(R.drawable.ic_stat_distillery)
            .setContentTitle(state?.let(::title) ?: "Read aloud")
            .setContentText(state?.let { "Sentence ${it.index + 1} of ${it.sentences.size}" })
            .setContentIntent(openScreen())
            .setDeleteIntent(action(ACTION_STOP, 4))
            .setOngoing(playing)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(button(R.drawable.ic_skip_previous, "Previous", ACTION_PREVIOUS, 1))
            .addAction(
                if (playing) button(R.drawable.ic_pause, "Pause", ACTION_TOGGLE, 2)
                else button(R.drawable.ic_play, "Play", ACTION_TOGGLE, 2),
            )
            .addAction(button(R.drawable.ic_skip_next, "Next", ACTION_NEXT, 3))
        session?.let {
            builder.setStyle(Notification.MediaStyle().setMediaSession(it.sessionToken).setShowActionsInCompactView(0, 1, 2))
        }
        return builder.build()
    }

    private fun title(state: ReadAloud.State): String =
        state.title?.takeIf { it.isNotBlank() } ?: state.sentences.firstOrNull()?.text?.take(60) ?: "Read aloud"

    private fun button(icon: Int, label: String, action: String, code: Int) =
        Notification.Action.Builder(Icon.createWithResource(this, icon), label, action(action, code)).build()

    private fun action(action: String, code: Int): PendingIntent = PendingIntent.getService(
        this,
        code,
        Intent(this, ReadAloudService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun openScreen(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, ReadAloudActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    override fun onDestroy() {
        watcher?.cancel()
        watcher = null
        runCatching { unregisterReceiver(noisy) }
        session?.release()
        session = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_TOGGLE = "app.distillery.stt.action.READ_TOGGLE"
        const val ACTION_NEXT = "app.distillery.stt.action.READ_NEXT"
        const val ACTION_PREVIOUS = "app.distillery.stt.action.READ_PREVIOUS"
        const val ACTION_STOP = "app.distillery.stt.action.READ_STOP"
    }
}
