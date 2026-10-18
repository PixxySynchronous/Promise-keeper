package com.satyam.whatsappbot.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface ReminderDao {
    @Insert
    fun insert(reminder: ReminderEntity): Long

    @Update
    fun update(reminder: ReminderEntity)

    @Query("SELECT * FROM reminders WHERE id = :id LIMIT 1")
    fun findById(id: Long): ReminderEntity?

    @Query("SELECT * FROM reminders ORDER BY reminderAtEpochMillis")
    fun findAll(): List<ReminderEntity>

    @Query("SELECT * FROM reminders WHERE status IN ('SCHEDULED', 'SNOOZED') ORDER BY reminderAtEpochMillis")
    fun findPending(): List<ReminderEntity>

    @Query("UPDATE reminders SET status = :status WHERE id = :id")
    fun updateStatus(id: Long, status: String)

    @Query("UPDATE reminders SET reminderAtEpochMillis = :timeMillis, status = 'SCHEDULED' WHERE id = :id")
    fun updateTimeAndSchedule(id: Long, timeMillis: Long)

    @Query("UPDATE reminders SET title = :title WHERE id = :id")
    fun updateTitle(id: Long, title: String)

    @Query("DELETE FROM reminders WHERE id = :id")
    fun delete(id: Long)
}
