package com.walarm.app.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationIdsTest {

    /** Realistic WhatsApp notification keys. */
    private val keys = (0..5000).map { "0|com.whatsapp|$it|null|10123" }

    /**
     * Regression: alert ids were `sbn.id + 1000` taken straight from WhatsApp's own
     * notification id, so a WhatsApp id of 8901 produced 9901 — the foreground-service
     * notification id — and posting the alarm tore down the service's badge.
     */
    @Test
    fun `derived ids never collide with the foreground service id`() {
        keys.forEach { key ->
            assertNotEquals(NotificationIds.FOREGROUND_SERVICE, NotificationIds.alert(key))
            assertNotEquals(NotificationIds.FOREGROUND_SERVICE, NotificationIds.repost(key))
        }
    }

    @Test
    fun `alert and repost ids never collide with each other`() {
        keys.forEach { key ->
            assertNotEquals(NotificationIds.alert(key), NotificationIds.repost(key))
        }
    }

    @Test
    fun `ids are stable for a given key`() {
        val key = "0|com.whatsapp|42|null|10123"
        assertEquals(NotificationIds.alert(key), NotificationIds.alert(key))
        assertEquals(NotificationIds.repost(key), NotificationIds.repost(key))
    }

    @Test
    fun `ids are positive`() {
        keys.forEach { key ->
            assertTrue(NotificationIds.alert(key) > 0)
            assertTrue(NotificationIds.repost(key) > 0)
        }
    }
}
