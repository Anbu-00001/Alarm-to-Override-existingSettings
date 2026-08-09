package com.walarm.app.domain

import com.walarm.app.data.WatchedContact
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TriggerGateTest {

    private val start = 1_000_000L

    @Before
    fun setUp() = TriggerGate.reset()

    /**
     * Regression: keyword and urgency triggers build a throwaway WatchedContact whose
     * `lastTriggeredTime` is always 0, so a cooldown check against that field alone
     * always passed. A burst of urgent-looking messages produced an alarm per message.
     */
    @Test
    fun `transient contact is rate limited despite having no persisted timestamp`() {
        val transient = WatchedContact(id = 0, name = "Unknown Sender", cooldownSeconds = 30)

        assertTrue(TriggerGate.tryTrigger(transient, start))
        assertFalse(TriggerGate.tryTrigger(transient, start + 1_000))
        assertFalse(TriggerGate.tryTrigger(transient, start + 29_999))
        assertTrue(TriggerGate.tryTrigger(transient, start + 30_000))
    }

    @Test
    fun `persisted contact respects its stored last trigger time`() {
        val contact = WatchedContact(
            id = 7,
            name = "Mom",
            cooldownSeconds = 60,
            lastTriggeredTime = start
        )

        assertFalse(TriggerGate.tryTrigger(contact, start + 30_000))
        assertTrue(TriggerGate.tryTrigger(contact, start + 60_000))
    }

    @Test
    fun `different contacts have independent cooldowns`() {
        val mom = WatchedContact(id = 1, name = "Mom", cooldownSeconds = 30)
        val dad = WatchedContact(id = 2, name = "Dad", cooldownSeconds = 30)

        assertTrue(TriggerGate.tryTrigger(mom, start))
        assertTrue(TriggerGate.tryTrigger(dad, start))
        assertFalse(TriggerGate.tryTrigger(mom, start))
    }

    @Test
    fun `transient triggers from the same sender collapse regardless of case`() {
        val first = WatchedContact(id = 0, name = "Alice", cooldownSeconds = 30)
        val second = WatchedContact(id = 0, name = "alice", cooldownSeconds = 30)

        assertTrue(TriggerGate.tryTrigger(first, start))
        assertFalse(TriggerGate.tryTrigger(second, start + 1_000))
    }

    @Test
    fun `zero cooldown always allows the trigger`() {
        val contact = WatchedContact(id = 3, name = "Always", cooldownSeconds = 0)

        assertTrue(TriggerGate.tryTrigger(contact, start))
        assertTrue(TriggerGate.tryTrigger(contact, start))
    }
}
