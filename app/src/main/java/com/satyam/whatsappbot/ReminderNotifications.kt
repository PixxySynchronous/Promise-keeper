package com.satyam.whatsappbot

import android.app.Activity
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.satyam.whatsappbot.data.ReminderEntity
import com.satyam.whatsappbot.domain.ManualReminder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object ReminderNotifications {
    const val ACTION_SET = "com.satyam.whatsappbot.action.SET"
    const val ACTION_EDIT = "com.satyam.whatsappbot.action.EDIT"
    const val ACTION_IGNORE = "com.satyam.whatsappbot.action.IGNORE"
    const val ACTION_FIRE = "com.satyam.whatsappbot.action.FIRE"
    const val ACTION_DONE = "com.satyam.whatsappbot.action.DONE"
    const val ACTION_SNOOZE = "com.satyam.whatsappbot.action.SNOOZE"
    const val EXTRA_REMINDER_ID = "reminder_id"
    const val CHANNEL_CONFIRMATION = "commitment_confirmation"
    const val CHANNEL_REMINDER = "reminder"

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_CONFIRMATION, "Commitment confirmations", NotificationManager.IMPORTANCE_HIGH))
        manager.createNotificationChannel(NotificationChannel(CHANNEL_REMINDER, "Reminders", NotificationManager.IMPORTANCE_HIGH))
    }

    fun showConfirmation(context: Context, reminder: ReminderEntity) {
        createChannels(context)
        val manual = reminder.matchedPhrase == ManualReminder.MATCHED_PHRASE
        val text = if (manual) {
            "Remind you ${formatTime(reminder.reminderAtEpochMillis)}: ${reminder.title}?"
        } else {
            "Remind you ${formatTime(reminder.reminderAtEpochMillis)}: ${reminder.message} (to ${reminder.contact})?"
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_CONFIRMATION)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(if (manual) "Your reminder" else "Reminder suggestion")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .addAction(action(context, ACTION_SET, reminder.id, "Set"))
            .addAction(editAction(context, reminder.id))
            .addAction(action(context, ACTION_IGNORE, reminder.id, "Ignore"))
            .build()
        NotificationManagerCompat.from(context).notify(confirmationNotificationId(reminder.id), notification)
    }

    fun showReminder(context: Context, reminder: ReminderEntity) {
        createChannels(context)
        val body = "${reminder.message} (to ${reminder.contact})"
        val notification = NotificationCompat.Builder(context, CHANNEL_REMINDER)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(reminder.title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(false)
            .addAction(action(context, ACTION_DONE, reminder.id, "Done"))
            .addAction(action(context, ACTION_SNOOZE, reminder.id, "Snooze 1 hour"))
            .build()
        NotificationManagerCompat.from(context).notify(reminderNotificationId(reminder.id), notification)
    }

    fun schedule(context: Context, reminder: ReminderEntity): Boolean {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            // Android blocks activity starts from receivers; the health banner covers that case.
            if (context is Activity) {
                context.startActivity(Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
            }
            return false
        }
        val intent = Intent(context, ReminderActionReceiver::class.java).apply {
            action = ACTION_FIRE
            putExtra(EXTRA_REMINDER_ID, reminder.id)
        }
        val pendingIntent = PendingIntent.getBroadcast(context, alarmRequestCode(reminder.id), intent, pendingFlags())
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.reminderAtEpochMillis, pendingIntent)
        return true
    }

    fun cancel(context: Context, reminderId: Long) {
        val intent = Intent(context, ReminderActionReceiver::class.java).apply {
            action = ACTION_FIRE
            putExtra(EXTRA_REMINDER_ID, reminderId)
        }
        context.getSystemService(AlarmManager::class.java).cancel(
            PendingIntent.getBroadcast(context, alarmRequestCode(reminderId), intent, pendingFlags())
        )
    }

    fun confirmationNotificationId(id: Long) = (id xor (id ushr 32)).toInt()
    fun reminderNotificationId(id: Long) = confirmationNotificationId(id) + 100000
    private fun alarmRequestCode(id: Long) = confirmationNotificationId(id)

    private fun action(context: Context, action: String, id: Long, title: String): NotificationCompat.Action {
        val intent = Intent(context, ReminderActionReceiver::class.java).apply {
            this.action = action
            putExtra(EXTRA_REMINDER_ID, id)
        }
        return NotificationCompat.Action.Builder(0, title, PendingIntent.getBroadcast(context, (id + action.hashCode()).toInt(), intent, pendingFlags())).build()
    }

    // Opens MainActivity directly: Android 12+ blocks starting activities from a notification's receiver.
    private fun editAction(context: Context, id: Long): NotificationCompat.Action {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_EDIT
            putExtra(EXTRA_REMINDER_ID, id)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return NotificationCompat.Action.Builder(0, "Edit time", PendingIntent.getActivity(context, (id + ACTION_EDIT.hashCode()).toInt(), intent, pendingFlags())).build()
    }

    private fun pendingFlags() = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    private fun formatTime(epochMillis: Long): String = DateTimeFormatter.ofPattern("EEE, d MMM 'at' h:mm a", Locale.getDefault())
        .format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))
}
