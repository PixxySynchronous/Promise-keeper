package com.satyam.whatsappbot.domain

/**
 * Messages in your own WhatsApp chat ("Message yourself") are notes to yourself, not replies to someone. So a note
 * there with a time or a repeat ("wish her good morning every day at 10 am") is a reminder, like one starting with
 * "reminder". The same words sent to someone else are telling them, so they still need a promise to count.
 */
object SelfNote {
    const val MATCHED_PHRASE = "note to yourself"

    // WhatsApp titles your own chat "<your name> (You)", or "(आप)" in Hindi; the subtitle reads "Message yourself".
    private val SELF_CHAT = Regex("(\\((you|आप)\\)\\s*$)|^\\s*(you|message yourself)\\s*$", RegexOption.IGNORE_CASE)

    fun isSelfChat(contact: String): Boolean = SELF_CHAT.containsMatchIn(contact)
}
