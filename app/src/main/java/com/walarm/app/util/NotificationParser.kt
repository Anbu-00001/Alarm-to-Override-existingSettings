package com.walarm.app.util

import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Routes a notification to the parser that understands its package.
 */
object NotificationParser {
    private const val TAG = "NotificationParser"

    /** ADB-injected test notifications arrive under the shell package. */
    const val SHELL_PACKAGE = "com.android.shell"

    data class ParsedNotification(
        val sender: String, // Contact or Group Name
        val message: String, // Message body
        val groupName: String?, // Group name if applicable
        val isGroup: Boolean,
        val individualSender: String?, // Specific sender in a group, if extractable
        val rawTitle: String?,
        val rawText: String?,
        val rawSubText: String?,
        val rawConversationTitle: String?,
        /**
         * Originating package. Required rather than defaulted: it drives per-contact app
         * filtering, and a silent "" default there means "matches every app".
         */
        val packageName: String
    )

    private val parsers: List<AppNotificationParser> = listOf(WhatsAppParser, InstagramParser)

    fun parse(sbn: StatusBarNotification): ParsedNotification? {
        val packageName = sbn.packageName

        val parser = parsers.firstOrNull { packageName in it.supportedPackages }
            ?: if (packageName == SHELL_PACKAGE) WhatsAppParser else null

        if (parser == null) {
            Log.d(TAG, "No parser registered for package: $packageName")
            return null
        }

        return parser.parse(sbn)
    }
}
