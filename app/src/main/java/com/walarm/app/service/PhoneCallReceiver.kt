package com.walarm.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.TelephonyManager
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import com.walarm.app.alarm.AlarmPlayer
import com.walarm.app.data.AppDatabase
import com.walarm.app.data.WatchedContact
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class PhoneCallReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "PhoneCallReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)

        Log.i(TAG, "Phone State Change: $state, Number: $incomingNumber")

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Check if global Phone Call override is enabled
                val overrideEnabled = context.dataStore.data.map { 
                    it[booleanPreferencesKey("override_phone_calls")] ?: true 
                }.first()

                if (!overrideEnabled) {
                    Log.d(TAG, "Phone call override disabled in settings.")
                    return@launch
                }

                when (state) {
                    TelephonyManager.EXTRA_STATE_RINGING -> {
                        if (!incomingNumber.isNullOrEmpty()) {
                            handleRingingCall(context, incomingNumber)
                        }
                    }
                    TelephonyManager.EXTRA_STATE_OFFHOOK, 
                    TelephonyManager.EXTRA_STATE_IDLE -> {
                        // Terminate alarm if ringing stops or call is answered
                        Log.i(TAG, "Call answered or ended. Stopping alarm player...")
                        AlarmPlayer.stop()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in PhoneCallReceiver processing", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun handleRingingCall(context: Context, number: String) {
        val database = AppDatabase.getDatabase(context)
        val contactDao = database.contactDao()

        // 1. Direct search: does database watchlist contain this raw phone number?
        var matchedContact = contactDao.getContactByName(number)

        // 2. Secondary lookup: resolve number to Contacts name
        if (matchedContact == null) {
            val resolvedName = getContactNameFromNumber(context, number)
            if (!resolvedName.isNullOrEmpty()) {
                Log.i(TAG, "Resolved incoming number $number to contact name: $resolvedName")
                matchedContact = contactDao.getContactByName(resolvedName)
            }
        }

        if (matchedContact != null) {
            Log.w(TAG, "LOUD ALARM: Incoming phone call matched VIP: ${matchedContact.name}")
            
            // Check Schedule Constraints
            var playSound = true
            var vibeOnly = false

            if (matchedContact.isScheduleEnabled) {
                val inSchedule = isCurrentTimeInSchedule(matchedContact)
                if (!inSchedule) {
                    if (matchedContact.vibeOnlyOutsideSchedule) {
                        vibeOnly = true
                    } else {
                        playSound = false
                    }
                }
            }

            if (playSound) {
                if (vibeOnly) {
                    AlarmPlayer.triggerVibration(context, false)
                } else {
                    AlarmPlayer.play(context, matchedContact)
                }
            }
        } else {
            Log.i(TAG, "Incoming call $number did not match any watched contact.")
        }
    }

    private fun getContactNameFromNumber(context: Context, phoneNumber: String): String? {
        try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(phoneNumber)
            )
            val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    return cursor.getString(0)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to lookup contact name for $phoneNumber", e)
        }
        return null
    }

    private fun isCurrentTimeInSchedule(contact: WatchedContact): Boolean {
        val calendar = java.util.Calendar.getInstance()
        val hour = calendar.get(java.util.Calendar.HOUR_OF_DAY)
        val minute = calendar.get(java.util.Calendar.MINUTE)
        
        val currentMinutes = hour * 60 + minute
        val startMinutes = contact.startHour * 60 + contact.startMinute
        val endMinutes = contact.endHour * 60 + contact.endMinute
        
        return if (startMinutes <= endMinutes) {
            currentMinutes in startMinutes..endMinutes
        } else {
            currentMinutes >= startMinutes || currentMinutes <= endMinutes
        }
    }
}
