package com.walarm.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [WatchedContact::class, DebugLog::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun contactDao(): ContactDao
    abstract fun debugLogDao(): DebugLogDao

    companion object {
        private const val DATABASE_NAME = "zalarm_database"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME
                )
                    // Scoped to the pre-release schemas only. A blanket
                    // fallbackToDestructiveMigration() silently wipes the user's entire
                    // watchlist on any future schema bump — for an app whose whole job is
                    // to alarm on specific people, that is a silent, total failure.
                    // Future versions must ship a real Migration instead.
                    .fallbackToDestructiveMigrationFrom(1, 2)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
