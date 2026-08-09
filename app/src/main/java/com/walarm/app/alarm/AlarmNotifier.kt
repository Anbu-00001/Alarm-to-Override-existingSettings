package com.walarm.app.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import androidx.core.app.NotificationCompat
import com.walarm.app.service.StopAlarmReceiver
import com.walarm.app.ui.MainActivity

/**
 * Notification ids, partitioned into non-overlapping ranges.
 *
 * Ids were previously derived as `sbn.id` and `sbn.id + 1000` straight from WhatsApp's
 * own notification id. That is attacker-free but not collision-free: a WhatsApp id of
 * 8901 produced an alert id of 9901 — exactly [FOREGROUND_SERVICE] — so posting the
 * alarm would overwrite the foreground-service notification that keeps the listener
 * alive. Deriving from the (stable, per-conversation) notification key into reserved
 * high ranges makes that impossible.
 */
object NotificationIds {
    const val FOREGROUND_SERVICE = 9901

    private const val ALERT_BASE = 1_000_000
    private const val REPOST_BASE = 2_000_000
    private const val SPAN = 100_000

    fun alert(sbnKey: String): Int = ALERT_BASE + slot(sbnKey)
    fun repost(sbnKey: String): Int = REPOST_BASE + slot(sbnKey)

    private fun slot(sbnKey: String): Int =
        ((sbnKey.hashCode().toLong() and 0x7FFFFFFFL) % SPAN).toInt()
}

/**
 * Owns every notification ZAlarm posts: the foreground-service badge, the silent
 * repost of an intercepted message, and the full-screen alarm alert.
 */
class AlarmNotifier(private val context: Context) {

    private val manager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun createChannels() {
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_FOREGROUND,
                "ZAlarm Background Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps ZAlarm listening in the background"
                setShowBadge(false)
            }
        )

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SILENT_REPOST,
                "Silent WhatsApp Reposts",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Displays muted notifications intercepted from WhatsApp"
                enableVibration(false)
                enableLights(false)
            }
        )

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERT,
                "ZAlarm Priority Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "High priority alarms that bypass DND and show overlays"
                enableLights(true)
                lightColor = Color.RED
                enableVibration(true)
                // Audio is driven by AlarmPlayer on STREAM_ALARM; a channel sound here
                // would double up and play at notification volume.
                setSound(null, null)
            }
        )
    }

    fun buildForegroundNotification(): Notification =
        NotificationCompat.Builder(context, CHANNEL_FOREGROUND)
            .setContentTitle("ZAlarm active")
            .setContentText("Monitoring priority WhatsApp notifications")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentIntent(activityPendingIntent(REQUEST_OPEN_APP, Intent(context, MainActivity::class.java)))
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()

    /**
     * Posts the full-screen alarm alert and returns the id it was posted under, so the
     * caller can hand the same id to the STOP action.
     */
    fun postAlert(content: AlertContent, overlayIntent: Intent): Int {
        val notificationId = NotificationIds.alert(content.sbnKey)

        val fullScreenIntent = PendingIntent.getActivity(
            context,
            notificationId,
            overlayIntent,
            // Mutable: the system fills in launch context when it shows the activity.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        val stopIntent = Intent(context, StopAlarmReceiver::class.java).apply {
            action = StopAlarmReceiver.ACTION_STOP_ALARM
            putExtra(StopAlarmReceiver.EXTRA_NOTIFICATION_ID, notificationId)
        }
        val stopPendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId + 1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ALERT)
            .setContentTitle("🚨 ZAlarm: ${content.contactName}")
            .setContentText(content.message)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(fullScreenIntent, true)
            .setOngoing(true)
            .setAutoCancel(false)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "🔇 STOP ALARM", stopPendingIntent)
            .setContentIntent(fullScreenIntent)
            .build()

        manager.notify(notificationId, notification)
        return notificationId
    }

    /** Re-posts an intercepted message silently so it still reaches the shade. */
    fun postSilentRepost(sbnKey: String, sender: String, message: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_SILENT_REPOST)
            .setContentTitle(sender)
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setContentIntent(
                activityPendingIntent(
                    NotificationIds.repost(sbnKey),
                    Intent(context, MainActivity::class.java)
                )
            )
            .setAutoCancel(true)
            .build()

        manager.notify(NotificationIds.repost(sbnKey), notification)
    }

    private fun activityPendingIntent(requestCode: Int, intent: Intent): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    companion object {
        const val CHANNEL_FOREGROUND = "zalarm_service_channel"
        const val CHANNEL_SILENT_REPOST = "zalarm_silent_reposts"
        const val CHANNEL_ALERT = "zalarm_alerts"

        private const val REQUEST_OPEN_APP = 0
    }
}
