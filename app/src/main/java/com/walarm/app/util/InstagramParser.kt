package com.walarm.app.util

import android.app.Notification
import android.os.Build
import android.service.notification.StatusBarNotification
import android.util.Log

object InstagramParser : AppNotificationParser {
    private const val TAG = "InstagramParser"

    override val supportedPackages: Set<String> = setOf(
        "com.instagram.android",
        "com.instagram.lite"
    )

    /**
     * Common Instagram non-DM notification phrases (social noise).
     * If title, text, or subText matches these, it is discarded immediately.
     */
    private val SOCIAL_NOISE_PATTERNS = listOf(
        "liked your",
        "commented on",
        "commented:",
        "started a live",
        "is live now",
        "went live",
        "mentioned you",
        "tagged you",
        "started following",
        "requested to follow",
        "accepted your follow",
        "shared a post",
        "shared a reel",
        "shared a photo",
        "broadcast channel",
        "posted for the first time",
        "suggested for you",
        "new post by",
        "added to their story",
        "reposted your"
    )

    override fun parse(sbn: StatusBarNotification): NotificationParser.ParsedNotification? {
        val extras = sbn.notification.extras ?: return null

        val rawTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val rawText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val rawSubText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
        val rawConversationTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()

        if (rawTitle.isNullOrEmpty()) return null

        val fullText = "$rawTitle $rawText $rawSubText ${rawConversationTitle ?: ""}".lowercase()
        val isCallNotification = sbn.notification.category == Notification.CATEGORY_CALL ||
                fullText.contains("call") || fullText.contains("calling")

        // 1. Social Noise Filter: Rejection of non-DM engagement alerts (bypassed for calls)
        if (!isCallNotification && SOCIAL_NOISE_PATTERNS.any { fullText.contains(it) }) {
            Log.d(TAG, "Instagram notification discarded (social noise): title=$rawTitle | text=$rawText")
            return null
        }

        // 2. MessagingStyle extraction (Most reliable on Android 7+)
        val messagingStyleSender = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val messages = Notification.MessagingStyle.Message.getMessagesFromBundleArray(
                    extras.getParcelableArray(Notification.EXTRA_MESSAGES)
                )
                messages?.lastOrNull()?.senderPerson?.name?.toString()
            } else null
        } catch (e: Exception) { null }

        Log.d(TAG, "Instagram Raw: title=$rawTitle | text=$rawText | subText=$rawSubText | convTitle=$rawConversationTitle | msgStyleSender=$messagingStyleSender")

        val isGroupFlag = extras.getBoolean("android.isGroupConversation", false)
        val hasConversationTitle = !rawConversationTitle.isNullOrEmpty()

        var isGroup = isGroupFlag || hasConversationTitle ||
                (rawSubText != null && rawSubText.isNotEmpty() && rawTitle != rawSubText)

        var messageText = rawText ?: ""
        var individualSender: String? = null
        var groupName: String? = null

        if (isGroup) {
            groupName = rawConversationTitle ?: rawTitle
            individualSender = messagingStyleSender

            if (individualSender == null) {
                val colonIndex = messageText.indexOf(": ")
                if (colonIndex > 0 && colonIndex < 40) {
                    val candidateSender = messageText.substring(0, colonIndex).trim()
                    if (candidateSender.length <= 35 && !candidateSender.contains("http")) {
                        individualSender = candidateSender
                        messageText = messageText.substring(colonIndex + 2)
                    }
                }
            }

            if (individualSender == null && rawTitle != groupName) {
                individualSender = rawTitle
            }
        }

        val sender = if (isGroup) {
            groupName ?: rawTitle
        } else {
            rawTitle
        }

        Log.d(TAG, "Instagram Parsed DM: sender=$sender | group=$groupName | individualSender=$individualSender | isGroup=$isGroup | message=$messageText")

        return NotificationParser.ParsedNotification(
            sender = sender.trim(),
            message = messageText,
            groupName = groupName?.trim(),
            isGroup = isGroup,
            individualSender = individualSender?.trim(),
            rawTitle = rawTitle,
            rawText = rawText,
            rawSubText = rawSubText,
            rawConversationTitle = rawConversationTitle
        )
    }
}
