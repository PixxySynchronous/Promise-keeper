package com.satyam.whatsappbot

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.satyam.whatsappbot.data.ReminderEntity

/**
 * The "Enjoying Promise Keeper?" prompt, like an app store rating prompt, but asking for a GitHub star (or a share
 * with a friend, for people without a GitHub account). Asked only once the app has been useful: at least 3 days
 * since first opened and 3 reminders set. "Maybe later" asks again after 14 days; any other answer never asks again.
 */
object Appreciation {
    const val REPO_URL = "https://github.com/PixxySynchronous/Promise-keeper"
    const val DOWNLOAD_PAGE_URL = "https://pixxysynchronous.github.io/Promise-keeper/"
    private const val ISSUES_URL = "$REPO_URL/issues/new"
    private const val PREFS = "appreciation"
    private const val KEY_FIRST_OPENED = "first_opened"
    private const val KEY_ASK_AFTER = "ask_after"
    private const val KEY_FINISHED = "finished"
    private const val DAY_MS = 24 * 60 * 60 * 1000L
    // TESTING ONLY: shows the prompt on every app open. Must be false in any release.
    private const val TEST_ALWAYS_ASK = false

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Call on every app open; remembers when the app was first opened. */
    fun shouldAsk(context: Context, reminders: List<ReminderEntity>): Boolean {
        val prefs = prefs(context)
        val now = System.currentTimeMillis()
        if (!prefs.contains(KEY_FIRST_OPENED)) prefs.edit().putLong(KEY_FIRST_OPENED, now).apply()
        if (TEST_ALWAYS_ASK) return true
        if (prefs.getBoolean(KEY_FINISHED, false)) return false
        val usedForDays = (now - prefs.getLong(KEY_FIRST_OPENED, now)) / DAY_MS
        // Reminders the user actually set, now or in the past.
        val remindersSet = reminders.count {
            it.status in setOf(ReminderEntity.STATUS_SCHEDULED, ReminderEntity.STATUS_SNOOZED, ReminderEntity.STATUS_DONE, ReminderEntity.STATUS_PAUSED)
        }
        return usedForDays >= 3 && remindersSet >= 3 && now >= prefs.getLong(KEY_ASK_AFTER, 0L)
    }

    fun later(context: Context) = prefs(context).edit().putLong(KEY_ASK_AFTER, System.currentTimeMillis() + 14 * DAY_MS).apply()

    fun finished(context: Context) = prefs(context).edit().putBoolean(KEY_FINISHED, true).apply()

    fun shareWithFriend(context: Context) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(
                Intent.EXTRA_TEXT,
                "I use Promise Keeper: it notices when I promise something on WhatsApp and reminds me. It works offline, " +
                    "nothing leaves your phone. Free download: $DOWNLOAD_PAGE_URL"
            )
        }
        context.startActivity(Intent.createChooser(send, "Share Promise Keeper"))
    }

    @Composable
    fun Dialog(close: () -> Unit) {
        val context = LocalContext.current
        // ASK -> LIKED (star) -> NO_ACCOUNT (share), or ASK -> DISLIKED (feedback).
        var step by remember { mutableStateOf(Step.ASK) }
        val done = { finished(context); close() }
        val later = { later(context); close() }
        when (step) {
            Step.ASK -> AlertDialog(
                onDismissRequest = later,
                title = { Text("Enjoying Promise Keeper?") },
                text = { Text("Promise Keeper is free and open source. Your answer helps decide what to improve next.") },
                confirmButton = { TextButton(onClick = { step = Step.LIKED }) { Text("Yes!") } },
                dismissButton = { TextButton(onClick = { step = Step.DISLIKED }) { Text("Not really") } }
            )
            Step.LIKED -> AlertDialog(
                onDismissRequest = later,
                title = { Text("⭐ Give it a star?") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Promise Keeper isn't on the Play Store, so there's no rating. A star on GitHub is the same thing: it shows others the app is worth trying, and helps more people find it.")
                        Text("Open the page and tap ☆ Star at the top. It takes a few seconds with a free GitHub account.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                confirmButton = {
                    Column(horizontalAlignment = Alignment.End) {
                        TextButton(onClick = { openInBrowser(context, REPO_URL); done() }) { Text("⭐ Star on GitHub") }
                        TextButton(onClick = { step = Step.NO_ACCOUNT }) { Text("No GitHub account?") }
                        TextButton(onClick = later) { Text("Maybe later") }
                    }
                }
            )
            Step.NO_ACCOUNT -> AlertDialog(
                onDismissRequest = later,
                title = { Text("Share it with a friend") },
                text = { Text("No account needed: send the download link to someone who keeps forgetting their promises. That's the best thank-you.") },
                confirmButton = {
                    Column(horizontalAlignment = Alignment.End) {
                        TextButton(onClick = { shareWithFriend(context); done() }) { Text("Share with a friend") }
                        TextButton(onClick = done) { Text("No thanks") }
                    }
                }
            )
            Step.DISLIKED -> AlertDialog(
                onDismissRequest = done,
                title = { Text("Sorry about that") },
                text = { Text("What could be better? Wrong suggestions, missed promises, something confusing? Tell us on GitHub and it may get fixed in the next version.") },
                confirmButton = {
                    Column(horizontalAlignment = Alignment.End) {
                        TextButton(onClick = { openInBrowser(context, ISSUES_URL); done() }) { Text("Report a problem") }
                        TextButton(onClick = done) { Text("Close") }
                    }
                }
            )
        }
    }

    private enum class Step { ASK, LIKED, NO_ACCOUNT, DISLIKED }
}
