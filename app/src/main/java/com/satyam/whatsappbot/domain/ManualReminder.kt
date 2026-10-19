package com.satyam.whatsappbot.domain

/**
 * A message starting with the word "reminder" ("Reminder: pay rent on 1/11", "reminder call mom at 5") is an explicit
 * request for a reminder, so it always produces one, bypassing promise detection.
 */
object ManualReminder {
    const val MATCHED_PHRASE = "manual (reminder keyword)"

    private val KEYWORD = Regex("^\\s*reminder\\b[\\s:,.\\-–—]*", RegexOption.IGNORE_CASE)

    /** The reminder text after the keyword, or null when the message does not start with it. */
    fun extract(message: String): String? {
        val match = KEYWORD.find(message) ?: return null
        return message.substring(match.range.last + 1).trim().ifEmpty { message.trim() }
    }
}
