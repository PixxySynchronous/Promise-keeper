package com.satyam.whatsappbot

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * About once a month, a local notification suggests checking for a new version. The app can't check by itself (it
 * has no internet access), so the notification opens the GitHub releases page in the browser. No data is sent or
 * collected. The next reminder's time is stored, so opening the app doesn't keep pushing it back. Off switch in
 * Settings.
 */
object UpdateReminder {
    private const val PREFS = "update_reminder"
    private const val KEY_ON = "on"
    private const val KEY_NEXT = "next_at"
    private const val CHANNEL = "updates"
    private const val NOTIFICATION_ID = 8
    private const val INTERVAL_MS = 30L * 24 * 60 * 60 * 1000
    // TESTING ONLY: the reminder fires about a minute after opening the app. Must be false in any release.
    private const val TEST_REMIND_SOON = false

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isOn(context: Context) = prefs(context).getBoolean(KEY_ON, true)

    fun setOn(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_ON, on).apply()
        if (on) schedule(context) else alarmManager(context).cancel(pendingIntent(context))
    }

    /** Safe to call on every app open and after a restart: keeps the stored time, starting it 30 days out if unset. */
    fun schedule(context: Context) {
        if (!isOn(context)) return
        val now = System.currentTimeMillis()
        if (TEST_REMIND_SOON) prefs(context).edit().putLong(KEY_NEXT, now + 60_000L).apply()
        val next = prefs(context).getLong(KEY_NEXT, 0L).takeIf { it > 0 } ?: (now + INTERVAL_MS).also { prefs(context).edit().putLong(KEY_NEXT, it).apply() }
        // A time that passed while the phone was off rings shortly after.
        alarmManager(context).set(AlarmManager.RTC, maxOf(next, now + 60_000L), pendingIntent(context))
    }

    fun remind(context: Context) {
        prefs(context).edit().putLong(KEY_NEXT, System.currentTimeMillis() + INTERVAL_MS).apply()
        schedule(context)
        if (!isOn(context)) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "New version reminders", NotificationManager.IMPORTANCE_LOW))
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
        val open = PendingIntent.getActivity(
            context, 4, Intent(Intent.ACTION_VIEW, Uri.parse(WhatsNew.RELEASES_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = "You're on version $version. New versions come out from time to time with fixes and features. Tap to see the latest."
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Check for a new Promise Keeper version")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(NotificationCompat.Action.Builder(0, "See releases", open).build())
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    private fun alarmManager(context: Context) = context.getSystemService(AlarmManager::class.java)

    private fun pendingIntent(context: Context) =
        PendingIntent.getBroadcast(context, 0, Intent(context, UpdateReminderReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}

class UpdateReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = UpdateReminder.remind(context)
}
