package com.satyam.whatsappbot

import android.content.Context
import java.time.LocalTime

/** User preferences, stored in app-private SharedPreferences. */
object ReminderSettings {
    private const val PREFS = "settings"
    private const val KEY_DEFAULT_MINUTE_OF_DAY = "default_minute_of_day"
    val DEFAULT_TIME: LocalTime = LocalTime.of(10, 0)

    /** Time used when a message names a day but no time ("kal bhej dunga", "by fri"). */
    fun defaultTime(context: Context): LocalTime {
        val minuteOfDay = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_DEFAULT_MINUTE_OF_DAY, DEFAULT_TIME.hour * 60 + DEFAULT_TIME.minute)
        return LocalTime.of(minuteOfDay / 60, minuteOfDay % 60)
    }

    fun setDefaultTime(context: Context, time: LocalTime) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_DEFAULT_MINUTE_OF_DAY, time.hour * 60 + time.minute)
            .apply()
    }
}
