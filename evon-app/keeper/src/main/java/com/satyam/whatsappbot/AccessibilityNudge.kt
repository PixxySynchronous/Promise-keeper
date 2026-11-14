package com.satyam.whatsappbot

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Once a day, if Promise Keeper's accessibility service is off (turned off for a bank app, by Android, or never
 * turned on), a notification asks the user to switch it back on. One inexact, non-waking alarm a day at the default
 * reminder time, so it costs almost no battery; when the service is on, the check does nothing.
 */
object AccessibilityNudge {
    private const val CHANNEL = "status"
    private const val NOTIFICATION_ID = 7
    const val ACTION_OPEN_SETUP = "com.satyam.whatsappbot.action.OPEN_SETUP"

    /** Safe to call often: it replaces the same daily alarm. */
    fun schedule(context: Context) {
        val zone = ZoneId.systemDefault()
        val now = LocalDateTime.now(zone)
        val today = now.toLocalDate().atTime(ReminderSettings.defaultTime(context))
        val first = if (today.isAfter(now)) today else today.plusDays(1)
        context.getSystemService(AlarmManager::class.java).setInexactRepeating(
            AlarmManager.RTC, first.atZone(zone).toInstant().toEpochMilli(), AlarmManager.INTERVAL_DAY,
            PendingIntent.getBroadcast(context, 0, Intent(context, AccessibilityNudgeReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        )
    }

    /**
     * Called when the service stops. Checks once, about 2 minutes later, and notifies if it's still off: an app update
     * also stops the service briefly before Android restarts it, and that shouldn't cause a "switched off" alert.
     * A separate request code keeps it from replacing the daily alarm.
     */
    fun checkSoon(context: Context) {
        context.getSystemService(AlarmManager::class.java).set(
            AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 2 * 60 * 1000L,
            PendingIntent.getBroadcast(context, 1, Intent(context, AccessibilityNudgeReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        )
    }

    fun check(context: Context) {
        if (isAccessibilityOn(context)) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Promise Keeper switched off", NotificationManager.IMPORTANCE_DEFAULT))
        // Tapping opens setup step 2, which also explains the greyed-out "Restricted setting" fix.
        val openSetup = PendingIntent.getActivity(
            context, 1,
            Intent(context, MainActivity::class.java).setAction(ACTION_OPEN_SETUP).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openAccessibility = PendingIntent.getActivity(
            context, 2, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = "It can't notice your promises in WhatsApp until you turn it back on in Accessibility. Your reminders still ring."
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("Promise Keeper is switched off")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openSetup)
            .setAutoCancel(true)
            .addAction(NotificationCompat.Action.Builder(0, "Turn on", openAccessibility).build())
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    /** Called when the service connects, so a nudge from earlier doesn't linger. */
    fun clear(context: Context) = NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
}

class AccessibilityNudgeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = AccessibilityNudge.check(context)
}
