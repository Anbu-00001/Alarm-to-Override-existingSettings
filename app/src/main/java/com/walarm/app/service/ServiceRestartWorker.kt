package com.walarm.app.service

import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class ServiceRestartWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "ServiceRestartWorker"
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Service check running...")
        
        if (!WaListenerService.isRunning()) {
            Log.w(TAG, "WaListenerService is NOT running! Requesting OS rebind...")
            try {
                NotificationListenerService.requestRebind(
                    ComponentName(applicationContext, WaListenerService::class.java)
                )
                Log.i(TAG, "OS rebind requested successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Error attempting to rebind service", e)
            }
        } else {
            Log.i(TAG, "WaListenerService is confirmed running.")
        }
        
        return Result.success()
    }
}
