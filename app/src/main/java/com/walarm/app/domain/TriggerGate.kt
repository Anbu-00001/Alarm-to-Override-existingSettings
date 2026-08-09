package com.walarm.app.domain

import com.walarm.app.data.WatchedContact
import java.util.concurrent.ConcurrentHashMap

/**
 * Enforces per-contact alarm cooldowns.
 *
 * Watchlisted contacts persist `lastTriggeredTime` in the database, but keyword- and
 * urgency-triggered alarms are built from throwaway [WatchedContact] instances whose
 * `lastTriggeredTime` is always 0. Checking only the persisted field therefore gave
 * those transient triggers *no* cooldown at all: a burst of ten urgent-looking
 * messages produced ten overlapping alarms.
 *
 * This gate keeps an in-memory record keyed by contact identity and takes the later of
 * the persisted and in-memory timestamps, so both kinds of trigger are rate limited.
 * In-memory state is intentionally process-scoped — a cold start should be able to
 * alarm immediately.
 */
object TriggerGate {

    /** Above this many tracked keys, entries older than [PRUNE_AGE_MS] are dropped. */
    private const val PRUNE_THRESHOLD = 256
    private const val PRUNE_AGE_MS = 60 * 60 * 1000L

    private val lastTriggerByKey = ConcurrentHashMap<String, Long>()

    /**
     * Identity for cooldown purposes. Persisted contacts key on their row id; transient
     * ones key on the (case-folded) sender name so repeats from the same sender collapse.
     */
    fun keyFor(contact: WatchedContact): String =
        if (contact.id != 0L) "id:${contact.id}" else "name:${contact.name.lowercase()}"

    /**
     * Atomically claims a trigger slot for [contact].
     *
     * @return true when the alarm may fire, false when it falls inside the cooldown.
     */
    fun tryTrigger(contact: WatchedContact, now: Long): Boolean {
        val cooldownMs = contact.cooldownSeconds.coerceAtLeast(0) * 1000L
        val key = keyFor(contact)
        var allowed = false

        lastTriggerByKey.compute(key) { _, remembered ->
            val last = maxOf(remembered ?: 0L, contact.lastTriggeredTime)
            if (now - last >= cooldownMs) {
                allowed = true
                now
            } else {
                remembered ?: last
            }
        }

        if (allowed && lastTriggerByKey.size > PRUNE_THRESHOLD) prune(now)
        return allowed
    }

    private fun prune(now: Long) {
        lastTriggerByKey.entries.removeAll { (_, at) -> now - at > PRUNE_AGE_MS }
    }

    /** Test hook — clears all remembered triggers. */
    fun reset() = lastTriggerByKey.clear()
}
