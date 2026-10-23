package com.satyam.whatsappbot.domain

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecurrenceTest {
    // Saturday 24 Oct 2026, 2 PM.
    private val now = LocalDateTime.of(2026, 10, 24, 14, 0)
    private val parser = TimeParser()

    private fun parse(message: String) = parser.parseRecurrence(message, now)

    @Test
    fun nextOccurrence() {
        val nine = LocalTime.of(9, 0)
        assertEquals(LocalDateTime.of(2026, 10, 25, 9, 0), Recurrence(Recurrence.Frequency.DAILY, nine).next(now))
        assertEquals(LocalDateTime.of(2026, 10, 24, 18, 0), Recurrence(Recurrence.Frequency.DAILY, LocalTime.of(18, 0)).next(now))
        val monThu = Recurrence(Recurrence.Frequency.WEEKLY, nine, weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY))
        assertEquals(LocalDateTime.of(2026, 10, 26, 9, 0), monThu.next(now))
        assertEquals(LocalDateTime.of(2026, 10, 29, 9, 0), monThu.next(LocalDateTime.of(2026, 10, 26, 9, 0)))
        // Same weekday, time still ahead today; same weekday, time passed: next week.
        assertEquals(LocalDateTime.of(2026, 10, 24, 18, 0), Recurrence(Recurrence.Frequency.WEEKLY, LocalTime.of(18, 0), weekdays = setOf(DayOfWeek.SATURDAY)).next(now))
        assertEquals(LocalDateTime.of(2026, 10, 31, 9, 0), Recurrence(Recurrence.Frequency.WEEKLY, nine, weekdays = setOf(DayOfWeek.SATURDAY)).next(now))
        // The 31st rings on the last day of shorter months.
        assertEquals(LocalDateTime.of(2026, 10, 31, 9, 0), Recurrence(Recurrence.Frequency.MONTHLY, nine, dayOfMonth = 31).next(now))
        assertEquals(LocalDateTime.of(2026, 11, 30, 9, 0), Recurrence(Recurrence.Frequency.MONTHLY, nine, dayOfMonth = 31).next(LocalDateTime.of(2026, 10, 31, 9, 0)))
        assertEquals(LocalDateTime.of(2026, 11, 15, 9, 0), Recurrence(Recurrence.Frequency.MONTHLY, nine, dayOfMonth = 15).next(now))
        assertEquals(LocalDateTime.of(2026, 12, 25, 9, 0), Recurrence(Recurrence.Frequency.YEARLY, nine, month = 12, dayOfMonth = 25).next(now))
        assertEquals(LocalDateTime.of(2027, 2, 28, 9, 0), Recurrence(Recurrence.Frequency.YEARLY, nine, month = 2, dayOfMonth = 29).next(now))
        assertEquals(LocalDateTime.of(2027, 3, 1, 9, 0), Recurrence(Recurrence.Frequency.YEARLY, nine, month = 3, dayOfMonth = 1).next(now))
    }

    @Test
    fun encodesAndDecodes() {
        val rules = listOf(
            Recurrence(Recurrence.Frequency.DAILY, LocalTime.of(9, 30)),
            Recurrence(Recurrence.Frequency.WEEKLY, LocalTime.of(10, 0), weekdays = setOf(DayOfWeek.THURSDAY, DayOfWeek.MONDAY)),
            Recurrence(Recurrence.Frequency.MONTHLY, LocalTime.of(9, 30), dayOfMonth = 15),
            Recurrence(Recurrence.Frequency.YEARLY, LocalTime.of(10, 0), month = 12, dayOfMonth = 25)
        )
        rules.forEach { assertEquals(it, Recurrence.decode(it.encode())) }
        assertEquals("WEEKLY:MON,THU@10:00", rules[1].encode())
        assertNull(Recurrence.decode(""))
        assertNull(Recurrence.decode("nonsense"))
    }

    @Test
    fun describes() {
        val en = Locale.ENGLISH
        assertEquals("Every day at 9:30 AM", Recurrence(Recurrence.Frequency.DAILY, LocalTime.of(9, 30)).describe(en))
        assertEquals("Every Monday at 10:00 AM", Recurrence(Recurrence.Frequency.WEEKLY, LocalTime.of(10, 0), weekdays = setOf(DayOfWeek.MONDAY)).describe(en))
        assertEquals("Every Mon, Wed and Fri at 10:00 AM", Recurrence(Recurrence.Frequency.WEEKLY, LocalTime.of(10, 0), weekdays = setOf(DayOfWeek.FRIDAY, DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY)).describe(en))
        assertEquals("Every weekday at 10:00 AM", Recurrence(Recurrence.Frequency.WEEKLY, LocalTime.of(10, 0), weekdays = Recurrence.WEEKDAYS).describe(en))
        assertEquals("Every month on the 1st at 10:00 AM", Recurrence(Recurrence.Frequency.MONTHLY, LocalTime.of(10, 0), dayOfMonth = 1).describe(en))
        assertEquals("Every month on the 12th at 10:00 AM", Recurrence(Recurrence.Frequency.MONTHLY, LocalTime.of(10, 0), dayOfMonth = 12).describe(en))
        assertEquals("Every year on 25 Dec at 10:00 AM", Recurrence(Recurrence.Frequency.YEARLY, LocalTime.of(10, 0), month = 12, dayOfMonth = 25).describe(en))
    }

    @Test
    fun readsDailyFromMessages() {
        val daily = Recurrence.Frequency.DAILY
        assertEquals(Recurrence(daily, LocalTime.of(10, 0)), parse("I'll take my medicine every day"))
        assertEquals(Recurrence(daily, LocalTime.of(9, 0)), parse("reminder daily standup at 9"))
        assertEquals(Recurrence(daily, LocalTime.of(9, 30)), parse("every morning paani piunga"))
        assertEquals(Recurrence(daily, LocalTime.of(7, 0)), parse("roz subah 7 baje walk karunga"))
        assertEquals(Recurrence(daily, LocalTime.of(19, 0)), parse("har shaam 7 baje call karunga"))
        assertEquals(Recurrence(daily, LocalTime.of(21, 0)), parse("har raat dawai lunga"))
        assertEquals(Recurrence(daily, LocalTime.of(22, 0)), parse("har din raat 10 baje padhunga"))
        assertEquals(Recurrence(daily, LocalTime.of(9, 30)), parse("मैं हर सुबह दौड़ूंगा"))
    }

    @Test
    fun readsWeeklyFromMessages() {
        val weekly = Recurrence.Frequency.WEEKLY
        assertEquals(Recurrence(weekly, LocalTime.of(10, 0), weekdays = setOf(DayOfWeek.MONDAY)), parse("I'll call you every monday"))
        assertEquals(Recurrence(weekly, LocalTime.of(10, 0), weekdays = setOf(DayOfWeek.MONDAY)), parse("har somvar mandir jaunga"))
        assertEquals(Recurrence(weekly, LocalTime.of(17, 0), weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY)), parse("will send the report every mon and thu at 5"))
        assertEquals(Recurrence(weekly, LocalTime.of(10, 0), weekdays = setOf(DayOfWeek.SUNDAY)), parse("I'll clean the room on sundays"))
        assertEquals(Recurrence(weekly, LocalTime.of(9, 30), weekdays = Recurrence.WEEKDAYS), parse("I'll go for a run on weekdays in the morning"))
        assertEquals(Recurrence(weekly, LocalTime.of(10, 0), weekdays = Recurrence.WEEKEND), parse("every weekend ghar call karunga"))
        // No day named: today's weekday (Saturday).
        assertEquals(Recurrence(weekly, LocalTime.of(10, 0), weekdays = setOf(DayOfWeek.SATURDAY)), parse("I'll update you every week"))
        assertEquals(Recurrence(weekly, LocalTime.of(10, 0), weekdays = setOf(DayOfWeek.FRIDAY)), parse("will send it weekly on friday"))
        assertEquals(Recurrence(weekly, LocalTime.of(10, 0), weekdays = setOf(DayOfWeek.MONDAY)), parse("हर सोमवार भेजूंगा"))
    }

    @Test
    fun readsMonthlyAndYearlyFromMessages() {
        assertEquals(Recurrence(Recurrence.Frequency.MONTHLY, LocalTime.of(10, 0), dayOfMonth = 1), parse("I'll pay rent on the 1st of every month"))
        assertEquals(Recurrence(Recurrence.Frequency.MONTHLY, LocalTime.of(10, 0), dayOfMonth = 15), parse("every month on 15th bill bharunga"))
        assertEquals(Recurrence(Recurrence.Frequency.MONTHLY, LocalTime.of(10, 0), dayOfMonth = 5), parse("har mahine 5 tareekh ko bhejunga"))
        assertEquals(Recurrence(Recurrence.Frequency.MONTHLY, LocalTime.of(10, 0), dayOfMonth = 24), parse("I'll send the invoice monthly"))
        assertEquals(Recurrence(Recurrence.Frequency.YEARLY, LocalTime.of(10, 0), month = 12, dayOfMonth = 25), parse("reminder every year on 25/12 wish him"))
        assertEquals(Recurrence(Recurrence.Frequency.YEARLY, LocalTime.of(9, 0), month = 3, dayOfMonth = 14), parse("I'll wish her every year on 14th march at 9 am"))
        assertEquals(Recurrence(Recurrence.Frequency.YEARLY, LocalTime.of(10, 0), month = 10, dayOfMonth = 24), parse("har saal yaad se bhejunga"))
    }

    @Test
    fun datesWithMonthNamesAreUnderstood() {
        // The reported case: "reminder Piyush birthday 28 May" rang the next day instead of on 28 May.
        assertEquals(LocalDateTime.of(2027, 5, 28, 10, 0), parser.parse("Piyush birthday 28 May", now)!!.dateTime)
        listOf("party on 28 may", "party on may 28", "party on 28th of May", "party on 28th may", "party on May 28th", "party on 28/5", "party on 28-05")
            .forEach { assertEquals("Wrong date: $it", LocalDateTime.of(2027, 5, 28, 10, 0), parser.parse(it, now)?.dateTime) }
        assertEquals(LocalDateTime.of(2026, 12, 28, 10, 0), parser.parse("I'll send it on 28 dec", now)!!.dateTime)
        assertEquals(LocalDateTime.of(2028, 5, 28, 10, 0), parser.parse("trip on 28 May 2028", now)!!.dateTime)
        assertEquals(LocalDateTime.of(2027, 5, 28, 19, 0), parser.parse("dinner on 28 May at 7 pm", now)!!.dateTime)
        // "may" as a verb is not a month.
        assertNull(parser.parse("I may send 2 files", now))
    }

    @Test
    fun birthdaysAndAnniversariesRepeatEveryYear() {
        val yearly = Recurrence.Frequency.YEARLY
        val ten = LocalTime.of(10, 0)
        assertEquals(Recurrence(yearly, ten, month = 5, dayOfMonth = 28), parse("Piyush birthday 28 May"))
        assertEquals(LocalDateTime.of(2027, 5, 28, 10, 0), parse("Piyush birthday 28 May")!!.next(now))
        assertEquals(Recurrence(yearly, ten, month = 2, dayOfMonth = 14), parse("our anniversary on 14 feb"))
        assertEquals(Recurrence(yearly, ten, month = 7, dayOfMonth = 3), parse("wish Rahul happy bday on 3/7"))
        assertEquals(Recurrence(yearly, ten, month = 10, dayOfMonth = 25), parse("mom ka janamdin kal hai"))
        assertEquals(Recurrence(yearly, ten, month = 1, dayOfMonth = 9), parse("पापा का जन्मदिन 9 jan"))
        // A one-off task for a birthday, or a birthday with no date, doesn't repeat.
        assertNull(parse("buy a cake for Piyush birthday on 28 May"))
        assertNull(parse("book the birthday dinner for 28 may"))
        assertNull(parse("wish Piyush happy birthday"))
    }

    @Test
    fun rentAndBillsRepeatEveryMonthWhenADayIsNamed() {
        val monthly = Recurrence.Frequency.MONTHLY
        assertEquals(Recurrence(monthly, LocalTime.of(10, 0), dayOfMonth = 1), parse("pay rent on the 1st"))
        assertEquals(Recurrence(monthly, LocalTime.of(10, 0), dayOfMonth = 5), parse("EMI 5 tareekh ko"))
        assertEquals(Recurrence(monthly, LocalTime.of(9, 0), dayOfMonth = 10), parse("credit card bill on 10th at 9 am"))
        assertNull(parse("pay rent tomorrow"))
        assertNull(parse("I'll pay the rent"))
    }

    @Test
    fun oneTimeMessagesDoNotRepeat() {
        listOf(
            "I'll send it tomorrow", "kal bhej dunga", "I'll call you on monday", "will send every file by 5",
            "this weekend I'll come", "I'll do it next week", "I'll send it next month", "12 o'clock karunga"
        ).forEach { assertNull("Expected no repeat: $it", parse(it)) }
    }
}
