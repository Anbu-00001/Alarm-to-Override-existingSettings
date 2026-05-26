package com.walarm.app.util

import android.app.Notification
import android.os.Build
import android.service.notification.StatusBarNotification
import android.util.Log

object NotificationParser {
    private const val TAG = "NotificationParser"

    data class ParsedNotification(
        val sender: String, // Contact or Group Name
        val message: String, // Message body
        val groupName: String?, // Group name if applicable
        val isGroup: Boolean,
        val individualSender: String?, // Specific sender in a group, if extractable
        val rawTitle: String?,
        val rawText: String?,
        val rawSubText: String?,
        val rawConversationTitle: String?
    )

    fun parse(sbn: StatusBarNotification): ParsedNotification? {
        val extras = sbn.notification.extras ?: return null
        
        val rawTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val rawText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val rawSubText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
        val rawConversationTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
        
        // Also try MessagingStyle for modern WhatsApp versions
        val messagingStyleSender = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val messages = Notification.MessagingStyle.Message.getMessagesFromBundleArray(
                    extras.getParcelableArray(Notification.EXTRA_MESSAGES)
                )
                messages?.lastOrNull()?.senderPerson?.name?.toString()
            } else null
        } catch (e: Exception) { null }

        if (rawTitle.isNullOrEmpty()) return null

        Log.d(TAG, "Raw: title=$rawTitle | text=$rawText | subText=$rawSubText | convTitle=$rawConversationTitle | msgStyleSender=$messagingStyleSender")

        // Step 1: Determine if this is a group conversation
        val isGroupFlag = extras.getBoolean("android.isGroupConversation", false)
        val hasConversationTitle = !rawConversationTitle.isNullOrEmpty()
        
        // If EXTRA_CONVERSATION_TITLE is present, it's almost certainly a group chat.
        // The isGroupConversation flag is also reliable.
        // SubText != Title can also indicate a group on older WhatsApp versions.
        val isGroup = isGroupFlag || hasConversationTitle ||
                      (rawSubText != null && rawSubText.isNotEmpty() && rawTitle != rawSubText)
        
        var messageText = rawText ?: ""
        var individualSender: String? = null
        var groupName: String? = null

        if (isGroup) {
            // For groups: rawConversationTitle or rawTitle is the group name
            groupName = rawConversationTitle ?: rawTitle
            
            // The individual sender can come from multiple sources:
            // 1. MessagingStyle sender (most reliable on modern Android)
            // 2. The "SenderName: Message" pattern in rawText
            // 3. rawTitle if it differs from groupName
            
            individualSender = messagingStyleSender
            
            if (individualSender == null) {
                // WhatsApp formats group messages as "SenderName: actual message" in EXTRA_TEXT
                val colonIndex = messageText.indexOf(": ")
                if (colonIndex > 0 && colonIndex < 40) {
                    val candidateSender = messageText.substring(0, colonIndex).trim()
                    // Validate: sender name should not look like a message fragment
                    // It should be relatively short and not contain typical message patterns
                    if (candidateSender.length <= 35 && !candidateSender.contains("http")) {
                        individualSender = candidateSender
                        messageText = messageText.substring(colonIndex + 2)
                    }
                }
            }
            
            if (individualSender == null && rawTitle != groupName) {
                individualSender = rawTitle
            }
        } else {
            // For direct chats: rawTitle IS the sender name. 
            // Do NOT apply colon-splitting to direct messages!
        }

        val sender = if (isGroup) {
            groupName ?: rawTitle
        } else {
            rawTitle
        }

        Log.d(TAG, "Parsed: sender=$sender | group=$groupName | individualSender=$individualSender | isGroup=$isGroup | message=$messageText")

        return ParsedNotification(
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
