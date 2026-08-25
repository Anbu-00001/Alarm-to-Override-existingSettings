package com.walarm.app.util

import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Instagram is a low-signal channel: direct messages arrive alongside a constant stream of
 * likes, comments, follows, reels and growth prompts. Everything that decides *whether*
 * a notification deserves an alarm lives in [InstagramDmClassifier]; this parser only
 * applies that verdict and then shares the conversation extraction with WhatsApp.
 */
object InstagramParser : AppNotificationParser {
    private const val TAG = "InstagramParser"

    override val supportedPackages: Set<String> = setOf(
        "com.instagram.android",
        "com.instagram.lite"
    )

    /**
     * When true, a notification must carry positive structural evidence of being a
     * conversation. Overridden per-parse via [parse] so the setting can drive it.
     */
    @Volatile
    var strictDmMode: Boolean = true

    override fun parse(sbn: StatusBarNotification): NotificationParser.ParsedNotification? =
        parse(sbn, strictDmMode)

    fun parse(sbn: StatusBarNotification, strict: Boolean): NotificationParser.ParsedNotification? {
        val signals = NotificationSignals.from(sbn) ?: return null

        val verdict = InstagramDmClassifier.classify(signals, strict)
        if (!verdict.isAlarmable) {
            Log.d(TAG, "Rejected: ${verdict.reason} | ${InstagramDmClassifier.signalSummary(signals)}")
            return null
        }

        Log.i(TAG, "Accepted as ${verdict.decision}: ${verdict.reason}")
        return ConversationParsing.toParsed(signals)
    }
}
