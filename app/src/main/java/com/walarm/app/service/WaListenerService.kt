package com.walarm.app.service

import android.app.Notification
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.walarm.app.alarm.AlarmIntents
import com.walarm.app.alarm.AlarmNotifier
import com.walarm.app.alarm.AlarmPlayer
import com.walarm.app.alarm.AlertContent
import com.walarm.app.alarm.NotificationIds
import com.walarm.app.data.AppDatabase
import com.walarm.app.data.AppSettings
import com.walarm.app.data.DebugLog
import com.walarm.app.data.SettingsRepository
import com.walarm.app.data.WatchedContact
import com.walarm.app.domain.AlarmAction
import com.walarm.app.domain.AlarmDecision
import com.walarm.app.domain.ContactMatcher
import com.walarm.app.domain.PresenceSnapshot
import com.walarm.app.domain.ScheduleWindow
import com.walarm.app.domain.TriggerGate
import com.walarm.app.domain.TriggerSource
import com.walarm.app.util.NotificationParser
import com.walarm.app.util.PresenceHelper
import com.walarm.app.util.UrgencyClassifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Collections

/**
 * Intercepts WhatsApp notifications and escalates the ones that matter into a full alarm.
 *
 * The per-notification work is a straight pipeline — parse, match, log, gate, decide,
 * act — with each stage delegated to a pure, unit-testable object in `domain`. The
 * service itself is only responsible for Android lifecycle, wake locks and I/O.
 */
class WaListenerService : NotificationListenerService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var database: AppDatabase
    private lateinit var notifier: AlarmNotifier

    companion object {
        private const val TAG = "WaListenerService"

        /** `com.android.shell` is kept so `test_device.py` can inject synthetic notifications. */
        private val WATCHED_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b", "com.android.shell")

        /** Wake lock budget for processing a single notification. */
        private const val PROCESSING_WAKELOCK_MS = 15_000L

        /**
         * The reply cache holds live [StatusBarNotification] objects. It used to be an
         * unsynchronized map that only ever shrank when Android happened to call
         * onNotificationRemoved, so a busy device leaked notifications for the life of
         * the process. It is now a bounded, synchronized LRU.
         */
        private const val REPLY_CACHE_SIZE = 64

        private val replyCache: MutableMap<String, StatusBarNotification> =
            Collections.synchronizedMap(
                object : LinkedHashMap<String, StatusBarNotification>(REPLY_CACHE_SIZE, 0.75f, true) {
                    override fun removeEldestEntry(
                        eldest: MutableMap.MutableEntry<String, StatusBarNotification>
                    ): Boolean = size > REPLY_CACHE_SIZE
                }
            )

        @Volatile
        private var isServiceRunning = false

        /** Key of the notification whose alarm is currently sounding. */
        @Volatile
        private var activeTriggerKey: String? = null

        fun isRunning(): Boolean = isServiceRunning

        /** Reply to a cached WhatsApp notification through its RemoteInput action. */
        fun replyToNotification(context: Context, sbnKey: String, replyText: String) {
            val sbn = replyCache[sbnKey]
            if (sbn == null) {
                Log.w(TAG, "Cannot reply: notification for key $sbnKey is not cached")
                return
            }

            if (sendRemoteInputReply(context, sbn, replyText)) return

            Log.d(TAG, "RemoteInput unavailable — falling back to launching ${sbn.packageName}")
            openPackage(context, sbn.packageName)
        }

        private fun sendRemoteInputReply(
            context: Context,
            sbn: StatusBarNotification,
            replyText: String
        ): Boolean {
            val actions = sbn.notification.actions ?: return false

            for (action in actions) {
                val remoteInputs = action.remoteInputs ?: continue
                for (remoteInput in remoteInputs) {
                    val results = Bundle().apply { putCharSequence(remoteInput.resultKey, replyText) }
                    val intent = Intent()
                    RemoteInput.addResultsToIntent(arrayOf(remoteInput), intent, results)

                    try {
                        action.actionIntent.send(context, 0, intent)
                        Log.d(TAG, "Replied to ${sbn.key} via RemoteInput")
                        return true
                    } catch (e: Exception) {
                        Log.e(TAG, "Error sending reply intent", e)
                    }
                }
            }
            return false
        }

        private fun openPackage(context: Context, packageName: String) {
            try {
                // Requires a <queries> entry in the manifest on Android 11+, otherwise
                // package visibility rules make this silently return null.
                val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
                if (launchIntent == null) {
                    Log.w(TAG, "No launch intent visible for $packageName")
                    return
                }
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Error opening $packageName", e)
            }
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.getDatabase(this)
        notifier = AlarmNotifier(this)
        isServiceRunning = true
        notifier.createChannels()
        startForegroundBadge()
        Log.i(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand (flags=$flags, startId=$startId)")
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        // Release the scope with the instance that owns it; a rebind builds a fresh one.
        serviceScope.cancel()
        Log.w(TAG, "Service destroyed — requesting immediate rebind")
        try {
            requestRebind(ComponentName(this, WaListenerService::class.java))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request rebind on destroy", e)
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        isServiceRunning = true
        HeartbeatReceiver.lastNotificationTimestamp = System.currentTimeMillis()
        HeartbeatReceiver.scheduleHeartbeats(applicationContext)
        Log.i(TAG, "Listener connected; heartbeats (re)scheduled")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isServiceRunning = false
        // Heartbeats are deliberately left running — they are what gets us re-bound.
        Log.w(TAG, "Listener disconnected — relying on heartbeat for rebind")
    }

    // ── Notification pipeline ────────────────────────────────────────────────────

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // Any notification at all proves the listener is still live.
        HeartbeatReceiver.lastNotificationTimestamp = System.currentTimeMillis()

        if (sbn.packageName !in WATCHED_PACKAGES) return
        replyCache[sbn.key] = sbn

        val wakeLock = acquireProcessingWakeLock(sbn.key)
        serviceScope.launch {
            try {
                process(sbn)
            } catch (e: Exception) {
                Log.e(TAG, "Error processing ${sbn.key}", e)
            } finally {
                releaseQuietly(wakeLock, sbn.key)
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        replyCache.remove(sbn.key)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap, reason: Int) {
        replyCache.remove(sbn.key)
        if (sbn.key != activeTriggerKey) return

        if (reason in USER_DISMISSAL_REASONS) {
            Log.i(TAG, "Triggering notification dismissed by user — stopping alarm")
            AlarmPlayer.stop()
            activeTriggerKey = null
        } else {
            Log.d(TAG, "Triggering notification removed programmatically (reason=$reason); alarm continues")
        }
    }

    private suspend fun process(sbn: StatusBarNotification) {
        val parsed = NotificationParser.parse(sbn) ?: return
        val settings = SettingsRepository.current(applicationContext)

        val isCall = isCallNotification(sbn, parsed.message)
        if (isCall && !settings.overrideWaCalls) {
            Log.d(TAG, "WhatsApp call ignored — override_wa_calls is off")
            return
        }

        val contactDao = database.contactDao()
        val watched = ContactMatcher.findWatched(contactDao, parsed)
        val keywordMatch = if (watched == null) {
            ContactMatcher.findKeywordMatch(contactDao, parsed.message)
        } else {
            null
        }

        val urgency = UrgencyClassifier.analyze(parsed.message)
        val urgencyOverride = settings.nlpEnabled && urgency.score >= settings.nlpThreshold

        val source = when {
            watched != null -> TriggerSource.WATCHLIST
            keywordMatch != null -> TriggerSource.KEYWORD
            urgencyOverride -> TriggerSource.URGENCY
            else -> null
        }

        database.debugLogDao().insertLog(parsed.toDebugLog(sbn.packageName, matched = source != null))
        if (source == null) return

        val target = watched ?: keywordMatch ?: transientContactFor(parsed)
        Log.i(TAG, "Trigger via $source for '${target.name}' (urgency=${urgency.score})")

        val now = System.currentTimeMillis()
        if (!TriggerGate.tryTrigger(target, now)) {
            Log.d(TAG, "Trigger for '${target.name}' skipped — inside ${target.cooldownSeconds}s cooldown")
            return
        }
        if (target.id != 0L) contactDao.updateLastTriggered(target.id, now)

        val verdict = AlarmDecision.decide(
            contact = target,
            settings = settings,
            presence = presenceSnapshot(settings),
            minuteOfDay = ScheduleWindow.minuteOfDayNow()
        )
        Log.i(TAG, "Verdict=${verdict.action}: ${verdict.reason}")

        // Silence WhatsApp's own tone and keep the message in the shade. Calls are left
        // alone: cancelling a ringing call notification would drop the call UI.
        if (!isCall) {
            try {
                cancelNotification(sbn.key)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to cancel ${sbn.key}", e)
            }
            notifier.postSilentRepost(sbn.key, parsed.sender, parsed.message)
        }

        when (verdict.action) {
            AlarmAction.SILENT -> Unit
            AlarmAction.VIBRATE_ONLY -> AlarmPlayer.triggerVibration(applicationContext, repeat = false)
            AlarmAction.LOUD -> raiseFullAlarm(sbn, parsed, target)
        }
    }

    private fun raiseFullAlarm(
        sbn: StatusBarNotification,
        parsed: NotificationParser.ParsedNotification,
        contact: WatchedContact
    ) {
        AlarmPlayer.play(applicationContext, contact)
        activeTriggerKey = sbn.key

        val content = AlertContent(
            sbnKey = sbn.key,
            contactName = contact.name,
            message = parsed.message,
            groupName = parsed.groupName,
            isGroup = parsed.isGroup
        )
        val overlayIntent = AlarmIntents.overlayIntent(applicationContext, content)

        // The full-screen intent is the supported way to reach a locked screen; the
        // direct start is a best-effort fallback for OEMs that defer the FSI.
        notifier.postAlert(content, overlayIntent)
        try {
            startActivity(overlayIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Direct overlay start refused (expected on Android 10+)", e)
        }
    }

    /** Builds a throwaway contact for keyword/urgency triggers that have no watchlist row. */
    private fun transientContactFor(parsed: NotificationParser.ParsedNotification) = WatchedContact(
        name = parsed.sender,
        isGroup = parsed.isGroup,
        useAlarmVolume = true,
        repeatUntilDismissed = true,
        escalatingVolume = true,
        cooldownSeconds = TRANSIENT_COOLDOWN_SECONDS
    )

    /** Only queries the sensors the user actually enabled a rule for. */
    private fun presenceSnapshot(settings: AppSettings) = PresenceSnapshot(
        screenInteractive = settings.suppressOnScreenOn &&
            PresenceHelper.isScreenInteractive(applicationContext),
        wifiSsid = if (settings.suppressOnHomeWifi && settings.homeWifiSsid.isNotBlank()) {
            PresenceHelper.getWifiSsid(applicationContext)
        } else {
            null
        },
        wearableConnected = settings.suppressOnWearable &&
            PresenceHelper.isSmartwatchConnected(applicationContext)
    )

    private fun isCallNotification(sbn: StatusBarNotification, message: String): Boolean =
        sbn.notification.category == Notification.CATEGORY_CALL ||
            CALL_HINTS.any { message.contains(it, ignoreCase = true) }

    private fun NotificationParser.ParsedNotification.toDebugLog(packageName: String, matched: Boolean) =
        DebugLog(
            timestamp = System.currentTimeMillis(),
            packageName = packageName,
            title = rawTitle,
            text = rawText,
            subText = rawSubText,
            conversationTitle = rawConversationTitle,
            matched = matched,
            parsedSender = sender,
            parsedMessage = message,
            isGroupChat = isGroup,
            individualSender = individualSender
        )

    // ── Foreground + wake locks ──────────────────────────────────────────────────

    private fun startForegroundBadge() {
        val notification = notifier.buildForegroundNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NotificationIds.FOREGROUND_SERVICE,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NotificationIds.FOREGROUND_SERVICE, notification)
        }
    }

    private fun acquireProcessingWakeLock(key: String): PowerManager.WakeLock? = try {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "zalarm:notification_posted_wakelock").apply {
            setReferenceCounted(false)
            acquire(PROCESSING_WAKELOCK_MS)
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to acquire processing WakeLock for $key", e)
        null
    }

    private fun releaseQuietly(wakeLock: PowerManager.WakeLock?, key: String) {
        try {
            if (wakeLock?.isHeld == true) wakeLock.release()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release processing WakeLock for $key", e)
        }
    }
}

private const val TRANSIENT_COOLDOWN_SECONDS = 30

private val CALL_HINTS = listOf("voice call", "video call", "incoming call")

/**
 * REASON_* values that mean *the user* got rid of the notification.
 *
 * The previous literal set was `{1, 3, 8}` with a comment claiming 8 was
 * REASON_USER_STOPPED. It is not — 8 is REASON_APP_CANCEL, i.e. WhatsApp withdrawing
 * its own notification. So the alarm stopped when WhatsApp tidied up, while a genuine
 * swipe-away (REASON_CANCEL, 2) was missing from the set and left the alarm ringing.
 *
 * Our own `cancelNotification()` arrives as REASON_LISTENER_CANCEL and is correctly
 * excluded, so silencing WhatsApp's tone still never silences the alarm.
 */
private val USER_DISMISSAL_REASONS = setOf(
    NotificationListenerService.REASON_CLICK,
    NotificationListenerService.REASON_CANCEL,
    NotificationListenerService.REASON_CANCEL_ALL
)
