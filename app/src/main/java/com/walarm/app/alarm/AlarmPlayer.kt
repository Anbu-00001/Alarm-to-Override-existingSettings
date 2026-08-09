package com.walarm.app.alarm

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import com.walarm.app.data.WatchedContact
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Plays (and reliably stops) the alarm: ringtone, vibration, DND/silent-mode bypass and
 * the CPU wake lock that keeps it all running with the screen off.
 *
 * Every piece of global device state this touches — ringer mode, DND filter, stream
 * volume — is captured before it is changed and put back by [stop]. [AudioPolicyOverride]
 * exists to keep that save/restore pairing symmetric and impossible to half-apply.
 */
object AlarmPlayer {

    private const val TAG = "AlarmPlayer"

    /** Upper bound on how long a single alarm may hold the CPU awake. */
    private const val WAKELOCK_TIMEOUT_MS = 10 * 60 * 1000L

    /** Volume ramp for `escalatingVolume` contacts. */
    private const val ESCALATION_START = 0.5f
    private const val ESCALATION_STEP = 0.1f
    private const val ESCALATION_DELAY_MS = 10_000L
    private const val ESCALATION_INTERVAL_MS = 5_000L

    /** Fraction of max system volume we force the stream to while alarming. */
    private const val BYPASS_VOLUME_FRACTION = 0.8

    private val VIBRATION_PATTERN = longArrayOf(0, 500, 300, 500, 300, 1000)

    private val playerScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var volumeJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var audioOverride: AudioPolicyOverride? = null

    fun isPlaying(): Boolean = synchronized(this) { mediaPlayer != null }

    fun play(context: Context, contact: WatchedContact) = synchronized(this) {
        stop()

        val appContext = context.applicationContext
        Log.d(TAG, "Playing alarm for '${contact.name}'")

        wakeLock = acquireWakeLock(appContext)
        audioOverride = AudioPolicyOverride.applyTo(appContext, contact.useAlarmVolume)

        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(appContext, ringtoneUriFor(contact))
                setAudioAttributes(audioAttributesFor(contact))
                isLooping = contact.repeatUntilDismissed

                val initialVolume = if (contact.escalatingVolume) ESCALATION_START else 1.0f
                setVolume(initialVolume, initialVolume)

                // A non-looping alarm otherwise finishes silently while still holding the
                // wake lock and leaving the device's volume and DND overridden.
                setOnCompletionListener { if (!it.isLooping) stop() }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer error (what=$what extra=$extra)")
                    stop()
                    true
                }

                prepare()
                start()
            }

            if (contact.escalatingVolume) startEscalatingVolume(mediaPlayer)
            triggerVibration(appContext, repeat = contact.repeatUntilDismissed)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start ringtone; falling back to vibration only", e)
            releasePlayer()
            triggerVibration(appContext, repeat = true)
        }
    }

    fun stop() = synchronized(this) {
        volumeJob?.cancel()
        volumeJob = null

        releasePlayer()

        audioOverride?.restore()
        audioOverride = null

        try {
            vibrator?.cancel()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping vibrator", e)
        } finally {
            vibrator = null
        }

        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.i(TAG, "WakeLock released")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing WakeLock", e)
        } finally {
            wakeLock = null
        }
    }

    fun triggerVibration(context: Context, repeat: Boolean) {
        try {
            vibrator = vibratorFor(context)
            val repeatIndex = if (repeat) 0 else -1
            vibrator?.vibrate(VibrationEffect.createWaveform(VIBRATION_PATTERN, repeatIndex))
        } catch (e: Exception) {
            Log.e(TAG, "Error starting vibration", e)
        }
    }

    // ── Internals ────────────────────────────────────────────────────────────────

    /**
     * Ramps volume on the exact player it was started for. Reading the shared field on
     * every tick meant a ramp left over from a previous alarm could turn down a newer one.
     */
    private fun startEscalatingVolume(player: MediaPlayer?) {
        if (player == null) return
        volumeJob?.cancel()
        volumeJob = playerScope.launch {
            delay(ESCALATION_DELAY_MS)
            var volume = ESCALATION_START
            while (volume < 1.0f) {
                delay(ESCALATION_INTERVAL_MS)
                volume = (volume + ESCALATION_STEP).coerceAtMost(1.0f)
                try {
                    player.setVolume(volume, volume)
                } catch (e: IllegalStateException) {
                    Log.d(TAG, "Escalation stopped — player already released")
                    return@launch
                }
            }
        }
    }

    private fun releasePlayer() {
        try {
            mediaPlayer?.apply {
                setOnCompletionListener(null)
                setOnErrorListener(null)
                if (isPlaying) stop()
                release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing media player", e)
        } finally {
            mediaPlayer = null
        }
    }

    private fun acquireWakeLock(context: Context): PowerManager.WakeLock? = try {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "zalarm:alarm_player_wakelock").apply {
            setReferenceCounted(false)
            acquire(WAKELOCK_TIMEOUT_MS)
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to acquire WakeLock", e)
        null
    }

    private fun audioAttributesFor(contact: WatchedContact): AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(
                if (contact.useAlarmVolume) AudioAttributes.USAGE_ALARM
                else AudioAttributes.USAGE_NOTIFICATION
            )
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

    private fun ringtoneUriFor(contact: WatchedContact): Uri {
        val custom = contact.ringtonePath
        if (!custom.isNullOrEmpty()) {
            runCatching { Uri.parse(custom) }
                .onSuccess { return it }
                .onFailure { Log.e(TAG, "Bad custom ringtone URI; using default", it) }
        }
        return Settings.System.DEFAULT_ALARM_ALERT_URI ?: Settings.System.DEFAULT_RINGTONE_URI
    }

    private fun vibratorFor(context: Context): Vibrator =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

    /**
     * A recorded set of device audio-policy changes that can be undone exactly once.
     *
     * Only the values actually modified are remembered, so restoring never writes back a
     * volume or DND filter the alarm was never allowed to change in the first place.
     */
    private class AudioPolicyOverride private constructor(
        private val audioManager: AudioManager,
        private val notificationManager: NotificationManager,
        private val previousRingerMode: Int?,
        private val previousInterruptionFilter: Int?,
        private val streamType: Int?,
        private val previousStreamVolume: Int?
    ) {
        fun restore() {
            try {
                previousRingerMode?.let { mode ->
                    if (audioManager.ringerMode != mode) {
                        audioManager.ringerMode = mode
                        Log.i(TAG, "Ringer mode restored to $mode")
                    }
                }

                previousInterruptionFilter?.let { filter ->
                    if (notificationManager.isNotificationPolicyAccessGranted &&
                        notificationManager.currentInterruptionFilter != filter
                    ) {
                        notificationManager.setInterruptionFilter(filter)
                        Log.i(TAG, "DND filter restored to $filter")
                    }
                }

                if (streamType != null && previousStreamVolume != null) {
                    audioManager.setStreamVolume(streamType, previousStreamVolume, 0)
                    Log.i(TAG, "Stream volume restored to $previousStreamVolume")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error restoring audio policy", e)
            }
        }

        companion object {
            fun applyTo(context: Context, useAlarmStream: Boolean): AudioPolicyOverride? = try {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val notificationManager =
                    context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

                var previousFilter: Int? = null
                if (notificationManager.isNotificationPolicyAccessGranted &&
                    notificationManager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
                ) {
                    previousFilter = notificationManager.currentInterruptionFilter
                    notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                    Log.i(TAG, "DND temporarily bypassed")
                }

                var previousRinger: Int? = null
                if (audioManager.ringerMode != AudioManager.RINGER_MODE_NORMAL) {
                    // Changing ringer mode while DND is on requires policy access.
                    val dndBlocksChange =
                        notificationManager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL &&
                            !notificationManager.isNotificationPolicyAccessGranted
                    if (dndBlocksChange) {
                        Log.w(TAG, "Cannot leave silent mode: DND active and policy access missing")
                    } else {
                        previousRinger = audioManager.ringerMode
                        audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
                        Log.i(TAG, "Ringer temporarily set to NORMAL")
                    }
                }

                val stream =
                    if (useAlarmStream) AudioManager.STREAM_ALARM else AudioManager.STREAM_NOTIFICATION
                val previousVolume = audioManager.getStreamVolume(stream)
                val target = (audioManager.getStreamMaxVolume(stream) * BYPASS_VOLUME_FRACTION)
                    .toInt()
                    .coerceAtLeast(1)
                audioManager.setStreamVolume(stream, target, 0)
                Log.d(TAG, "Bypass volume $target (was $previousVolume) on stream $stream")

                AudioPolicyOverride(
                    audioManager = audioManager,
                    notificationManager = notificationManager,
                    previousRingerMode = previousRinger,
                    previousInterruptionFilter = previousFilter,
                    streamType = stream,
                    previousStreamVolume = previousVolume
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error overriding audio policy", e)
                null
            }
        }
    }
}
