package com.satyam.whatsappbot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * What's new, shown once after an update. Add an entry for every release users should hear about; versionCode
 * must match app/build.gradle.kts.
 */
object WhatsNew {
    data class Release(val versionCode: Int, val versionName: String, val changes: List<String>)

    /** Opened in the phone's browser; the app itself still has no internet access. */
    const val RELEASES_URL = "https://github.com/PixxySynchronous/Promise-keeper/releases"

    /** The "Share false alarms" form, .github/ISSUE_TEMPLATE/false-alarms.yml. */
    const val FALSE_ALARMS_FORM_URL = "https://github.com/PixxySynchronous/Promise-keeper/issues/new?template=false-alarms.yml"

    val releases = listOf(
        Release(
            10, "1.4.0", listOf(
                "🔁 Repeating reminders: every day, every week (pick the days), every month or every year. Each has an on/off switch on its card.",
                "From WhatsApp too: \"har somvar mandir jaunga\", \"roz subah 7 baje walk karunga\", \"Reminder: pay rent on the 1st of every month\".",
                "🎂 Birthdays and anniversaries repeat every year on their own, and rent, EMI or bills \"on the 1st\" every month.",
                "📅 Dates like \"28 May\", \"May 28th\" and \"28 May 2027\" are understood.",
                "➕ New reminder button: make a reminder yourself, once or repeating. Edit on any reminder can make it repeat too.",
                "🔔 If Promise Keeper is switched off in Accessibility, for example for a bank app, you get a reminder once a day to turn it back on.",
                "🕙 Times are understood however you type them: \"1 o clock\", \"1oclock\", \"8.30pm\", \"8:30 P.M.\", \"5 bje\".",
                "Fewer false alarms: \"k lemme play\", \"I completed…\" and \"K I'm in\" style messages are no longer flagged.",
                "✨ The home screen shows what the app can do. Tap it for all features and the version.",
                "🛡️ Tap Verify under the title to check for yourself that nothing leaves your phone.",
                "📤 Sharing false alarms is now one tap: Export → Copy & open GitHub form."
            )
        )
    )

    /** Releases newer than [lastSeenCode], newest first. */
    fun since(lastSeenCode: Int) = releases.filter { it.versionCode > lastSeenCode }.sortedByDescending { it.versionCode }
}

@Composable
fun WhatsNewDialog(releases: List<WhatsNew.Release>, dismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("What's new in ${releases.first().versionName}") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                releases.forEachIndexed { index, release ->
                    if (index > 0) Text(release.versionName, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    release.changes.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                }
                // Shown as text too, so it can be copied if no browser opens.
                SelectionContainer {
                    Text("Full notes: ${WhatsNew.RELEASES_URL}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text("OK") } },
        dismissButton = {
            TextButton(onClick = { openReleasesPage(context) }) { Text("Read more") }
        }
    )
}
