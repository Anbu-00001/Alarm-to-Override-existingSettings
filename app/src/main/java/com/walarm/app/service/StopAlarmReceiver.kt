package com.walarm.app.service

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.walarm.app.alarm.AlarmPlayer

class StopAlarmReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_STOP_ALARM = "com.walarm.app.ACTION_STOP_ALARM"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_STOP_ALARM) {
            Log.i("StopAlarmReceiver", "Stop alarm action received from notification")
            
            // Stop the alarm audio + vibration
            AlarmPlayer.stop()
            
            // Cancel the alert notification itself
            val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
            if (notificationId != -1) {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(notificationId)
            }
        }
    }
}
