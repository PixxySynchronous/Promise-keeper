package com.satyam.whatsappbot.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "reminders")
data class ReminderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val message: String,
    val contact: String,
    val matchedPhrase: String,
    val reminderAtEpochMillis: Long,
    val status: String = STATUS_PENDING_CONFIRMATION,
    val createdAtEpochMillis: Long = System.currentTimeMillis()
) {
    companion object {
        const val STATUS_PENDING_CONFIRMATION = "PENDING_CONFIRMATION"
        const val STATUS_SCHEDULED = "SCHEDULED"
        const val STATUS_IGNORED = "IGNORED"
        const val STATUS_DONE = "DONE"
        const val STATUS_SNOOZED = "SNOOZED"
        /** The user marked the suggestion as "not a promise"; kept for review and optional export. */
        const val STATUS_FALSE_ALARM = "FALSE_ALARM"
    }
}
