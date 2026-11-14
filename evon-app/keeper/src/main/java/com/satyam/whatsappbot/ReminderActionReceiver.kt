package com.satyam.whatsappbot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.satyam.whatsappbot.data.ReminderEntity
import com.satyam.whatsappbot.data.ReminderRepository

class ReminderActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(ReminderNotifications.EXTRA_REMINDER_ID, 0L)
        if (id == 0L) return
        val pendingResult = goAsync()
        val repository = ReminderRepository(context)
        when (intent.action) {
            ReminderNotifications.ACTION_SET -> repository.get(id) { found ->
                // A repeating suggestion left waiting past its first time starts from the next occurrence.
                val reminder = found?.let { r ->
                    if (r.repeat != null && r.reminderAtEpochMillis <= System.currentTimeMillis()) r.copy(reminderAtEpochMillis = ReminderNotifications.nextOccurrence(r)!!) else r
                }
                if (reminder != null && ReminderNotifications.schedule(context, reminder)) {
                    repository.updateTimeAndSchedule(id, reminder.reminderAtEpochMillis)
                    cancelConfirmation(context, id)
                }
                pendingResult.finish()
            }
            ReminderNotifications.ACTION_IGNORE -> {
                repository.markIgnored(id)
                cancelConfirmation(context, id)
                pendingResult.finish()
            }
            // Skip asks why in the same notification; Back returns to Set / Edit time / Skip.
            ReminderNotifications.ACTION_SKIP, ReminderNotifications.ACTION_BACK -> repository.get(id) { reminder ->
                if (reminder != null) ReminderNotifications.showConfirmation(context, reminder, askWhy = intent.action == ReminderNotifications.ACTION_SKIP)
                pendingResult.finish()
            }
            ReminderNotifications.ACTION_NOT_PROMISE -> {
                repository.markFalseAlarm(id)
                ReminderNotifications.cancel(context, id)
                cancelConfirmation(context, id)
                pendingResult.finish()
            }
            ReminderNotifications.ACTION_FIRE -> repository.get(id) { reminder ->
                if (reminder != null) {
                    ReminderNotifications.showReminder(context, reminder)
                    // A repeating reminder books its next occurrence as soon as this one rings.
                    val next = ReminderNotifications.nextOccurrence(reminder)
                    if (next != null && reminder.status != ReminderEntity.STATUS_PAUSED &&
                        ReminderNotifications.schedule(context, reminder.copy(reminderAtEpochMillis = next))
                    ) {
                        repository.updateTimeAndSchedule(id, next)
                    }
                }
                pendingResult.finish()
            }
            // Done/Stop on a repeating reminder only dismisses this ring; its next occurrence is already booked.
            ReminderNotifications.ACTION_DONE -> repository.get(id) { reminder ->
                if (reminder?.repeat == null) {
                    repository.markDone(id)
                    ReminderNotifications.cancel(context, id)
                }
                NotificationManagerCompat.from(context).cancel(ReminderNotifications.reminderNotificationId(id))
                pendingResult.finish()
            }
            ReminderNotifications.ACTION_SNOOZE, ReminderNotifications.ACTION_SNOOZE_SHORT -> repository.get(id) { reminder ->
                if (reminder != null) {
                    // Alarms snooze for 10 minutes, notifications for an hour.
                    val minutes = if (intent.action == ReminderNotifications.ACTION_SNOOZE_SHORT) 10L else 60L
                    val snoozed = reminder.copy(reminderAtEpochMillis = System.currentTimeMillis() + minutes * 60 * 1000L)
                    if (ReminderNotifications.schedule(context, snoozed)) {
                        repository.markSnoozed(id, snoozed.reminderAtEpochMillis)
                        NotificationManagerCompat.from(context).cancel(ReminderNotifications.reminderNotificationId(id))
                    }
                }
                pendingResult.finish()
            }
            else -> pendingResult.finish()
        }
    }

    private fun cancelConfirmation(context: Context, id: Long) {
        NotificationManagerCompat.from(context).cancel(ReminderNotifications.confirmationNotificationId(id))
    }
}