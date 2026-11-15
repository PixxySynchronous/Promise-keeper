package com.satyam.whatsappbot

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.satyam.whatsappbot.data.ReminderEntity
import com.satyam.whatsappbot.data.ReminderRepository
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Full-screen alarm shown over the lock screen when a reminder set to "Alarm" goes off. The sound comes from the
 * insistent alarm notification; Stop and Snooze send the same actions as the notification's buttons, which cancel it.
 */
class AlarmActivity : ComponentActivity() {
    private var reminder by mutableStateOf<ReminderEntity?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        load(intent)
        setContent {
            PromiseKeeperTheme {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.fillMaxSize().systemBarsPadding().padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            LocalTime.now().format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())),
                            style = MaterialTheme.typography.displayMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(reminder?.title.orEmpty(), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                        reminder?.takeIf { it.title != it.message }?.let {
                            Text(it.message, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        reminder?.takeIf { it.contact.isNotBlank() }?.let { Text("To ${it.contact}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Button(onClick = { send(ReminderNotifications.ACTION_DONE) }, modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) {
                            Text("Stop", style = MaterialTheme.typography.titleLarge)
                        }
                        OutlinedButton(onClick = { send(ReminderNotifications.ACTION_SNOOZE_SHORT) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Snooze 10 min", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        load(intent)
    }

    private fun load(intent: Intent) {
        val id = intent.getLongExtra(ReminderNotifications.EXTRA_REMINDER_ID, 0L)
        if (id == 0L) return finish()
        ReminderRepository(this).get(id) { found -> runOnUiThread { if (found == null) finish() else reminder = found } }
    }

    private fun send(action: String) {
        reminder?.let {
            sendBroadcast(Intent(this, ReminderActionReceiver::class.java).setAction(action).putExtra(ReminderNotifications.EXTRA_REMINDER_ID, it.id))
        }
        finish()
    }
}
