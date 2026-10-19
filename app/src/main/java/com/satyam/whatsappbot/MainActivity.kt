package com.satyam.whatsappbot

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import android.provider.Settings
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
import java.io.File
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Transparent system bars, so the dark theme reaches the screen edges instead of a light navigation bar.
        // The app is always dark, so force light status/navigation icons even when the phone is in light mode.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        ReminderNotifications.createChannels(this)
        setupStatus = readSetupStatus(this)
        showSetup = !setupPrefs.getBoolean(KEY_SETUP_FINISHED, false)
        setContent {
            PromiseKeeperTheme {
                if (showSetup) {
                    // Back returns to the reminders once setup has been finished before; on first run it leaves the app.
                    BackHandler(enabled = setupPrefs.getBoolean(KEY_SETUP_FINISHED, false)) { showSetup = false }
                    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                        SetupGuide(setupStatus, onFinish = ::finishSetup)
                    }
                } else {
                    HomeScreen(
                        reminders, setupStatus, timeEditing, ::refreshReminders,
                        openSetup = { showSetup = true },
                        editTime = { timeEditing = it },
                        applyTime = ::applyTime,
                        cancelTimeEdit = { timeEditing = null },
                        editTitle = ::editTitle,
                        delete = ::deleteReminder,
                        confirm = ::confirmReminder,
                        notPromise = ::markNotPromise,
                        noReminderNeeded = ::markNoReminderNeeded,
                        toggleAlarm = ::setAlarm,
                        restoreFalseAlarm = ::restoreFalseAlarm,
                        deleteAllFalseAlarms = ::deleteAllFalseAlarms,
                        exportFalseAlarms = ::exportFalseAlarms
                    )
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

    private fun handleIntent(intent: Intent?) {
        if (intent?.action != ReminderNotifications.ACTION_EDIT) return
        val id = intent.getLongExtra(ReminderNotifications.EXTRA_REMINDER_ID, 0L)
        if (id == 0L) return
        repository.get(id) { reminder ->
            if (reminder != null) runOnUiThread { timeEditing = reminder }
        }
    }

    private fun refreshReminders() = repository.getAll { runOnUiThread { reminders = it } }

    private fun applyTime(reminder: ReminderEntity, dateTime: LocalDateTime) {
        timeEditing = null
        val edited = dateTime.atZone(ZoneId.systemDefault())
        val updated = reminder.copy(reminderAtEpochMillis = edited.toInstant().toEpochMilli(), status = ReminderEntity.STATUS_SCHEDULED)
        if (ReminderNotifications.schedule(this, updated)) {
            repository.updateTimeAndSchedule(reminder.id, updated.reminderAtEpochMillis)
            NotificationManagerCompat.from(this).cancel(ReminderNotifications.confirmationNotificationId(reminder.id))
            refreshReminders()
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

    private fun deleteAllFalseAlarms() = repository.deleteFalseAlarms { runOnUiThread { refreshReminders() } }

    /**
     * Shares the false alarms as a JSON file through the system share sheet (the app itself has no internet access).
     * Only message text and what flagged it are included, never contact names.
     */
    private fun exportFalseAlarms(items: List<ReminderEntity>) {
        val zone = ZoneId.systemDefault()
        val format = DateTimeFormatter.ISO_LOCAL_DATE_TIME
        val json = FalseAlarmExport.toJson(
            items.map {
                FalseAlarmExport.Item(it.message, it.matchedPhrase, Instant.ofEpochMilli(it.createdAtEpochMillis).atZone(zone).toLocalDateTime().withNano(0).format(format))
            },
            appVersion = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty(),
            exportedAt = LocalDateTime.now().withNano(0).format(format)
        )
        val directory = File(cacheDir, "exports").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val file = File(directory, "promise-keeper-false-alarms-${LocalDate.now()}.json").apply { writeText(json) }
        val uri = FileProvider.getUriForFile(this, "$packageName.exports", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Promise Keeper false alarms")
            putExtra(
                Intent.EXTRA_TEXT,
                "${items.size} messages Promise Keeper wrongly flagged as promises. To help improve it, add this file to " +
                    "tools/contributed/ in https://github.com/PixxySynchronous/Promise-keeper or send it to the maintainer."
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Share false alarms"))
    }

    // Same as the notification's "Set". If the suggested time has already passed, ask for a new one instead.
    private fun confirmReminder(reminder: ReminderEntity) {
        if (reminder.reminderAtEpochMillis <= System.currentTimeMillis()) {
            timeEditing = reminder
            return
        }
        if (ReminderNotifications.schedule(this, reminder)) {
            repository.markScheduled(reminder.id)
            NotificationManagerCompat.from(this).cancel(ReminderNotifications.confirmationNotificationId(reminder.id))
            refreshReminders()
        }
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
    applyTime: (ReminderEntity, LocalDateTime) -> Unit,
    cancelTimeEdit: () -> Unit,
    editTitle: (ReminderEntity, String) -> Unit,
    delete: (ReminderEntity) -> Unit,
    confirm: (ReminderEntity) -> Unit,
    notPromise: (ReminderEntity) -> Unit,
    noReminderNeeded: (ReminderEntity) -> Unit,
    toggleAlarm: (ReminderEntity, Boolean) -> Unit,
    restoreFalseAlarm: (ReminderEntity) -> Unit,
    deleteAllFalseAlarms: () -> Unit,
    exportFalseAlarms: (List<ReminderEntity>) -> Unit
) {
    var editingTitle by androidx.compose.runtime.remember { mutableStateOf<ReminderEntity?>(null) }
    var pendingDelete by androidx.compose.runtime.remember { mutableStateOf<ReminderEntity?>(null) }
    // Suggestions waiting for Set / Skip are not active reminders yet; keep the two groups apart.
    val toReview = reminders.filter { it.status == ReminderEntity.STATUS_PENDING_CONFIRMATION }
    val active = reminders.filter { it.status == ReminderEntity.STATUS_SCHEDULED || it.status == ReminderEntity.STATUS_SNOOZED }
    val falseAlarms = reminders.filter { it.status == ReminderEntity.STATUS_FALSE_ALARM }.sortedByDescending { it.createdAtEpochMillis }
    var tab by androidx.compose.runtime.remember { mutableStateOf(0) }
    var skipping by androidx.compose.runtime.remember { mutableStateOf<ReminderEntity?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
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
                },
                actions = { TextButton(onClick = openSetup) { Text("Settings") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { insets ->
        Column(modifier = Modifier.fillMaxSize().padding(insets).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SummaryStrip(active.size, toReview.size)
            SetupBanner(setupStatus, openSetup)
            TabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Reminders") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(if (falseAlarms.isEmpty()) "False alarms" else "False alarms (${falseAlarms.size})") })
            }
            if (tab == 0) {
                SectionHeading("Reminders", "${active.size} set · ${toReview.size} to review", refresh)
                if (active.isEmpty() && toReview.isEmpty()) EmptyState()
                LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 20.dp)) {
                    if (toReview.isNotEmpty()) {
                        item(key = "header-review") { ListHeader("Needs review") }
                        items(toReview, key = { it.id }) { reminder ->
                            ReminderRow(reminder, confirm, editTime, { editingTitle = reminder }, { pendingDelete = reminder }, { skipping = it }, toggleAlarm)
                        }
                    }
                    if (active.isNotEmpty()) {
                        item(key = "header-upcoming") { ListHeader("Upcoming") }
                        items(active, key = { it.id }) { reminder ->
                            ReminderRow(reminder, confirm, editTime, { editingTitle = reminder }, { pendingDelete = reminder }, { skipping = it }, toggleAlarm)
                        }
                    }
                }
            } else {
                FalseAlarmsTab(falseAlarms, restoreFalseAlarm, { pendingDelete = it }, deleteAllFalseAlarms, exportFalseAlarms, Modifier.weight(1f))
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
    timeEditing?.let { reminder -> DateTimeDialog(reminder, applyTime, cancelTimeEdit) }
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

// Date (Today / Tomorrow / calendar) plus typed hour and minute fields with an AM/PM toggle.
@androidx.compose.runtime.Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun DateTimeDialog(reminder: ReminderEntity, applyTime: (ReminderEntity, LocalDateTime) -> Unit, cancel: () -> Unit) {
    val zone = ZoneId.systemDefault()
    val current = Instant.ofEpochMilli(reminder.reminderAtEpochMillis).atZone(zone)
    val today = LocalDate.now(zone)
    var date by androidx.compose.runtime.remember { mutableStateOf(maxOf(current.toLocalDate(), today)) }
    var pickingDate by androidx.compose.runtime.remember { mutableStateOf(false) }
    val timeState = rememberTimePickerState(initialHour = current.hour, initialMinute = current.minute, is24Hour = false)
    val chosen = LocalDateTime.of(date, LocalTime.of(timeState.hour, timeState.minute))
    val inPast = !chosen.atZone(zone).isAfter(ZonedDateTime.now(zone))

    AlertDialog(
        onDismissRequest = cancel,
        title = { Text("Change date & time") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Date", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = date == today, onClick = { date = today }, label = { Text("Today") })
                    FilterChip(selected = date == today.plusDays(1), onClick = { date = today.plusDays(1) }, label = { Text("Tomorrow") })
                }
                OutlinedButton(onClick = { pickingDate = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.getDefault()).format(date))
                }
                Text("Time", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TimeInput(state = timeState)
                if (inPast) {
                    Text("That time has already passed. Pick a later time or date.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = { TextButton(onClick = { applyTime(reminder, chosen) }, enabled = !inPast) { Text("Save") } },
        dismissButton = { TextButton(onClick = cancel) { Text("Cancel") } }
    )

    if (pickingDate) {
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
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    pickingDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickingDate = false }) { Text("Cancel") } }
        ) { DatePicker(state = pickerState) }
    }
}

@androidx.compose.runtime.Composable
@OptIn(ExperimentalLayoutApi::class)
private fun ReminderRow(reminder: ReminderEntity, confirm: (ReminderEntity) -> Unit, editTime: (ReminderEntity) -> Unit, editTitle: () -> Unit, delete: (ReminderEntity) -> Unit, skip: (ReminderEntity) -> Unit, toggleAlarm: (ReminderEntity, Boolean) -> Unit) {
    val pending = reminder.status == ReminderEntity.STATUS_PENDING_CONFIRMATION
    // The title starts as the message itself; only show the message separately once it has been renamed.
    val renamed = reminder.title.isNotBlank() && reminder.title != reminder.message
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
            Text(
                (if (pending) "Suggested: " else "Reminds you: ") + formatReminderTime(reminder.reminderAtEpochMillis),
                style = MaterialTheme.typography.bodyMedium,
                color = if (pending) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
            )
            Text("To ${reminder.contact}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                TextButton(onClick = { editTime(reminder) }) { Text("Change time") }
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
    modifier: Modifier
) {
    var confirmExport by androidx.compose.runtime.remember { mutableStateOf(false) }
    var confirmDeleteAll by androidx.compose.runtime.remember { mutableStateOf(false) }
    LazyColumn(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 20.dp)) {
        item {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Messages you marked \"Not a promise\". They stay on this phone. Export them to help make detection better for everyone.",
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
            title = { Text("Export ${items.size} false alarms?") },
            text = {
                Text(
                    "The file contains the text of these messages, without contact names. Check them for anything private " +
                        "(names, numbers, addresses) and delete those first.\n\nYou choose where it goes: send it to the developer, " +
                        "or add it to tools/contributed/ on the Promise Keeper GitHub page."
                )
            },
            confirmButton = { TextButton(onClick = { confirmExport = false; export(items) }) { Text("Share") } },
            dismissButton = { TextButton(onClick = { confirmExport = false }) { Text("Cancel") } }
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
private fun ListHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
}

@androidx.compose.runtime.Composable
private fun SummaryStrip(upcoming: Int, pending: Int) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SummaryCell("ACTIVE", upcoming.toString(), Modifier.weight(1f))
        SummaryCell("TO REVIEW", pending.toString(), Modifier.weight(1f))
    }
}

@androidx.compose.runtime.Composable
private fun SummaryCell(label: String, value: String, modifier: Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(value, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@androidx.compose.runtime.Composable
private fun SectionHeading(title: String, meta: String, onRefresh: (() -> Unit)?) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onRefresh != null) TextButton(onClick = onRefresh) { Text("Refresh") }
    }
}

@androidx.compose.runtime.Composable
private fun EmptyState() {
    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Nothing waiting on you", style = MaterialTheme.typography.titleMedium)
            Text("When a promise is detected, it will appear here for your approval.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun formatReminderTime(epochMillis: Long): String = DateTimeFormatter.ofPattern("EEE, d MMM yyyy, h:mm a", Locale.getDefault()).format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))
