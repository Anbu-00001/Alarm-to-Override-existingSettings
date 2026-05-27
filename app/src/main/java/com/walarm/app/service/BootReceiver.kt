package com.walarm.app.service

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.notification.NotificationListenerService
import android.util.Log

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i(TAG, "Device booted. Triggering rebind for WaListenerService...")
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    NotificationListenerService.requestRebind(
                        ComponentName(context, WaListenerService::class.java)
                    )
                    Log.i(TAG, "OS rebind requested on boot")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error rebinding listener service on boot", e)
            }

            // Schedule Doze-resistant heartbeats on boot so they survive reboots
            HeartbeatReceiver.scheduleHeartbeats(context)
            Log.i(TAG, "Heartbeat alarms scheduled from BootReceiver")
        }
    }
}
