package com.satyam.whatsappbot.data

import android.content.Context
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.Executors

class ReminderRepository(context: Context) {
    private val dao = ReminderDatabase.get(context).reminderDao()
    private val executor = Executors.newSingleThreadExecutor()

    fun insertPending(message: String, contact: String, matchedPhrase: String, reminderAt: LocalDateTime, onComplete: (ReminderEntity) -> Unit) {
        executor.execute {
            val reminder = ReminderEntity(
                message = message,
                title = message,
                contact = contact,
                matchedPhrase = matchedPhrase,
                reminderAtEpochMillis = reminderAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            )
            val saved = reminder.copy(id = dao.insert(reminder))
            onComplete(saved)
        }
    }

    fun get(id: Long, onComplete: (ReminderEntity?) -> Unit) = executor.execute { onComplete(dao.findById(id)) }

    fun getAll(onComplete: (List<ReminderEntity>) -> Unit) = executor.execute { onComplete(dao.findAll()) }

    fun updateTitle(id: Long, title: String) = executor.execute { dao.updateTitle(id, title) }

    fun delete(id: Long) = executor.execute { dao.delete(id) }

    fun markScheduled(id: Long) = executor.execute { dao.updateStatus(id, ReminderEntity.STATUS_SCHEDULED) }

    fun markIgnored(id: Long) = executor.execute { dao.updateStatus(id, ReminderEntity.STATUS_IGNORED) }

    fun markDone(id: Long) = executor.execute { dao.updateStatus(id, ReminderEntity.STATUS_DONE) }

    fun markSnoozed(id: Long, timeMillis: Long) = executor.execute {
        dao.updateTimeAndSchedule(id, timeMillis)
        dao.updateStatus(id, ReminderEntity.STATUS_SNOOZED)
    }

    fun updateTimeAndSchedule(id: Long, timeMillis: Long) = executor.execute {
        dao.updateTimeAndSchedule(id, timeMillis)
    }

    fun scheduleAll(onEach: (ReminderEntity) -> Unit) = executor.execute { dao.findPending().forEach(onEach) }
}
