package com.walarm.app.util

import java.util.Locale

object UrgencyClassifier {

    fun calculateUrgencyScore(message: String): Int {
        if (message.isBlank()) return 0
        
        var score = 0
        val lowerMessage = message.lowercase(Locale.getDefault())

        // 1. Extreme urgency phrases (+40)
        val extremeKeywords = listOf("accident", "hospital", "emergency", "dying", "police", "heart attack", "icu", "ambulance")
        for (keyword in extremeKeywords) {
            if (lowerMessage.contains(keyword)) {
                score += 40
            }
        }

        // 2. High urgency phrases (+25)
        val highKeywords = listOf("urgent", "come quick", "help", "danger", "fire", "bleeding", "broken bone", "call me now", "where are you", "answer me", "stuck")
        for (keyword in highKeywords) {
            if (lowerMessage.contains(keyword)) {
                score += 25
            }
        }

        // 3. Medium urgency phrases (+15)
        val mediumKeywords = listOf("please call", "need help", "lost", "stolen", "hurt", "danger", "asap", "quickly")
        for (keyword in mediumKeywords) {
            if (lowerMessage.contains(keyword)) {
                score += 15
            }
        }

        // 4. Multiple exclamation marks (+15 for !!!, +5 for !)
        if (message.contains("!!!")) {
            score += 15
        } else if (message.contains("!")) {
            score += 5
        }

        // 5. ALL CAPS check (+15)
        // If the message has sufficient alphabetical characters and is mostly uppercase
        val letters = message.filter { it.isLetter() }
        if (letters.length >= 4 && letters.all { it.isUpperCase() }) {
            score += 15
        }

        // 6. Question seeking presence/safety (+15)
        val presenceQuestions = listOf("are you ok", "are you safe", "where are you", "where r u", "r u ok")
        for (question in presenceQuestions) {
            if (lowerMessage.contains(question)) {
                score += 15
            }
        }

        return score.coerceIn(0, 100)
    }

    fun isUrgent(message: String, threshold: Int = 50): Boolean {
        return calculateUrgencyScore(message) >= threshold
    }
}
