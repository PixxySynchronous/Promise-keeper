package com.satyam.whatsappbot.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters
import java.util.Locale

data class DefaultTimes(
    /** Used when a message names a day but no time: "kal bhej dunga", "by fri", "next week", "21/10". User-adjustable. */
    val day: LocalTime = LocalTime.of(10, 0),
    val eod: LocalTime = LocalTime.of(18, 0),
    val tonight: LocalTime = LocalTime.of(21, 0),
    val morning: LocalTime = LocalTime.of(9, 30),
    val evening: LocalTime = LocalTime.of(18, 0),
    val afternoon: LocalTime = LocalTime.of(14, 0),
    val noTimeDelayHours: Long = 2
)

data class ParsedTime(
    val dateTime: LocalDateTime,
    val matchedText: String,
    val usedDefault: Boolean
)

class TimeParser(private val defaults: DefaultTimes = DefaultTimes()) {
    fun parse(message: String, now: LocalDateTime): ParsedTime? {
        val text = canonicalDayWords(message)
        parseRelativeDuration(text, now)?.let { return it }
        parseRelativeDays(text, now)?.let { return it }
        parseExplicitDate(text, now)?.let { return it }
        parseLongerRange(text, now)?.let { return it }
        parseNextWeek(text, now)?.let { return it }
        parseWeekday(text, now)?.let { return it }
        parseClock(text, now)?.let { return it }
        parseNamedDefault(text, now)?.let { return it }
        return null
    }

    /**
     * Lowercases and rewrites day words to one spelling each: "tom"/"tmrw"/"2moro"/"tommorow" -> "tomorrow",
     * "kl"/"कल" -> "kal", "day after tomorrow"/"परसों" -> "parso", "आज" -> "aaj".
     * "tom" only counts in lowercase, because "Tom" is usually a name.
     */
    private fun canonicalDayWords(message: String): String {
        val withTom = message.replace(Regex("\\btom\\b"), "tomorrow")
        return canonicalTimeWords(withTom.lowercase(Locale.ROOT).replace("’", "'").trim())
            .replace(TOMORROW_VARIANTS, "tomorrow")
            .replace(Regex("\\bday after (tomorrow|kal)\\b"), "parso")
            .replace(Regex("\\b(parson|parsoon)\\b"), "parso")
            .replace(Regex("\\bkl\\b"), "kal")
            .replace(devanagariWord("कल"), "kal")
            .replace(devanagariWord("परसों"), "parso")
            .replace(devanagariWord("आज"), "aaj")
    }

    /**
     * Rewrites how a clock time is typed into one form, so the parser only handles one spelling: spacing, dots and
     * short forms after a number don't matter. "1 o clock" / "1o'clock" / "1 o-clk" -> "1 oclock"; "1 a.m." / "1 p m" /
     * "1AM" -> "1 am" / "1 pm"; "5 bje" / "5 bajey" / "5 बजे" -> "5 baje"; "8.30pm" -> "8:30 pm". Expects lowercase.
     */
    private fun canonicalTimeWords(text: String): String = text
        .replace(Regex("\\b(\\d{1,2})[.](\\d{2})(?=\\s*(?:[ap]\\s*\\.?\\s*m\\b|baje|bje|baj|o\\b|o'|o-|oc))"), "$1:$2")
        .replace(Regex("\\b(\\d{1,2}(?::\\d{2})?)\\s*o\\s*['-]?\\s*(?:clock|clk|klock|klok)\\b"), "$1 oclock")
        .replace(Regex("\\b(\\d{1,2}(?::\\d{2})?)\\s*([ap])\\s*\\.?\\s*m\\b\\.?"), "$1 $2m")
        .replace(Regex("\\b(\\d{1,2}(?::\\d{2})?)\\s*(?:baje|bje|baj|bajey|bajay|baaje|bajje)\\b"), "$1 baje")
        .replace(Regex("\\b(\\d{1,2}(?::\\d{2})?)\\s*बजे"), "$1 baje")

    /** 1 for tomorrow/kal (unless kal is about the past), 2 for parso, 0 for today/aaj, null if no day word. */
    private fun dayOffset(text: String): Long? = when {
        Regex("\\bparso\\b").containsMatchIn(text) -> 2
        Regex("\\btomorrow\\b").containsMatchIn(text) -> 1
        Regex("\\bkal\\b").containsMatchIn(text) && isFutureKal(text) -> 1
        Regex("\\b(today|aaj)\\b").containsMatchIn(text) -> 0
        else -> null
    }

    /**
     * A repeating reminder in the message, or null: "every monday", "har somvar", "mondays", "every mon and thu",
     * "weekdays", "every day", "roz subah", "every week", "every month on the 15th", "every year on 25/12". Weekly with
     * no day named, and monthly or yearly with no date, repeat from today.
     */
    fun parseRecurrence(message: String, now: LocalDateTime): Recurrence? {
        val text = canonicalDayWords(message)
        val time = timeOfDay(text)
        val today = now.toLocalDate()
        if (YEARLY.containsMatchIn(text) || YEARLY_DEVANAGARI.any { text.contains(it) }) {
            val (month, day) = yearlyDate(text) ?: (today.monthValue to today.dayOfMonth)
            return Recurrence(Recurrence.Frequency.YEARLY, time, month = month, dayOfMonth = day)
        }
        if (MONTHLY.containsMatchIn(text) || MONTHLY_DEVANAGARI.any { text.contains(it) }) {
            return Recurrence(Recurrence.Frequency.MONTHLY, time, dayOfMonth = monthlyDay(text) ?: today.dayOfMonth)
        }
        val days = repeatingWeekdays(text)
        return when {
            days.isNotEmpty() -> Recurrence(Recurrence.Frequency.WEEKLY, time, weekdays = days)
            // "weekly on monday", "har hafte somvar ko": the day named, else today's.
            WEEKLY.containsMatchIn(text) || WEEKLY_DEVANAGARI.any { text.contains(it) } ->
                Recurrence(Recurrence.Frequency.WEEKLY, time, weekdays = setOf(findWeekday(text)?.first ?: today.dayOfWeek))
            DAILY.containsMatchIn(text) || DAILY_DEVANAGARI.any { text.contains(it) } -> Recurrence(Recurrence.Frequency.DAILY, time)
            else -> repeatsByNature(message, text, time, now)
        }
    }

    /**
     * Reminders that repeat without saying "every". A birthday or anniversary is yearly, on the date it names
     * ("piyush birthday 28 may") or the day it names ("mom's birthday kal hai"); without any date or day it stays
     * one-time, since the date is unknown. Rent, EMI, salary, fees and bills are monthly when a day of the month is
     * named ("pay rent on the 1st"); "pay rent tomorrow" stays one-time.
     */
    private fun repeatsByNature(message: String, text: String, time: LocalTime, now: LocalDateTime): Recurrence? {
        val yearly = (YEARLY_BY_NATURE.containsMatchIn(text) || YEARLY_BY_NATURE_DEVANAGARI.any { text.contains(it) }) && !ONE_OFF_TASK.containsMatchIn(text)
        if (yearly) {
            val date = findDate(text)?.let { it.month to it.day }
                ?: parse(message, now)?.dateTime?.toLocalDate()?.let { it.monthValue to it.dayOfMonth }
                ?: return null
            return Recurrence(Recurrence.Frequency.YEARLY, time, month = date.first, dayOfMonth = date.second)
        }
        if (MONTHLY_BY_NATURE.containsMatchIn(text)) {
            return monthlyDay(text)?.let { Recurrence(Recurrence.Frequency.MONTHLY, time, dayOfMonth = it) }
        }
        return null
    }

    /**
     * The time of day for a repeating reminder. A clock time without am/pm follows the part of the day named with it
     * ("subah 7 baje" is 7 AM, "shaam 7 baje" 7 PM), else the future-day rule. No clock: the part of the day's default,
     * else the default day time.
     */
    private fun timeOfDay(text: String): LocalTime {
        val morning = Regex("\\b(morning|subah|subha)\\b").containsMatchIn(text) || text.contains("सुबह")
        val later = Regex("\\b(evening|shaam|sham|afternoon|dopahar|dopeher|dophar|dupahar|dopher|night|tonight|nightly|raat)\\b").containsMatchIn(text) ||
            listOf("शाम", "दोपहर", "रात").any { text.contains(it) }
        val clock = extractClock(text)
        if (clock != null) {
            val time = clock.first
            return when {
                hasMeridiem(clock) || time.hour == 0 || time.hour >= 12 || morning -> time
                // "raat 1 baje" is after midnight, "raat 10 baje" is 10 PM.
                later -> if (time.hour < 5) time else time.plusHours(12)
                else -> futureDayTime(clock)
            }
        }
        return when {
            Regex("\\b(night|tonight|nightly|raat)\\b").containsMatchIn(text) || text.contains("रात") -> defaults.tonight
            morning -> defaults.morning
            Regex("\\b(evening|shaam|sham)\\b").containsMatchIn(text) || text.contains("शाम") -> defaults.evening
            later -> defaults.afternoon
            else -> defaults.day
        }
    }

    private fun repeatingWeekdays(text: String): Set<DayOfWeek> {
        if (EVERY_WEEKDAY.containsMatchIn(text)) return Recurrence.WEEKDAYS
        if (EVERY_WEEKEND.containsMatchIn(text)) return Recurrence.WEEKEND
        val days = mutableSetOf<DayOfWeek>()
        EVERY_DAY_LIST.findAll(text).forEach { list -> DAY_TOKEN.findAll(list.groupValues[2]).forEach { days += ANY_WEEKDAY.getValue(it.value) } }
        PLURAL_WEEKDAY.findAll(text).forEach { days += WEEKDAYS.getValue(it.groupValues[1]) }
        DEVANAGARI_WEEKDAYS.forEach { (name, day) -> if (text.contains("हर $name")) days += day }
        return days
    }

    // "on the 15th", "15 tareekh", "1st of every month", or the day of a date like 15/11 or 15 nov.
    private fun monthlyDay(text: String): Int? {
        findDate(text)?.let { return it.day }
        val match = Regex("\\b(\\d{1,2})(?:st|nd|rd|th)?\\s*(?:tareekh|tarikh|tarik|date)\\b").find(text)
            ?: Regex("\\bon\\s+(?:the\\s+)?(\\d{1,2})(?:st|nd|rd|th)?\\b").find(text)
            ?: Regex("\\b(\\d{1,2})(?:st|nd|rd|th)\\b").find(text)
            ?: return null
        return match.groupValues[1].toInt().takeIf { it in 1..31 }
    }

    // (month, day) of the date in the text.
    private fun yearlyDate(text: String): Pair<Int, Int>? = findDate(text)?.let { it.month to it.day }

    fun parseOrDefault(message: String, now: LocalDateTime): ParsedTime =
        parse(message, now) ?: ParsedTime(laterOrNextMorning(now), "no explicit time", usedDefault = true)

    /**
     * The no-time fallback: a couple of hours from now, unless that lands between 10 PM and 8 AM, in which case the
     * user's default time the next morning ("ill do it" sent at 11:45 PM should not ring at 1:45 AM).
     */
    private fun laterOrNextMorning(now: LocalDateTime): LocalDateTime {
        val later = now.plusHours(defaults.noTimeDelayHours)
        return when {
            later.hour >= 22 -> LocalDateTime.of(later.toLocalDate().plusDays(1), defaults.day)
            later.hour < 8 -> LocalDateTime.of(later.toLocalDate(), defaults.day)
            else -> later
        }
    }

    // "in 10 mins", "after an hour", "do ghante mein", "half an hour", "aadha ghanta".
    private fun parseRelativeDuration(text: String, now: LocalDateTime): ParsedTime? {
        HALF_HOUR.find(text)?.let { return ParsedTime(now.plusMinutes(30), it.value, usedDefault = false) }
        val match = Regex("\\b(?:in|after|within)\\s+$NUMBER\\s*$SHORT_UNIT\\b").find(text)
            // No "a"/"an" here: "9 am me" must not read as "a minute".
            ?: Regex("\\b$NUMBER_NO_ARTICLE\\s*$SHORT_UNIT\\s*(?:mein|me|mai|mei|ma|baad|bad)\\b").find(text)
            ?: return null
        val amount = numberValue(match.groupValues[1]) ?: return null
        val unit = match.groupValues[2]
        val target = if (unit == "m" || unit.startsWith("min")) now.plusMinutes(amount) else now.plusHours(amount)
        return ParsedTime(target, match.value, usedDefault = false)
    }

    // "in 3 days", "after 2 weeks", "in a month", "3 din mein", "ek hafte mein", "do mahine baad", at the default time.
    private fun parseRelativeDays(text: String, now: LocalDateTime): ParsedTime? {
        val match = Regex("\\b(?:in|after|within)\\s+(?:the next\\s+)?$NUMBER\\s+$DAY_UNIT\\b").find(text)
            ?: Regex("\\b$NUMBER\\s+$DAY_UNIT\\s+(?:mein|me|mai|mei|ma|baad|bad|tak|tk)\\b").find(text)
            ?: return null
        val amount = numberValue(match.groupValues[1]) ?: return null
        val unit = match.groupValues[2]
        val date = when {
            unit.startsWith("week") || unit.startsWith("wk") || unit.startsWith("haft") -> now.toLocalDate().plusWeeks(amount)
            unit.startsWith("month") || unit.startsWith("mahin") -> now.toLocalDate().plusMonths(amount)
            else -> now.toLocalDate().plusDays(amount)
        }
        val clock = extractClock(text.replaceRange(match.range, " "))
        return ParsedTime(LocalDateTime.of(date, clock?.let(::futureDayTime) ?: defaults.day), match.value, clock == null)
    }

    private fun numberValue(word: String): Long? = word.toLongOrNull() ?: NUMBER_WORDS[word]

    private fun parseExplicitDate(text: String, now: LocalDateTime): ParsedTime? {
        val mention = findDate(text) ?: return null
        val date = LocalDate.of(mention.year ?: now.year, mention.month, mention.day)
        // Without a year, a date already past this year means next year ("28 may" said in October).
        val futureDate = if (mention.year == null && date.isBefore(now.toLocalDate())) date.plusYears(1) else date
        val clock = extractClock(text.replaceRange(mention.range, " "))
        val time = clock?.let(::futureDayTime) ?: defaults.day
        return ParsedTime(LocalDateTime.of(futureDate, time), text.substring(mention.range), clock == null)
    }

    private data class DateMention(val day: Int, val month: Int, val year: Int?, val range: IntRange)

    /**
     * The first valid calendar date in the text, written as numbers (day first: "28/5", "28-05-2027") or with a month
     * name ("28 may", "28th of may", "may 28", "may 28, 2027", "28 dec 2026"). One date finder for one-time and
     * repeating reminders, so both understand the same dates.
     */
    private fun findDate(text: String): DateMention? {
        val numeric = Regex("\\b(\\d{1,2})[/-](\\d{1,2})(?:[/-](\\d{2,4}))?\\b").findAll(text).map {
            DateMention(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].ifEmpty { null }?.let(::normalizeYear), it.range)
        }
        val dayMonth = Regex("\\b(\\d{1,2})(?:st|nd|rd|th)?\\s+(?:of\\s+)?$MONTH_NAME(?:,?\\s+(\\d{4}))?\\b").findAll(text).map {
            DateMention(it.groupValues[1].toInt(), MONTHS.getValue(it.groupValues[2]), it.groupValues[3].ifEmpty { null }?.toInt(), it.range)
        }
        val monthDay = Regex("\\b$MONTH_NAME\\s+(\\d{1,2})(?:st|nd|rd|th)?(?:,?\\s+(\\d{4}))?\\b").findAll(text).map {
            DateMention(it.groupValues[2].toInt(), MONTHS.getValue(it.groupValues[1]), it.groupValues[3].ifEmpty { null }?.toInt(), it.range)
        }
        // 29 Feb without a year is valid (2024 is a leap year).
        return (numeric + dayMonth + monthDay)
            .filter { runCatching { LocalDate.of(it.year ?: 2024, it.month, it.day) }.isSuccess }
            .minByOrNull { it.range.first }
    }

    /**
     * "next week" means the same weekday next week (today + 7 days); with a weekday ("monday next week") it is that
     * day in the week starting next Monday. A clock time in the message is kept; otherwise the next-week default.
     */
    private fun parseNextWeek(text: String, now: LocalDateTime): ParsedTime? {
        val marker = NEXT_WEEK.find(text)?.value ?: NEXT_WEEK_DEVANAGARI.firstOrNull { text.contains(it) } ?: return null
        val weekday = findWeekday(text)?.first
        val date = if (weekday == null) {
            now.toLocalDate().plusWeeks(1)
        } else {
            now.toLocalDate().with(TemporalAdjusters.next(DayOfWeek.MONDAY)).with(TemporalAdjusters.nextOrSame(weekday))
        }
        val clock = extractClock(text.replace(marker, " "))
        return ParsedTime(LocalDateTime.of(date, clock?.let(::futureDayTime) ?: defaults.day), marker, usedDefault = clock == null)
    }

    /**
     * "next month" / "agle mahine" (same date next month), "next year" / "agle saal", "this weekend" (coming Saturday),
     * "next weekend" (the Saturday after), "end of the month" (last day). A time in the message is kept.
     */
    private fun parseLongerRange(text: String, now: LocalDateTime): ParsedTime? {
        val today = now.toLocalDate()
        val (date, matched) = when {
            NEXT_MONTH.containsMatchIn(text) || NEXT_MONTH_DEVANAGARI.any { text.contains(it) } -> today.plusMonths(1) to "next month"
            NEXT_YEAR.containsMatchIn(text) || NEXT_YEAR_DEVANAGARI.any { text.contains(it) } -> today.plusYears(1) to "next year"
            MONTH_END.containsMatchIn(text) -> today.with(TemporalAdjusters.lastDayOfMonth()) to "end of month"
            NEXT_WEEKEND.containsMatchIn(text) -> today.with(TemporalAdjusters.next(DayOfWeek.SATURDAY)).plusWeeks(1) to "next weekend"
            WEEKEND.containsMatchIn(text) -> today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY)) to "weekend"
            else -> return null
        }
        val clock = extractClock(text)
        val dateTime = LocalDateTime.of(date, clock?.let(::futureDayTime) ?: defaults.day)
        // "month end" said on the last day after the default time: fall back rather than set a past reminder.
        if (!dateTime.isAfter(now)) return ParsedTime(laterOrNextMorning(now), matched, usedDefault = true)
        return ParsedTime(dateTime, matched, clock == null)
    }

    // On another day, "by 5" with no am/pm almost always means the afternoon or evening: read 1-7 as PM.
    private fun futureDayTime(clock: Pair<LocalTime, String>): LocalTime =
        if (!hasMeridiem(clock) && clock.first.hour in 1..7) clock.first.plusHours(12) else clock.first

    // Also when typed without a space ("8:30pm"), which a word-boundary check would miss.
    private fun hasMeridiem(clock: Pair<LocalTime, String>) = Regex("\\d\\s*(am|pm)$").containsMatchIn(clock.second)

    private fun parseWeekday(text: String, now: LocalDateTime): ParsedTime? {
        val (day, matched) = findWeekday(text) ?: return null
        // The coming occurrence; on that same day it means next week, unless the message says "today" or "this <day>".
        val sameDayMeansToday = text.contains("today") || Regex("\\bthis\\s+${Regex.escape(matched.substringAfterLast(' '))}").containsMatchIn(text)
        val date = now.toLocalDate().with(TemporalAdjusters.nextOrSame(day)).let {
            if (it == now.toLocalDate() && !sameDayMeansToday) it.plusWeeks(1) else it
        }
        // A number straight after the day ("fri 5", "wed 10:30") is a time even without "at"/"by".
        val clock = extractClock(text)
            ?: Regex("${Regex.escape(matched)}\\s+(\\d{1,2}(?::\\d{2})?)\\b").find(text)?.let { extractClock("at ${it.groupValues[1]}") }
        val dateTime = LocalDateTime.of(date, clock?.let(::futureDayTime) ?: defaults.day)
        // "this mon" said on Monday afternoon: the default time has passed, so fall back to later today.
        if (!dateTime.isAfter(now)) return ParsedTime(laterOrNextMorning(now), matched, usedDefault = true)
        return ParsedTime(dateTime, matched, clock == null)
    }

    /**
     * Full English names anywhere; Hindi names (romanised or Devanagari) anywhere; short forms ("wed", "sat", "sun")
     * only next to a date word ("this wed", "by fri", "mon tak"), because "sat", "sun" and "wed" are also ordinary words.
     */
    private fun findWeekday(text: String): Pair<DayOfWeek, String>? {
        WEEKDAY.find(text)?.let { return WEEKDAYS.getValue(it.groupValues[1]) to it.value }
        HINDI_WEEKDAY.find(text)?.let { return HINDI_WEEKDAYS.getValue(it.groupValues[1]) to it.value }
        DEVANAGARI_WEEKDAYS.entries.firstOrNull { text.contains(it.key) }?.let { return it.value to it.key }
        SHORT_WEEKDAY_AFTER_WORD.find(text)?.let { return SHORT_WEEKDAYS.getValue(it.groupValues[2]) to it.value }
        SHORT_WEEKDAY_BEFORE_WORD.find(text)?.let { return SHORT_WEEKDAYS.getValue(it.groupValues[1]) to it.value }
        return null
    }

    private fun parseClock(text: String, now: LocalDateTime): ParsedTime? {
        val rawClock = extractClock(text) ?: return null
        val offset = dayOffset(text) ?: 0
        val date = now.toLocalDate().plusDays(offset)
        // "5" said at 2 PM today means 5 PM; on another day use the future-day rule instead ("kal 10 baje" is 10 AM).
        val time = if (offset == 0L) adjustAmbiguousClock(rawClock, now).first else futureDayTime(rawClock)
        val adjustedDate = if (date == now.toLocalDate() && time.isBefore(now.toLocalTime())) date.plusDays(1) else date
        return ParsedTime(LocalDateTime.of(adjustedDate, time), rawClock.second, usedDefault = false)
    }

    private fun adjustAmbiguousClock(clock: Pair<LocalTime, String>, now: LocalDateTime): Pair<LocalTime, String> {
        val hourText = Regex("\\d{1,2}").find(clock.second)?.value ?: return clock
        val hour = hourText.toInt()
        return if (!hasMeridiem(clock) && hour in 1..11 && hour < now.hour) {
            clock.copy(first = clock.first.plusHours(12))
        } else {
            clock
        }
    }

    private fun parseNamedDefault(text: String, now: LocalDateTime): ParsedTime? {
        // Tomorrow / day after: a part-of-day word sets the time ("kal subah", "tomorrow evening"), else the default.
        val offset = dayOffset(text)
        if (offset != null && offset > 0) {
            val partOfDay = when {
                Regex("\\b(tonight|night|raat)\\b").containsMatchIn(text) -> defaults.tonight
                Regex("\\b(eod|end of day)\\b").containsMatchIn(text) -> defaults.eod
                Regex("\\b(morning|subah|subha)\\b").containsMatchIn(text) -> defaults.morning
                Regex("\\b(evening|shaam|sham)\\b").containsMatchIn(text) -> defaults.evening
                Regex("\\b(afternoon|dopahar|dopeher|dophar|dupahar|dopher)\\b").containsMatchIn(text) -> defaults.afternoon
                else -> null
            }
            return ParsedTime(LocalDateTime.of(now.toLocalDate().plusDays(offset), partOfDay ?: defaults.day), if (offset == 2L) "parso" else "tomorrow", true)
        }
        // Checked before "today" so "aaj raat" is not read as plain "aaj".
        val tonight = Regex("\\b(tonight|aaj raat|raat)\\b").find(text)
        if (tonight != null) return ParsedTime(todayOrTomorrow(now, defaults.tonight), tonight.value, true)
        val eod = Regex("\\b(eod|end of day)\\b").find(text)
        if (eod != null) return ParsedTime(todayOrTomorrow(now, defaults.eod), eod.value, true)
        val morning = Regex("\\b(morning|subah)\\b").find(text)
        if (morning != null) return ParsedTime(todayOrTomorrow(now, defaults.morning), morning.value, true)
        val evening = Regex("\\b(evening|shaam)\\b").find(text)
        if (evening != null) return ParsedTime(todayOrTomorrow(now, defaults.evening), evening.value, true)
        val afternoon = Regex("\\b(afternoon|dopahar|dopeher|dophar|dupahar|dopher)\\b").find(text)
        if (afternoon != null) return ParsedTime(todayOrTomorrow(now, defaults.afternoon), afternoon.value, true)
        val today = Regex("\\b(today|aaj)\\b").find(text)
        if (today != null) return ParsedTime(laterToday(now), today.value, true)
        return null
    }

    private companion object {
        val WEEKDAYS = mapOf(
            "monday" to DayOfWeek.MONDAY, "tuesday" to DayOfWeek.TUESDAY, "wednesday" to DayOfWeek.WEDNESDAY,
            "thursday" to DayOfWeek.THURSDAY, "friday" to DayOfWeek.FRIDAY, "saturday" to DayOfWeek.SATURDAY,
            "sunday" to DayOfWeek.SUNDAY
        )
        val WEEKDAY = Regex("\\b(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b")
        val SHORT_WEEKDAYS = mapOf(
            "mon" to DayOfWeek.MONDAY, "tue" to DayOfWeek.TUESDAY, "tues" to DayOfWeek.TUESDAY,
            "wed" to DayOfWeek.WEDNESDAY, "weds" to DayOfWeek.WEDNESDAY, "thu" to DayOfWeek.THURSDAY,
            "thur" to DayOfWeek.THURSDAY, "thurs" to DayOfWeek.THURSDAY, "fri" to DayOfWeek.FRIDAY,
            "sat" to DayOfWeek.SATURDAY, "sun" to DayOfWeek.SUNDAY
        )
        private const val SHORT = "(mon|tues|tue|weds|wed|thurs|thur|thu|fri|sat|sun)"
        val SHORT_WEEKDAY_AFTER_WORD = Regex("\\b(this|by|on|next|till|until|before|coming|upto)\\s+$SHORT\\b")
        val SHORT_WEEKDAY_BEFORE_WORD = Regex("\\b$SHORT\\s+(tak|ko|tk)\\b")
        val HINDI_WEEKDAYS = mapOf(
            "somvar" to DayOfWeek.MONDAY, "somwar" to DayOfWeek.MONDAY,
            "mangalvar" to DayOfWeek.TUESDAY, "mangalwar" to DayOfWeek.TUESDAY,
            "budhvar" to DayOfWeek.WEDNESDAY, "budhwar" to DayOfWeek.WEDNESDAY,
            "guruvar" to DayOfWeek.THURSDAY, "guruwar" to DayOfWeek.THURSDAY, "brihaspativar" to DayOfWeek.THURSDAY,
            "shukravar" to DayOfWeek.FRIDAY, "shukrawar" to DayOfWeek.FRIDAY,
            "shanivar" to DayOfWeek.SATURDAY, "shaniwar" to DayOfWeek.SATURDAY,
            "ravivar" to DayOfWeek.SUNDAY, "raviwar" to DayOfWeek.SUNDAY, "itwar" to DayOfWeek.SUNDAY,
            "itvaar" to DayOfWeek.SUNDAY, "etwar" to DayOfWeek.SUNDAY, "itvar" to DayOfWeek.SUNDAY
        )
        val HINDI_WEEKDAY = Regex("\\b(${HINDI_WEEKDAYS.keys.joinToString("|")})\\b")
        val DEVANAGARI_WEEKDAYS = mapOf(
            "सोमवार" to DayOfWeek.MONDAY, "मंगलवार" to DayOfWeek.TUESDAY, "बुधवार" to DayOfWeek.WEDNESDAY,
            "गुरुवार" to DayOfWeek.THURSDAY, "बृहस्पतिवार" to DayOfWeek.THURSDAY, "शुक्रवार" to DayOfWeek.FRIDAY,
            "शनिवार" to DayOfWeek.SATURDAY, "रविवार" to DayOfWeek.SUNDAY, "इतवार" to DayOfWeek.SUNDAY
        )
        val NEXT_WEEK = Regex("\\b(next week|next wk|agle hafte|agle week|agle hafta)\\b")
        val NEXT_MONTH = Regex("\\b(next month|agle mahine|agle month|agle mahina|next mnth)\\b")
        val NEXT_MONTH_DEVANAGARI = listOf("अगले महीने", "अगले माह")
        val NEXT_YEAR = Regex("\\b(next year|agle saal|agle sal|next yr)\\b")
        val NEXT_YEAR_DEVANAGARI = listOf("अगले साल", "अगले वर्ष")
        val MONTH_END = Regex("\\b(end of (the )?month|month end|month ke end|mahine ke end|mahine ke aakhir)\\b")
        val NEXT_WEEKEND = Regex("\\b(next weekend|agle weekend)\\b")
        val WEEKEND = Regex("\\b(this weekend|weekend|weekend tak|weekend pe|weekend par)\\b")
        val NEXT_WEEK_DEVANAGARI = listOf("अगले हफ्ते", "अगले हफ़्ते", "अगले सप्ताह")
        val NUMBER_WORDS = mapOf(
            "a" to 1L, "an" to 1L, "one" to 1L, "two" to 2L, "three" to 3L, "four" to 4L, "five" to 5L, "six" to 6L,
            "seven" to 7L, "eight" to 8L, "nine" to 9L, "ten" to 10L, "ek" to 1L, "do" to 2L, "teen" to 3L, "char" to 4L,
            "chaar" to 4L, "paanch" to 5L, "panch" to 5L, "chhe" to 6L, "che" to 6L, "saat" to 7L, "aath" to 8L,
            "nau" to 9L, "das" to 10L
        )
        private val NUMBER = "(\\d+|${NUMBER_WORDS.keys.joinToString("|")})"
        private val NUMBER_NO_ARTICLE = "(\\d+|${(NUMBER_WORDS.keys - setOf("a", "an")).joinToString("|")})"
        private const val SHORT_UNIT = "(m|mins?|minutes?|h|hrs?|hours?|ghante|ghanta|ghnte|ghnta)"
        private const val DAY_UNIT = "(days?|din|weeks?|wks?|hafte|hafta|haftey|months?|mahine|mahina|mahino)"
        val HALF_HOUR = Regex("\\b(half an hour|half hour|aadha ghanta|adha ghanta|aadhe ghante|adhe ghante)\\b")
        val TOMORROW_VARIANTS = Regex(
            "\\b(tmrw|tmr|tmw|tmrow|tmrrw|tmmrw|tommrw|2mrw|2mro|2moro|2morow|2morrow|tomo|tomm|tomorow|tommorow|tommorrow|tomorrw|tomoro|tomorro|tmorrow)\\b"
        )

        // Repeating reminders.
        val ANY_WEEKDAY = WEEKDAYS + SHORT_WEEKDAYS + HINDI_WEEKDAYS
        // Longest first, so "thursday" is not read as "thu".
        private val DAY_TOKENS = ANY_WEEKDAY.keys.sortedByDescending { it.length }.joinToString("|")
        val DAY_TOKEN = Regex("\\b($DAY_TOKENS)\\b")
        // "every monday", "har somvar", "every mon, wed and fri", "each tuesday & thursday".
        val EVERY_DAY_LIST = Regex("\\b(every|each|har|hr)\\s+((?:$DAY_TOKENS)(?:\\s*(?:,|and|&|aur|or|n)\\s*(?:$DAY_TOKENS))*)\\b")
        val PLURAL_WEEKDAY = Regex("\\b(monday|tuesday|wednesday|thursday|friday|saturday|sunday)s\\b")
        val EVERY_WEEKDAY = Regex("\\b(every weekday|each weekday|weekdays|har weekday|monday to friday|mon to fri|monday-friday|mon-fri)\\b")
        val EVERY_WEEKEND = Regex("\\b(every weekend|each weekend|weekends|har weekend)\\b")
        val DAILY = Regex(
            "\\b(every ?day|each day|every single day|daily|har din|har roz|har roj|roz|rozana|rozaana|roj|nightly|" +
                "(every|each|har)\\s+(morning|evening|night|afternoon|subah|subha|shaam|sham|raat))\\b"
        )
        val DAILY_DEVANAGARI = listOf("रोज़", "रोज", "हर दिन", "प्रतिदिन", "हर सुबह", "हर शाम", "हर रात")
        val WEEKLY = Regex("\\b(every week|each week|every wk|weekly|once a week|har hafte|har hafta|har week)\\b")
        val WEEKLY_DEVANAGARI = listOf("हर हफ्ते", "हर हफ़्ते", "हर सप्ताह")
        val MONTHLY = Regex("\\b(every month|each month|monthly|once a month|har mahine|har mahina|har month)\\b")
        val MONTHLY_DEVANAGARI = listOf("हर महीने", "हर माह")
        val YEARLY = Regex("\\b(every year|each year|yearly|annually|once a year|har saal|har sal|har year)\\b")
        val YEARLY_DEVANAGARI = listOf("हर साल", "हर वर्ष")
        val MONTHS = mapOf(
            "jan" to 1, "january" to 1, "feb" to 2, "february" to 2, "mar" to 3, "march" to 3, "apr" to 4, "april" to 4,
            "may" to 5, "jun" to 6, "june" to 6, "jul" to 7, "july" to 7, "aug" to 8, "august" to 8, "sep" to 9,
            "sept" to 9, "september" to 9, "oct" to 10, "october" to 10, "nov" to 11, "november" to 11, "dec" to 12,
            "december" to 12
        )
        private val MONTH_NAME = "(${MONTHS.keys.sortedByDescending { it.length }.joinToString("|")})"
        // Things that repeat by nature, said without "every". Yearly: birthdays and anniversaries, unless the message
        // is about a one-off task for it (buying the cake, booking the dinner).
        val YEARLY_BY_NATURE = Regex("\\b(birthday|bday|b'day|bdy|budday|anniversary|anniv|janamdin|janmdin|janmadin|saalgirah|salgirah|barsi)\\b")
        val YEARLY_BY_NATURE_DEVANAGARI = listOf("जन्मदिन", "सालगिरह", "वर्षगांठ", "बरसी")
        val ONE_OFF_TASK = Regex("\\b(party|gift|cake|present|plan|planning|celebration|dinner|lunch|treat|order|book|buy|card|surprise|decorat\\w*)\\b")
        // Monthly: payments and bills that come every month, when a day of the month is named ("rent on the 1st").
        val MONTHLY_BY_NATURE = Regex("\\b(rent|emi|sip|kiraya|kiraaya|salary|credit card bill|card bill|maintenance|school fees?|tuition fees?|insurance premium|premium)\\b")

        // Whole Devanagari word only, so "कल" does not match inside "कलम".
        fun devanagariWord(word: String) = Regex("(?<![\\u0900-\\u097F])$word(?![\\u0900-\\u097F])")
    }

    // "kal" also means yesterday: past-tense words mark that. English "the" is deliberately not one of them.
    private fun isFutureKal(text: String): Boolean =
        !Regex("\\b(kya hua|hua tha|tha|thi|thhe|gaya tha|gayi thi)\\b").containsMatchIn(text) &&
            listOf("था", "थी", "थे", "हुआ").none { text.contains(it) }

    private fun todayOrTomorrow(now: LocalDateTime, time: LocalTime): LocalDateTime {
        val today = LocalDateTime.of(now.toLocalDate(), time)
        return if (today.isAfter(now)) today else today.plusDays(1)
    }

    // "today" with no time: the morning default if still ahead, else end of day, else the no-time delay.
    private fun laterToday(now: LocalDateTime): LocalDateTime =
        listOf(defaults.morning, defaults.eod)
            .map { LocalDateTime.of(now.toLocalDate(), it) }
            .firstOrNull { it.isAfter(now) }
            ?: laterOrNextMorning(now)

    private fun extractClock(text: String): Pair<LocalTime, String>? {
        // A bare number ("send 2 files") is not a time; require by/at, a colon, am/pm, baje or oclock. Other spellings
        // were rewritten to these by canonicalTimeWords.
        return Regex("\\b(by\\s+|at\\s+)?(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm|baje|oclock)?\\b").findAll(text).firstNotNullOfOrNull { match ->
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