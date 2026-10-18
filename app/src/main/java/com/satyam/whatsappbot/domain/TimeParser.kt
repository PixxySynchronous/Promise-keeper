package com.satyam.whatsappbot.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters
import java.util.Locale

data class DefaultTimes(
    val tomorrow: LocalTime = LocalTime.of(10, 0),
    val eod: LocalTime = LocalTime.of(18, 0),
    val tonight: LocalTime = LocalTime.of(21, 0),
    val morning: LocalTime = LocalTime.of(9, 30),
    val evening: LocalTime = LocalTime.of(18, 0),
    val nextWeek: LocalTime = LocalTime.of(10, 0),
    val noTimeDelayHours: Long = 2
)

data class ParsedTime(
    val dateTime: LocalDateTime,
    val matchedText: String,
    val usedDefault: Boolean
)

class TimeParser(private val defaults: DefaultTimes = DefaultTimes()) {
    fun parse(message: String, now: LocalDateTime): ParsedTime? {
        val text = message.lowercase(Locale.ROOT).replace("’", "'").trim()
        parseRelativeDuration(text, now)?.let { return it }
        parseExplicitDate(text, now)?.let { return it }
        parseWeekday(text, now)?.let { return it }
        parseClock(text, now)?.let { return it }
        parseNamedDefault(text, now)?.let { return it }
        return null
    }

    fun parseOrDefault(message: String, now: LocalDateTime): ParsedTime =
        parse(message, now) ?: ParsedTime(
            now.plusHours(defaults.noTimeDelayHours),
            "no explicit time",
            usedDefault = true
        )

    private fun parseRelativeDuration(text: String, now: LocalDateTime): ParsedTime? {
        val match = Regex("\\b(?:in|after)\\s+(\\d+)\\s*(m|mins?|minutes?|h|hrs?|hours?)\\b").find(text)
            ?: Regex("\\b(\\d+)\\s*(m|mins?|minutes?|h|hrs?|hours?)\\s*(?:mein|me|ma)\\b").find(text)
            ?: return null
        val amount = (match.groupValues[1].ifEmpty { match.groupValues[2] }).toLong()
        val unit = if (match.groupValues[1].isNotEmpty()) match.groupValues[2] else match.groupValues[3]
        val target = if (unit == "m" || unit.startsWith("min")) now.plusMinutes(amount) else now.plusHours(amount)
        return ParsedTime(target, match.value, usedDefault = false)
    }

    private fun parseExplicitDate(text: String, now: LocalDateTime): ParsedTime? {
        val match = Regex("\\b(\\d{1,2})[/-](\\d{1,2})(?:[/-](\\d{2,4}))?\\b").find(text) ?: return null
        val day = match.groupValues[1].toInt()
        val month = match.groupValues[2].toInt()
        val year = match.groupValues[3].takeIf { it.isNotEmpty() }?.let { normalizeYear(it) } ?: now.year
        val date = runCatching { LocalDate.of(year, month, day) }.getOrNull() ?: return null
        val futureDate = if (date.isBefore(now.toLocalDate())) date.plusYears(1) else date
        val withoutDate = text.replaceRange(match.range, " ")
        val clock = extractClock(withoutDate)
        val time = clock?.first ?: LocalTime.of(10, 0)
        return ParsedTime(LocalDateTime.of(futureDate, time), match.value, clock == null)
    }

    private fun parseWeekday(text: String, now: LocalDateTime): ParsedTime? {
        val names = mapOf(
            "monday" to DayOfWeek.MONDAY, "tuesday" to DayOfWeek.TUESDAY, "wednesday" to DayOfWeek.WEDNESDAY,
            "thursday" to DayOfWeek.THURSDAY, "friday" to DayOfWeek.FRIDAY, "saturday" to DayOfWeek.SATURDAY,
            "sunday" to DayOfWeek.SUNDAY
        )
        val match = Regex("\\b(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b").find(text) ?: return null
        val day = names.getValue(match.groupValues[1])
        val date = now.toLocalDate().with(TemporalAdjusters.nextOrSame(day)).let {
            if (it == now.toLocalDate() && !text.contains("today")) it.plusWeeks(1) else it
        }
        val clock = extractClock(text)
        return ParsedTime(LocalDateTime.of(date, clock?.first ?: LocalTime.of(10, 0)), match.value, clock == null)
    }

    private fun parseClock(text: String, now: LocalDateTime): ParsedTime? {
        val rawClock = extractClock(text) ?: return null
        val clock = adjustAmbiguousClock(rawClock, now)
        val date = when {
            Regex("\\b(tomorrow|kal)\\b").containsMatchIn(text) -> now.toLocalDate().plusDays(1)
            Regex("\\b(today|aaj)\\b").containsMatchIn(text) -> now.toLocalDate()
            else -> now.toLocalDate()
        }
        val adjustedDate = if (date == now.toLocalDate() && clock.first.isBefore(now.toLocalTime())) date.plusDays(1) else date
        return ParsedTime(LocalDateTime.of(adjustedDate, clock.first), clock.second, usedDefault = false)
    }

    private fun adjustAmbiguousClock(clock: Pair<LocalTime, String>, now: LocalDateTime): Pair<LocalTime, String> {
        val hourText = Regex("\\d{1,2}").find(clock.second)?.value ?: return clock
        val hasMeridiem = Regex("(?i)\\b(am|pm)\\b").containsMatchIn(clock.second)
        val hour = hourText.toInt()
        return if (!hasMeridiem && hour in 1..11 && hour < now.hour) {
            clock.copy(first = clock.first.plusHours(12))
        } else {
            clock
        }
    }

    private fun parseNamedDefault(text: String, now: LocalDateTime): ParsedTime? {
        val tomorrow = Regex("\\b(tomorrow|kal)\\b").find(text)
        if (tomorrow != null && isFutureKal(text)) return ParsedTime(LocalDateTime.of(now.toLocalDate().plusDays(1), defaults.tomorrow), tomorrow.value, true)
        // Checked before "today" so "aaj raat" is not read as plain "aaj".
        val tonight = Regex("\\b(tonight|aaj raat)\\b").find(text)
        if (tonight != null) return ParsedTime(todayOrTomorrow(now, defaults.tonight), tonight.value, true)
        val eod = Regex("\\b(eod|end of day)\\b").find(text)
        if (eod != null) return ParsedTime(todayOrTomorrow(now, defaults.eod), eod.value, true)
        val morning = Regex("\\b(morning|subah)\\b").find(text)
        if (morning != null) return ParsedTime(todayOrTomorrow(now, defaults.morning), morning.value, true)
        val evening = Regex("\\b(evening|shaam)\\b").find(text)
        if (evening != null) return ParsedTime(todayOrTomorrow(now, defaults.evening), evening.value, true)
        val today = Regex("\\b(today|aaj)\\b").find(text)
        if (today != null) return ParsedTime(laterToday(now), today.value, true)
        if (Regex("\\bnext week\\b").containsMatchIn(text)) {
            val nextMonday = now.toLocalDate().plusWeeks(1).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY))
            return ParsedTime(LocalDateTime.of(nextMonday, defaults.nextWeek), "next week", true)
        }
        return null
    }

    private fun isFutureKal(text: String): Boolean = !Regex("\\b(kya hua|hua tha|tha|thi|the)\\b").containsMatchIn(text)

    private fun todayOrTomorrow(now: LocalDateTime, time: LocalTime): LocalDateTime {
        val today = LocalDateTime.of(now.toLocalDate(), time)
        return if (today.isAfter(now)) today else today.plusDays(1)
    }

    // "today" with no time: the morning default if still ahead, else end of day, else the no-time delay.
    private fun laterToday(now: LocalDateTime): LocalDateTime =
        listOf(defaults.morning, defaults.eod)
            .map { LocalDateTime.of(now.toLocalDate(), it) }
            .firstOrNull { it.isAfter(now) }
            ?: now.plusHours(defaults.noTimeDelayHours)

    private fun extractClock(text: String): Pair<LocalTime, String>? {
        // A bare number ("send 2 files") is not a time; require by/at, a colon, am/pm or baje.
        return Regex("\\b(by\\s+|at\\s+)?(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm|baje)?\\b").findAll(text).firstNotNullOfOrNull { match ->
            val prefix = match.groupValues[1]
            val hour = match.groupValues[2].toInt()
            val minuteText = match.groupValues[3]
            val minute = minuteText.ifEmpty { "0" }.toInt()
            val meridiem = match.groupValues[4]
            if (prefix.isEmpty() && minuteText.isEmpty() && meridiem.isEmpty()) return@firstNotNullOfOrNull null
            if (hour !in 0..23 || minute !in 0..59 || (meridiem in setOf("am", "pm") && hour !in 1..12)) return@firstNotNullOfOrNull null
            val adjustedHour = when (meridiem) {
                "pm" -> if (hour == 12) 12 else hour + 12
                "am" -> if (hour == 12) 0 else hour
                else -> hour
            }
            LocalTime.of(adjustedHour, minute) to match.value
        }
    }

    private fun normalizeYear(value: String): Int = if (value.length == 2) 2000 + value.toInt() else value.toInt()
}