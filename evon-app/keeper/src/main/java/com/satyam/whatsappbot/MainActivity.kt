package com.satyam.whatsappbot

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
// Aliased: android.provider.Settings is also used in this file.
import androidx.compose.material.icons.filled.Settings as SettingsGear
import androidx.compose.ui.text.withStyle
import androidx.core.app.NotificationManagerCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.core.content.FileProvider
import com.satyam.whatsappbot.domain.FalseAlarmExport
import com.satyam.whatsappbot.domain.ManualReminder
import com.satyam.whatsappbot.domain.Recurrence
import java.io.File
import java.time.DayOfWeek
import java.time.format.TextStyle
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Switch
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.material3.Surface
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.satyam.whatsappbot.data.ReminderRepository
import com.satyam.whatsappbot.data.ReminderEntity
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.rememberDatePickerState
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val repository by lazy { ReminderRepository(this) }
    private var reminders by mutableStateOf<List<ReminderEntity>>(emptyList())
    private var timeEditing by mutableStateOf<ReminderEntity?>(null)
    private val setupPrefs by lazy { getSharedPreferences("setup", MODE_PRIVATE) }
    private var setupStatus by mutableStateOf(SetupStatus(notifications = true, accessibility = true, exactAlarms = true, battery = true))
    private var showSetup by mutableStateOf(false)
    // Opened from the home screen's feature carousel; Back returns there.
    private var showFeatures by mutableStateOf(false)
    private var whatsNew by mutableStateOf<List<WhatsNew.Release>>(emptyList())
    private var askAppreciation by mutableStateOf(false)
    private var appreciationChecked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Transparent system bars, so the dark theme reaches the screen edges instead of a light navigation bar.
        // The app is always dark, so force light status/navigation icons even when the phone is in light mode.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        ReminderNotifications.createChannels(this)
        AccessibilityNudge.schedule(this)
        UpdateReminder.schedule(this)
        setupStatus = readSetupStatus(this)
        showSetup = !setupPrefs.getBoolean(KEY_SETUP_FINISHED, false)
        whatsNew = unseenReleases()
        setContent {
            PromiseKeeperTheme {
                if (showFeatures) {
                    BackHandler { showFeatures = false }
                    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                        FeaturesScreen(back = { showFeatures = false })
                    }
                } else if (showSetup) {
                    // Back returns to the reminders once setup has been finished before; on first run it leaves the app.
                    BackHandler(enabled = setupPrefs.getBoolean(KEY_SETUP_FINISHED, false)) { showSetup = false }
                    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                        SetupGuide(setupStatus, onFinish = ::finishSetup, refreshStatus = { setupStatus = readSetupStatus(this) })
                    }
                } else {
                    HomeScreen(
                        reminders, setupStatus, timeEditing, ::refreshReminders,
                        openSetup = { showSetup = true },
                        editTime = { timeEditing = it },
                        cancelTimeEdit = { timeEditing = null },
                        editTitle = ::editTitle,
                        delete = ::deleteReminder,
                        confirm = ::confirmReminder,
                        notPromise = ::markNotPromise,
                        noReminderNeeded = ::markNoReminderNeeded,
                        toggleAlarm = ::setAlarm,
                        restoreFalseAlarm = ::restoreFalseAlarm,
                        deleteAllFalseAlarms = ::deleteAllFalseAlarms,
                        deleteReminders = ::deleteReminders,
                        exportFalseAlarms = ::exportFalseAlarms,
                        contributeFalseAlarms = ::contributeFalseAlarms,
                        saveReminder = ::saveReminder,
                        switchRepeating = ::switchRepeating,
                        openFeatures = { showFeatures = true }
                    )
                    if (whatsNew.isNotEmpty()) WhatsNewDialog(whatsNew) { whatsNew = emptyList() }
                    else if (askAppreciation) Appreciation.Dialog { askAppreciation = false }
                }
            }
        }
        handleIntent(intent)
        refreshReminders()
    }

    override fun onResume() {
        super.onResume()
        setupStatus = readSetupStatus(this)
        refreshReminders()
    }

    private fun finishSetup() {
        setupPrefs.edit().putBoolean(KEY_SETUP_FINISHED, true).apply()
        showSetup = false
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /**
     * Release notes the user hasn't seen, marked seen straight away so they show once. A fresh install sees the setup
     * guide instead. Users updating from before this existed (setup finished, nothing stored) see this version's notes.
     */
    private fun unseenReleases(): List<WhatsNew.Release> {
        val current = packageManager.getPackageInfo(packageName, 0).longVersionCode.toInt()
        val setupFinished = setupPrefs.getBoolean(KEY_SETUP_FINISHED, false)
        val lastSeen = setupPrefs.getInt(KEY_LAST_SEEN_VERSION, if (setupFinished) current - 1 else current)
        setupPrefs.edit().putInt(KEY_LAST_SEEN_VERSION, current).apply()
        return WhatsNew.since(lastSeen).filter { it.versionCode <= current }
    }

    private fun handleIntent(intent: Intent?) {
        // From the daily "switched off" notification.
        if (intent?.action == AccessibilityNudge.ACTION_OPEN_SETUP) {
            showSetup = true
            return
        }
        if (intent?.action != ReminderNotifications.ACTION_EDIT) return
        val id = intent.getLongExtra(ReminderNotifications.EXTRA_REMINDER_ID, 0L)
        if (id == 0L) return
        repository.get(id) { reminder ->
            if (reminder != null) runOnUiThread { timeEditing = reminder }
        }
    }

    private fun refreshReminders() = repository.getAll {
        runOnUiThread {
            reminders = it
            // The "Enjoying Promise Keeper?" prompt: decided once per launch, never during setup or over What's new.
            if (!appreciationChecked) {
                appreciationChecked = true
                askAppreciation = !showSetup && whatsNew.isEmpty() && Appreciation.shouldAsk(this, it)
            }
        }
    }

    private fun markNotPromise(reminder: ReminderEntity) {
        ReminderNotifications.cancel(this, reminder.id)
        NotificationManagerCompat.from(this).cancel(ReminderNotifications.confirmationNotificationId(reminder.id))
        repository.markFalseAlarm(reminder.id)
        refreshReminders()
    }

    // The alarm flag is read when the reminder fires, so nothing needs rescheduling.
    private fun setAlarm(reminder: ReminderEntity, alarm: Boolean) {
        repository.setAlarm(reminder.id, alarm)
        if (alarm && !ReminderNotifications.canShowFullScreenAlarms(this)) {
            Toast.makeText(this, "It will ring as a pop-up. Allow full-screen alarms in Settings to take over the lock screen.", Toast.LENGTH_LONG).show()
        }
        refreshReminders()
    }

    // A real promise the user doesn't want a reminder for: dismissed, not counted as a false alarm.
    private fun markNoReminderNeeded(reminder: ReminderEntity) {
        ReminderNotifications.cancel(this, reminder.id)
        NotificationManagerCompat.from(this).cancel(ReminderNotifications.confirmationNotificationId(reminder.id))
        repository.markIgnored(reminder.id)
        refreshReminders()
    }

    private fun restoreFalseAlarm(reminder: ReminderEntity) {
        repository.restoreFromFalseAlarm(reminder.id)
        refreshReminders()
    }

    /** Delete all reminders in a tab: cancels their alarms and notifications, then removes them. */
    private fun deleteReminders(reminders: List<ReminderEntity>) {
        val notifications = NotificationManagerCompat.from(this)
        reminders.forEach {
            ReminderNotifications.cancel(this, it.id)
            notifications.cancel(ReminderNotifications.confirmationNotificationId(it.id))
            notifications.cancel(ReminderNotifications.reminderNotificationId(it.id))
        }
        repository.deleteAll(reminders.map { it.id }) { runOnUiThread { refreshReminders() } }
    }

    private fun deleteAllFalseAlarms() = repository.deleteFalseAlarms { runOnUiThread { refreshReminders() } }

    /**
     * Shares the false alarms as a JSON file through the system share sheet (the app itself has no internet access).
     * Only message text and what flagged it are included, never contact names.
     */
    private fun falseAlarmsJson(items: List<ReminderEntity>): String {
        val zone = ZoneId.systemDefault()
        val format = DateTimeFormatter.ISO_LOCAL_DATE_TIME
        return FalseAlarmExport.toJson(
            items.map {
                FalseAlarmExport.Item(it.message, it.matchedPhrase, Instant.ofEpochMilli(it.createdAtEpochMillis).atZone(zone).toLocalDateTime().withNano(0).format(format))
            },
            appVersion = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty(),
            exportedAt = LocalDateTime.now().withNano(0).format(format)
        )
    }

    /**
     * The easiest way to contribute: copies the export to the clipboard and opens the "Share false alarms" GitHub
     * form in the browser, where the user pastes it and submits. Nothing is sent by the app itself.
     */
    private fun contributeFalseAlarms(items: List<ReminderEntity>) {
        getSystemService(android.content.ClipboardManager::class.java)
            .setPrimaryClip(android.content.ClipData.newPlainText("Promise Keeper false alarms", falseAlarmsJson(items)))
        Toast.makeText(this, "Copied. In the form, long-press the box and tap Paste, then Submit.", Toast.LENGTH_LONG).show()
        openInBrowser(this, WhatsNew.FALSE_ALARMS_FORM_URL)
    }

    private fun exportFalseAlarms(items: List<ReminderEntity>) {
        val json = falseAlarmsJson(items)
        val directory = File(cacheDir, "exports").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val file = File(directory, "promise-keeper-false-alarms-${LocalDate.now()}.json").apply { writeText(json) }
        val uri = FileProvider.getUriForFile(this, "$packageName.exports", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Promise Keeper false alarms")
            putExtra(
                Intent.EXTRA_TEXT,
                "${items.size} messages Promise Keeper wrongly flagged as promises. To help improve it, attach this file " +
                    "to the form at ${WhatsNew.FALSE_ALARMS_FORM_URL}"
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Share false alarms"))
    }

    // Same as the notification's "Set". If the suggested time has already passed, a repeating reminder starts from its
    // next occurrence; a one-time one asks for a new time.
    private fun confirmReminder(suggested: ReminderEntity) {
        var reminder = suggested
        if (reminder.reminderAtEpochMillis <= System.currentTimeMillis()) {
            val next = ReminderNotifications.nextOccurrence(reminder) ?: run { timeEditing = reminder; return }
            reminder = reminder.copy(reminderAtEpochMillis = next)
        }
        if (ReminderNotifications.schedule(this, reminder)) {
            repository.updateTimeAndSchedule(reminder.id, reminder.reminderAtEpochMillis)
            NotificationManagerCompat.from(this).cancel(ReminderNotifications.confirmationNotificationId(reminder.id))
            refreshReminders()
        }
    }

    /** From the reminder form: a new reminder made in the app ([existing] null), or an edit of any reminder. */
    private fun saveReminder(existing: ReminderEntity?, title: String, first: LocalDateTime, repeat: Recurrence?, alarm: Boolean) {
        timeEditing = null
        val millis = first.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val encoded = repeat?.encode().orEmpty()
        if (existing == null) {
            repository.insertPending(
                title, contact = "", ReminderEntity.MADE_IN_APP, first, title = title, alarm = alarm,
                recurrence = encoded, status = ReminderEntity.STATUS_SCHEDULED
            ) { saved ->
                runOnUiThread {
                    // Without exact alarms it would never ring; schedule() has opened that setting.
                    if (!ReminderNotifications.schedule(this, saved)) {
                        repository.delete(saved.id)
                        Toast.makeText(this, "Allow alarms & reminders, then save the reminder again.", Toast.LENGTH_LONG).show()
                    }
                    refreshReminders()
                }
            }
            return
        }
        val updated = existing.copy(title = title, alarm = alarm, recurrence = encoded, reminderAtEpochMillis = millis)
        if (ReminderNotifications.schedule(this, updated)) {
            repository.updateAndSchedule(existing.id, title, alarm, encoded, millis) { runOnUiThread { refreshReminders() } }
            NotificationManagerCompat.from(this).cancel(ReminderNotifications.confirmationNotificationId(existing.id))
        }
    }

    // Off keeps the repeating reminder but cancels its alarm; on books the next occurrence from now.
    private fun switchRepeating(reminder: ReminderEntity, on: Boolean) {
        if (on) {
            val next = ReminderNotifications.nextOccurrence(reminder.copy(reminderAtEpochMillis = 0)) ?: return
            if (ReminderNotifications.schedule(this, reminder.copy(reminderAtEpochMillis = next))) repository.updateTimeAndSchedule(reminder.id, next)
        } else {
            ReminderNotifications.cancel(this, reminder.id)
            NotificationManagerCompat.from(this).cancel(ReminderNotifications.reminderNotificationId(reminder.id))
            repository.markPaused(reminder.id)
        }
        refreshReminders()
    }

    private fun editTitle(reminder: ReminderEntity, title: String) {
        repository.updateTitle(reminder.id, title.ifBlank { reminder.message })
        refreshReminders()
    }

    // Removes the reminder and its message from the database, its alarm, and any notification still showing it.
    private fun deleteReminder(reminder: ReminderEntity) {
        ReminderNotifications.cancel(this, reminder.id)
        NotificationManagerCompat.from(this).apply {
            cancel(ReminderNotifications.confirmationNotificationId(reminder.id))
            cancel(ReminderNotifications.reminderNotificationId(reminder.id))
        }
        repository.delete(reminder.id)
        refreshReminders()
    }

    private companion object {
        const val KEY_SETUP_FINISHED = "finished"
        const val KEY_LAST_SEEN_VERSION = "last_seen_version"
    }
}

@androidx.compose.runtime.Composable
fun PromiseKeeperTheme(content: @androidx.compose.runtime.Composable () -> Unit) {
    val colors = darkColorScheme(
        primary = Color(0xFFFF4FA3),
        onPrimary = Color(0xFF210A2C),
        secondary = Color(0xFF42E8FF),
        onSecondary = Color(0xFF07151F),
        background = Color(0xFF100A1C),
        surface = Color(0xFF1A1029),
        surfaceVariant = Color(0xFF28153D),
        onSurface = Color(0xFFFFF2FC),
        onSurfaceVariant = Color(0xFFC9A9D7),
        outlineVariant = Color(0xFF59356B)
    )
    MaterialTheme(colorScheme = colors, content = content)
}

@androidx.compose.runtime.Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun HomeScreen(
    reminders: List<ReminderEntity>,
    setupStatus: SetupStatus,
    timeEditing: ReminderEntity?,
    refresh: () -> Unit,
    openSetup: () -> Unit,
    editTime: (ReminderEntity) -> Unit,
    cancelTimeEdit: () -> Unit,
    editTitle: (ReminderEntity, String) -> Unit,
    delete: (ReminderEntity) -> Unit,
    confirm: (ReminderEntity) -> Unit,
    notPromise: (ReminderEntity) -> Unit,
    noReminderNeeded: (ReminderEntity) -> Unit,
    toggleAlarm: (ReminderEntity, Boolean) -> Unit,
    restoreFalseAlarm: (ReminderEntity) -> Unit,
    deleteAllFalseAlarms: () -> Unit,
    deleteReminders: (List<ReminderEntity>) -> Unit,
    exportFalseAlarms: (List<ReminderEntity>) -> Unit,
    contributeFalseAlarms: (List<ReminderEntity>) -> Unit,
    saveReminder: (ReminderEntity?, String, LocalDateTime, Recurrence?, Boolean) -> Unit,
    switchRepeating: (ReminderEntity, Boolean) -> Unit,
    openFeatures: () -> Unit
) {
    var editingTitle by androidx.compose.runtime.remember { mutableStateOf<ReminderEntity?>(null) }
    var pendingDelete by androidx.compose.runtime.remember { mutableStateOf<ReminderEntity?>(null) }
    var creating by androidx.compose.runtime.remember { mutableStateOf(false) }
    var verifying by androidx.compose.runtime.remember { mutableStateOf(false) }
    // Suggestions waiting for Set / Skip are not active reminders yet; keep the two groups apart.
    val toReview = reminders.filter { it.status == ReminderEntity.STATUS_PENDING_CONFIRMATION }
    // Switched-off repeating reminders stay here, so they can be switched back on.
    val active = reminders.filter { it.status in setOf(ReminderEntity.STATUS_SCHEDULED, ReminderEntity.STATUS_SNOOZED, ReminderEntity.STATUS_PAUSED) }
    val falseAlarms = reminders.filter { it.status == ReminderEntity.STATUS_FALSE_ALARM }.sortedByDescending { it.createdAtEpochMillis }
    // Tabs: 0 To review, 1 Active, 2 False alarms. Opens on To review when there's something to review,
    // until the user picks a tab themselves.
    var tab by androidx.compose.runtime.remember { mutableStateOf(TAB_ACTIVE) }
    var tabChosen by androidx.compose.runtime.remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(toReview.isNotEmpty()) {
        if (!tabChosen && toReview.isNotEmpty()) tab = TAB_REVIEW
    }
    val selectTab: (Int) -> Unit = { tab = it; tabChosen = true }
    var skipping by androidx.compose.runtime.remember { mutableStateOf<ReminderEntity?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "Promise Keeper",
                            style = MaterialTheme.typography.headlineSmall.copy(
                                fontFamily = FontFamily.Cursive,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                letterSpacing = 1.5.sp,
                                shadow = Shadow(Color(0xFFFF4FA3), Offset(0f, 0f), 18f)
                            ),
                            color = Color(0xFFFFD4F1),
                            maxLines = 1
                        )
                        // The whole line is the tap target for "Verify".
                        Text(
                            androidx.compose.ui.text.buildAnnotatedString {
                                append("Your data never leaves your phone · ")
                                withStyle(androidx.compose.ui.text.SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)) { append("Verify ›") }
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.secondary,
                            maxLines = 2,
                            modifier = Modifier.clickable { verifying = true }.padding(vertical = 2.dp)
                        )
                    }
                },
                actions = {
                    androidx.compose.material3.IconButton(onClick = openSetup) {
                        androidx.compose.material3.Icon(Icons.Filled.SettingsGear, contentDescription = "Settings", tint = MaterialTheme.colorScheme.primary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { creating = true }, text = { Text("New reminder") }, icon = { Text("+", style = MaterialTheme.typography.titleLarge) })
        }
    ) { insets ->
        Column(modifier = Modifier.fillMaxSize().padding(insets).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // The tabs below already show the counts; this space shows what the app can do.
            FeatureCarousel(openAll = openFeatures)
            SetupBanner(setupStatus, openSetup)
            TabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background) {
                Tab(selected = tab == TAB_REVIEW, onClick = { selectTab(TAB_REVIEW) }, text = { TabLabel("To review", toReview.size) })
                Tab(selected = tab == TAB_ACTIVE, onClick = { selectTab(TAB_ACTIVE) }, text = { TabLabel("Active", active.size) })
                Tab(selected = tab == TAB_FALSE, onClick = { selectTab(TAB_FALSE) }, text = { TabLabel("False alarms", falseAlarms.size) })
            }
            when (tab) {
                TAB_REVIEW -> ReminderList(
                    toReview, "Nothing to review", "When a promise is detected, it appears here. Tap Set reminder or Skip.",
                    refresh, Modifier.weight(1f),
                    deleteAllTitle = "Delete all ${toReview.size} suggestions to review?",
                    onDeleteAll = { deleteReminders(toReview) }
                ) { reminder -> ReminderRow(reminder, confirm, editTime, { editingTitle = reminder }, { pendingDelete = reminder }, { skipping = it }, toggleAlarm, switchRepeating) }
                TAB_ACTIVE -> ReminderList(
                    active, "No reminders set", "Reminders you set appear here, soonest first. Tap New reminder to make one yourself.",
                    refresh, Modifier.weight(1f),
                    deleteAllTitle = "Delete all ${active.size} active reminders?",
                    onDeleteAll = { deleteReminders(active) }
                ) { reminder -> ReminderRow(reminder, confirm, editTime, { editingTitle = reminder }, { pendingDelete = reminder }, { skipping = it }, toggleAlarm, switchRepeating) }
                else -> FalseAlarmsTab(falseAlarms, restoreFalseAlarm, { pendingDelete = it }, deleteAllFalseAlarms, exportFalseAlarms, contributeFalseAlarms, Modifier.weight(1f))
            }
        }
    }
    editingTitle?.let { reminder ->
        var title by androidx.compose.runtime.remember(reminder.id) { mutableStateOf(reminder.title) }
        AlertDialog(
            onDismissRequest = { editingTitle = null },
            title = { Text("Edit reminder title") },
            text = { TextField(value = title, onValueChange = { title = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { editTitle(reminder, title); editingTitle = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { editingTitle = null }) { Text("Cancel") } }
        )
    }
    pendingDelete?.let { reminder ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete reminder?") },
            text = { Text("This cancels the alarm and permanently removes the reminder and its message from your phone.") },
            confirmButton = {
                TextButton(onClick = {
                    delete(reminder)
                    pendingDelete = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } }
        )
    }
    // Every reminder is edited in the full form, so any of them can be made repeating.
    timeEditing?.let { reminder -> ReminderEditorDialog(reminder, saveReminder, cancelTimeEdit) }
    if (verifying) VerifyPrivacyDialog { verifying = false }
    if (creating) ReminderEditorDialog(null, { existing, title, first, repeat, alarm -> creating = false; saveReminder(existing, title, first, repeat, alarm) }, { creating = false })
    // Same choice as the notification's Skip: only "Not a promise" counts as a false alarm.
    skipping?.let { reminder ->
        AlertDialog(
            onDismissRequest = { skipping = null },
            title = { Text("Why skip it?") },
            text = { Text("Was this a promise? Your answer helps Promise Keeper learn.") },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { notPromise(reminder); skipping = null }) { Text("Not a promise") }
                    TextButton(onClick = { noReminderNeeded(reminder); skipping = null }) { Text("It was, but no reminder needed") }
                    TextButton(onClick = { skipping = null }) { Text("Cancel") }
                }
            }
        )
    }
}

/**
 * "Verify ›" under the title: two checks anyone can do. Android's Permissions screen doesn't list internet access
 * for any app, so the checks are data used (always 0 B) and the public manifest, which has no INTERNET permission.
 */
@androidx.compose.runtime.Composable
private fun VerifyPrivacyDialog(dismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Check it yourself") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Promise Keeper never asks Android for internet access, so Android itself stops it from going online. " +
                        "Your messages are read and processed only on this phone.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text("1. It has never used any data", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Tap Open app info, then Mobile data (or Data usage, depending on your phone). It shows 0 B used, on mobile data and Wi-Fi.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text("2. Read the code", style = MaterialTheme.typography.titleSmall)
                Text(
                    "All of Promise Keeper's code is public. Its list of permissions (AndroidManifest.xml) has no INTERNET line. Anyone can check, or build the app from the code.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(MANIFEST_URL, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${context.packageName}")))
                }) { Text("Open app info") }
                TextButton(onClick = { openInBrowser(context, MANIFEST_URL) }) { Text("See the code on GitHub") }
                TextButton(onClick = dismiss) { Text("OK") }
            }
        }
    )
}

private const val MANIFEST_URL = "https://github.com/PixxySynchronous/Promise-keeper/blob/main/app/src/main/AndroidManifest.xml"

@androidx.compose.runtime.Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun FutureDatePicker(date: LocalDate, today: LocalDate, pick: (LocalDate) -> Unit, dismiss: () -> Unit) {
    // DatePicker works in UTC midnight millis; past days are not selectable.
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) =
                !Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate().isBefore(today)
            override fun isSelectableYear(year: Int) = year >= today.year
        }
    )
    DatePickerDialog(
        onDismissRequest = dismiss,
        confirmButton = {
            TextButton(onClick = {
                pickerState.selectedDateMillis?.let { pick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) } ?: dismiss()
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } }
    ) { DatePicker(state = pickerState) }
}

/**
 * New reminder (from the New reminder button, [existing] null) or editing one: title, Once / every day / week /
 * month / year, the days for weekly, a date (the day of the month or year for monthly and yearly), time, and
 * notification or alarm.
 */
@androidx.compose.runtime.Composable
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
private fun ReminderEditorDialog(
    existing: ReminderEntity?,
    save: (ReminderEntity?, String, LocalDateTime, Recurrence?, Boolean) -> Unit,
    cancel: () -> Unit
) {
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val existingRepeat = existing?.repeat
    // A new reminder starts at the next full hour.
    val start = existing?.let { Instant.ofEpochMilli(it.reminderAtEpochMillis).atZone(zone).toLocalDateTime() }
        ?: LocalDateTime.now(zone).plusHours(1).withMinute(0)
    var title by androidx.compose.runtime.remember { mutableStateOf(existing?.title.orEmpty()) }
    // null means once.
    var frequency by androidx.compose.runtime.remember { mutableStateOf(existingRepeat?.frequency) }
    var date by androidx.compose.runtime.remember { mutableStateOf(maxOf(start.toLocalDate(), today)) }
    var weekdays by androidx.compose.runtime.remember { mutableStateOf(existingRepeat?.weekdays?.takeIf { it.isNotEmpty() } ?: setOf(start.dayOfWeek)) }
    var alarm by androidx.compose.runtime.remember { mutableStateOf(existing?.alarm ?: ReminderSettings.alarmByDefault(context)) }
    var pickingDate by androidx.compose.runtime.remember { mutableStateOf(false) }
    val initialTime = existingRepeat?.time ?: start.toLocalTime()
    val timeState = rememberTimePickerState(initialHour = initialTime.hour, initialMinute = initialTime.minute, is24Hour = false)
    val time = LocalTime.of(timeState.hour, timeState.minute)
    val repeat = when (frequency) {
        null -> null
        Recurrence.Frequency.DAILY -> Recurrence(Recurrence.Frequency.DAILY, time)
        Recurrence.Frequency.WEEKLY -> Recurrence(Recurrence.Frequency.WEEKLY, time, weekdays = weekdays)
        // An unchanged date keeps the original day, so "the 31st" isn't turned into the 30th by a short month.
        Recurrence.Frequency.MONTHLY -> Recurrence(
            Recurrence.Frequency.MONTHLY, time,
            dayOfMonth = if (existingRepeat?.frequency == Recurrence.Frequency.MONTHLY && date == start.toLocalDate()) existingRepeat.dayOfMonth else date.dayOfMonth
        )
        Recurrence.Frequency.YEARLY -> Recurrence(Recurrence.Frequency.YEARLY, time, dayOfMonth = date.dayOfMonth, month = date.monthValue)
    }
    val now = LocalDateTime.now(zone)
    // Weekly with no day picked has no next occurrence; Save stays disabled then.
    val first = if (repeat == null || (frequency == Recurrence.Frequency.WEEKLY && weekdays.isEmpty())) LocalDateTime.of(date, time) else repeat.next(now)
    val problem = when {
        frequency == Recurrence.Frequency.WEEKLY && weekdays.isEmpty() -> "Pick at least one day."
        repeat == null && !first.isAfter(now) -> "That time has already passed. Pick a later time or date."
        else -> null
    }

    AlertDialog(
        onDismissRequest = cancel,
        title = { Text(if (existing == null) "New reminder" else "Edit reminder") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TextField(value = title, onValueChange = { title = it }, singleLine = true, label = { Text("Remind me to…") }, modifier = Modifier.fillMaxWidth())
                Text("Repeat", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(null to "Once", Recurrence.Frequency.DAILY to "Every day", Recurrence.Frequency.WEEKLY to "Every week",
                        Recurrence.Frequency.MONTHLY to "Every month", Recurrence.Frequency.YEARLY to "Every year"
                    ).forEach { (value, label) -> FilterChip(selected = frequency == value, onClick = { frequency = value }, label = { Text(label) }) }
                }
                if (frequency == Recurrence.Frequency.WEEKLY) {
                    Text("On", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        DayOfWeek.entries.forEach { day ->
                            FilterChip(
                                selected = day in weekdays,
                                onClick = { weekdays = if (day in weekdays) weekdays - day else weekdays + day },
                                label = { Text(day.getDisplayName(TextStyle.SHORT, Locale.getDefault())) }
                            )
                        }
                    }
                }
                if (frequency != Recurrence.Frequency.DAILY && frequency != Recurrence.Frequency.WEEKLY) {
                    Text(
                        when (frequency) {
                            Recurrence.Frequency.MONTHLY -> "Day of the month"
                            Recurrence.Frequency.YEARLY -> "Date each year"
                            else -> "Date"
                        },
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (frequency == null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = date == today, onClick = { date = today }, label = { Text("Today") })
                            FilterChip(selected = date == today.plusDays(1), onClick = { date = today.plusDays(1) }, label = { Text("Tomorrow") })
                        }
                    }
                    OutlinedButton(onClick = { pickingDate = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            when (frequency) {
                                Recurrence.Frequency.MONTHLY -> "Day ${repeat?.dayOfMonth} of every month"
                                Recurrence.Frequency.YEARLY -> DateTimeFormatter.ofPattern("d MMMM 'every year'", Locale.getDefault()).format(date)
                                else -> DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.getDefault()).format(date)
                            }
                        )
                    }
                }
                Text("Time", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TimeInput(state = timeState)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !alarm, onClick = { alarm = false }, label = { Text("🔔 Notification") })
                    FilterChip(selected = alarm, onClick = { alarm = true }, label = { Text("⏰ Alarm") })
                }
                if (repeat != null && problem == null) {
                    Text(
                        "${repeat.describe()}. First: ${DateTimeFormatter.ofPattern("EEE, d MMM, h:mm a", Locale.getDefault()).format(first)}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary
                    )
                }
                problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = { save(existing, title.trim(), first, repeat, alarm) }, enabled = title.isNotBlank() && problem == null) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = cancel) { Text("Cancel") } }
    )
    if (pickingDate) FutureDatePicker(date, today, { date = it; pickingDate = false }, { pickingDate = false })
}

@androidx.compose.runtime.Composable
@OptIn(ExperimentalLayoutApi::class)
private fun ReminderRow(
    reminder: ReminderEntity, confirm: (ReminderEntity) -> Unit, editTime: (ReminderEntity) -> Unit, editTitle: () -> Unit,
    delete: (ReminderEntity) -> Unit, skip: (ReminderEntity) -> Unit, toggleAlarm: (ReminderEntity, Boolean) -> Unit,
    switchRepeating: (ReminderEntity, Boolean) -> Unit
) {
    val pending = reminder.status == ReminderEntity.STATUS_PENDING_CONFIRMATION
    val repeat = reminder.repeat
    val off = reminder.status == ReminderEntity.STATUS_PAUSED
    // The title starts as the message itself; only show the message separately once it has been renamed.
    // Reminders made in the app have no WhatsApp message to show.
    val renamed = reminder.title.isNotBlank() && reminder.title != reminder.message && reminder.matchedPhrase != ReminderEntity.MADE_IN_APP
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Text(
                    if (renamed) reminder.title else reminder.message,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(12.dp))
                if (repeat != null && !pending) {
                    // On/off for repeating reminders, in place of the status label.
                    Switch(checked = !off, onCheckedChange = { switchRepeating(reminder, it) })
                } else {
                    Text(
                        when (reminder.status) {
                            ReminderEntity.STATUS_PENDING_CONFIRMATION -> "Needs review"
                            ReminderEntity.STATUS_SNOOZED -> "Snoozed"
                            else -> "Set"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = if (pending) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
            val timeColor = when {
                pending -> MaterialTheme.colorScheme.secondary
                off -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.primary
            }
            if (repeat != null) {
                Text("🔁 " + repeat.describe(), style = MaterialTheme.typography.bodyMedium, color = timeColor)
                Text(
                    when {
                        off -> "Switched off"
                        reminder.status == ReminderEntity.STATUS_SNOOZED -> "Snoozed until " + formatReminderTime(reminder.reminderAtEpochMillis)
                        else -> "Next: " + formatReminderTime(reminder.reminderAtEpochMillis)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text((if (pending) "Suggested: " else "Reminds you: ") + formatReminderTime(reminder.reminderAtEpochMillis), style = MaterialTheme.typography.bodyMedium, color = timeColor)
            }
            if (reminder.contact.isNotBlank()) {
                Text("To ${reminder.contact}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !reminder.alarm, onClick = { if (reminder.alarm) toggleAlarm(reminder, false) }, label = { Text("🔔 Notification") })
                FilterChip(selected = reminder.alarm, onClick = { if (!reminder.alarm) toggleAlarm(reminder, true) }, label = { Text("⏰ Alarm") })
            }
            if (renamed) {
                Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Text(reminder.message, modifier = Modifier.fillMaxWidth().padding(10.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
            // Wraps onto a second line on narrow screens instead of squeezing the buttons.
            FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (pending) Button(onClick = { confirm(reminder) }) { Text("Set reminder") }
                TextButton(onClick = { editTime(reminder) }) { Text("Edit") }
                TextButton(onClick = editTitle) { Text("Rename") }
                TextButton(onClick = { delete(reminder) }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                if (pending && reminder.matchedPhrase != ManualReminder.MATCHED_PHRASE) {
                    TextButton(onClick = { skip(reminder) }) { Text("Skip", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun FalseAlarmsTab(
    items: List<ReminderEntity>,
    restore: (ReminderEntity) -> Unit,
    delete: (ReminderEntity) -> Unit,
    deleteAll: () -> Unit,
    export: (List<ReminderEntity>) -> Unit,
    contribute: (List<ReminderEntity>) -> Unit,
    modifier: Modifier
) {
    var confirmExport by androidx.compose.runtime.remember { mutableStateOf(false) }
    var confirmDeleteAll by androidx.compose.runtime.remember { mutableStateOf(false) }
    LazyColumn(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 88.dp)) {
        item {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Messages you marked \"Not a promise\". They stay on this phone. Tap Export to share them on GitHub, " +
                            "where they're used to make detection better for everyone.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { confirmExport = true }, enabled = items.isNotEmpty()) { Text("Export") }
                        OutlinedButton(onClick = { confirmDeleteAll = true }, enabled = items.isNotEmpty()) { Text("Delete all") }
                    }
                }
            }
        }
        if (items.isEmpty()) {
            item {
                Text(
                    "No false alarms yet. When a suggestion isn't really a promise, tap Skip and then \"Not a promise\" on the notification or the reminder.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        items(items, key = { it.id }) { reminder ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(reminder.message, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Flagged by " + if (reminder.matchedPhrase.startsWith("semantic AI")) "the AI model ${reminder.matchedPhrase.substringAfter("semantic AI").trim()}" else "the phrase \"${reminder.matchedPhrase}\"",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { restore(reminder) }) { Text("It was a promise") }
                        TextButton(onClick = { delete(reminder) }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }
    if (confirmExport) {
        AlertDialog(
            onDismissRequest = { confirmExport = false },
            title = { Text("Share ${items.size} false alarms?") },
            text = {
                Text(
                    "Only the text of these messages is shared, never contact names. Check them for anything private " +
                        "(names, numbers, addresses) and delete those first: what you post on GitHub is public.\n\n" +
                        "Easiest: tap Copy & open GitHub form. Your browser opens a form; long-press the box, tap Paste, " +
                        "then Submit. You need a free GitHub account.\n\nOr tap Share file to send the file some other way."
                )
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { confirmExport = false; contribute(items) }) { Text("Copy & open GitHub form") }
                    TextButton(onClick = { confirmExport = false; export(items) }) { Text("Share file") }
                    TextButton(onClick = { confirmExport = false }) { Text("Cancel") }
                }
            }
        )
    }
    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text("Delete all false alarms?") },
            text = { Text("This permanently removes these ${items.size} messages from your phone.") },
            confirmButton = { TextButton(onClick = { confirmDeleteAll = false; deleteAll() }) { Text("Delete all", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDeleteAll = false }) { Text("Cancel") } }
        )
    }
}

@androidx.compose.runtime.Composable
private fun TabLabel(text: String, count: Int) {
    Text(if (count == 0) text else "$text ($count)", maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = MaterialTheme.typography.labelLarge)
}

@androidx.compose.runtime.Composable
private fun ReminderList(
    items: List<ReminderEntity>,
    emptyTitle: String,
    emptyText: String,
    refresh: () -> Unit,
    modifier: Modifier,
    deleteAllTitle: String,
    onDeleteAll: () -> Unit,
    row: @androidx.compose.runtime.Composable (ReminderEntity) -> Unit
) {
    var confirmDeleteAll by androidx.compose.runtime.remember { mutableStateOf(false) }
    LazyColumn(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 88.dp)) {
        if (items.isNotEmpty()) {
            item(key = "delete-all") {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    OutlinedButton(onClick = { confirmDeleteAll = true }) { Text("Delete all", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        if (items.isEmpty()) {
            item {
                Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
                    Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(emptyTitle, style = MaterialTheme.typography.titleMedium)
                        Text(emptyText, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = refresh) { Text("Refresh") }
                    }
                }
            }
        }
        items(items, key = { it.id }) { row(it) }
    }
    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text(deleteAllTitle) },
            text = { Text("This cancels their alarms and permanently removes them and their messages from your phone.") },
            confirmButton = { TextButton(onClick = { confirmDeleteAll = false; onDeleteAll() }) { Text("Delete all", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDeleteAll = false }) { Text("Cancel") } }
        )
    }
}

private const val TAB_REVIEW = 0
private const val TAB_ACTIVE = 1
private const val TAB_FALSE = 2

private fun formatReminderTime(epochMillis: Long): String = DateTimeFormatter.ofPattern("EEE, d MMM yyyy, h:mm a", Locale.getDefault()).format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))
