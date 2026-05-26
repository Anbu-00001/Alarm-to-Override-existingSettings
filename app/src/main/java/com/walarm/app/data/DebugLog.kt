package com.walarm.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "debug_logs")
data class DebugLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val packageName: String,
    val title: String?,
    val text: String?,
    val subText: String?,
    val conversationTitle: String?,
    val matched: Boolean,
    val parsedSender: String?,
    val parsedMessage: String?,
    val isGroupChat: Boolean
)
