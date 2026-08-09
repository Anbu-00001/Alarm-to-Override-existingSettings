package com.walarm.app.util

import java.util.Locale

/**
 * Heuristic "is this message an emergency?" scorer, 0..100.
 *
 * Phrases are declared in tiers, but a phrase listed in more than one tier scores its
 * *highest* weight once rather than the sum of every tier it appears in. That matters:
 * "danger" used to sit in both the high and medium lists and "where are you" in both
 * the high and presence lists, so those two words silently counted double and pushed
 * ordinary messages over the default threshold of 50.
 */
object UrgencyClassifier {

    const val MAX_SCORE = 100
    const val DEFAULT_THRESHOLD = 50

    private const val WEIGHT_EXTREME = 40
    private const val WEIGHT_HIGH = 25
    private const val WEIGHT_MEDIUM = 15

    private const val BONUS_SHOUTED = 15
    private const val BONUS_MULTI_BANG = 15
    private const val BONUS_SINGLE_BANG = 5

    /** Minimum letters before an all-caps message counts as shouting. */
    private const val MIN_LETTERS_FOR_SHOUT = 4

    private val EXTREME = listOf(
        "accident", "hospital", "emergency", "dying", "police",
        "heart attack", "icu", "ambulance"
    )

    private val HIGH = listOf(
        "urgent", "come quick", "help", "danger", "fire", "bleeding",
        "broken bone", "call me now", "where are you", "answer me", "stuck"
    )

    private val MEDIUM = listOf(
        "please call", "need help", "lost", "stolen", "hurt", "danger", "asap", "quickly"
    )

    /** Questions about the recipient's safety or whereabouts. */
    private val PRESENCE = listOf(
        "are you ok", "are you safe", "where are you", "where r u", "r u ok"
    )

    /** Phrase -> highest weight it earns, computed once at class-init instead of per message. */
    private val PHRASE_WEIGHTS: Map<String, Int> = buildMap {
        fun addAll(phrases: List<String>, weight: Int) =
            phrases.forEach { phrase -> merge(phrase, weight, ::maxOf) }

        addAll(EXTREME, WEIGHT_EXTREME)
        addAll(HIGH, WEIGHT_HIGH)
        addAll(MEDIUM, WEIGHT_MEDIUM)
        addAll(PRESENCE, WEIGHT_MEDIUM)
    }

    data class Analysis(val score: Int, val matchedPhrases: List<String>, val shouted: Boolean)

    fun analyze(message: String): Analysis {
        if (message.isBlank()) return Analysis(0, emptyList(), shouted = false)

        val lower = message.lowercase(Locale.ROOT)
        val matched = PHRASE_WEIGHTS.keys.filter { lower.contains(it) }
        var score = matched.sumOf { PHRASE_WEIGHTS.getValue(it) }

        score += when {
            message.contains("!!!") -> BONUS_MULTI_BANG
            message.contains('!') -> BONUS_SINGLE_BANG
            else -> 0
        }

        val shouted = isShouted(message)
        if (shouted) score += BONUS_SHOUTED

        return Analysis(score.coerceIn(0, MAX_SCORE), matched, shouted)
    }

    fun calculateUrgencyScore(message: String): Int = analyze(message).score

    fun isUrgent(message: String, threshold: Int = DEFAULT_THRESHOLD): Boolean =
        calculateUrgencyScore(message) >= threshold

    private fun isShouted(message: String): Boolean {
        var letters = 0
        for (ch in message) {
            if (!ch.isLetter()) continue
            if (!ch.isUpperCase()) return false
            letters++
        }
        return letters >= MIN_LETTERS_FOR_SHOUT
    }
}
