package com.walarm.app.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

object OemBatteryHelper {

    fun getOemName(): String {
        return Build.MANUFACTURER.uppercase()
    }

    fun getOemInstructions(): String {
        return when (Build.MANUFACTURER.lowercase()) {
            "xiaomi", "redmi", "poco" -> "Enable Autostart and set Battery Saver to 'No restrictions' for ZAlarm."
            "oppo", "realme" -> "Enable Startup Manager / Autostart and allow background run settings for ZAlarm."
            "vivo", "iqoo" -> "Enable High Background Power Consumption and Autostart for ZAlarm."
            "huawei" -> "Go to App Launch settings, set ZAlarm to 'Manage manually' and enable Auto-launch & Run in background."
            "oneplus" -> "Set Battery Optimization to 'Don't optimize' for ZAlarm."
            "samsung" -> "Ensure ZAlarm is excluded from 'Sleeping Apps' and battery optimization is disabled."
            else -> "Disable battery optimization for ZAlarm in Settings to prevent the service from being closed."
        }
    }

    fun getOemIntent(context: Context): Intent? {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val intent = Intent()
        
        try {
            when {
                manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || manufacturer.contains("poco") -> {
                    intent.component = ComponentName(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity"
                    )
                    return intent
                }
                manufacturer.contains("oppo") || manufacturer.contains("realme") || manufacturer.contains("oneplus") -> {
                    intent.component = ComponentName(
                        "com.coloros.safecenter",
                        "com.coloros.safecenter.startupapp.StartupAppListActivity"
                    )
                    if (context.packageManager.resolveActivity(intent, 0) == null) {
                        intent.component = ComponentName(
                            "com.oppo.safe",
                            "com.oppo.safe.permission.startup.StartupAppListActivity"
                        )
                    }
                    if (context.packageManager.resolveActivity(intent, 0) == null) {
                        intent.component = ComponentName(
                            "com.coloros.safecenter",
                            "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                        )
                    }
                    return intent
                }
                manufacturer.contains("vivo") || manufacturer.contains("iqoo") -> {
                    intent.component = ComponentName(
                        "com.vivo.permissionmanager",
                        "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
                    )
                    if (context.packageManager.resolveActivity(intent, 0) == null) {
                        intent.component = ComponentName(
                            "com.iqoo.secure",
                            "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"
                        )
                    }
                    return intent
                }
                manufacturer.contains("huawei") -> {
                    intent.component = ComponentName(
                        "com.huawei.systemmanager",
                        "com.huawei.systemmanager.optimize.process.ProtectActivity"
                    )
                    if (context.packageManager.resolveActivity(intent, 0) == null) {
                        intent.component = ComponentName(
                            "com.huawei.systemmanager",
                            "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                        )
                    }
                    return intent
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    fun launchPowerSettings(context: Context) {
        val intent = getOemIntent(context)
        if (intent != null && context.packageManager.resolveActivity(intent, 0) != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } else {
            // Launch standard battery optimization screen
            val standardIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(standardIntent)
            } catch (e: Exception) {
                // In case settings intent fails, open application details
                val detailIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(detailIntent)
            }
        }
    }
}
