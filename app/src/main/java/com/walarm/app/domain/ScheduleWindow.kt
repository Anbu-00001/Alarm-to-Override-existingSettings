package com.walarm.app.domain

import com.walarm.app.data.WatchedContact
import java.util.Calendar

/**
 * Evaluates a contact's "quiet hours" window.
 *
 * Previously this was duplicated verbatim in WaListenerService and PhoneCallReceiver;
 * the two copies had to agree for a VIP call and a VIP message to behave the same way.
 * It lives here as pure arithmetic on minutes-of-day so it can be unit tested without
 * a device clock.
 */
object ScheduleWindow {

    const val MINUTES_PER_DAY = 24 * 60

    /**
     * True when [minuteOfDay] falls inside the inclusive window [start]..[end].
     *
     * A window whose end is earlier than its start wraps around midnight
     * (e.g. 22:00 -> 06:00 covers the night).
     */
    fun contains(startMinuteOfDay: Int, endMinuteOfDay: Int, minuteOfDay: Int): Boolean {
        val start = startMinuteOfDay.mod(MINUTES_PER_DAY)
        val end = endMinuteOfDay.mod(MINUTES_PER_DAY)
        val now = minuteOfDay.mod(MINUTES_PER_DAY)
        return if (start <= end) now in start..end else now >= start || now <= end
    }

    /** True when the contact has no schedule, or [minuteOfDay] is inside its window. */
    fun isActiveAt(contact: WatchedContact, minuteOfDay: Int): Boolean {
        if (!contact.isScheduleEnabled) return true
        return contains(
            startMinuteOfDay = contact.startHour * 60 + contact.startMinute,
            endMinuteOfDay = contact.endHour * 60 + contact.endMinute,
            minuteOfDay = minuteOfDay
        )
    }

    /** Minute-of-day for the supplied calendar, defaulting to now in the device timezone. */
    fun minuteOfDayNow(calendar: Calendar = Calendar.getInstance()): Int =
        calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
}
