package com.walarm.app.ui

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.walarm.app.alarm.AlarmPlayer
import com.walarm.app.service.WaListenerService

class AlarmActivity : ComponentActivity() {

    companion object {
        private const val TAG = "AlarmActivity"
    }

    // Hold the screen-on WakeLock for the ENTIRE duration of the alarm overlay
    private var screenWakeLock: PowerManager.WakeLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setupScreenWake()
        setupAlarmContent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // If another alarm fires while overlay is showing, update the content
        Log.d(TAG, "onNewIntent — updating alarm overlay with new data")
        setupAlarmContent(intent)
    }

    /**
     * Configure all necessary flags and wake locks to force the screen on
     * and show the alarm overlay above the lock screen.
     */
    private fun setupScreenWake() {
        // Turn screen on, show when locked, and dismiss keyguard
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        }
        
        // Also add window flags for maximum compatibility across OEMs
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )

        // Force the screen physically ON using PowerManager wake lock with ACQUIRE_CAUSES_WAKEUP.
        // Hold the lock for 5 MINUTES (not 3 seconds!) so the alarm overlay stays visible
        // even when waking from deep Doze where the system may be sluggish to render.
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            screenWakeLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE,
                "zalarm:screen_wakeup_wakelock"
            ).apply {
                acquire(5 * 60 * 1000L) // 5 minutes — released in onDestroy
            }
            Log.d(TAG, "Screen WakeLock acquired (5 min timeout)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire screen wakeup lock", e)
        }
    }

    /**
     * Extract intent extras and render the alarm overlay Compose UI.
     */
    private fun setupAlarmContent(intent: Intent) {
        val contactName = intent.getStringExtra("contact_name") ?: "VIP Contact"
        val messageBody = intent.getStringExtra("message_body") ?: "Incoming Urgent Message"
        val groupName = intent.getStringExtra("group_name")
        val isGroup = intent.getBooleanExtra("is_group", false)
        val sbnKey = intent.getStringExtra("sbn_key") ?: ""

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AlarmOverlay(
                        contactName = contactName,
                        messageBody = messageBody,
                        groupName = groupName,
                        isGroup = isGroup,
                        onDismiss = {
                            AlarmPlayer.stop()
                            finish()
                        },
                        onReply = { replyText ->
                            if (sbnKey.isNotEmpty()) {
                                WaListenerService.replyToNotification(this@AlarmActivity, sbnKey, replyText)
                            }
                            AlarmPlayer.stop()
                            finish()
                        }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Stop playing just in case the activity is killed or swiped away
        AlarmPlayer.stop()
        
        // Release screen wake lock
        try {
            if (screenWakeLock?.isHeld == true) {
                screenWakeLock?.release()
                Log.d(TAG, "Screen WakeLock released in onDestroy")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing screen WakeLock", e)
        } finally {
            screenWakeLock = null
        }
    }
}
