package com.walarm.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "watched_contacts")
data class WatchedContact(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String, // Contact or group name (case-insensitive search)
    val isGroup: Boolean = false,
    val ringtonePath: String? = null, // URI of chosen ringtone, null for default
    val useAlarmVolume: Boolean = true, // Play on STREAM_ALARM
    val repeatUntilDismissed: Boolean = false, // VIP repeat mode
    val escalatingVolume: Boolean = false, // Increase volume every 5s after 10s
    val cooldownSeconds: Int = 30, // Cooldown in seconds
    val lastTriggeredTime: Long = 0L, // Timestamp in milliseconds

    // Schedule Rules
    val isScheduleEnabled: Boolean = false,
    val startHour: Int = 9,
    val startMinute: Int = 0,
    val endHour: Int = 23,
    val endMinute: Int = 0,
    val vibeOnlyOutsideSchedule: Boolean = true, // Soft vibe instead of loud alarm outside hours

    // Keyword override for group / chats
    val isKeywordFilterEnabled: Boolean = false,
    val keywords: String = "urgent,help,emergency,call me" // Comma-separated
)
