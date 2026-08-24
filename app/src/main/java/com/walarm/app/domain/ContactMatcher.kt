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
        val candidate = resolve(dao, parsed.sender) ?: if (parsed.isGroup) {
            parsed.individualSender?.let { resolve(dao, it) }
        } else null

        if (candidate != null && matchesApp(candidate, parsed.packageName)) {
            return candidate
        }

        // If direct DB query candidate failed targetApp filter, evaluate all contacts
        val allContacts = dao.getAllContacts()
        val senderLower = parsed.sender.trim().lowercase()
        val individualLower = parsed.individualSender?.trim()?.lowercase()

        return allContacts.firstOrNull { contact ->
            matchesApp(contact, parsed.packageName) && isNameMatch(contact, senderLower, individualLower)
        }
    }

    private fun isNameMatch(contact: WatchedContact, senderLower: String, individualLower: String?): Boolean {
        val cName = contact.name.trim().lowercase()
        if (cName.isEmpty()) return false

        if (cName == senderLower) return true
        if (individualLower != null && cName == individualLower) return true

        if (cName.length >= MIN_FUZZY_LENGTH && senderLower.length >= MIN_FUZZY_LENGTH) {
            if (senderLower.contains(cName) || cName.contains(senderLower)) return true
        }
        if (individualLower != null && cName.length >= MIN_FUZZY_LENGTH && individualLower.length >= MIN_FUZZY_LENGTH) {
            if (individualLower.contains(cName) || cName.contains(individualLower)) return true
        }

        return false
    }

    private suspend fun resolve(dao: ContactDao, candidate: String): WatchedContact? {
        val name = candidate.trim()
        if (name.isEmpty()) return null

        dao.getContactByName(name)?.let { return it }
        if (name.length < MIN_FUZZY_LENGTH) return null

        return dao.getContactByNameFuzzy(name)
    }

    /** First watchlist entry whose keyword filter matches [message], if any. */
    suspend fun findKeywordMatch(
        dao: ContactDao,
        message: String,
        packageName: String = ""
    ): WatchedContact? {
        if (message.isBlank()) return null
        return dao.getAllContacts().firstOrNull { contact ->
            matchesApp(contact, packageName) && matchesKeywords(contact, message)
        }
    }

    /** App target validation: matches if contact's targetApp is ALL or matches target package. */
    fun matchesApp(contact: WatchedContact, packageName: String): Boolean {
        if (packageName.isBlank() || packageName == "com.android.shell") return true
        return contact.targetAppEnum.matchesPackage(packageName)
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
