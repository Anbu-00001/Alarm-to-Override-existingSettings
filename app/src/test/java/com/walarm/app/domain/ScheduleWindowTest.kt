package com.walarm.app.domain

import com.walarm.app.data.WatchedContact
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleWindowTest {

    private fun at(hour: Int, minute: Int = 0) = hour * 60 + minute

    @Test
    fun `daytime window includes times inside it`() {
        assertTrue(ScheduleWindow.contains(at(9), at(23), at(12)))
    }

    @Test
    fun `daytime window excludes times outside it`() {
        assertFalse(ScheduleWindow.contains(at(9), at(23), at(7)))
        assertFalse(ScheduleWindow.contains(at(9), at(23), at(23, 30)))
    }

    @Test
    fun `window bounds are inclusive`() {
        assertTrue(ScheduleWindow.contains(at(9), at(23), at(9)))
        assertTrue(ScheduleWindow.contains(at(9), at(23), at(23)))
    }

    @Test
    fun `window wraps around midnight`() {
        val start = at(22)
        val end = at(6)
        assertTrue(ScheduleWindow.contains(start, end, at(23, 30)))
        assertTrue(ScheduleWindow.contains(start, end, at(1)))
        assertTrue(ScheduleWindow.contains(start, end, at(6)))
        assertFalse(ScheduleWindow.contains(start, end, at(12)))
    }

    @Test
    fun `contact without a schedule is always active`() {
        val contact = WatchedContact(name = "Mom", isScheduleEnabled = false)
        assertTrue(ScheduleWindow.isActiveAt(contact, at(3)))
    }

    @Test
    fun `contact with a schedule is only active inside it`() {
        val contact = WatchedContact(
            name = "Work",
            isScheduleEnabled = true,
            startHour = 9, startMinute = 0,
            endHour = 17, endMinute = 30
        )
        assertTrue(ScheduleWindow.isActiveAt(contact, at(17, 30)))
        assertFalse(ScheduleWindow.isActiveAt(contact, at(17, 31)))
    }
}
