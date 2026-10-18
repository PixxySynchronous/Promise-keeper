package com.satyam.whatsappbot

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat

data class SetupStatus(
    val notifications: Boolean,
    val accessibility: Boolean,
    val exactAlarms: Boolean,
    val battery: Boolean
) {
    /** Without these two the app cannot detect promises or show suggestions. */
    val requiredDone get() = notifications && accessibility
    val allDone get() = requiredDone && exactAlarms && battery
}

fun readSetupStatus(context: Context) = SetupStatus(
    notifications = NotificationManagerCompat.from(context).areNotificationsEnabled(),
    accessibility = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        .orEmpty().contains("${context.packageName}/", ignoreCase = true),
    exactAlarms = Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms(),
    battery = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
)

private val manufacturer = Build.MANUFACTURER.lowercase()

private fun appInfoIntent(context: Context) =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

/** Step-by-step setup shown on first launch and from the "Setup" button. Statuses refresh on every resume. */
@Composable
fun SetupGuide(status: SetupStatus, onFinish: () -> Unit) {
    val context = LocalContext.current
    var notificationAsked by remember { mutableStateOf(false) }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notificationAsked = true }

    Column(
        // systemBarsPadding keeps the guide clear of the status and navigation bars on edge-to-edge Android 15+.
        modifier = Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Set up Promise Keeper", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "When you promise something in WhatsApp, like \"kal bhej dunga\" or \"I'll call you at 5\", Promise Keeper offers to remind you. Setup takes about 2 minutes.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            Text(
                "🔒 Private by design: the app has no internet permission, so Android blocks it from going online. Your messages are processed on this phone and never leave it.",
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall
            )
        }

        SetupStep(
            number = 1, title = "Allow notifications", done = status.notifications, required = true,
            why = "Reminder suggestions and the reminders themselves arrive as notifications."
        ) {
            Button(onClick = {
                // After one refusal Android stops showing the prompt, so send the user to settings instead.
                if (notificationAsked && Build.VERSION.SDK_INT >= 33) {
                    context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                } else if (Build.VERSION.SDK_INT >= 33) {
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }) { Text(if (notificationAsked) "Open notification settings" else "Allow notifications") }
        }

        SetupStep(
            number = 2, title = "Turn on Promise Keeper in Accessibility", done = status.accessibility, required = true,
            why = "This lets the app notice the messages you send in WhatsApp. It only works inside WhatsApp and WhatsApp Business, and ignores every other app."
        ) {
            Instructions(
                "Tap Open Accessibility settings below.",
                when (manufacturer) {
                    "samsung" -> "Tap Installed apps (sometimes under Installed services)."
                    "xiaomi", "redmi", "poco" -> "Tap Downloaded apps."
                    "google" -> "Under Downloaded apps, find the list of apps."
                    else -> "Look for Installed apps, Downloaded apps or Installed services."
                },
                "Tap Promise Keeper, turn the switch on, then tap Allow.",
                "Come back to this app. Step 2 shows ✓ Done."
            )
            Button(onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("Open Accessibility settings") }
            HelpBox(
                "Promise Keeper greyed out or \"Restricted setting\"?",
                "Android locks this for apps installed from a downloaded file. Unlock it once:\n" +
                    "1. In Accessibility → Installed apps, tap the greyed-out Promise Keeper and tap OK on the popup. " +
                    "(This first try is needed, or the option in step 3 doesn't appear.)\n" +
                    "2. Come back here and tap Open app info below.\n" +
                    "3. Tap ⋮ (top-right corner) → Allow restricted settings, and confirm with your PIN or fingerprint.\n" +
                    "4. Open Accessibility settings again and turn Promise Keeper on."
            )
            OutlinedButton(onClick = { context.startActivity(appInfoIntent(context)) }) { Text("Open app info") }
        }

        SetupStep(
            number = 3, title = "Allow alarms & reminders", done = status.exactAlarms, required = false,
            why = "Lets reminders ring at exactly the time you chose instead of several minutes late."
        ) {
            Instructions("Tap the button, then turn on Allow setting alarms and reminders.")
            Button(onClick = {
                context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
            }) { Text("Open alarm settings") }
        }

        SetupStep(
            number = 4, title = "Let it run in the background", done = status.battery, required = false,
            why = "Many phones stop background apps to save battery. If that happens, promises are missed and reminders don't ring."
        ) {
            Instructions("Tap Remove battery limits, then choose Allow.")
            Button(onClick = {
                context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))
            }) { Text("Remove battery limits") }
            HelpBox("Extra step for your phone (${Build.MANUFACTURER})", brandBatteryTip())
            OutlinedButton(onClick = { context.startActivity(appInfoIntent(context)) }) { Text("Open app info") }
        }

        SetupStep(number = 5, title = "Try it", done = null, required = false, why = "Check that everything works.") {
            Instructions(
                "Open WhatsApp and send a promise to any chat, for example to yourself: kal bhej dunga",
                "Within a few seconds, a \"Reminder suggestion\" notification appears.",
                "Tap Set to keep the reminder, Edit time to change when it rings, or Ignore.",
                "Right after the phone restarts, the first suggestion can take about 15 seconds while the app gets ready."
            )
        }

        Button(onClick = onFinish, enabled = status.requiredDone, modifier = Modifier.fillMaxWidth()) {
            Text(if (status.requiredDone) "Done, go to my reminders" else "Finish steps 1 and 2 to continue")
        }
        if (!status.requiredDone) {
            TextButton(onClick = onFinish, modifier = Modifier.fillMaxWidth()) { Text("I'll do it later") }
        }
    }
}

@Composable
private fun SetupStep(number: Int, title: String, done: Boolean?, required: Boolean, why: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("$number. $title", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                when (done) {
                    true -> Text("✓ Done", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                    false -> Text(if (required) "Required" else "Recommended", color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.labelLarge)
                    null -> {}
                }
            }
            Text(why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (done != true) content()
        }
    }
}

@Composable
private fun Instructions(vararg steps: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        steps.forEachIndexed { index, step ->
            Text(if (steps.size > 1) "${index + 1}. $step" else step, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun HelpBox(title: String, body: String) {
    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun brandBatteryTip(): String = when (manufacturer) {
    "samsung" -> "Open app info → Battery → choose Unrestricted. Then in Settings → Battery → Background usage limits, make sure Promise Keeper is not in Sleeping apps or Deep sleeping apps."
    "xiaomi", "redmi", "poco" -> "Open app info → turn on Autostart. Then Battery saver → choose No restrictions."
    "oppo", "realme", "oneplus" -> "Open app info → Battery → turn on Allow background activity, and Allow auto launch if you see it."
    "vivo", "iqoo" -> "Settings → Battery → Background power consumption management → Promise Keeper → Allow. Also turn on Autostart for Promise Keeper if your phone has it."
    "huawei", "honor" -> "Settings → Battery → App launch → Promise Keeper → turn off Manage automatically and allow all three options."
    "motorola", "google", "nokia" -> "Usually nothing extra is needed. If reminders stop, open app info → Battery → choose Unrestricted."
    else -> "Open app info → Battery and choose Unrestricted (or Don't optimise). If your phone has Autostart, turn it on for Promise Keeper."
}

/** Shown on the home screen when setup is incomplete, e.g. Android turned the accessibility service off. */
@Composable
fun SetupBanner(status: SetupStatus, openSetup: () -> Unit) {
    if (status.allDone) return
    val message = when {
        !status.accessibility -> "Promise Keeper isn't watching WhatsApp right now. Turn it on in Accessibility."
        !status.notifications -> "Notifications are off, so you won't see suggestions or reminders."
        !status.exactAlarms -> "Reminders may ring late. Allow alarms & reminders."
        else -> "Your phone may stop Promise Keeper in the background. Remove battery limits."
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(message, style = MaterialTheme.typography.bodySmall, color = if (status.requiredDone) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.secondary, modifier = Modifier.weight(1f).padding(vertical = 10.dp))
            TextButton(onClick = openSetup) { Text("Fix") }
        }
    }
}
