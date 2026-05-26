package com.walarm.app.util

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.util.Log

object PresenceHelper {
    private const val TAG = "PresenceHelper"

    fun isScreenInteractive(context: Context): Boolean {
        return try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            powerManager.isInteractive
        } catch (e: Exception) {
            Log.e(TAG, "Error checking screen interactive state", e)
            false
        }
    }

    fun getWifiSsid(context: Context): String? {
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val connectionInfo = wifiManager.connectionInfo
            if (connectionInfo != null && connectionInfo.networkId != -1) {
                var ssid = connectionInfo.ssid
                if (ssid.startsWith("\"") && ssid.endsWith("\"")) {
                    ssid = ssid.substring(1, ssid.length - 1)
                }
                if (ssid == "<unknown ssid>") null else ssid
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting Wifi SSID", e)
            null
        }
    }

    fun isSmartwatchConnected(context: Context): Boolean {
        return try {
            @Suppress("DEPRECATION")
            val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter() ?: return false
            if (!bluetoothAdapter.isEnabled) return false
            
            val bondedDevices = bluetoothAdapter.bondedDevices
            if (bondedDevices.isNullOrEmpty()) return false
            
            for (device in bondedDevices) {
                val name = device.name?.lowercase() ?: continue
                if (name.contains("watch") || 
                    name.contains("wear") || 
                    name.contains("fit") || 
                    name.contains("gear") || 
                    name.contains("band") || 
                    name.contains("galaxy active") || 
                    name.contains("amazfit")) {
                    
                    // We treat a paired smartwatch as connected if bluetooth is enabled.
                    // (As a simplified implementation details proxy to avoid demanding fine permissions).
                    return true
                }
            }
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "Bluetooth permission missing for checking watch connection", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error checking smartwatch connection", e)
            false
        }
    }
}
