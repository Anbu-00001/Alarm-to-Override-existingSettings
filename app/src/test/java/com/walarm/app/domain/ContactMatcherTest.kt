package com.walarm.app.domain

import com.walarm.app.data.WatchedContact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactMatcherTest {

    private fun contact(enabled: Boolean, keywords: String) = WatchedContact(
        id = 1,
        name = "Family Group",
        isKeywordFilterEnabled = enabled,
        keywords = keywords
    )

    @Test
    fun `keyword filter is ignored when disabled`() {
        val subject = contact(enabled = false, keywords = "urgent")
        assertFalse(ContactMatcher.matchesKeywords(subject, "this is urgent"))
    }

    @Test
    fun `keyword matching is case insensitive`() {
        val subject = contact(enabled = true, keywords = "urgent,help")
        assertTrue(ContactMatcher.matchesKeywords(subject, "This Is URGENT"))
    }

    @Test
    fun `blank keyword entries never match everything`() {
        val subject = contact(enabled = true, keywords = "urgent, ,,  ")
        assertEquals(listOf("urgent"), ContactMatcher.keywordsOf(subject))
        assertFalse(ContactMatcher.matchesKeywords(subject, "just saying hello"))
    }

    @Test
    fun `keywords are trimmed and lowercased`() {
        val subject = contact(enabled = true, keywords = "  Call Me , EMERGENCY ")
        assertEquals(listOf("call me", "emergency"), ContactMatcher.keywordsOf(subject))
        assertTrue(ContactMatcher.matchesKeywords(subject, "please call me back"))
    }

    @Test
    fun `unrelated message does not match`() {
        val subject = contact(enabled = true, keywords = "urgent,emergency")
        assertFalse(ContactMatcher.matchesKeywords(subject, "see you at dinner"))
    }
}
