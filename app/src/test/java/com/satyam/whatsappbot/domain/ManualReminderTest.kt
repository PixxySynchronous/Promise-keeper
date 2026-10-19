package com.satyam.whatsappbot.domain

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ManualReminderTest {
    @Test
    fun messagesStartingWithReminderAreExtracted() {
        assertEquals("pay rent on 1/11", ManualReminder.extract("Reminder: pay rent on 1/11"))
        assertEquals("call mom at 5", ManualReminder.extract("reminder call mom at 5"))
        assertEquals("dentist kal 10 baje", ManualReminder.extract("  REMINDER - dentist kal 10 baje"))
        assertEquals("buy milk", ManualReminder.extract("Reminder, buy milk"))
        assertEquals("Reminder", ManualReminder.extract("Reminder"))
    }

    @Test
    fun otherMessagesAreNotManualReminders() {
        assertNull(ManualReminder.extract("reminders are annoying"))
        assertNull(ManualReminder.extract("send me a reminder tomorrow"))
        assertNull(ManualReminder.extract("remind me later"))
        assertNull(ManualReminder.extract("kal bhej dunga"))
    }

    @Test
    fun shortAndHindiWeekdayNames() {
        val parser = TimeParser()
        val monday = LocalDateTime.of(2026, 10, 19, 11, 0)
        fun at(text: String) = parser.parseOrDefault(text, monday).dateTime
        assertEquals(LocalDateTime.of(2026, 10, 21, 10, 0), at(ManualReminder.extract("reminder finish this work by this wed")!!))
        assertEquals(LocalDateTime.of(2026, 10, 23, 17, 0), at("will send by fri 5"))
        assertEquals(LocalDateTime.of(2026, 10, 24, 10, 0), at("on sat bhej dunga"))
        assertEquals(LocalDateTime.of(2026, 10, 22, 10, 0), at("thurs tak kar dunga"))
        assertEquals(LocalDateTime.of(2026, 10, 26, 10, 0), at("mon tak bhej dunga"))
        assertEquals(LocalDateTime.of(2026, 10, 21, 10, 0), at("budhvar tak kar dunga"))
        assertEquals(LocalDateTime.of(2026, 10, 25, 10, 0), at("itwar ko milte hain"))
        assertEquals(LocalDateTime.of(2026, 10, 21, 10, 0), at("बुधवार को भेज दूंगा"))
        assertEquals(LocalDateTime.of(2026, 10, 30, 19, 0), at("by fri next week at 7"))
        // "this mon" on Monday after 10 AM: later today, not a past time.
        assertEquals(monday.plusHours(2), at("this mon"))
        // Ordinary words that look like short weekday names are ignored.
        assertEquals(LocalDateTime.of(2026, 10, 20, 10, 0), at("I sat with him, will send tomorrow"))
        assertEquals(LocalDateTime.of(2026, 10, 19, 17, 0), at("the sun is out, will send at 5"))
    }

    @Test
    fun tomorrowSpellingsAndDayAfter() {
        val parser = TimeParser()
        val evening = LocalDateTime.of(2026, 10, 19, 18, 0)
        fun at(text: String) = parser.parseOrDefault(text, evening).dateTime
        val tomorrowDefault = LocalDateTime.of(2026, 10, 20, 10, 0)
        listOf("ill do it tom", "will send tmrw", "kal tak 2moro", "send by tommorow", "tmr pakka", "kl bhej dunga", "कल भेज दूंगा")
            .forEach { assertEquals(it, tomorrowDefault, at(it)) }
        // "Tom" with a capital is a name, not tomorrow.
        assertEquals(evening.plusHours(2), at("I'll call Tom"))
        // English "the" no longer cancels "tomorrow".
        assertEquals(tomorrowDefault, at("I'll send the file tomorrow"))
        assertEquals(LocalDateTime.of(2026, 10, 21, 10, 0), at("parso bhej dunga"))
        assertEquals(LocalDateTime.of(2026, 10, 21, 10, 0), at("will send day after tomorrow"))
        assertEquals(LocalDateTime.of(2026, 10, 21, 10, 0), at("परसों कर दूंगा"))
        // A tomorrow time is not pushed to PM just because it is earlier than the current hour.
        assertEquals(LocalDateTime.of(2026, 10, 20, 10, 0), at("kal 10 baje call karta hu"))
        assertEquals(LocalDateTime.of(2026, 10, 20, 9, 30), at("kal subah bhej dunga"))
        assertEquals(LocalDateTime.of(2026, 10, 20, 18, 0), at("tom evening will call"))
        // "kal" about the past is still not tomorrow, and "कलम" (pen) is not "कल".
        assertNull(parser.parse("kal kya hua tha", evening))
        assertEquals(evening.plusHours(2), at("कलम भेज दूंगा"))
    }

    @Test
    fun chosenDefaultTimeAppliesWheneverADayHasNoTime() {
        val parser = TimeParser(DefaultTimes(day = java.time.LocalTime.of(8, 30)))
        val monday = LocalDateTime.of(2026, 10, 19, 11, 0)
        assertEquals(LocalDateTime.of(2026, 10, 20, 8, 30), parser.parseOrDefault("kal bhej dunga", monday).dateTime)
        assertEquals(LocalDateTime.of(2026, 10, 23, 8, 30), parser.parseOrDefault("will send by fri", monday).dateTime)
        assertEquals(LocalDateTime.of(2026, 10, 26, 8, 30), parser.parseOrDefault("will share next week", monday).dateTime)
        assertEquals(LocalDateTime.of(2026, 10, 25, 8, 30), parser.parseOrDefault("send it on 25/10", monday).dateTime)
        // An explicit time still wins.
        assertEquals(LocalDateTime.of(2026, 10, 23, 17, 0), parser.parseOrDefault("will send by fri 5", monday).dateTime)
    }

    @Test
    fun timeIsReadFromTheTextAfterTheKeyword() {
        val sunday = LocalDateTime.of(2026, 10, 18, 17, 30)
        val text = ManualReminder.extract("Reminder: submit form next week by 4")!!
        assertEquals(LocalDateTime.of(2026, 10, 25, 16, 0), TimeParser().parseOrDefault(text, sunday).dateTime)
    }
}
