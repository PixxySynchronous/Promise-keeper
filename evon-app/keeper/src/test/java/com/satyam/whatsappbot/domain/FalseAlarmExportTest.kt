package com.satyam.whatsappbot.domain

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class FalseAlarmExportTest {
    @Test
    fun exportIsValidJsonWithTextAndSourceOnly() {
        val items = listOf(
            FalseAlarmExport.Item("ok fatty", "semantic AI (0.99)", "2026-10-19T16:01:12"),
            FalseAlarmExport.Item("he said \"I'll send\"\nnew line \\ slash\ttab", "will send", "2026-10-19T16:02:00"),
            FalseAlarmExport.Item("कल मिलते हैं 😀", "semantic AI (0.81)", "2026-10-19T16:03:00")
        )
        val json = FalseAlarmExport.toJson(items, appVersion = "1.1", exportedAt = "2026-10-19T16:30:00")
        val root = JsonParser.parseString(json).asJsonObject
        assertEquals("promise-keeper-false-alarms", root.get("format").asString)
        assertEquals("not_a_promise", root.get("label").asString)
        val messages = root.getAsJsonArray("messages").map { it.asJsonObject }
        assertEquals(items.map { it.text }, messages.map { it.get("text").asString })
        assertEquals(items.map { it.detectedBy }, messages.map { it.get("detected_by").asString })
        // Only text, source and time: no contact or other personal fields.
        messages.forEach { assertEquals(setOf("text", "detected_by", "detected_at"), it.keySet()) }
        assertFalse(json.contains("contact"))
    }

    @Test
    fun emptyExportIsValid() {
        val root = JsonParser.parseString(FalseAlarmExport.toJson(emptyList(), "1.1", "2026-10-19T16:30:00")).asJsonObject
        assertEquals(0, root.getAsJsonArray("messages").size())
    }
}
