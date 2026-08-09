package com.walarm.app.domain

import com.walarm.app.data.ContactDao
import com.walarm.app.data.WatchedContact
import com.walarm.app.util.NotificationParser

/** Why an alarm fired — recorded so debug logs explain themselves. */
enum class TriggerSource { WATCHLIST, KEYWORD, URGENCY }

/**
 * Resolves an incoming notification to a watched contact.
 *
 * Matching runs in tiers, cheapest and most precise first:
 *  1. Exact match on the parsed sender (contact name for a DM, group name for a group).
 *  2. Fuzzy/contains match on the parsed sender.
 *  3. Exact match on the individual sender inside a group.
 *  4. Fuzzy/contains match on that individual sender.
 */
object ContactMatcher {

    /**
     * Fuzzy matching is substring-based in both directions, so very short names match
     * almost anything ("Al" would fire on "Ronald"). Anything shorter than this only
     * ever matches exactly.
     */
    const val MIN_FUZZY_LENGTH = 3

    suspend fun findWatched(
        dao: ContactDao,
        parsed: NotificationParser.ParsedNotification
    ): WatchedContact? {
        resolve(dao, parsed.sender)?.let { return it }

        if (parsed.isGroup) {
            parsed.individualSender?.let { individual -> resolve(dao, individual)?.let { return it } }
        }

        return null
    }

    private suspend fun resolve(dao: ContactDao, candidate: String): WatchedContact? {
        val name = candidate.trim()
        if (name.isEmpty()) return null

        dao.getContactByName(name)?.let { return it }
        if (name.length < MIN_FUZZY_LENGTH) return null

        return dao.getContactByNameFuzzy(name)
    }

    /** First watchlist entry whose keyword filter matches [message], if any. */
    suspend fun findKeywordMatch(dao: ContactDao, message: String): WatchedContact? {
        if (message.isBlank()) return null
        return dao.getAllContacts().firstOrNull { matchesKeywords(it, message) }
    }

    /** Pure keyword test, split out so it can be unit tested without a database. */
    fun matchesKeywords(contact: WatchedContact, message: String): Boolean {
        if (!contact.isKeywordFilterEnabled) return false
        val haystack = message.lowercase()
        return keywordsOf(contact).any { haystack.contains(it) }
    }

    fun keywordsOf(contact: WatchedContact): List<String> =
        contact.keywords.split(',')
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
}
