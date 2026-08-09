package com.walarm.app.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.PowerManager
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.util.Log
import com.walarm.app.ui.MainActivity
import java.util.concurrent.atomic.AtomicInteger

/**
 * HeartbeatReceiver — Doze-resistant keepalive for [WaListenerService].
 *
 * Android Doze (entered ~2 minutes after screen-off on many OEMs) suspends WorkManager
 * jobs, defers PARTIAL_WAKE_LOCKs, and can unbind the notification listener entirely.
 *
 * Two tiers of one-shot alarms, each re-armed when it fires:
 *   Tier 1: setAlarmClock()             → ~14 min, the only API guaranteed to fire in Doze.
 *   Tier 2: setExactAndAllowWhileIdle() → ~4 min, best-effort rapid check.
 */
class HeartbeatReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "HeartbeatReceiver"

        const val ACTION_HEARTBEAT_ALARM_CLOCK = "com.walarm.app.HEARTBEAT_ALARM_CLOCK"
        const val ACTION_HEARTBEAT_EXACT = "com.walarm.app.HEARTBEAT_EXACT"

        private const val ALARM_CLOCK_INTERVAL_MS = 14 * 60 * 1000L
        private const val EXACT_ALARM_INTERVAL_MS = 4 * 60 * 1000L

        /** No listener callback for this long means the binding is a zombie. */
        private const val ZOMBIE_THRESHOLD_MS = 10 * 60 * 1000L

        private const val REQUEST_ALARM_CLOCK = 7001
        private const val REQUEST_SHOW_INTENT = 7002
        private const val REQUEST_EXACT = 7003

        private const val WAKELOCK_TIMEOUT_MS = 10_000L

        /**
         * Component toggling is a heavy hammer: on some OEM builds it can drop the
         * user's notification-access grant. It is only used once plain requestRebind()
         * has demonstrably failed this many heartbeats in a row.
         */
        private const val TOGGLE_AFTER_CONSECUTIVE_FAILURES = 3

        /** Set by [WaListenerService] on every notification callback. */
        @Volatile
        var lastNotificationTimestamp: Long = System.currentTimeMillis()

        @Volatile
        var lastHeartbeatTimestamp: Long = 0L

        private val consecutiveRebindFailures = AtomicInteger(0)

        /**
         * Arms both tiers. Safe to call repeatedly — FLAG_UPDATE_CURRENT means each call
         * replaces the pending alarm rather than stacking a new one.
         *
         * Called from MainActivity.onCreate, BootReceiver and onListenerConnected.
         */
        fun scheduleHeartbeats(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

            // ── Tier 1: setAlarmClock() — guaranteed Doze breakthrough ──
            val showIntent = PendingIntent.getActivity(
                context,
                REQUEST_SHOW_INTENT,
                Intent(context, MainActivity::class.java),
                PENDING_INTENT_FLAGS
            )
            val alarmClockInfo = AlarmManager.AlarmClockInfo(
                System.currentTimeMillis() + ALARM_CLOCK_INTERVAL_MS,
                showIntent
            )
            try {
                alarmManager.setAlarmClock(alarmClockInfo, heartbeatIntent(context, Tier.ALARM_CLOCK))
                Log.i(TAG, "Tier 1 AlarmClock heartbeat armed (+${ALARM_CLOCK_INTERVAL_MS / 1000}s)")
            } catch (e: SecurityException) {
                Log.e(TAG, "Cannot set alarm clock — SCHEDULE_EXACT_ALARM not granted?", e)
            }

            // ── Tier 2: setExactAndAllowWhileIdle() — best-effort rapid keepalive ──
            val exactPendingIntent = heartbeatIntent(context, Tier.EXACT)
            val triggerAt = SystemClock.elapsedRealtime() + EXACT_ALARM_INTERVAL_MS
            try {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    exactPendingIntent
                )
                Log.i(TAG, "Tier 2 exact heartbeat armed (+${EXACT_ALARM_INTERVAL_MS / 1000}s)")
            } catch (e: SecurityException) {
                Log.e(TAG, "Exact alarm denied; falling back to inexact", e)
                alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, exactPendingIntent)
            }
        }

        fun cancelHeartbeats(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.cancel(heartbeatIntent(context, Tier.ALARM_CLOCK))
            alarmManager.cancel(heartbeatIntent(context, Tier.EXACT))
            Log.i(TAG, "All heartbeat alarms cancelled")
        }

        private enum class Tier(val action: String, val requestCode: Int) {
            ALARM_CLOCK(ACTION_HEARTBEAT_ALARM_CLOCK, REQUEST_ALARM_CLOCK),
            EXACT(ACTION_HEARTBEAT_EXACT, REQUEST_EXACT)
        }

        /**
         * Single source of truth for the heartbeat PendingIntents.
         *
         * Scheduling and cancelling used to build these separately; a PendingIntent only
         * cancels when request code, action and flags all match, so any drift between the
         * two copies would have left un-cancellable alarms behind.
         */
        private fun heartbeatIntent(context: Context, tier: Tier): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                tier.requestCode,
                Intent(context, HeartbeatReceiver::class.java).setAction(tier.action),
                PENDING_INTENT_FLAGS
            )

        private const val PENDING_INTENT_FLAGS =
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.d(TAG, "Heartbeat received: $action")

        val wakeLock = try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "zalarm:heartbeat_wakelock").apply {
                setReferenceCounted(false)
                acquire(WAKELOCK_TIMEOUT_MS)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire heartbeat WakeLock", e)
            null
        }

        try {
            lastHeartbeatTimestamp = System.currentTimeMillis()
            checkListenerHealth(context)
            // One-shot alarms: re-arm for the next round.
            scheduleHeartbeats(context)
        } finally {
            try {
                if (wakeLock?.isHeld == true) wakeLock.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing heartbeat WakeLock", e)
            }
        }
    }

    private fun checkListenerHealth(context: Context) {
        val silentFor = System.currentTimeMillis() - lastNotificationTimestamp

        val problem = when {
            !WaListenerService.isRunning() -> "service is not running"
            silentFor > ZOMBIE_THRESHOLD_MS -> "no callback in ${silentFor / 1000}s — zombie binding"
            else -> null
        }

        if (problem == null) {
            consecutiveRebindFailures.set(0)
            Log.i(TAG, "✅ Listener healthy (last callback ${silentFor / 1000}s ago)")
            return
        }

        val failures = consecutiveRebindFailures.incrementAndGet()
        Log.w(TAG, "⚠️ Listener unhealthy: $problem (attempt $failures)")
        forceRebind(context, escalate = failures >= TOGGLE_AFTER_CONSECUTIVE_FAILURES)
    }

    private fun forceRebind(context: Context, escalate: Boolean) {
        val component = ComponentName(context, WaListenerService::class.java)

        try {
            NotificationListenerService.requestRebind(component)
            Log.i(TAG, "requestRebind() dispatched")
        } catch (e: Exception) {
            Log.e(TAG, "requestRebind() failed", e)
        }

        if (!escalate) return

        // Last resort: bounce the component so the OS re-evaluates the binding.
        try {
            val pm = context.packageManager
            pm.setComponentEnabledSetting(
                component,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
            pm.setComponentEnabledSetting(
                component,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            Log.i(TAG, "NLS component bounced after $TOGGLE_AFTER_CONSECUTIVE_FAILURES failed rebinds")
            consecutiveRebindFailures.set(0)
        } catch (e: Exception) {
            Log.e(TAG, "Error bouncing NLS component", e)
        }
    }
}
