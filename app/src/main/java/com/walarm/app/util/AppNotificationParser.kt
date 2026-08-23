package com.walarm.app.util

import android.service.notification.StatusBarNotification

/**
 * Interface for application-specific notification parsers.
 * Each supported messaging platform (WhatsApp, Instagram, etc.) implements this
 * interface to handle package-specific payload quirks and noise filtering.
 */
interface AppNotificationParser {
    /**
     * Package names supported by this parser.
     */
    val supportedPackages: Set<String>

    /**
     * Parses a [StatusBarNotification] into a standardized [NotificationParser.ParsedNotification].
     * Returns null if the notification is invalid, unsupported, or filtered out as non-message noise.
     */
    fun parse(sbn: StatusBarNotification): NotificationParser.ParsedNotification?
}
