package com.walarm.app.alarm

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Vibrator
import android.os.VibratorManager
import android.os.VibrationEffect
import android.util.Log
import com.walarm.app.data.WatchedContact
import kotlinx.coroutines.*
import java.io.IOException

object AlarmPlayer {
    private const val TAG = "AlarmPlayer"
    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var volumeJob: Job? = null
    private val playerScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var appContext: Context? = null
    private var originalRingerMode: Int? = null
    private var originalInterruptionFilter: Int? = null

    fun play(context: Context, contact: WatchedContact) {
        stop()
        appContext = context.applicationContext
        Log.d(TAG, "Playing alarm for contact: ${contact.name}")

        // 1. DND and Silent Mode Overrides
        try {
            appContext?.let { ctx ->
                val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val notificationManager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

                originalRingerMode = audioManager.ringerMode
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    originalInterruptionFilter = notificationManager.currentInterruptionFilter
                }

                // Check and temporarily disable Do Not Disturb (requires policy access)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && notificationManager.isNotificationPolicyAccessGranted) {
                    if (notificationManager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL) {
                        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                        Log.i(TAG, "DND temporarily bypassed (Filter set to ALL)")
                    }
                }

                // Temporarily disable silent/vibration ringer mode if active
                if (audioManager.ringerMode != AudioManager.RINGER_MODE_NORMAL) {
                    var canChangeRinger = true
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        val currentFilter = notificationManager.currentInterruptionFilter
                        if (currentFilter != NotificationManager.INTERRUPTION_FILTER_ALL && 
                            !notificationManager.isNotificationPolicyAccessGranted) {
                            canChangeRinger = false
                        }
                    }
                    
                    if (canChangeRinger) {
                        audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
                        Log.i(TAG, "Ringer temporarily set to NORMAL")
                    } else {
                        Log.w(TAG, "Cannot bypass silent ringer: DND is active and policy access is missing")
                    }
                }

                // Set stream volume to high level (80% of max)
                val streamType = if (contact.useAlarmVolume) AudioManager.STREAM_ALARM else AudioManager.STREAM_NOTIFICATION
                val maxVolume = audioManager.getStreamMaxVolume(streamType)
                val targetVolume = (maxVolume * 0.8).toInt().coerceAtLeast(1)
                audioManager.setStreamVolume(streamType, targetVolume, 0)
                Log.d(TAG, "Bypass volume set to $targetVolume / $maxVolume")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error overriding ringer or DND modes", e)
        }

        // 2. MediaPlayer setup
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(if (contact.useAlarmVolume) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        val ringtoneUri = if (!contact.ringtonePath.isNullOrEmpty()) {
            try {
                Uri.parse(contact.ringtonePath)
            } catch (e: Exception) {
                Log.e(TAG, "Failed parsing custom ringtone path, using default", e)
                android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI 
                    ?: android.provider.Settings.System.DEFAULT_RINGTONE_URI
            }
        } else {
            android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI 
                ?: android.provider.Settings.System.DEFAULT_RINGTONE_URI
        }

        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(context, ringtoneUri)
                setAudioAttributes(audioAttributes)
                isLooping = contact.repeatUntilDismissed
                
                // If escalating volume, start low, else full
                val initialVol = if (contact.escalatingVolume) 0.1f else 1.0f
                setVolume(initialVol, initialVol)

                prepare()
                start()
            }

            if (contact.escalatingVolume) {
                startEscalatingVolume()
            }

            triggerVibration(context, contact.repeatUntilDismissed)

        } catch (e: Exception) {
            Log.e(TAG, "Error launching MediaPlayer playing ringtone", e)
            // Fallback play vibration
            triggerVibration(context, true)
        }
    }

    private fun startEscalatingVolume() {
        volumeJob?.cancel()
        volumeJob = playerScope.launch {
            delay(10000) // Wait 10 seconds before starting escalation
            var currentVol = 0.1f
            while (currentVol < 1.0f && mediaPlayer != null) {
                delay(5000) // Increase every 5 seconds
                currentVol = (currentVol + 0.2f).coerceAtMost(1.0f)
                try {
                    mediaPlayer?.setVolume(currentVol, currentVol)
                } catch (e: Exception) {
                    Log.e(TAG, "Error setting volume in escalation loop", e)
                }
                Log.d(TAG, "Escalating volume to: $currentVol")
            }
        }
    }

    fun triggerVibration(context: Context, repeat: Boolean) {
        try {
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }

            val pattern = longArrayOf(0, 500, 300, 500, 300, 1000)
            val repeatIndex = if (repeat) 0 else -1

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, repeatIndex))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, repeatIndex)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting vibration", e)
        }
    }

    fun stop() {
        volumeJob?.cancel()
        volumeJob = null

        // Restore original ringer mode & DND filter
        try {
            appContext?.let { ctx ->
                val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val notificationManager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

                originalRingerMode?.let { mode ->
                    if (audioManager.ringerMode != mode) {
                        audioManager.ringerMode = mode
                        Log.i(TAG, "Ringer Mode restored to: $mode")
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    originalInterruptionFilter?.let { filter ->
                        if (notificationManager.isNotificationPolicyAccessGranted &&
                            notificationManager.currentInterruptionFilter != filter) {
                            notificationManager.setInterruptionFilter(filter)
                            Log.i(TAG, "DND filter restored to: $filter")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error restoring original ringer or DND modes", e)
        } finally {
            originalRingerMode = null
            originalInterruptionFilter = null
            appContext = null
        }

        try {
            mediaPlayer?.apply {
                if (isPlaying) {
                    stop()
                }
                release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping media player", e)
        } finally {
            mediaPlayer = null
        }

        try {
            vibrator?.cancel()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping vibrator", e)
        } finally {
            vibrator = null
        }
    }
}

