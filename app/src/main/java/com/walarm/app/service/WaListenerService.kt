package com.walarm.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.service.notification.NotificationListenerService
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.walarm.app.R
import com.walarm.app.alarm.AlarmPlayer
import com.walarm.app.data.AppDatabase
import com.walarm.app.data.DebugLog
import com.walarm.app.data.WatchedContact
import com.walarm.app.ui.AlarmActivity
import com.walarm.app.ui.MainActivity
import com.walarm.app.util.NotificationParser
import com.walarm.app.util.PresenceHelper
import com.walarm.app.util.UrgencyClassifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

val Context.dataStore by preferencesDataStore(name = "settings")

class WaListenerService : NotificationListenerService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var database: AppDatabase

    companion object {
        private const val TAG = "WaListenerService"
        private const val SERVICE_NOTIFICATION_ID = 9901
        private const val FOREGROUND_CHANNEL_ID = "zalarm_service_channel"
        private const val SILENT_POST_CHANNEL_ID = "zalarm_silent_reposts"
        private const val ALARM_ALERT_CHANNEL_ID = "zalarm_alerts"

        private var isServiceRunning = false
        private val notificationsMap = mutableMapOf<String, StatusBarNotification>()
        private var activeTriggerKey: String? = null

        fun isRunning(): Boolean = isServiceRunning

        // Reply to an active WhatsApp notification using RemoteInput API
        fun replyToNotification(context: Context, sbnKey: String, replyText: String) {
            val sbn = notificationsMap[sbnKey]
            if (sbn == null) {
                Log.w(TAG, "Cannot reply: notification for key $sbnKey is not cached")
                return
            }

            val actions = sbn.notification.actions
            if (actions.isNullOrEmpty()) {
                Log.w(TAG, "Cannot reply: notification has no actions")
                return
            }

            var replySent = false
            for (action in actions) {
                val remoteInputs = action.remoteInputs ?: continue
                for (remoteInput in remoteInputs) {
                    val resultKey = remoteInput.resultKey
                    val bundle = Bundle().apply {
                        putCharSequence(resultKey, replyText)
                    }
                    val intent = Intent()
                    android.app.RemoteInput.addResultsToIntent(
                        arrayOf(remoteInput),
                        intent,
                        bundle
                    )

                    try {
                        action.actionIntent.send(context, 0, intent)
                        replySent = true
                        Log.d(TAG, "Successfully replied to $sbnKey via RemoteInput")
                        break
                    } catch (e: Exception) {
                        Log.e(TAG, "Error sending action reply intent", e)
                    }
                }
                if (replySent) break
            }

            if (!replySent) {
                // Fallback: Open WhatsApp directly via package URI deep link
                try {
                    val contactTitle = sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
                    Log.d(TAG, "RemoteInput unavailable. Triggering WhatsApp deep link fallback for: $contactTitle")
                    // Since we do not have their phone number, opening the main app is the best fallback
                    val launchIntent = context.packageManager.getLaunchIntentForPackage(sbn.packageName)
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(launchIntent)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error opening WhatsApp fallback launcher", e)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.getDatabase(this)
        isServiceRunning = true
        createNotificationChannels()
        startForegroundService()
        Log.i(TAG, "WaListenerService.onCreate() — service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY ensures the OS restarts this service if it's killed
        Log.d(TAG, "onStartCommand called (flags=$flags, startId=$startId)")
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        Log.w(TAG, "WaListenerService.onDestroy() — requesting immediate rebind")
        // Immediately request rebind when destroyed
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                requestRebind(
                    android.content.ComponentName(this, WaListenerService::class.java)
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request rebind on destroy", e)
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        isServiceRunning = true
        HeartbeatReceiver.lastNotificationTimestamp = System.currentTimeMillis()
        Log.i(TAG, "Notification Listener connected successfully")

        // Schedule Doze-resistant heartbeats whenever we connect/reconnect
        HeartbeatReceiver.scheduleHeartbeats(applicationContext)
        Log.i(TAG, "Heartbeat alarms (re)scheduled from onListenerConnected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isServiceRunning = false
        Log.w(TAG, "Notification Listener disconnected — will rely on heartbeat for rebind")
        // Don't cancel heartbeats here — they're our lifeline to get re-bound
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // Update heartbeat liveness tracker for EVERY notification (proves NLS is alive)
        HeartbeatReceiver.lastNotificationTimestamp = System.currentTimeMillis()

        val packageName = sbn.packageName
        if (packageName != "com.whatsapp" && 
            packageName != "com.whatsapp.w4b" && 
            packageName != "com.android.shell") {
            return
        }

        // Cache the notification for replies
        notificationsMap[sbn.key] = sbn

        // Acquire WakeLock synchronously to keep CPU awake for coroutine execution
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "zalarm:notification_posted_wakelock")
        try {
            wakeLock.acquire(15000L) // 15 seconds timeout max
            Log.d(TAG, "Sync WakeLock acquired for ${sbn.key}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire sync WakeLock", e)
        }

        serviceScope.launch {
            try {
                val parsed = NotificationParser.parse(sbn) ?: return@launch
            
            // Check database to see if we match a WatchedContact
            val contactDao = database.contactDao()
            val debugLogDao = database.debugLogDao()
            
            // Multi-tier matching:
            // Tier 1: Exact match by parsed sender (contact name for DM, group name for groups)
            // Tier 2: Fuzzy/contains match by parsed sender
            // Tier 3: Exact match by individual sender (for group chats)
            // Tier 4: Fuzzy/contains match by individual sender (for group chats)
            var matchedContact = contactDao.getContactByName(parsed.sender)
            Log.d(TAG, "Tier 1 exact match for '${parsed.sender}': ${matchedContact?.name ?: "NONE"}")
            
            if (matchedContact == null) {
                matchedContact = contactDao.getContactByNameFuzzy(parsed.sender)
                Log.d(TAG, "Tier 2 fuzzy match for '${parsed.sender}': ${matchedContact?.name ?: "NONE"}")
            }
            
            if (matchedContact == null && parsed.isGroup && parsed.individualSender != null) {
                matchedContact = contactDao.getContactByName(parsed.individualSender)
                Log.d(TAG, "Tier 3 exact match for individual '${parsed.individualSender}': ${matchedContact?.name ?: "NONE"}")
                
                if (matchedContact == null) {
                    matchedContact = contactDao.getContactByNameFuzzy(parsed.individualSender)
                    Log.d(TAG, "Tier 4 fuzzy match for individual '${parsed.individualSender}': ${matchedContact?.name ?: "NONE"}")
                }
            }

            // If we still don't have a contact, let's load all contacts and check for keyword filters
            var isKeywordMatched = false
            var keywordContact: WatchedContact? = null

            if (matchedContact == null) {
                val allContacts = contactDao.getAllContacts()
                for (contact in allContacts) {
                    if (contact.isKeywordFilterEnabled) {
                        val keywordList = contact.keywords.split(",").map { it.trim().lowercase() }
                        val lowerMessage = parsed.message.lowercase()
                        if (keywordList.any { it.isNotEmpty() && lowerMessage.contains(it) }) {
                            isKeywordMatched = true
                            keywordContact = contact
                            break
                        }
                    }
                }
            }

            // NLP Urgency Override Check
            val dataStore = applicationContext.dataStore
            val preferences = dataStore.data.first()
            val nlpEnabled = preferences[booleanPreferencesKey("nlp_enabled")] ?: true
            val nlpThreshold = preferences[intPreferencesKey("nlp_threshold")] ?: 50
            
            val nlpScore = UrgencyClassifier.calculateUrgencyScore(parsed.message)
            val isNlpOverride = nlpEnabled && nlpScore >= nlpThreshold

            // Detect if incoming WhatsApp notification represents a voice or video call
            val isWaCall = sbn.notification.category == android.app.Notification.CATEGORY_CALL ||
                    parsed.message.contains("voice call", ignoreCase = true) ||
                    parsed.message.contains("video call", ignoreCase = true) ||
                    parsed.message.contains("incoming call", ignoreCase = true)

            val overrideWaCalls = preferences[booleanPreferencesKey("override_wa_calls")] ?: true
            if (isWaCall && !overrideWaCalls) {
                Log.d(TAG, "WhatsApp Call received, but override_wa_calls is disabled")
                return@launch
            }

            val isTriggered = matchedContact != null || isKeywordMatched || isNlpOverride
            
            // Log to debug logs database
            val debugLog = DebugLog(
                timestamp = System.currentTimeMillis(),
                packageName = packageName,
                title = parsed.rawTitle,
                text = parsed.rawText,
                subText = parsed.rawSubText,
                conversationTitle = parsed.rawConversationTitle,
                matched = isTriggered,
                parsedSender = parsed.sender,
                parsedMessage = parsed.message,
                isGroupChat = parsed.isGroup,
                individualSender = parsed.individualSender
            )
            debugLogDao.insertLog(debugLog)

            if (!isTriggered) return@launch

            // Use matched contact or build a temporary one for keyword/NLP overrides
            val targetContact = matchedContact 
                ?: keywordContact 
                ?: WatchedContact(
                    name = parsed.sender,
                    isGroup = parsed.isGroup,
                    ringtonePath = null,
                    useAlarmVolume = true,
                    repeatUntilDismissed = true, // Force repeating for emergency NLP override
                    escalatingVolume = true,
                    cooldownSeconds = 30
                )

            // Cooldown check:
            val currentTime = System.currentTimeMillis()
            val timeDiff = currentTime - targetContact.lastTriggeredTime
            if (timeDiff < targetContact.cooldownSeconds * 1000L) {
                Log.d(TAG, "Trigger skipped due to cooldown: $timeDiff < ${targetContact.cooldownSeconds * 1000L}")
                return@launch
            }

            // Update last triggered time in DB
            if (targetContact.id != 0L) {
                contactDao.updateLastTriggered(targetContact.id, currentTime)
            }

            // Presence-Aware Alarm Suppression
            val screenInteractive = PresenceHelper.isScreenInteractive(applicationContext)
            
            // Settings for presence
            val suppressOnScreenOn = preferences[booleanPreferencesKey("suppress_screen_on")] ?: false
            val suppressOnWifi = preferences[booleanPreferencesKey("suppress_wifi")] ?: false
            val homeWifiSsid = preferences[stringPreferencesKey("home_wifi_ssid")] ?: ""
            val suppressOnWearable = preferences[booleanPreferencesKey("suppress_wearable")] ?: false

            var shouldSuppressAlarmSound = false

            if (suppressOnScreenOn && screenInteractive) {
                shouldSuppressAlarmSound = true
                Log.i(TAG, "Alarm suppressed: Screen is interactive (user active)")
            }

            if (suppressOnWifi && !shouldSuppressAlarmSound && homeWifiSsid.isNotEmpty()) {
                val currentSsid = PresenceHelper.getWifiSsid(applicationContext)
                if (currentSsid != null && currentSsid.lowercase() == homeWifiSsid.lowercase()) {
                    shouldSuppressAlarmSound = true
                    Log.i(TAG, "Alarm suppressed: Connected to home Wi-Fi SSID ($currentSsid)")
                }
            }

            if (suppressOnWearable && !shouldSuppressAlarmSound) {
                if (PresenceHelper.isSmartwatchConnected(applicationContext)) {
                    shouldSuppressAlarmSound = true
                    Log.i(TAG, "Alarm suppressed: Smartwatch connected")
                }
            }

            // Cancel the original WhatsApp notification to suppress its built-in tone (skip for calls)
            if (!isWaCall) {
                try {
                    cancelNotification(sbn.key)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to cancel notification: ${sbn.key}", e)
                }

                // Repost notification silently so user still has it in shade
                repostNotificationSilently(sbn, parsed.sender, parsed.message)
            }

            // Execute Alarm Action
            if (shouldSuppressAlarmSound) {
                // Just trigger a single vibration pattern and do NOT play loud music or show overlay
                AlarmPlayer.triggerVibration(applicationContext, false)
            } else {
                // Check Schedule Constraints
                var playSound = true
                var vibeOnly = false

                if (targetContact.isScheduleEnabled) {
                    val inSchedule = isCurrentTimeInSchedule(targetContact)
                    if (!inSchedule) {
                        if (targetContact.vibeOnlyOutsideSchedule) {
                            vibeOnly = true
                            Log.d(TAG, "Schedule active but time is outside: playing vibe only")
                        } else {
                            playSound = false
                            Log.d(TAG, "Schedule active but time is outside: muted completely")
                        }
                    }
                }

                if (playSound) {
                    if (vibeOnly) {
                        AlarmPlayer.triggerVibration(applicationContext, false)
                    } else {
                        // Play ringtone (STREAM_ALARM or STREAM_NOTIFICATION)
                        AlarmPlayer.play(applicationContext, targetContact)
                        activeTriggerKey = sbn.key
                        
                        // Launch Lock-screen Full Screen Activity via Full-Screen Intent Notification (handles locked screens on Android 10+)
                        val overlayIntent = Intent(applicationContext, AlarmActivity::class.java).apply {
                            putExtra("contact_name", targetContact.name)
                            putExtra("message_body", parsed.message)
                            putExtra("group_name", parsed.groupName)
                            putExtra("is_group", parsed.isGroup)
                            putExtra("sbn_key", sbn.key)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or 
                                      Intent.FLAG_ACTIVITY_CLEAR_TOP or 
                                      Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        }

                        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                        } else {
                            PendingIntent.FLAG_UPDATE_CURRENT
                        }
                        val fullScreenPendingIntent = PendingIntent.getActivity(
                            applicationContext,
                            sbn.id + 100,
                            overlayIntent,
                            flags
                        )

                        // Create PendingIntent for STOP ALARM action button
                        val stopAlarmIntent = Intent(applicationContext, StopAlarmReceiver::class.java).apply {
                            action = StopAlarmReceiver.ACTION_STOP_ALARM
                            putExtra(StopAlarmReceiver.EXTRA_NOTIFICATION_ID, sbn.id + 1000)
                        }
                        val stopFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        } else {
                            PendingIntent.FLAG_UPDATE_CURRENT
                        }
                        val stopAlarmPendingIntent = PendingIntent.getBroadcast(
                            applicationContext,
                            sbn.id + 200,
                            stopAlarmIntent,
                            stopFlags
                        )

                        val alertNotification = NotificationCompat.Builder(applicationContext, ALARM_ALERT_CHANNEL_ID)
                            .setContentTitle("🚨 ZAlarm: " + targetContact.name)
                            .setContentText(parsed.message)
                            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                            .setPriority(NotificationCompat.PRIORITY_MAX)
                            .setCategory(NotificationCompat.CATEGORY_ALARM)
                            .setFullScreenIntent(fullScreenPendingIntent, true)
                            .setOngoing(true) // Keep in notification shade until stopped
                            .setAutoCancel(false) // Do not dismiss on click
                            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "🔇 STOP ALARM", stopAlarmPendingIntent)
                            .setContentIntent(fullScreenPendingIntent)
                            .build()

                        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        notificationManager.notify(sbn.id + 1000, alertNotification)

                        // Launch lockscreen activity directly as backup fallback
                        try {
                            startActivity(overlayIntent)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed starting activity directly from background", e)
                        }
                    }
                }
            }
            } finally {
                try {
                    if (wakeLock.isHeld) {
                        wakeLock.release()
                        Log.d(TAG, "Sync WakeLock released for ${sbn.key}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to release sync WakeLock", e)
                }
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        notificationsMap.remove(sbn.key)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap, reason: Int) {
        notificationsMap.remove(sbn.key)
        if (sbn.key == activeTriggerKey) {
            Log.d(TAG, "Active triggering notification removed: reason=$reason")
            // 8 = REASON_USER_STOPPED (swipe away)
            // 1 = REASON_CLICK (clicked)
            // 3 = REASON_CANCEL_ALL (cleared all)
            val isUserDismissed = reason == 8 || reason == 1 || reason == 3
            if (isUserDismissed) {
                Log.i(TAG, "Active triggering notification dismissed by user. Stopping alarm...")
                AlarmPlayer.stop()
                activeTriggerKey = null
            } else {
                Log.d(TAG, "Active triggering notification removed programmatically (reason $reason). Alarm kept playing.")
            }
        }
    }

    private fun isCurrentTimeInSchedule(contact: WatchedContact): Boolean {
        val calendar = java.util.Calendar.getInstance()
        val hour = calendar.get(java.util.Calendar.HOUR_OF_DAY)
        val minute = calendar.get(java.util.Calendar.MINUTE)
        
        val currentMinutes = hour * 60 + minute
        val startMinutes = contact.startHour * 60 + contact.startMinute
        val endMinutes = contact.endHour * 60 + contact.endMinute
        
        return if (startMinutes <= endMinutes) {
            currentMinutes in startMinutes..endMinutes
        } else {
            // Overlap midnight (e.g. 22:00 to 06:00)
            currentMinutes >= startMinutes || currentMinutes <= endMinutes
        }
    }

    private fun startForegroundService() {
        val pendingIntent: PendingIntent = Intent(this, MainActivity::class.java).let { notificationIntent ->
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            PendingIntent.getActivity(this, 0, notificationIntent, flags)
        }

        val notification: Notification = NotificationCompat.Builder(this, FOREGROUND_CHANNEL_ID)
            .setContentTitle("ZAlarm active")
            .setContentText("Monitoring priority WhatsApp notifications")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                SERVICE_NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(SERVICE_NOTIFICATION_ID, notification)
        }
    }

    private fun repostNotificationSilently(sbn: StatusBarNotification, sender: String, message: String) {
        val context = applicationContext
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        // Open main app on tap
        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context,
            sbn.id,
            intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        val silentNotification = NotificationCompat.Builder(context, SILENT_POST_CHANNEL_ID)
            .setContentTitle(sender)
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW) // Silent
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        // Repost using same ID but under ZAlarm package
        notificationManager.notify(sbn.id, silentNotification)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            
            // 1. Foreground service channel
            val serviceChannel = NotificationChannel(
                FOREGROUND_CHANNEL_ID,
                "ZAlarm Background Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps ZAlarm listening in the background"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(serviceChannel)

            // 2. Silent repost channel
            val repostChannel = NotificationChannel(
                SILENT_POST_CHANNEL_ID,
                "Silent WhatsApp Reposts",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Displays muted notifications intercepted from WhatsApp"
                enableVibration(false)
                enableLights(false)
            }
            notificationManager.createNotificationChannel(repostChannel)

            // 3. Alarm Alert channel
            val alertChannel = NotificationChannel(
                ALARM_ALERT_CHANNEL_ID,
                "ZAlarm Priority Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "High priority alarms that bypass DND and show overlays"
                enableLights(true)
                lightColor = android.graphics.Color.RED
                enableVibration(true)
                setSound(null, null)
            }
            notificationManager.createNotificationChannel(alertChannel)
        }
    }
}
