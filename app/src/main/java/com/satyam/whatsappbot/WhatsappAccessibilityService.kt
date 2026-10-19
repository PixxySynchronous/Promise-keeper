package com.satyam.whatsappbot

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.satyam.whatsappbot.data.ReminderRepository
import com.satyam.whatsappbot.domain.RuleBasedCommitmentDetector
import com.satyam.whatsappbot.domain.OnDeviceSemanticClassifier
import com.satyam.whatsappbot.domain.CommitmentMatch
import com.satyam.whatsappbot.domain.ManualReminder
import com.satyam.whatsappbot.domain.TimeParser
import com.satyam.whatsappbot.domain.DefaultTimes
import java.time.LocalDateTime
import java.util.concurrent.Executors

class WhatsappAccessibilityService : AccessibilityService() {
    companion object {
        private var activeService: WhatsappAccessibilityService? = null

        fun requestNodeDump() {
            activeService?.dumpCurrentWindow()
                ?: DebugLogStore.add("NODE_DUMP_FAILED reason=service_not_connected")
        }
    }

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
        activeService = this
        // Loading the model takes ~15 s on a phone; do it now so the first message is not delayed.
        detectionExecutor.execute { semanticClassifier.warmUp() }
        DebugLogStore.add("SERVICE_CONNECTED packageScope=com.whatsapp,com.whatsapp.w4b")
    }

    private fun processCapturedMessage(message: String, contact: String) {
        detectionExecutor.execute { detectAndSuggest(message, contact) }
    }

    private fun detectAndSuggest(message: String, contact: String) {
        ManualReminder.extract(message)?.let { text ->
            val parsedTime = timeParser().parseOrDefault(text, LocalDateTime.now())
            val safeContact = contact.ifBlank { "Unknown contact" }
            reminderRepository.insertPending(message, safeContact, ManualReminder.MATCHED_PHRASE, parsedTime.dateTime, title = text) { reminder ->
                ReminderNotifications.showConfirmation(this, reminder)
            }
            DebugLogStore.add("MANUAL_REMINDER time=${parsedTime.dateTime} contact=$safeContact")
            return
        }

        // Phrase list first; the on-device model only sees messages without a clear "not a promise" signal.
        val match = detector.detect(message)
            ?: semanticClassifier.takeUnless { detector.rejects(message) }?.classify(message)
                ?.takeIf { it.isCommitment }
                ?.let { CommitmentMatch(message, "semantic AI (${"%.2f".format(it.confidence)})") }
        if (match == null) {
            DebugLogStore.add("COMMITMENT_UNMATCHED text=${DebugLogStore.safeText(message)}")
            return
        }
        val parsedTime = timeParser().parseOrDefault(message, LocalDateTime.now())
        val safeContact = contact.ifBlank { "Unknown contact" }
        reminderRepository.insertPending(message, safeContact, match.matchedPhrase, parsedTime.dateTime) { reminder ->
            ReminderNotifications.showConfirmation(this, reminder)
        }
        DebugLogStore.add("COMMITMENT_DETECTED phrase=${match.matchedPhrase} time=${parsedTime.dateTime} contact=$safeContact")
    }

    override fun onDestroy() {
        if (activeService === this) activeService = null
        detectionExecutor.shutdown()
        super.onDestroy()
    }

    private fun dumpCurrentWindow() {
        val root = rootInActiveWindow
        DebugLogStore.add("NODE_DUMP_BEGIN")
        ViewNodeDumper.dump(root).forEach(DebugLogStore::add)
        root?.recycle()
        DebugLogStore.add("NODE_DUMP_END")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.packageName?.toString() !in setOf("com.whatsapp", "com.whatsapp.w4b")) return
        val source = event.source
        val sourceId = source?.viewIdResourceName ?: "<none>"
        val className = source?.className ?: "<none>"
        DebugLogStore.add("EVENT type=${eventTypeName(event.eventType)} id=$sourceId class=$className")

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val contact = findContact(event.source)
                if (contact.isNotEmpty() && contact != currentContact) {
                    currentContact = contact
                    DebugLogStore.add("CONTACT_CHANGED label=$contact")
                }
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> handleTextChanged(event)
            AccessibilityEvent.TYPE_VIEW_CLICKED -> handleClicked(event)
        }
        source?.let { node ->
            DebugLogStore.add("SOURCE id=$sourceId class=$className text=${if (node.isEditable) DebugLogStore.safeText(node.text) else DebugLogStore.maskedText(node.text)}")
            node.recycle()
        }
    }

    private fun handleTextChanged(event: AccessibilityEvent) {
        val text = event.text.joinToString("")
        val id = event.source?.viewIdResourceName.orEmpty()
        val isInput = id == WhatsappConfig.inputViewId || event.source?.isEditable == true
        if (!isInput) return
        val previous = lastInputText
        lastInputText = text
        DebugLogStore.add("INPUT_CHANGED text=${DebugLogStore.safeText(text)} id=${id.ifEmpty { "<none>" }}")
        if (previous.isNotEmpty() && text.isEmpty()) {
            fallbackCandidate = previous
            fallbackContact = currentContact
            handler.postDelayed({ captureFallbackIfEligible() }, 1000L)
        }
    }

    private fun handleClicked(event: AccessibilityEvent) {
        val id = event.source?.viewIdResourceName.orEmpty()
        val description = event.contentDescription?.toString().orEmpty()
        val isSend = id == WhatsappConfig.sendButtonViewId || description.contains("send", ignoreCase = true)
        if (!isSend || lastInputText.isEmpty()) return
        lastSendClickAt = System.currentTimeMillis()
        lastSendClickContact = currentContact
        DebugLogStore.add("SEND_CLICK_CAPTURE text=${DebugLogStore.safeText(lastInputText)} contact=$currentContact id=${id.ifEmpty { "<none>" }}")
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
        if (!sendWasRecentForSameContact && sameContact) {
            DebugLogStore.add("FALLBACK_CAPTURE text=${DebugLogStore.safeText(candidate)} contact=$fallbackContact reason=input_emptied_without_recent_send_click")
            processCapturedMessage(candidate, fallbackContact)
        } else {
            DebugLogStore.add("FALLBACK_IGNORED reason=${if (!sameContact) "contact_changed" else "send_click_seen"}")
        }
        fallbackCandidate = null
        fallbackContact = ""
    }

    private fun findContact(root: AccessibilityNodeInfo?): String {
        if (root == null) return ""
        if (root.viewIdResourceName == WhatsappConfig.contactViewId && !root.text.isNullOrEmpty()) return root.text.toString()
        for (index in 0 until root.childCount) {
            val child = root.getChild(index) ?: continue
            val result = findContact(child)
            child.recycle()
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

    private fun eventTypeName(type: Int): String = when (type) {
        AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> "TEXT_CHANGED"
        AccessibilityEvent.TYPE_VIEW_CLICKED -> "CLICKED"
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "WINDOW_STATE_CHANGED"
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "WINDOW_CONTENT_CHANGED"
        else -> type.toString()
    }

    override fun onInterrupt() {
        DebugLogStore.add("SERVICE_INTERRUPTED")
    }
}
