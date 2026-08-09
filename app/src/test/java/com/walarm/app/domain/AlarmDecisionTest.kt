package com.walarm.app.domain

import com.walarm.app.data.AppSettings
import com.walarm.app.data.WatchedContact
import org.junit.Assert.assertEquals
import org.junit.Test

class AlarmDecisionTest {

    private val noon = 12 * 60

    private val mom = WatchedContact(id = 1, name = "Mom")

    private fun decide(
        contact: WatchedContact = mom,
        settings: AppSettings = AppSettings(),
        presence: PresenceSnapshot = PresenceSnapshot(),
        minuteOfDay: Int = noon
    ) = AlarmDecision.decide(contact, settings, presence, minuteOfDay).action

    @Test
    fun `alarms loudly by default`() {
        assertEquals(AlarmAction.LOUD, decide())
    }

    @Test
    fun `screen-on suppression softens the alarm only when enabled`() {
        val interactive = PresenceSnapshot(screenInteractive = true)

        assertEquals(AlarmAction.LOUD, decide(presence = interactive))
        assertEquals(
            AlarmAction.VIBRATE_ONLY,
            decide(settings = AppSettings(suppressOnScreenOn = true), presence = interactive)
        )
    }

    @Test
    fun `home wifi match is case insensitive`() {
        val settings = AppSettings(suppressOnHomeWifi = true, homeWifiSsid = "HomeNet")

        assertEquals(
            AlarmAction.VIBRATE_ONLY,
            decide(settings = settings, presence = PresenceSnapshot(wifiSsid = "homenet"))
        )
    }

    @Test
    fun `home wifi rule does not fire on a different or unknown network`() {
        val settings = AppSettings(suppressOnHomeWifi = true, homeWifiSsid = "HomeNet")

        assertEquals(
            AlarmAction.LOUD,
            decide(settings = settings, presence = PresenceSnapshot(wifiSsid = "CoffeeShop"))
        )
        assertEquals(
            AlarmAction.LOUD,
            decide(settings = settings, presence = PresenceSnapshot(wifiSsid = null))
        )
    }

    @Test
    fun `blank configured ssid never suppresses`() {
        val settings = AppSettings(suppressOnHomeWifi = true, homeWifiSsid = "")

        assertEquals(
            AlarmAction.LOUD,
            decide(settings = settings, presence = PresenceSnapshot(wifiSsid = ""))
        )
    }

    @Test
    fun `outside schedule falls back to vibration or silence per contact preference`() {
        val vibeOutside = WatchedContact(
            id = 2, name = "Work",
            isScheduleEnabled = true, startHour = 9, endHour = 17,
            vibeOnlyOutsideSchedule = true
        )
        val muteOutside = vibeOutside.copy(id = 3, vibeOnlyOutsideSchedule = false)
        val elevenPm = 23 * 60

        assertEquals(AlarmAction.VIBRATE_ONLY, decide(contact = vibeOutside, minuteOfDay = elevenPm))
        assertEquals(AlarmAction.SILENT, decide(contact = muteOutside, minuteOfDay = elevenPm))
        assertEquals(AlarmAction.LOUD, decide(contact = vibeOutside, minuteOfDay = 10 * 60))
    }

    @Test
    fun `presence suppression takes precedence over a schedule that would mute entirely`() {
        val muteOutside = WatchedContact(
            id = 4, name = "Work",
            isScheduleEnabled = true, startHour = 9, endHour = 17,
            vibeOnlyOutsideSchedule = false
        )

        assertEquals(
            AlarmAction.VIBRATE_ONLY,
            decide(
                contact = muteOutside,
                settings = AppSettings(suppressOnWearable = true),
                presence = PresenceSnapshot(wearableConnected = true),
                minuteOfDay = 23 * 60
            )
        )
    }
}
