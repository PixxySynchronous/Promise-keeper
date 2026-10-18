package com.satyam.whatsappbot

import androidx.compose.runtime.mutableStateListOf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DebugLogStore {
    private val entries = mutableStateListOf<String>()
    private val formatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    @Volatile var loggingEnabled: Boolean = false

    val logs: List<String>
        get() = entries

    fun add(message: String) {
        if (!loggingEnabled) return
        entries.add("${formatter.format(Date())}  $message")
        if (entries.size > 500) entries.removeAt(0)
    }

    fun clear() = entries.clear()

    fun maskedText(text: CharSequence?): String {
        val value = text?.toString().orEmpty()
        return if (value.isEmpty()) "<empty>" else "<${value.length} chars>"
    }

    fun safeText(text: CharSequence?): String = text?.toString().orEmpty()
}
