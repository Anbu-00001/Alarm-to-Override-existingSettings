package com.walarm.app.util

import android.app.Notification
import android.service.notification.StatusBarNotification

object NotificationParser {
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
        
        if (rawTitle.isNullOrEmpty()) return null

        // Detect if group chat
        val isGroup = !rawConversationTitle.isNullOrEmpty() || 
                      (rawSubText != null && rawSubText.isNotEmpty() && rawTitle != rawSubText)
        
        val groupName = if (isGroup) {
            rawConversationTitle ?: rawTitle
        } else {
            null
        }

        var messageText = rawText ?: ""
        var individualSender: String? = null
        
        if (isGroup && groupName != null) {
            // Check if the message text starts with "Name: Message"
            val colonIndex = messageText.indexOf(": ")
            if (colonIndex > 0 && colonIndex < 35) { // reasonable sender name length
                individualSender = messageText.substring(0, colonIndex).trim()
                messageText = messageText.substring(colonIndex + 2)
            } else {
                // Check if title is different from groupName, representing the sender
                if (rawTitle != groupName) {
                    individualSender = rawTitle
                }
            }
        }

        // The primary sender name is the chat title (could be contact name or group name)
        val sender = if (isGroup) {
            groupName ?: rawTitle
        } else {
            rawTitle
        }

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
