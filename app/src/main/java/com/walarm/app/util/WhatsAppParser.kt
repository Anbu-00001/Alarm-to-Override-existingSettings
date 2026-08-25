package com.walarm.app.util

import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * WhatsApp is a high-intent channel: essentially every notification is a real
 * conversation, so no noise filtering is needed beyond dropping summary roll-ups.
 */
object WhatsAppParser : AppNotificationParser {
    private const val TAG = "WhatsAppParser"

    override val supportedPackages: Set<String> = setOf("com.whatsapp", "com.whatsapp.w4b")

    override fun parse(sbn: StatusBarNotification): NotificationParser.ParsedNotification? {
        val signals = NotificationSignals.from(sbn) ?: return null

        // WhatsApp posts an InboxStyle summary above the per-chat notifications. Parsing it
        // too meant every message was processed twice — a duplicate debug-log row, and a
        // second alarm attempt that only the cooldown happened to absorb.
        if (signals.isGroupSummary) {
            Log.d(TAG, "Skipping group summary notification")
            return null
        }

        return ConversationParsing.toParsed(signals)?.also {
            Log.d(TAG, "Parsed: sender=${it.sender} isGroup=${it.isGroup} individual=${it.individualSender}")
        }
    }
}
