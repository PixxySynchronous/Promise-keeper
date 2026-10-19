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
    fun timeIsReadFromTheTextAfterTheKeyword() {
        val sunday = LocalDateTime.of(2026, 10, 18, 17, 30)
        val text = ManualReminder.extract("Reminder: submit form next week by 4")!!
        assertEquals(LocalDateTime.of(2026, 10, 25, 16, 0), TimeParser().parseOrDefault(text, sunday).dateTime)
    }
}
