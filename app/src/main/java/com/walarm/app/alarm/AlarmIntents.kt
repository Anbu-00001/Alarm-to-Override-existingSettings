package com.walarm.app.alarm

import android.content.Context
import android.content.Intent
import com.walarm.app.ui.AlarmActivity

/** The payload shown on the lock-screen alarm overlay. */
data class AlertContent(
    val sbnKey: String,
    val contactName: String,
    val message: String,
    val groupName: String? = null,
    val isGroup: Boolean = false
)

/**
 * Builds and reads the alarm-overlay Intent.
 *
 * The extra keys used to be repeated as bare string literals in both the producer
 * (WaListenerService) and the consumer (AlarmActivity), where a typo on either side
 * would have silently degraded the overlay to its placeholder text.
 */
object AlarmIntents {

    private const val EXTRA_CONTACT_NAME = "contact_name"
    private const val EXTRA_MESSAGE_BODY = "message_body"
    private const val EXTRA_GROUP_NAME = "group_name"
    private const val EXTRA_IS_GROUP = "is_group"
    private const val EXTRA_SBN_KEY = "sbn_key"

    const val FALLBACK_CONTACT_NAME = "VIP Contact"
    const val FALLBACK_MESSAGE = "Incoming Urgent Message"

    fun overlayIntent(context: Context, content: AlertContent): Intent =
        Intent(context, AlarmActivity::class.java).apply {
            putExtra(EXTRA_CONTACT_NAME, content.contactName)
            putExtra(EXTRA_MESSAGE_BODY, content.message)
            putExtra(EXTRA_GROUP_NAME, content.groupName)
            putExtra(EXTRA_IS_GROUP, content.isGroup)
            putExtra(EXTRA_SBN_KEY, content.sbnKey)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }

    fun contentFrom(intent: Intent): AlertContent = AlertContent(
        sbnKey = intent.getStringExtra(EXTRA_SBN_KEY).orEmpty(),
        contactName = intent.getStringExtra(EXTRA_CONTACT_NAME) ?: FALLBACK_CONTACT_NAME,
        message = intent.getStringExtra(EXTRA_MESSAGE_BODY) ?: FALLBACK_MESSAGE,
        groupName = intent.getStringExtra(EXTRA_GROUP_NAME),
        isGroup = intent.getBooleanExtra(EXTRA_IS_GROUP, false)
    )
}
