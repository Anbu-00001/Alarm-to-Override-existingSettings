package com.walarm.app.util

/**
 * Turns [NotificationSignals] into a [NotificationParser.ParsedNotification].
 *
 * WhatsApp and Instagram present conversations almost identically once the app-specific
 * filtering is done, and the two parsers previously carried byte-identical copies of this
 * logic. Sharing it means a fix to group-sender extraction lands on both platforms.
 */
internal object ConversationParsing {

    /** "Sender: message" prefixes longer than this are message text, not a name. */
    private const val MAX_SENDER_PREFIX_INDEX = 40
    private const val MAX_SENDER_NAME_LENGTH = 35
    private const val SENDER_SEPARATOR = ": "

    fun toParsed(signals: NotificationSignals): NotificationParser.ParsedNotification? {
        val rawTitle = signals.title
        if (rawTitle.isNullOrEmpty()) return null

        val rawText = signals.text
        var isGroup = signals.isGroupConversation ||
            !signals.conversationTitle.isNullOrEmpty() ||
            (!signals.subText.isNullOrEmpty() && rawTitle != signals.subText)

        // Some builds omit both the group flag and the conversation title; a
        // "Sender: message" body is then the only hint that this is a group chat.
        if (!isGroup && !rawText.isNullOrEmpty() && senderPrefixOf(rawText) != null) {
            isGroup = true
        }

        var messageText = rawText.orEmpty()
        var individualSender: String? = null
        var groupName: String? = null

        if (isGroup) {
            groupName = signals.conversationTitle ?: rawTitle
            individualSender = signals.messagingSender

            if (individualSender == null) {
                senderPrefixOf(messageText)?.let { prefix ->
                    individualSender = prefix
                    messageText = messageText.substring(prefix.length + SENDER_SEPARATOR.length)
                }
            }

            if (individualSender == null && rawTitle != groupName) {
                individualSender = rawTitle
            }
        }

        val sender = if (isGroup) groupName ?: rawTitle else rawTitle

        return NotificationParser.ParsedNotification(
            sender = sender.trim(),
            message = messageText,
            groupName = groupName?.trim(),
            isGroup = isGroup,
            individualSender = individualSender?.trim(),
            rawTitle = rawTitle,
            rawText = rawText,
            rawSubText = signals.subText,
            rawConversationTitle = signals.conversationTitle,
            packageName = signals.packageName
        )
    }

    /** The "Sender" half of a "Sender: message" body, or null if it does not look like one. */
    private fun senderPrefixOf(body: String): String? {
        val separator = body.indexOf(SENDER_SEPARATOR)
        if (separator <= 0 || separator >= MAX_SENDER_PREFIX_INDEX) return null

        val candidate = body.substring(0, separator).trim()
        if (candidate.isEmpty() || candidate.length > MAX_SENDER_NAME_LENGTH) return null
        if (candidate.contains("http")) return null

        return candidate
    }
}
