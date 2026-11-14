package com.satyam.whatsappbot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.satyam.whatsappbot.data.ReminderRepository

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // Android clears alarms on restart.
        AccessibilityNudge.schedule(context)
        UpdateReminder.schedule(context)
        val repository = ReminderRepository(context)
        repository.scheduleAll { reminder ->
            // A repeating reminder whose time passed while the phone was off moves to its next occurrence.
            val next = ReminderNotifications.nextOccurrence(reminder)?.takeIf { reminder.reminderAtEpochMillis <= System.currentTimeMillis() }
            if (next == null) {
                ReminderNotifications.schedule(context, reminder)
            } else if (ReminderNotifications.schedule(context, reminder.copy(reminderAtEpochMillis = next))) {
                repository.updateTimeAndSchedule(reminder.id, next)
            }
        }
    }
}
