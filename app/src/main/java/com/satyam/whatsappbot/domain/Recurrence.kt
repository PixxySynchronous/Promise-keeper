package com.satyam.whatsappbot.domain

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * How a repeating reminder repeats, including its time of day. Stored on the reminder as a short string ([encode]);
 * the reminder's own time is always the next occurrence, worked out with [next].
 */
data class Recurrence(
    val frequency: Frequency,
    val time: LocalTime,
    /** WEEKLY only. */
    val weekdays: Set<DayOfWeek> = emptySet(),
    /** MONTHLY and YEARLY. Days past the end of a month ring on its last day (31 -> 30 Nov, 28/29 Feb). */
    val dayOfMonth: Int = 1,
    /** YEARLY only, 1-12. */
    val month: Int = 1
) {
    enum class Frequency { DAILY, WEEKLY, MONTHLY, YEARLY }

    /** The first occurrence strictly after [after]. */
    fun next(after: LocalDateTime): LocalDateTime {
        val start = after.toLocalDate()
        return when (frequency) {
            Frequency.DAILY -> (0L..1L).map { start.plusDays(it).atTime(time) }.first { it.isAfter(after) }
            Frequency.WEEKLY -> (0L..7L).map { start.plusDays(it) }
                .filter { it.dayOfWeek in weekdays }
                .map { it.atTime(time) }
                .first { it.isAfter(after) }
            Frequency.MONTHLY -> (0L..2L).map { YearMonth.from(start).plusMonths(it) }
                .map { it.atDay(minOf(dayOfMonth, it.lengthOfMonth())).atTime(time) }
                .first { it.isAfter(after) }
            Frequency.YEARLY -> (0..8).map { YearMonth.of(start.year + it, month) }
                .map { it.atDay(minOf(dayOfMonth, it.lengthOfMonth())).atTime(time) }
                .first { it.isAfter(after) }
        }
    }

    // "DAILY@09:30", "WEEKLY:MON,THU@10:00", "MONTHLY:15@09:30", "YEARLY:12-25@10:00".
    fun encode(): String {
        val rule = when (frequency) {
            Frequency.DAILY -> "DAILY"
            Frequency.WEEKLY -> "WEEKLY:" + weekdays.sorted().joinToString(",") { it.name.take(3) }
            Frequency.MONTHLY -> "MONTHLY:$dayOfMonth"
            Frequency.YEARLY -> "YEARLY:$month-$dayOfMonth"
        }
        return "$rule@$time"
    }

    /** "Every day at 9:30 AM", "Every Mon and Thu at 10:00 AM", "Every month on the 15th at 9:30 AM". */
    fun describe(locale: Locale = Locale.getDefault()): String {
        val at = "at " + time.format(DateTimeFormatter.ofPattern("h:mm a", locale))
        return when (frequency) {
            Frequency.DAILY -> "Every day $at"
            Frequency.WEEKLY -> when (weekdays) {
                WEEKDAYS -> "Every weekday $at"
                WEEKEND -> "Every weekend $at"
                DayOfWeek.entries.toSet() -> "Every day $at"
                else -> "Every " + joinWithAnd(weekdays.sorted().map { it.getDisplayName(if (weekdays.size == 1) TextStyle.FULL else TextStyle.SHORT, locale) }) + " $at"
            }
            Frequency.MONTHLY -> "Every month on the ${ordinal(dayOfMonth)} $at"
            Frequency.YEARLY -> "Every year on $dayOfMonth ${java.time.Month.of(month).getDisplayName(TextStyle.SHORT, locale)} $at"
        }
    }

    companion object {
        val WEEKDAYS = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
        val WEEKEND = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)

        /** Null for one-time reminders (an empty string) or anything unreadable. */
        fun decode(value: String): Recurrence? = runCatching {
            if (value.isBlank()) return null
            val (rule, timeText) = value.split("@", limit = 2)
            val time = LocalTime.parse(timeText)
            val kind = rule.substringBefore(":")
            val arg = rule.substringAfter(":", "")
            when (kind) {
                "DAILY" -> Recurrence(Frequency.DAILY, time)
                "WEEKLY" -> Recurrence(
                    Frequency.WEEKLY, time,
                    weekdays = arg.split(",").map { short -> DayOfWeek.entries.first { it.name.startsWith(short) } }.toSet()
                )
                "MONTHLY" -> Recurrence(Frequency.MONTHLY, time, dayOfMonth = arg.toInt())
                "YEARLY" -> Recurrence(Frequency.YEARLY, time, month = arg.substringBefore("-").toInt(), dayOfMonth = arg.substringAfter("-").toInt())
                else -> null
            }
        }.getOrNull()

        private fun joinWithAnd(items: List<String>) =
            if (items.size <= 1) items.joinToString() else items.dropLast(1).joinToString(", ") + " and " + items.last()

        private fun ordinal(day: Int) = day.toString() + when {
            day in 11..13 -> "th"
            day % 10 == 1 -> "st"
            day % 10 == 2 -> "nd"
            day % 10 == 3 -> "rd"
            else -> "th"
        }
    }
}
