package com.walarm.app.ui

import android.app.KeyguardManager
import android.content.Context
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
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

        // Force the screen physically ON using PowerManager wake lock with ACQUIRE_CAUSES_WAKEUP
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val screenLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE,
                "zalarm:screen_wakeup_wakelock"
            )
            screenLock.acquire(3000L) // Wakes up screen for 3 seconds, window flags keep it on
            Log.d("AlarmActivity", "Physically woke up the screen via WakeLock")
        } catch (e: Exception) {
            Log.e("AlarmActivity", "Failed to acquire screen wakeup lock", e)
        }

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
                                WaListenerService.replyToNotification(this, sbnKey, replyText)
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
    }
}
