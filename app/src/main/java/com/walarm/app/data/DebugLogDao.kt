package com.walarm.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DebugLogDao {
    @Query("SELECT * FROM debug_logs ORDER BY timestamp DESC LIMIT 100")
    fun getRecentLogsFlow(): Flow<List<DebugLog>>

    @Insert
    suspend fun insertLog(log: DebugLog): Long

    @Query("DELETE FROM debug_logs")
    suspend fun clearLogs()
}
