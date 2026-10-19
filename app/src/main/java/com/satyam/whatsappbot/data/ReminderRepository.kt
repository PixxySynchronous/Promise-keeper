package com.satyam.whatsappbot.data

import android.content.Context
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.Executors

class ReminderRepository(context: Context) {
    private val dao = ReminderDatabase.get(context).reminderDao()
    private val executor = Executors.newSingleThreadExecutor()

    fun insertPending(message: String, contact: String, matchedPhrase: String, reminderAt: LocalDateTime, title: String = message, alarm: Boolean = false, onComplete: (ReminderEntity) -> Unit) {
        executor.execute {
            val reminder = ReminderEntity(
                message = message,
                title = title,
                contact = contact,
                matchedPhrase = matchedPhrase,
                reminderAtEpochMillis = reminderAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                alarm = alarm
            )
            val saved = reminder.copy(id = dao.insert(reminder))
            onComplete(saved)
        }
    }

    fun get(id: Long, onComplete: (ReminderEntity?) -> Unit) = executor.execute { onComplete(dao.findById(id)) }

    fun getAll(onComplete: (List<ReminderEntity>) -> Unit) = executor.execute { onComplete(dao.findAll()) }

    fun updateTitle(id: Long, title: String) = executor.execute { dao.updateTitle(id, title) }

    fun setAlarm(id: Long, alarm: Boolean) = executor.execute { dao.updateAlarm(id, alarm) }

    fun delete(id: Long) = executor.execute { dao.delete(id) }

    fun deleteAll(ids: List<Long>, onComplete: () -> Unit = {}) = executor.execute { dao.deleteAll(ids); onComplete() }

    fun markScheduled(id: Long) = executor.execute { dao.updateStatus(id, ReminderEntity.STATUS_SCHEDULED) }

    fun markIgnored(id: Long) = executor.execute { dao.updateStatus(id, ReminderEntity.STATUS_IGNORED) }

    fun markDone(id: Long) = executor.execute { dao.updateStatus(id, ReminderEntity.STATUS_DONE) }

    fun markFalseAlarm(id: Long) = executor.execute { dao.updateStatus(id, ReminderEntity.STATUS_FALSE_ALARM) }

    /** "It was a promise after all": back to the review list. */
    fun restoreFromFalseAlarm(id: Long) = executor.execute { dao.updateStatus(id, ReminderEntity.STATUS_PENDING_CONFIRMATION) }

    fun deleteFalseAlarms(onComplete: () -> Unit = {}) = executor.execute { dao.deleteFalseAlarms(); onComplete() }

    fun markSnoozed(id: Long, timeMillis: Long) = executor.execute {
        dao.updateTimeAndSchedule(id, timeMillis)
        dao.updateStatus(id, ReminderEntity.STATUS_SNOOZED)
    }

    fun updateTimeAndSchedule(id: Long, timeMillis: Long) = executor.execute {
        dao.updateTimeAndSchedule(id, timeMillis)
    }

    fun scheduleAll(onEach: (ReminderEntity) -> Unit) = executor.execute { dao.findPending().forEach(onEach) }
}
