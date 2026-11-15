package com.satyam.whatsappbot.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.satyam.whatsappbot.domain.Recurrence

@Entity(tableName = "reminders")
data class ReminderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val message: String,
    /** The WhatsApp chat, or empty for reminders made in the app. */
    val contact: String,
    val matchedPhrase: String,
    /** For a repeating reminder, its next occurrence. */
    val reminderAtEpochMillis: Long,
    val status: String = STATUS_PENDING_CONFIRMATION,
    val createdAtEpochMillis: Long = System.currentTimeMillis(),
    /** Ring as an alarm (loud, repeating, full screen) instead of a normal notification. */
    @ColumnInfo(defaultValue = "0") val alarm: Boolean = false,
    /** [Recurrence.encode] for a repeating reminder, empty for a one-time one. */
    @ColumnInfo(defaultValue = "") val recurrence: String = ""
) {
    val repeat: Recurrence? get() = Recurrence.decode(recurrence)

    /** " (to Rahul)", or nothing for reminders made in the app. */
    val toContact: String get() = if (contact.isBlank()) "" else " (to $contact)"

    companion object {
        const val STATUS_PENDING_CONFIRMATION = "PENDING_CONFIRMATION"
        const val STATUS_SCHEDULED = "SCHEDULED"
        const val STATUS_IGNORED = "IGNORED"
        const val STATUS_DONE = "DONE"
        const val STATUS_SNOOZED = "SNOOZED"
        /** The user marked the suggestion as "not a promise"; kept for review and optional export. */
        const val STATUS_FALSE_ALARM = "FALSE_ALARM"
        /** A repeating reminder switched off: kept, but nothing is scheduled. */
        const val STATUS_PAUSED = "PAUSED"
        /** matchedPhrase for reminders made with the app's New reminder button. */
        const val MADE_IN_APP = "made in app"
    }
}
