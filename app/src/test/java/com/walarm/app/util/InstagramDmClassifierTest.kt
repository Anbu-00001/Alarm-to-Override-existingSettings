package com.walarm.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InstagramDmClassifierTest {

    private fun createSignals(
        title: String? = "Sender",
        text: String? = "Message content",
        subText: String? = null,
        conversationTitle: String? = null,
        messagingSender: String? = null,
        category: String? = null,
        channelId: String? = null,
        shortcutId: String? = null,
        template: String? = null,
        isGroupConversation: Boolean = false,
        isGroupSummary: Boolean = false,
        hasRemoteInputAction: Boolean = false
    ): NotificationSignals {
        return NotificationSignals(
            packageName = "com.instagram.android",
            title = title,
            text = text,
            subText = subText,
            conversationTitle = conversationTitle,
            messagingSender = messagingSender,
            category = category,
            channelId = channelId,
            shortcutId = shortcutId,
            template = template,
            isGroupConversation = isGroupConversation,
            isGroupSummary = isGroupSummary,
            hasRemoteInputAction = hasRemoteInputAction
        )
    }

    @Test
    fun `classify group summary returns REJECTED`() {
        val signals = createSignals(
            title = "Instagram",
            text = "3 new notifications",
            isGroupSummary = true
        )
        val verdict = InstagramDmClassifier.classify(signals, strict = true)
        assertEquals(DmDecision.REJECTED, verdict.decision)
        assertTrue(verdict.reason.contains("Group summary"))
    }

    @Test
    fun `classify empty title returns REJECTED`() {
        val signals = createSignals(
            title = "",
            text = "Hello"
        )
        val verdict = InstagramDmClassifier.classify(signals, strict = true)
        assertEquals(DmDecision.REJECTED, verdict.decision)
        assertTrue(verdict.reason.contains("No title"))
    }

    @Test
    fun `classify call category returns CALL`() {
        val signals = createSignals(
            title = "John Doe",
            text = "Incoming call",
            category = "call"
        )
        val verdict = InstagramDmClassifier.classify(signals, strict = true)
        assertEquals(DmDecision.CALL, verdict.decision)
    }

    @Test
    fun `classify call phrase returns CALL`() {
        val signals = createSignals(
            title = "Jane Doe",
            text = "incoming video call from Jane"
        )
        val verdict = InstagramDmClassifier.classify(signals, strict = true)
        assertEquals(DmDecision.CALL, verdict.decision)
    }

    @Test
    fun `classify structural MessagingStyle returns DIRECT_MESSAGE`() {
        val signals = createSignals(
            title = "Alice",
            text = "Hey check this out",
            template = "android.app.Notification\$MessagingStyle"
        )
        val verdict = InstagramDmClassifier.classify(signals, strict = true)
        assertEquals(DmDecision.DIRECT_MESSAGE, verdict.decision)
        assertTrue(verdict.reason.contains("MessagingStyle"))
    }

    @Test
    fun `classify remote input action returns DIRECT_MESSAGE`() {
        val signals = createSignals(
            title = "Bob",
            text = "Are you available?",
            hasRemoteInputAction = true
        )
        val verdict = InstagramDmClassifier.classify(signals, strict = true)
        assertEquals(DmDecision.DIRECT_MESSAGE, verdict.decision)
        assertTrue(verdict.reason.contains("RemoteInput"))
    }

    @Test
    fun `classify social noise returns REJECTED in both strict and lenient modes`() {
        val noisePhrases = listOf(
            "liked your photo",
            "commented on your post",
            "started following you",
            "shared a reel",
            "is live now",
            "suggested for you"
        )

        for (phrase in noisePhrases) {
            val signals = createSignals(
                title = "Charlie",
                text = "Charlie $phrase"
            )
            val verdictStrict = InstagramDmClassifier.classify(signals, strict = true)
            assertEquals("Expected REJECTED for phrase: $phrase", DmDecision.REJECTED, verdictStrict.decision)

            val verdictLenient = InstagramDmClassifier.classify(signals, strict = false)
            assertEquals("Expected REJECTED in lenient mode for phrase: $phrase", DmDecision.REJECTED, verdictLenient.decision)
        }
    }

    @Test
    fun `classify non-noise text without structure returns REJECTED in strict mode but DIRECT_MESSAGE in lenient mode`() {
        val signals = createSignals(
            title = "Dave",
            text = "Hey, let's meet tomorrow at 10 AM"
        )
        val verdictStrict = InstagramDmClassifier.classify(signals, strict = true)
        assertEquals(DmDecision.REJECTED, verdictStrict.decision)

        val verdictLenient = InstagramDmClassifier.classify(signals, strict = false)
        assertEquals(DmDecision.DIRECT_MESSAGE, verdictLenient.decision)
    }
}
