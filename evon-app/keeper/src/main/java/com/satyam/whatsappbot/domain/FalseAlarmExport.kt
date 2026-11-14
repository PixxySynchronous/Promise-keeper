package com.satyam.whatsappbot.domain

/**
 * Builds the file users share to help improve detection. It contains only the message text and what flagged it,
 * never the contact name. tools/train_semantic_head.py reads these files from tools/contributed/.
 */
object FalseAlarmExport {
    const val FORMAT = "promise-keeper-false-alarms"

    data class Item(val text: String, val detectedBy: String, val detectedAt: String)

    fun toJson(items: List<Item>, appVersion: String, exportedAt: String): String = buildString {
        append("{\n")
        append("  \"format\": ").append(quote(FORMAT)).append(",\n")
        append("  \"version\": 1,\n")
        append("  \"app_version\": ").append(quote(appVersion)).append(",\n")
        append("  \"exported_at\": ").append(quote(exportedAt)).append(",\n")
        append("  \"label\": \"not_a_promise\",\n")
        append("  \"messages\": [")
        items.forEachIndexed { index, item ->
            append(if (index == 0) "\n" else ",\n")
            append("    {\"text\": ").append(quote(item.text))
            append(", \"detected_by\": ").append(quote(item.detectedBy))
            append(", \"detected_at\": ").append(quote(item.detectedAt)).append("}")
        }
        append(if (items.isEmpty()) "]\n" else "\n  ]\n")
        append("}\n")
    }

    private fun quote(value: String): String = buildString {
        append('"')
        for (ch in value) {
            when {
                ch == '"' -> append("\\\"")
                ch == '\\' -> append("\\\\")
                ch == '\n' -> append("\\n")
                ch == '\r' -> append("\\r")
                ch == '\t' -> append("\\t")
                ch < ' ' -> append("\\u%04x".format(ch.code))
                else -> append(ch)
            }
        }
        append('"')
    }
}
