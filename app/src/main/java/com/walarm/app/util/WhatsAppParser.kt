package com.walarm.app.util

import android.app.Notification
import android.os.Build
import android.service.notification.StatusBarNotification
import android.util.Log

object WhatsAppParser : AppNotificationParser {
    private const val TAG = "WhatsAppParser"

    override val supportedPackages: Set<String> = setOf(
        "com.whatsapp",
        "com.whatsapp.w4b"
    )

    override fun parse(sbn: StatusBarNotification): NotificationParser.ParsedNotification? {
        val extras = sbn.notification.extras ?: return null

        val rawTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val rawText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val rawSubText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
        val rawConversationTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()

        val messagingStyleSender = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val messages = Notification.MessagingStyle.Message.getMessagesFromBundleArray(
                    extras.getParcelableArray(Notification.EXTRA_MESSAGES)
                )
                messages?.lastOrNull()?.senderPerson?.name?.toString()
            } else null
        } catch (e: Exception) { null }

        if (rawTitle.isNullOrEmpty()) return null

        Log.d(TAG, "WhatsApp Raw: title=$rawTitle | text=$rawText | subText=$rawSubText | convTitle=$rawConversationTitle | msgStyleSender=$messagingStyleSender")

        val isGroupFlag = extras.getBoolean("android.isGroupConversation", false)
        val hasConversationTitle = !rawConversationTitle.isNullOrEmpty()

        var isGroup = isGroupFlag || hasConversationTitle ||
                (rawSubText != null && rawSubText.isNotEmpty() && rawTitle != rawSubText)

        if (!isGroup && !rawText.isNullOrEmpty()) {
            val colonIndex = rawText.indexOf(": ")
            if (colonIndex > 0 && colonIndex < 40) {
                val candidateSender = rawText.substring(0, colonIndex).trim()
                if (candidateSender.length <= 35 && !candidateSender.contains("http")) {
                    isGroup = true
                }
            }
        }

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

        Log.d(TAG, "WhatsApp Parsed: sender=$sender | group=$groupName | individualSender=$individualSender | isGroup=$isGroup | message=$messageText")

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
