package com.walarm.app.util

/** What [InstagramDmClassifier] decided a notification actually is. */
enum class DmDecision {
    /** A direct message, accepted on structural evidence. */
    DIRECT_MESSAGE,

    /** An incoming voice/video call. */
    CALL,

    /** Social engagement noise, or not enough evidence to justify an alarm. */
    REJECTED
}

data class DmVerdict(
    val decision: DmDecision,
    /** Why — surfaced in logs and the in-app debug screen so field reports are diagnosable. */
    val reason: String
) {
    val isAlarmable: Boolean get() = decision != DmDecision.REJECTED
}

/**
 * Decides whether an Instagram notification is a real communication attempt or ambient
 * social noise.
 *
 * ## Why this is not just a keyword blocklist
 *
 * Phase 1 filtered on a list of twenty English phrases and accepted everything else.
 * That fails in both directions:
 *
 *  - **False alarms.** The blocklist is unbounded — Instagram ships new notification copy
 *    constantly ("X and 3 others liked…", "Your post is doing well", "New reel from X"),
 *    and it is localised. Anything not on the list became an alarm, and with the urgency
 *    classifier enabled by default a promo containing "urgent" would ring a full alarm.
 *  - **False negatives.** The list was matched against the whole payload including the
 *    *message body*, so a watchlisted friend messaging "she commented on my post" was
 *    silently dropped — a missed alarm, which is the one failure this app exists to prevent.
 *
 * ## The approach here
 *
 * Structure first, copy text only as a fallback:
 *
 *  1. Drop group-summary roll-ups outright — they duplicate the real notification.
 *  2. Detect calls from [android.app.Notification.CATEGORY_CALL] or specific multi-word
 *     phrases (never the bare substring "call", which appears inside "basically",
 *     "typically", "musically" and every other -cally adverb).
 *  3. Accept on **positive structural evidence** — MessagingStyle, a reply RemoteInput, a
 *     message category, or a conversation shortcut. You cannot quick-reply to a "like",
 *     and these signals are language independent. When such evidence exists the copy-text
 *     blocklist is skipped entirely, which is what removes the false-negative class above.
 *  4. With no structural evidence, fall back to the blocklist, then to [strict].
 *
 * ## On `strict`
 *
 * Instagram's notification internals are not publicly documented and could not be verified
 * against a real device here, so whether Instagram DMs carry MessagingStyle is genuinely
 * unknown. Strict mode (the default) requires positive evidence and therefore risks missing
 * DMs if Instagram uses none of these signals; lenient mode reproduces Phase 1's
 * blocklist-only behaviour. The debug log records exactly which signals were present on
 * each notification, so a user whose DMs are not alarming can see why and switch modes.
 */
object InstagramDmClassifier {

    /**
     * Call phrases. Deliberately multi-word: an earlier version tested
     * `fullText.contains("call")`, which is true of "basically", "typically", "musically",
     * "specifically" and so on — so essentially any comment notification bypassed the
     * noise filter and rang a full alarm.
     */
    private val CALL_PHRASES = listOf(
        "incoming call",
        "incoming video call",
        "incoming audio call",
        "incoming voice call",
        "video call",
        "voice call",
        "audio call",
        "is calling",
        "calling you",
        "missed call",
        "missed video call",
        "started a video chat",
        "joined the video chat"
    )

    /**
     * Social engagement copy. Only consulted when a notification carries no structural
     * evidence of being a conversation.
     */
    private val SOCIAL_NOISE_PHRASES = listOf(
        // Reactions and comments
        "liked your", "liked a", "reacted to", "commented on", "commented:",
        "replied to your comment", "and others liked", "others liked your",
        // Follows
        "started following", "requested to follow", "accepted your follow",
        "wants to follow", "follows you", "is on instagram",
        // Live / video
        "started a live", "is live now", "went live", "is live on instagram",
        // Posts, reels, stories
        "shared a post", "shared a reel", "shared a photo", "shared a story",
        "new post by", "posted for the first time", "added to their story",
        "added to his story", "added to her story", "reposted your",
        "mentioned you in", "tagged you in", "tagged you", "mentioned you",
        "your story", "story views", "viewed your story",
        // Growth / recommendation spam
        "suggested for you", "suggested account", "you might know",
        "broadcast channel", "new broadcast", "check out", "see what",
        "trending", "popular on instagram", "your post is", "your reel is",
        "complete your profile", "back on instagram", "recently on instagram"
    )

    /** Roll-up phrasing such as "3 new notifications" that never identifies one sender. */
    private val AGGREGATE_PATTERN = Regex("""\b\d+\s+(new\s+)?(notifications?|messages?|posts?|likes?)\b""")

    fun classify(signals: NotificationSignals, strict: Boolean = true): DmVerdict {
        // 1. Group summaries duplicate the per-conversation notification beneath them.
        if (signals.isGroupSummary) {
            return DmVerdict(DmDecision.REJECTED, "Group summary roll-up")
        }

        if (signals.title.isNullOrBlank()) {
            return DmVerdict(DmDecision.REJECTED, "No title to attribute a sender to")
        }

        val haystack = signals.searchableText

        // 2. Calls short-circuit: they are always a live communication attempt.
        if (signals.isCallCategory) {
            return DmVerdict(DmDecision.CALL, "CATEGORY_CALL")
        }
        CALL_PHRASES.firstOrNull { haystack.contains(it) }?.let { phrase ->
            return DmVerdict(DmDecision.CALL, "Call phrase: \"$phrase\"")
        }

        // 3. Positive structural evidence — language independent, so the copy-text
        //    blocklist is not consulted and a DM quoting "commented on" still alarms.
        structuralDmEvidence(signals)?.let { evidence ->
            return DmVerdict(DmDecision.DIRECT_MESSAGE, "Structural DM evidence: $evidence")
        }

        // 4. No structural evidence — fall back to copy text.
        SOCIAL_NOISE_PHRASES.firstOrNull { haystack.contains(it) }?.let { phrase ->
            return DmVerdict(DmDecision.REJECTED, "Social noise phrase: \"$phrase\"")
        }
        if (AGGREGATE_PATTERN.containsMatchIn(haystack)) {
            return DmVerdict(DmDecision.REJECTED, "Aggregate roll-up notification")
        }

        return if (strict) {
            DmVerdict(
                DmDecision.REJECTED,
                "No structural DM evidence (strict mode); signals: ${signalSummary(signals)}"
            )
        } else {
            DmVerdict(DmDecision.DIRECT_MESSAGE, "Lenient mode: not matched as social noise")
        }
    }

    /** The first piece of positive evidence that this is a conversation, or null. */
    private fun structuralDmEvidence(signals: NotificationSignals): String? = when {
        signals.hasMessagingStyle -> "MessagingStyle"
        signals.hasRemoteInputAction -> "reply RemoteInput"
        signals.isMessageCategory -> "category=msg"
        !signals.shortcutId.isNullOrBlank() -> "conversation shortcut"
        else -> null
    }

    /** Compact structural fingerprint, recorded so users can diagnose their own device. */
    fun signalSummary(signals: NotificationSignals): String = buildString {
        append("template=").append(signals.template?.substringAfterLast('$') ?: "none")
        append(" category=").append(signals.category ?: "none")
        append(" channel=").append(signals.channelId ?: "none")
        append(" shortcut=").append(if (signals.shortcutId.isNullOrBlank()) "no" else "yes")
        append(" remoteInput=").append(if (signals.hasRemoteInputAction) "yes" else "no")
        append(" groupConv=").append(signals.isGroupConversation)
    }
}
