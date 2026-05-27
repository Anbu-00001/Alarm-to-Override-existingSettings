package com.walarm.app.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.util.Log

/**
 * HeartbeatReceiver — Doze-resistant keepalive for NotificationListenerService.
 *
 * Android Doze mode (entered ~2 minutes after screen-off on many OEMs) suspends
 * WorkManager jobs, defers PARTIAL_WAKE_LOCKs, and can unbind the NLS entirely.
 *
 * This receiver uses AlarmManager.setAlarmClock() — the ONLY API guaranteed to
 * fire during deep Doze — to periodically verify that WaListenerService is still
 * bound and receiving notifications. If the NLS appears to have been unbound,
 * it forces a requestRebind().
 *
 * Two-tier scheduling:
 *   Tier 1: setAlarmClock()                 → fires every ~14 min (guaranteed in Doze)
 *   Tier 2: setExactAndAllowWhileIdle()     → fires every ~4 min (best-effort in Doze)
 */
class HeartbeatReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "HeartbeatReceiver"

        // Action constants
        const val ACTION_HEARTBEAT_ALARM_CLOCK = "com.walarm.app.HEARTBEAT_ALARM_CLOCK"
        const val ACTION_HEARTBEAT_EXACT = "com.walarm.app.HEARTBEAT_EXACT"

        // Intervals
        private const val ALARM_CLOCK_INTERVAL_MS = 14 * 60 * 1000L   // 14 minutes
        private const val EXACT_ALARM_INTERVAL_MS = 4 * 60 * 1000L    // 4 minutes

        // Tracks last time onNotificationPosted was called (set by WaListenerService)
        @Volatile
        var lastNotificationTimestamp: Long = System.currentTimeMillis()

        // Tracks last heartbeat execution
        @Volatile
        var lastHeartbeatTimestamp: Long = 0L

        /**
         * Schedule both tiers of heartbeat alarms. Should be called from:
         *  - MainActivity.onCreate()
         *  - BootReceiver.onReceive()
         *  - WaListenerService.onListenerConnected()
         */
        fun scheduleHeartbeats(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

            // ── Tier 1: setAlarmClock() — Guaranteed Doze breakthrough ──
            val alarmClockIntent = Intent(context, HeartbeatReceiver::class.java).apply {
                action = ACTION_HEARTBEAT_ALARM_CLOCK
            }
            val alarmClockPi = PendingIntent.getBroadcast(
                context,
                7001,
                alarmClockIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
            )
            // AlarmManager.AlarmClockInfo is the only alarm type that unconditionally wakes
            // the device from Doze. The showIntent (2nd param) is what the system shows the
            // user in the status bar — we point it at our main activity.
            val showIntent = PendingIntent.getActivity(
                context,
                7002,
                Intent(context, com.walarm.app.ui.MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
            )
            val triggerAtMillis = System.currentTimeMillis() + ALARM_CLOCK_INTERVAL_MS
            val alarmClockInfo = AlarmManager.AlarmClockInfo(triggerAtMillis, showIntent)
            try {
                alarmManager.setAlarmClock(alarmClockInfo, alarmClockPi)
                Log.i(TAG, "Tier 1 AlarmClock heartbeat scheduled at +${ALARM_CLOCK_INTERVAL_MS / 1000}s")
            } catch (e: SecurityException) {
                Log.e(TAG, "Cannot set alarm clock — SCHEDULE_EXACT_ALARM not granted?", e)
            }

            // ── Tier 2: setExactAndAllowWhileIdle() — Best-effort rapid keepalive ──
            val exactIntent = Intent(context, HeartbeatReceiver::class.java).apply {
                action = ACTION_HEARTBEAT_EXACT
            }
            val exactPi = PendingIntent.getBroadcast(
                context,
                7003,
                exactIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        SystemClock.elapsedRealtime() + EXACT_ALARM_INTERVAL_MS,
                        exactPi
                    )
                    Log.i(TAG, "Tier 2 exact heartbeat scheduled at +${EXACT_ALARM_INTERVAL_MS / 1000}s")
                } catch (e: SecurityException) {
                    Log.e(TAG, "Cannot set exact alarm", e)
                    // Fallback to inexact
                    alarmManager.set(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        SystemClock.elapsedRealtime() + EXACT_ALARM_INTERVAL_MS,
                        exactPi
                    )
                }
            } else {
                alarmManager.setExact(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + EXACT_ALARM_INTERVAL_MS,
                    exactPi
                )
            }
        }

        /**
         * Cancel all scheduled heartbeats (for testing or cleanup)
         */
        fun cancelHeartbeats(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

            val pi1 = PendingIntent.getBroadcast(
                context, 7001,
                Intent(context, HeartbeatReceiver::class.java).apply { action = ACTION_HEARTBEAT_ALARM_CLOCK },
                flags
            )
            val pi2 = PendingIntent.getBroadcast(
                context, 7003,
                Intent(context, HeartbeatReceiver::class.java).apply { action = ACTION_HEARTBEAT_EXACT },
                flags
            )
            alarmManager.cancel(pi1)
            alarmManager.cancel(pi2)
            Log.i(TAG, "All heartbeat alarms cancelled")
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.d(TAG, "Heartbeat received: $action")

        // Acquire a short WakeLock to ensure we complete the check
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "zalarm:heartbeat_wakelock"
        )
        try {
            wakeLock.acquire(10_000L) // 10 second timeout max
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire heartbeat WakeLock", e)
        }

        try {
            lastHeartbeatTimestamp = System.currentTimeMillis()

            // Check if WaListenerService is still alive
            if (!WaListenerService.isRunning()) {
                Log.w(TAG, "⚠️ WaListenerService is NOT running! Forcing rebind...")
                forceRebind(context)
            } else {
                // Check if notifications are actually being received
                // If no notification callback in 10 minutes, something is wrong
                val timeSinceLastNotification = System.currentTimeMillis() - lastNotificationTimestamp
                if (timeSinceLastNotification > 10 * 60 * 1000L) {
                    Log.w(TAG, "⚠️ No notification callback in ${timeSinceLastNotification / 1000}s — NLS may be zombie-bound. Forcing rebind...")
                    forceRebind(context)
                } else {
                    Log.i(TAG, "✅ WaListenerService confirmed alive (last notification ${timeSinceLastNotification / 1000}s ago)")
                }
            }

            // Re-schedule the next heartbeat (one-shot alarm pattern)
            scheduleHeartbeats(context)

        } finally {
            try {
                if (wakeLock.isHeld) wakeLock.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing heartbeat WakeLock", e)
            }
        }
    }

    private fun forceRebind(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // This tells the OS to unbind and re-bind the NLS
                NotificationListenerService.requestRebind(
                    ComponentName(context, WaListenerService::class.java)
                )
                Log.i(TAG, "requestRebind() dispatched successfully")
            }

            // Also try toggling the component to force OS re-evaluation
            val packageManager = context.packageManager
            val componentName = ComponentName(context, WaListenerService::class.java)
            
            // Disable then re-enable the component
            packageManager.setComponentEnabledSetting(
                componentName,
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                android.content.pm.PackageManager.DONT_KILL_APP
            )
            // Small delay via Handler isn't ideal in a BroadcastReceiver, so re-enable immediately
            packageManager.setComponentEnabledSetting(
                componentName,
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                android.content.pm.PackageManager.DONT_KILL_APP
            )
            Log.i(TAG, "NLS component toggled (disabled then re-enabled)")

        } catch (e: Exception) {
            Log.e(TAG, "Error during forceRebind", e)
        }
    }
}
