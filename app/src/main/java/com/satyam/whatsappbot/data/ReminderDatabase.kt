package com.satyam.whatsappbot.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [ReminderEntity::class], version = 4, exportSchema = false)
abstract class ReminderDatabase : RoomDatabase() {
    abstract fun reminderDao(): ReminderDao

    companion object {
        @Volatile private var instance: ReminderDatabase? = null

        fun get(context: Context): ReminderDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                ReminderDatabase::class.java,
                "reminders.db"
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build().also { instance = it }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE reminders ADD COLUMN title TEXT NOT NULL DEFAULT ''")
                database.execSQL("UPDATE reminders SET title = message WHERE title = ''")
            }
        }

        // 1.3: alarm-or-notification per reminder; existing reminders stay notifications.
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE reminders ADD COLUMN alarm INTEGER NOT NULL DEFAULT 0")
            }
        }

        // Repeating reminders; existing reminders stay one-time.
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE reminders ADD COLUMN recurrence TEXT NOT NULL DEFAULT ''")
            }
        }
    }
}
