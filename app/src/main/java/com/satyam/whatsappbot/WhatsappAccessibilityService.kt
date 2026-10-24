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
import com.satyam.whatsappbot.domain.SelfNote
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
    private var fallbackField = ""
    // Text per editable field, so another field (e.g. the emoji search box) going empty isn't mistaken for a send.
    private val fieldText = HashMap<String, String>()
    // contact + message -> when it was last processed; the same message is handled at most once per 10 minutes.
    private val recentlyProcessed = HashMap<String, Long>()
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
        running = this
        AccessibilityNudge.clear(this)
        // Loading the model takes ~15 s on a phone; do it now so the first message is not delayed.
        detectionExecutor.execute { semanticClassifier.warmUp() }
    }

    private fun processCapturedMessage(message: String, contact: String) {
        val now = System.currentTimeMillis()
        recentlyProcessed.entries.removeAll { now - it.value > DUPLICATE_WINDOW_MS }
        val key = contact + "\u0000" + message.trim()
        if (key in recentlyProcessed) return
        recentlyProcessed[key] = now
        detectionExecutor.execute { detectAndSuggest(message, contact) }
    }

    private fun detectAndSuggest(message: String, contact: String) {
        val safeContact = contact.ifBlank { "Unknown contact" }
        ManualReminder.extract(message)?.let { text ->
            val (time, repeat) = whenToRemind(text)
            reminderRepository.insertPending(message, safeContact, ManualReminder.MATCHED_PHRASE, time, title = text, alarm = ReminderSettings.alarmByDefault(this), recurrence = repeat) { reminder ->
                ReminderNotifications.showConfirmation(this, reminder)
            }
            return
        }

        // A note in your own chat with a time or a repeat is a reminder, whatever its wording.
        if (SelfNote.isSelfChat(contact)) {
            val now = LocalDateTime.now()
            val parser = timeParser()
            if (parser.parse(message, now) != null || parser.parseRecurrence(message, now) != null) {
                val (time, repeat) = whenToRemind(message)
                reminderRepository.insertPending(message, safeContact, SelfNote.MATCHED_PHRASE, time, alarm = ReminderSettings.alarmByDefault(this), recurrence = repeat) { reminder ->
                    ReminderNotifications.showConfirmation(this, reminder)
                }
                return
            }
        }

        // Phrase list first; the on-device model only sees messages without a clear "not a promise" signal.
        val match = detector.detect(message)
            ?: semanticClassifier
                .takeIf { !detector.rejects(message) && (detector.hasCommitmentCue(message) || timeParser().parse(message, LocalDateTime.now()) != null) }
                ?.classify(message)
                ?.takeIf { it.isCommitment }
                ?.let { CommitmentMatch(message, "semantic AI (${"%.2f".format(it.confidence)})") }
            ?: return
        val (time, repeat) = whenToRemind(message)
        reminderRepository.insertPending(message, safeContact, match.matchedPhrase, time, alarm = ReminderSettings.alarmByDefault(this), recurrence = repeat) { reminder ->
            ReminderNotifications.showConfirmation(this, reminder)
        }
    }

    /** The first reminder time, and the encoded repeat ("every monday", "roz subah") or empty for a one-time reminder. */
    private fun whenToRemind(text: String): Pair<LocalDateTime, String> {
        val now = LocalDateTime.now()
        val parser = timeParser()
        val repeat = parser.parseRecurrence(text, now)
        return (repeat?.next(now) ?: parser.parseOrDefault(text, now).dateTime) to repeat?.encode().orEmpty()
    }

    override fun onDestroy() {
        if (running === this) running = null
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
        val source = event.source ?: return
        if (!source.isEditable) return
        val field = fieldKey(source)
        val text = if (source.isShowingHintText) "" else event.text.joinToString("")
        val previous = fieldText[field].orEmpty()
        fieldText[field] = text
        if (text.isNotEmpty()) lastInputText = text
        // This same field emptied: usually a send. Used if no send-button click is seen (see captureFallbackIfEligible).
        if (previous.isNotEmpty() && text.isEmpty()) {
            fallbackCandidate = previous
            fallbackContact = currentContact
            fallbackField = field
            handler.postDelayed({ captureFallbackIfEligible() }, 1000L)
        }
    }

    private fun fieldKey(node: AccessibilityNodeInfo) = node.viewIdResourceName ?: "${node.windowId}:${node.hashCode()}"

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
        // Only a real send leaves the field empty a second later; opening the emoji panel or switching fields doesn't.
        if (!sendWasRecentForSameContact && sameContact && fieldIsEmptyNow(fallbackField)) {
            processCapturedMessage(candidate, fallbackContact)
        }
        fallbackCandidate = null
        fallbackContact = ""
    }

    /** True only if the field can still be found on screen and holds no text (a placeholder like "Message" counts as empty). */
    private fun fieldIsEmptyNow(field: String): Boolean {
        val node = findField(rootInActiveWindow ?: return false, field) ?: return false
        return node.isShowingHintText || node.text.isNullOrEmpty()
    }

    private fun findField(node: AccessibilityNodeInfo, field: String): AccessibilityNodeInfo? {
        if (node.isEditable && fieldKey(node) == field) return node
        for (index in 0 until node.childCount) {
            findField(node.getChild(index) ?: continue, field)?.let { return it }
        }
        return null
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

    companion object {
        private const val DUPLICATE_WINDOW_MS = 10 * 60 * 1000L

        @Volatile private var running: WhatsappAccessibilityService? = null

        /**
         * Really switches the service off in Android's Accessibility settings, so banking and UPI apps that refuse to
         * run next to an enabled accessibility service stop complaining. The user turns it back on in Accessibility.
         * Returns false if the service isn't connected right now.
         */
        fun turnOff(): Boolean {
            val service = running ?: return false
            service.disableSelf()
            return true
        }
    }
}
