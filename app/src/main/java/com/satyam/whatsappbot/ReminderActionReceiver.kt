package com.satyam.whatsappbot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.satyam.whatsappbot.data.ReminderRepository

class ReminderActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(ReminderNotifications.EXTRA_REMINDER_ID, 0L)
        if (id == 0L) return
        val pendingResult = goAsync()
        val repository = ReminderRepository(context)
        when (intent.action) {
            ReminderNotifications.ACTION_SET -> repository.get(id) { reminder ->
                if (reminder != null && ReminderNotifications.schedule(context, reminder)) {
                    repository.markScheduled(id)
                    cancelConfirmation(context, id)
                }
                pendingResult.finish()
            }
            ReminderNotifications.ACTION_IGNORE -> {
                repository.markIgnored(id)
                cancelConfirmation(context, id)
                pendingResult.finish()
            }
            ReminderNotifications.ACTION_NOT_PROMISE -> {
                repository.markFalseAlarm(id)
                ReminderNotifications.cancel(context, id)
                cancelConfirmation(context, id)
                pendingResult.finish()
            }
            ReminderNotifications.ACTION_FIRE -> repository.get(id) { reminder ->
                if (reminder != null) ReminderNotifications.showReminder(context, reminder)
                pendingResult.finish()
            }
            ReminderNotifications.ACTION_DONE -> {
                repository.markDone(id)
                ReminderNotifications.cancel(context, id)
                NotificationManagerCompat.from(context).cancel(ReminderNotifications.reminderNotificationId(id))
                pendingResult.finish()
            }
            ReminderNotifications.ACTION_SNOOZE -> repository.get(id) { reminder ->
                if (reminder != null) {
                    val snoozed = reminder.copy(reminderAtEpochMillis = System.currentTimeMillis() + 60 * 60 * 1000L)
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