package com.walarm.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.TelephonyManager
import android.util.Log
import com.walarm.app.alarm.AlarmPlayer
import com.walarm.app.data.AppDatabase
import com.walarm.app.data.SettingsRepository
import com.walarm.app.domain.AlarmAction
import com.walarm.app.domain.AlarmDecision
import com.walarm.app.domain.PresenceSnapshot
import com.walarm.app.domain.ScheduleWindow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Escalates an incoming *cellular* call from a watchlisted contact into a full alarm,
 * and stops the alarm once the call is answered or ends.
 */
class PhoneCallReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "PhoneCallReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        @Suppress("DEPRECATION")
        val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
        Log.i(TAG, "Phone state: $state")

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                when (state) {
                    TelephonyManager.EXTRA_STATE_RINGING -> {
                        if (!SettingsRepository.current(context).overridePhoneCalls) {
                            Log.d(TAG, "Phone call override disabled in settings")
                            return@launch
                        }
                        if (!incomingNumber.isNullOrEmpty()) handleRingingCall(context, incomingNumber)
                    }

                    TelephonyManager.EXTRA_STATE_OFFHOOK,
                    TelephonyManager.EXTRA_STATE_IDLE -> {
                        // Answered or ended — the user is on the phone either way.
                        Log.i(TAG, "Call answered or ended; stopping alarm")
                        AlarmPlayer.stop()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling phone state change", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun handleRingingCall(context: Context, number: String) {
        val contactDao = AppDatabase.getDatabase(context).contactDao()

        // The watchlist may store the raw number, or the contact name it resolves to.
        val matched = contactDao.getContactByName(number)
            ?: contactNameFor(context, number)?.let { name ->
                Log.i(TAG, "Resolved incoming number to contact '$name'")
                contactDao.getContactByName(name)
            }

        if (matched == null) {
            Log.i(TAG, "Incoming call did not match any watched contact")
            return
        }

        // Presence suppression deliberately does not apply to cellular calls: a ringing
        // phone is already audible, so only the contact's own schedule is consulted.
        val verdict = AlarmDecision.decide(
            contact = matched,
            settings = SettingsRepository.current(context),
            presence = PresenceSnapshot(),
            minuteOfDay = ScheduleWindow.minuteOfDayNow()
        )
        Log.w(TAG, "VIP call from '${matched.name}' -> ${verdict.action}: ${verdict.reason}")

        when (verdict.action) {
            AlarmAction.SILENT -> Unit
            AlarmAction.VIBRATE_ONLY -> AlarmPlayer.triggerVibration(context, repeat = false)
            AlarmAction.LOUD -> AlarmPlayer.play(context, matched)
        }
    }

    private fun contactNameFor(context: Context, phoneNumber: String): String? = try {
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(phoneNumber)
        )
        context.contentResolver
            .query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    } catch (e: Exception) {
        Log.e(TAG, "Contact lookup failed", e)
        null
    }
}
