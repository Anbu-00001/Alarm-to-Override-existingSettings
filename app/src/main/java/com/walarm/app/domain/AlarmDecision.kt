package com.walarm.app.domain

import com.walarm.app.data.AppSettings
import com.walarm.app.data.WatchedContact

/** What the alarm pipeline should actually do once a notification has matched. */
enum class AlarmAction {
    /** Full ringtone + vibration + lock-screen overlay. */
    LOUD,

    /** A single vibration pulse, no sound and no overlay. */
    VIBRATE_ONLY,

    /** Do nothing audible — the message is still reposted silently to the shade. */
    SILENT
}

/** Everything the decision needs to know about where the user is right now. */
data class PresenceSnapshot(
    val screenInteractive: Boolean = false,
    val wifiSsid: String? = null,
    val wearableConnected: Boolean = false
)

data class AlarmVerdict(
    val action: AlarmAction,
    /** Human-readable justification, surfaced in logs so field reports are diagnosable. */
    val reason: String
)

/**
 * Decides how loudly to alert, given the matched contact, the user's global settings,
 * and where the user appears to be.
 *
 * This is deliberately pure: no Context, no clock, no Android types. The service and
 * the phone-call receiver both route through it, which is what stops the two paths
 * from drifting apart the way the duplicated schedule checks previously did.
 *
 * Precedence (highest first):
 *  1. Presence suppression — the user is demonstrably already reachable.
 *  2. The contact's own quiet-hours schedule.
 *  3. Otherwise: full alarm.
 */
object AlarmDecision {

    fun decide(
        contact: WatchedContact,
        settings: AppSettings,
        presence: PresenceSnapshot,
        minuteOfDay: Int
    ): AlarmVerdict {
        suppressionReason(settings, presence)?.let { reason ->
            return AlarmVerdict(AlarmAction.VIBRATE_ONLY, reason)
        }

        if (!ScheduleWindow.isActiveAt(contact, minuteOfDay)) {
            return if (contact.vibeOnlyOutsideSchedule) {
                AlarmVerdict(AlarmAction.VIBRATE_ONLY, "Outside ${contact.name}'s schedule — vibrate only")
            } else {
                AlarmVerdict(AlarmAction.SILENT, "Outside ${contact.name}'s schedule — muted")
            }
        }

        return AlarmVerdict(AlarmAction.LOUD, "Alarm conditions met for ${contact.name}")
    }

    /** Returns why the alarm should be softened, or null when nothing suppresses it. */
    private fun suppressionReason(settings: AppSettings, presence: PresenceSnapshot): String? {
        if (settings.suppressOnScreenOn && presence.screenInteractive) {
            return "Screen is interactive — user already active"
        }

        if (settings.suppressOnHomeWifi && settings.homeWifiSsid.isNotBlank()) {
            val current = presence.wifiSsid
            if (current != null && current.equals(settings.homeWifiSsid, ignoreCase = true)) {
                return "Connected to home Wi-Fi ($current)"
            }
        }

        if (settings.suppressOnWearable && presence.wearableConnected) {
            return "Smartwatch connected — alert delivered to wrist"
        }

        return null
    }
}
