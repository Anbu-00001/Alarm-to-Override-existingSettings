package com.walarm.app.util

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

    private val parsers: List<AppNotificationParser> = listOf(
        WhatsAppParser,
        InstagramParser
    )

    fun parse(sbn: StatusBarNotification): ParsedNotification? {
        val packageName = sbn.packageName
        
        // Find matching parser by package name
        val parser = parsers.firstOrNull { packageName in it.supportedPackages }
            ?: if (packageName == "com.android.shell") WhatsAppParser else null

        if (parser == null) {
            Log.d(TAG, "No parser registered for package: $packageName")
            return null
        }

        return parser.parse(sbn)
    }
}
