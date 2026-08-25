package com.walarm.app.domain

import com.walarm.app.data.ContactDao
import com.walarm.app.data.TargetApp
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
 *
 * Every tier is a single indexed SQL query with the per-contact app filter applied in the
 * `WHERE` clause. That filter used to be applied in Kotlin *after* the query, with a full
 * table scan and a second hand-written matcher as the fallback — two engines that
 * disagreed on tie-breaking (SQL preferred the longest, i.e. most specific, name; the
 * Kotlin fallback took whatever sorted first alphabetically).
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
        val appKey = TargetApp.forPackage(parsed.packageName)?.name

        resolve(dao, parsed.sender, appKey)?.let { return it }

        if (parsed.isGroup) {
            parsed.individualSender?.let { individual ->
                resolve(dao, individual, appKey)?.let { return it }
            }
        }

        return null
    }

    /**
     * @param appKey the [TargetApp] name to filter on, or null to skip app filtering
     *   (ADB-injected test notifications, which must be able to reach any contact).
     */
    private suspend fun resolve(dao: ContactDao, candidate: String, appKey: String?): WatchedContact? {
        val name = candidate.trim()
        if (name.isEmpty()) return null

        val exact = if (appKey == null) {
            dao.getContactByName(name)
        } else {
            dao.getContactByNameForApp(name, appKey)
        }
        if (exact != null) return exact

        if (name.length < MIN_FUZZY_LENGTH) return null

        return if (appKey == null) {
            dao.getContactByNameFuzzy(name)
        } else {
            dao.getContactByNameFuzzyForApp(name, appKey)
        }
    }

    /** First watchlist entry whose keyword filter matches [message], if any. */
    suspend fun findKeywordMatch(
        dao: ContactDao,
        message: String,
        packageName: String
    ): WatchedContact? {
        if (message.isBlank()) return null

        val appKey = TargetApp.forPackage(packageName)?.name
        val candidates = if (appKey == null) dao.getAllContacts() else dao.getContactsForApp(appKey)

        return candidates.firstOrNull { matchesKeywords(it, message) }
    }

    /**
     * Whether [contact] accepts notifications from [packageName].
     *
     * Kept as a pure helper for tests and for callers that already hold the contact.
     * An untracked package (notably the ADB shell) matches every contact.
     */
    fun matchesApp(contact: WatchedContact, packageName: String): Boolean {
        val app = TargetApp.forPackage(packageName) ?: return true
        val target = contact.targetAppEnum
        return target == TargetApp.ALL || target == app
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
