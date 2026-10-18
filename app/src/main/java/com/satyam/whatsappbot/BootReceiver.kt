package com.satyam.whatsappbot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.satyam.whatsappbot.data.ReminderRepository

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        ReminderRepository(context).scheduleAll { reminder ->
            ReminderNotifications.schedule(context, reminder)
        }
    }
}