// SPDX-License-Identifier: GPL-3.0-or-later
package app.distillery.stt

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import app.distillery.stt.ui.MainActivity

internal object Notifications {

    private const val CHANNEL_DOWNLOADS = "downloads"
    private const val CHANNEL_ATTENTION = "attention"

    const val ID_DOWNLOAD = 1
    private const val ID_NEEDS_MIC = 2
    private const val ID_NEEDS_MODEL = 3

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(
                    CHANNEL_DOWNLOADS,
                    context.getString(R.string.channel_downloads),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = context.getString(R.string.channel_downloads_description) },
                NotificationChannel(
                    CHANNEL_ATTENTION,
                    context.getString(R.string.channel_attention),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = context.getString(R.string.channel_attention_description) },
            ),
        )
    }

    fun downloadProgress(
        context: Context,
        title: String,
        fraction: Float?,
        cancelIntent: PendingIntent,
    ): Notification {
        val builder = Notification.Builder(context, CHANNEL_DOWNLOADS)
            .setSmallIcon(R.drawable.ic_stat_distillery)
            .setContentTitle(title)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(context))
            .addAction(
                Notification.Action.Builder(null, context.getString(android.R.string.cancel), cancelIntent).build(),
            )
        if (fraction == null || fraction < 0f) {
            builder.setProgress(0, 0, true)
        } else {
            builder.setProgress(1000, (fraction * 1000).toInt().coerceIn(0, 1000), false)
        }
        return builder.build()
    }

    fun showNeedsMicrophone(context: Context) = showAttention(
        context,
        ID_NEEDS_MIC,
        context.getString(R.string.notification_needs_mic),
        context.getString(R.string.notification_needs_mic_body),
    )

    fun showNeedsModel(context: Context) = showAttention(
        context,
        ID_NEEDS_MODEL,
        context.getString(R.string.notification_needs_model),
        context.getString(R.string.notification_needs_model_body),
    )

    fun clearAttention(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancel(ID_NEEDS_MIC)
        manager.cancel(ID_NEEDS_MODEL)
    }

    private fun showAttention(context: Context, id: Int, title: String, body: String) {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        val notification = Notification.Builder(context, CHANNEL_ATTENTION)
            .setSmallIcon(R.drawable.ic_stat_distillery)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
        context.getSystemService(NotificationManager::class.java).notify(id, notification)
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
