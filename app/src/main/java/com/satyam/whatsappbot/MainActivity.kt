package com.satyam.whatsappbot

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.app.NotificationManagerCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.material3.Surface
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
    private var resumeCount by mutableStateOf(0)
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
                        confirm = ::confirmReminder
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
private fun PromiseKeeperTheme(content: @androidx.compose.runtime.Composable () -> Unit) {
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
    confirm: (ReminderEntity) -> Unit
) {
    var editingTitle by androidx.compose.runtime.remember { mutableStateOf<ReminderEntity?>(null) }
    var pendingDelete by androidx.compose.runtime.remember { mutableStateOf<ReminderEntity?>(null) }
    val upcoming = reminders.filter { it.status == ReminderEntity.STATUS_SCHEDULED || it.status == ReminderEntity.STATUS_SNOOZED || it.status == ReminderEntity.STATUS_PENDING_CONFIRMATION }
    val pending = upcoming.count { it.status == ReminderEntity.STATUS_PENDING_CONFIRMATION }

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
            SummaryStrip(upcoming.size, pending)
            SetupBanner(setupStatus, openSetup)
            SectionHeading("Upcoming reminders", "${upcoming.size} active", refresh)
            if (upcoming.isEmpty()) EmptyState()
            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 20.dp)) {
                items(upcoming, key = { it.id }) { reminder ->
                    ReminderRow(reminder, confirm, editTime, { editingTitle = reminder }, { pendingDelete = reminder })
                }
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
private fun ReminderRow(reminder: ReminderEntity, confirm: (ReminderEntity) -> Unit, editTime: (ReminderEntity) -> Unit, editTitle: () -> Unit, delete: (ReminderEntity) -> Unit) {
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
            }
        }
    }
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

@androidx.compose.runtime.Composable
private fun PastRow(reminder: ReminderEntity) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(reminder.title, style = MaterialTheme.typography.titleSmall)
            Text(reminder.status.lowercase(Locale.ROOT), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("${reminder.message} · ${reminder.contact}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun formatReminderTime(epochMillis: Long): String = DateTimeFormatter.ofPattern("EEE, d MMM yyyy, h:mm a", Locale.getDefault()).format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))

@androidx.compose.runtime.Composable
private fun DebugTools(context: Context) {
    var filter by androidx.compose.runtime.remember { mutableStateOf("") }
    DebugLogStore.loggingEnabled = true
    val logs = DebugLogStore.logs
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("Accessibility") }
            OutlinedButton(onClick = { WhatsappAccessibilityService.requestNodeDump() }) { Text("Dump") }
        }
        TextField(value = filter, onValueChange = { filter = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Filter debug log") }, singleLine = true)
        LazyColumn(modifier = Modifier.fillMaxWidth().weight(0.6f)) { items(logs.filter { filter.isBlank() || it.contains(filter, true) }) { Text(it) } }
    }
}
