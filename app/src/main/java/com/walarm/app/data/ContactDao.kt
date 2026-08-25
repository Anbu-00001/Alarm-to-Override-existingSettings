package com.walarm.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactDao {
    @Query("SELECT * FROM watched_contacts ORDER BY name ASC")
    fun getAllContactsFlow(): Flow<List<WatchedContact>>

    @Query("SELECT * FROM watched_contacts ORDER BY name ASC")
    suspend fun getAllContacts(): List<WatchedContact>

    /** Contacts that accept notifications from [targetApp] (plus the ALL-apps entries). */
    @Query(
        """
        SELECT * FROM watched_contacts
        WHERE targetApp = 'ALL' OR targetApp = :targetApp
        ORDER BY name ASC
        """
    )
    suspend fun getContactsForApp(targetApp: String): List<WatchedContact>

    @Query("SELECT * FROM watched_contacts WHERE LOWER(name) = LOWER(:name) LIMIT 1")
    suspend fun getContactByName(name: String): WatchedContact?

    /**
     * Exact lookup restricted to contacts that accept [targetApp].
     *
     * The app filter belongs in SQL. Matching in Kotlin over a full table load — as an
     * earlier version did whenever the app filter rejected the SQL candidate — meant two
     * matching engines with different tie-breaking, and a whole-table read on every
     * notification that did not match. Instagram's volume makes that read the hot path.
     */
    @Query(
        """
        SELECT * FROM watched_contacts
        WHERE LOWER(name) = LOWER(:name)
          AND (targetApp = 'ALL' OR targetApp = :targetApp)
        LIMIT 1
        """
    )
    suspend fun getContactByNameForApp(name: String, targetApp: String): WatchedContact?

    /**
     * Substring match in either direction, used when an exact name lookup misses.
     *
     * The `LENGTH(name) >= 3` guard matters: without it a watchlist entry named "Al"
     * matches every sender containing "al" — "Ronald", "Alice", "Natalie" — and turns
     * the whole watchlist into a catch-all. Short names still match exactly.
     * Longest entry first so the most specific watchlist name wins.
     */
    @Query(
        """
        SELECT * FROM watched_contacts
        WHERE LENGTH(name) >= 3
          AND (LOWER(:name) LIKE '%' || LOWER(name) || '%'
               OR LOWER(name) LIKE '%' || LOWER(:name) || '%')
        ORDER BY LENGTH(name) DESC
        LIMIT 1
        """
    )
    suspend fun getContactByNameFuzzy(name: String): WatchedContact?

    /** [getContactByNameFuzzy] restricted to contacts that accept [targetApp]. */
    @Query(
        """
        SELECT * FROM watched_contacts
        WHERE LENGTH(name) >= 3
          AND (targetApp = 'ALL' OR targetApp = :targetApp)
          AND (LOWER(:name) LIKE '%' || LOWER(name) || '%'
               OR LOWER(name) LIKE '%' || LOWER(:name) || '%')
        ORDER BY LENGTH(name) DESC
        LIMIT 1
        """
    )
    suspend fun getContactByNameFuzzyForApp(name: String, targetApp: String): WatchedContact?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertContact(contact: WatchedContact): Long

    @Update
    suspend fun updateContact(contact: WatchedContact)

    @Delete
    suspend fun deleteContact(contact: WatchedContact)

    @Query("UPDATE watched_contacts SET lastTriggeredTime = :timestamp WHERE id = :id")
    suspend fun updateLastTriggered(id: Long, timestamp: Long)
}
