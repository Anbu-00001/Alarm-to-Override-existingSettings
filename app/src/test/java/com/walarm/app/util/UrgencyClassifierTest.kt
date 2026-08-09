package com.walarm.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrgencyClassifierTest {

    @Test
    fun `blank message scores zero`() {
        assertEquals(0, UrgencyClassifier.calculateUrgencyScore(""))
        assertEquals(0, UrgencyClassifier.calculateUrgencyScore("   "))
    }

    /**
     * Regression: "danger" was listed in both the high (+25) and medium (+15) tiers and
     * so scored 40 for a single word. It must now score its highest tier once.
     */
    @Test
    fun `phrase listed in two tiers counts once at its highest weight`() {
        assertEquals(25, UrgencyClassifier.calculateUrgencyScore("danger"))
    }

    /**
     * Regression: "where are you" appeared in both the high tier and the presence
     * questions, so "where are you!!!" scored 55 and tripped the default threshold of 50 —
     * a full alarm for an ordinary impatient message.
     */
    @Test
    fun `impatient message no longer crosses the default urgency threshold`() {
        assertEquals(40, UrgencyClassifier.calculateUrgencyScore("where are you!!!"))
        assertFalse(UrgencyClassifier.isUrgent("where are you!!!"))
    }

    @Test
    fun `genuine emergency is urgent`() {
        assertTrue(UrgencyClassifier.isUrgent("accident, going to hospital"))
    }

    @Test
    fun `score is capped at the maximum`() {
        val score = UrgencyClassifier.calculateUrgencyScore(
            "accident hospital emergency dying ambulance"
        )
        assertEquals(UrgencyClassifier.MAX_SCORE, score)
    }

    @Test
    fun `shouting adds weight only with enough letters`() {
        assertTrue(UrgencyClassifier.analyze("HELP ME NOW").shouted)
        assertFalse(UrgencyClassifier.analyze("Help me now").shouted)
        // Too few letters to count as shouting.
        assertFalse(UrgencyClassifier.analyze("OK!").shouted)
    }

    @Test
    fun `matching is case insensitive`() {
        assertEquals(
            UrgencyClassifier.calculateUrgencyScore("emergency"),
            UrgencyClassifier.calculateUrgencyScore("EmErGeNcY")
        )
    }

    @Test
    fun `multiple exclamation marks outweigh a single one`() {
        val single = UrgencyClassifier.calculateUrgencyScore("hurt!")
        val multiple = UrgencyClassifier.calculateUrgencyScore("hurt!!!")
        assertTrue(multiple > single)
    }

    @Test
    fun `ordinary chatter scores below the threshold`() {
        assertFalse(UrgencyClassifier.isUrgent("hey, are we still on for lunch tomorrow?"))
        assertFalse(UrgencyClassifier.isUrgent("sent you the photos"))
    }
}
