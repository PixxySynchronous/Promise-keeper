package com.satyam.whatsappbot

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.satyam.whatsappbot.data.ReminderRepository
import com.satyam.whatsappbot.domain.CommitmentMatch
import com.satyam.whatsappbot.domain.DefaultTimes
import com.satyam.whatsappbot.domain.ManualReminder
import com.satyam.whatsappbot.domain.OnDeviceSemanticClassifier
import com.satyam.whatsappbot.domain.RuleBasedCommitmentDetector
import com.satyam.whatsappbot.domain.TimeParser
import java.time.LocalDateTime
import java.util.concurrent.Executors

/**
 * Watches WhatsApp (limited to com.whatsapp and com.whatsapp.w4b in accessibility_service_config.xml) for messages the
 * user sends, and suggests a reminder when one is a promise. Nothing is logged or stored except detected reminders.
 */
class WhatsappAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var lastInputText = ""
    private var currentContact = ""
    private var lastSendClickAt = 0L
    private var lastSendClickContact = ""
    private var fallbackCandidate: String? = null
    private var fallbackContact = ""
    private val detector = RuleBasedCommitmentDetector()
    private val semanticClassifier by lazy { OnDeviceSemanticClassifier(this) }
    // Built per message so a changed default time applies immediately.
    private fun timeParser() = TimeParser(DefaultTimes(day = ReminderSettings.defaultTime(this)))
    // Detection may load the ONNX model and tokenizer, so keep it off the main thread.
    private val detectionExecutor = Executors.newSingleThreadExecutor()
    private lateinit var reminderRepository: ReminderRepository

    override fun onServiceConnected() {
        super.onServiceConnected()
        reminderRepository = ReminderRepository(this)
        // Loading the model takes ~15 s on a phone; do it now so the first message is not delayed.
        detectionExecutor.execute { semanticClassifier.warmUp() }
    }

    private fun processCapturedMessage(message: String, contact: String) {
        detectionExecutor.execute { detectAndSuggest(message, contact) }
    }

    private fun detectAndSuggest(message: String, contact: String) {
        val safeContact = contact.ifBlank { "Unknown contact" }
        ManualReminder.extract(message)?.let { text ->
            val parsedTime = timeParser().parseOrDefault(text, LocalDateTime.now())
            reminderRepository.insertPending(message, safeContact, ManualReminder.MATCHED_PHRASE, parsedTime.dateTime, title = text) { reminder ->
                ReminderNotifications.showConfirmation(this, reminder)
            }
            return
        }

        // Phrase list first; the on-device model only sees messages without a clear "not a promise" signal.
        val match = detector.detect(message)
            ?: semanticClassifier
                .takeIf { !detector.rejects(message) && (detector.hasCommitmentCue(message) || timeParser().parse(message, LocalDateTime.now()) != null) }
                ?.classify(message)
                ?.takeIf { it.isCommitment }
                ?.let { CommitmentMatch(message, "semantic AI (${"%.2f".format(it.confidence)})") }
            ?: return
        val parsedTime = timeParser().parseOrDefault(message, LocalDateTime.now())
        reminderRepository.insertPending(message, safeContact, match.matchedPhrase, parsedTime.dateTime) { reminder ->
            ReminderNotifications.showConfirmation(this, reminder)
        }
    }

    override fun onDestroy() {
        detectionExecutor.shutdown()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.packageName?.toString() !in setOf("com.whatsapp", "com.whatsapp.w4b")) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val contact = findContact(event.source)
                if (contact.isNotEmpty()) currentContact = contact
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> handleTextChanged(event)
            AccessibilityEvent.TYPE_VIEW_CLICKED -> handleClicked(event)
        }
    }

    private fun handleTextChanged(event: AccessibilityEvent) {
        if (event.source?.isEditable != true) return
        val text = event.text.joinToString("")
        val previous = lastInputText
        lastInputText = text
        // The composer emptied: usually a send. Used if no send-button click is seen (see captureFallbackIfEligible).
        if (previous.isNotEmpty() && text.isEmpty()) {
            fallbackCandidate = previous
            fallbackContact = currentContact
            handler.postDelayed({ captureFallbackIfEligible() }, 1000L)
        }
    }

    private fun handleClicked(event: AccessibilityEvent) {
        val isSend = event.contentDescription?.toString().orEmpty().contains("send", ignoreCase = true)
        if (!isSend || lastInputText.isEmpty()) return
        lastSendClickAt = System.currentTimeMillis()
        lastSendClickContact = currentContact
        processCapturedMessage(lastInputText, currentContact)
        fallbackCandidate = null
        fallbackContact = ""
        lastInputText = ""
    }

    private fun captureFallbackIfEligible() {
        val candidate = fallbackCandidate ?: return
        val elapsed = System.currentTimeMillis() - lastSendClickAt
        val sameContact = fallbackContact == currentContact
        val sendWasRecentForSameContact = elapsed <= 1000L && lastSendClickContact == fallbackContact
        if (!sendWasRecentForSameContact && sameContact) processCapturedMessage(candidate, fallbackContact)
        fallbackCandidate = null
        fallbackContact = ""
    }

    // The chat title: the first short text near the top of the screen.
    private fun findContact(root: AccessibilityNodeInfo?): String {
        if (root == null) return ""
        for (index in 0 until root.childCount) {
            val result = findContact(root.getChild(index) ?: continue)
            if (result.isNotEmpty()) return result
        }
        return shortTopText(root)
    }

    private fun shortTopText(node: AccessibilityNodeInfo): String {
        val text = node.text?.toString().orEmpty()
        if (text.isEmpty() || text.length > 80 || node.className?.toString() != "android.widget.TextView") return ""
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return if (bounds.top <= 240) text else ""
    }

    override fun onInterrupt() = Unit
}
