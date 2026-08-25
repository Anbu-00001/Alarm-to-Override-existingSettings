package com.walarm.app.util

import android.app.Notification
import android.service.notification.StatusBarNotification

/**
 * The structural facts about a notification, extracted once into plain data.
 *
 * Every field here is a *structure* signal rather than a copy-text signal, which is what
 * makes it useful: Instagram's user-visible wording changes constantly and is localised
 * into dozens of languages, but whether a notification carries MessagingStyle, a reply
 * RemoteInput, or a conversation shortcut does not depend on either.
 *
 * Keeping this a pure data class (no Android types) is deliberate — the classification
 * built on top of it is then testable in plain JUnit, which [StatusBarNotification]
 * itself is not.
 */
data class NotificationSignals(
    val packageName: String,
    val title: String?,
    val text: String?,
    val subText: String?,
    val conversationTitle: String?,
    /** Sender reported by MessagingStyle's last message, when present. */
    val messagingSender: String?,
    /** [Notification.category], e.g. "msg" or "call". */
    val category: String?,
    val channelId: String?,
    /** Conversation shortcut id — set by apps that publish conversation shortcuts. */
    val shortcutId: String?,
    /** [Notification.EXTRA_TEMPLATE]: the style class name, e.g. "…Notification$MessagingStyle". */
    val template: String?,
    val isGroupConversation: Boolean,
    /** True for the roll-up notification an app posts above a bundle of real ones. */
    val isGroupSummary: Boolean,
    /** True when any action carries a RemoteInput — i.e. the notification is repliable. */
    val hasRemoteInputAction: Boolean
) {

    /**
     * MessagingStyle detected either from the template string or from a successfully
     * parsed sender. The template check is the cheaper and more reliable of the two:
     * `getMessagesFromBundleArray` can throw or return null on malformed payloads while
     * the template extra is a plain string the platform always writes.
     */
    val hasMessagingStyle: Boolean
        get() = template?.contains("MessagingStyle") == true || messagingSender != null

    val isMessageCategory: Boolean get() = category == Notification.CATEGORY_MESSAGE

    val isCallCategory: Boolean get() = category == Notification.CATEGORY_CALL

    /** Title + text + subtext + conversation title, lower-cased, for copy-text heuristics. */
    val searchableText: String by lazy {
        listOfNotNull(title, text, subText, conversationTitle)
            .joinToString(" ")
            .lowercase()
    }

    companion object {
        fun from(sbn: StatusBarNotification): NotificationSignals? {
            val notification = sbn.notification ?: return null
            val extras = notification.extras ?: return null

            return NotificationSignals(
                packageName = sbn.packageName,
                title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
                text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
                subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
                conversationTitle =
                    extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString(),
                messagingSender = lastMessagingSender(extras),
                category = notification.category,
                channelId = notification.channelId,
                shortcutId = notification.shortcutId,
                template = extras.getString(Notification.EXTRA_TEMPLATE),
                isGroupConversation = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false),
                isGroupSummary = notification.flags and Notification.FLAG_GROUP_SUMMARY != 0,
                hasRemoteInputAction = notification.actions
                    ?.any { !it.remoteInputs.isNullOrEmpty() } == true
            )
        }

        private fun lastMessagingSender(extras: android.os.Bundle): String? = try {
            Notification.MessagingStyle.Message
                .getMessagesFromBundleArray(extras.getParcelableArray(Notification.EXTRA_MESSAGES))
                ?.lastOrNull()
                ?.senderPerson
                ?.name
                ?.toString()
        } catch (e: Exception) {
            null
        }
    }
}
