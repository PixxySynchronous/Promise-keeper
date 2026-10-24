package com.satyam.whatsappbot

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
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

private data class Feature(val title: String, val text: String, val examples: String = "")

/** Everything the app can do: the home screen carousel and the Features page. Keep in step with the README. */
private val FEATURES = listOf(
    Feature("🤝 Notices your promises", "When you promise something in WhatsApp, a notification offers a reminder. Works in English, Hinglish and Hindi.", "\"kal bhej dunga\" · \"I'll call you at 5\" · \"मैं कल भेज दूंगी\""),
    Feature("🔒 Private", "The app has no internet permission, so Android blocks it from going online. Everything runs on this phone, and it only looks at the WhatsApp messages you send."),
    Feature("🕙 Understands times and dates", "It suggests a time from what you wrote, however you type it.", "\"tomorrow\" · \"by EOD\" · \"in 10 mins\" · \"5 baje\" · \"1 o clock\" · \"8.30pm\" · \"28 May\""),
    Feature("✍️ Reminder on purpose", "Start a message with the word reminder and you always get one. In your own chat (Message yourself), any note with a time or repeat works too.", "\"Reminder: pay rent on 1/11\" · \"wish her good morning every day at 10 am\""),
    Feature("🔁 Repeating reminders", "Say how often and it repeats: every day, morning or night, given weekdays, every week, month or year.", "\"har somvar mandir jaunga\" · \"roz subah 7 baje walk karunga\""),
    Feature("🎂 Birthdays and bills repeat by themselves", "Birthdays and anniversaries repeat every year. Rent, EMI and bills with a day of the month repeat every month.", "\"Piyush birthday 28 May\" · \"pay rent on the 1st\""),
    Feature("➕ Make your own reminders", "Tap New reminder, once or repeating. Edit on any reminder changes its time, makes it repeat, or renames it. Repeating reminders have an on/off switch."),
    Feature("⏰ Notification or alarm", "Each reminder can be a notification, or an alarm that rings until you tap Stop or Snooze, even on a locked phone. Set the default in Settings."),
    Feature("🗓️ Default reminder time", "When a message names a day but no time, like \"kal bhej dunga\", the reminder is set for your default time. Change it in Settings."),
    Feature("🏦 Bank and UPI apps", "If a payment app complains, tap Settings → Turn off for now. If it's switched off, you get a reminder a couple of minutes later, then once a day until it's back on."),
    Feature("🚫 Teach it", "Not a promise? Tap Skip → Not a promise. It goes to False alarms, which you can Export to improve detection for everyone. Was a promise you don't need? Tap No reminder needed.")
)

/**
 * The home screen's feature strip: moves to the next feature every 5 seconds on its own, can be swiped (the timer
 * restarts after a swipe), and opens the Features page when tapped.
 */
@Composable
fun FeatureCarousel(openAll: () -> Unit) {
    val pager = rememberPagerState { FEATURES.size }
    // Keyed on settledPage, which changes only once a slide has finished. currentPage changes halfway through a
    // slide, which would restart this effect and cancel the slide in the middle.
    LaunchedEffect(pager.settledPage) {
        delay(5000)
        pager.animateScrollToPage((pager.settledPage + 1) % FEATURES.size)
    }
    Surface(onClick = openAll, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Fixed height, so the screen below doesn't jump as features of different lengths go by.
            HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth().height(104.dp), pageSpacing = 16.dp, verticalAlignment = Alignment.Top) { page ->
                val feature = FEATURES[page]
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(feature.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(feature.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    if (feature.examples.isNotEmpty()) {
                        Text(feature.examples, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.weight(1f)) {
                    repeat(FEATURES.size) { index ->
                        Box(
                            Modifier.size(if (index == pager.currentPage) 8.dp else 6.dp).clip(CircleShape)
                                .background(if (index == pager.currentPage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
                        )
                    }
                }
                Text("All features ›", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
fun FeaturesScreen(back: () -> Unit) {
    val context = LocalContext.current
    val versionName = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
    val versionCode = remember { context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toInt() }
    var showWhatsNew by remember { mutableStateOf(false) }
    val notes = WhatsNew.releases.filter { it.versionCode <= versionCode }.sortedByDescending { it.versionCode }

    Column(
        modifier = Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TextButton(onClick = back) { Text("← Back") }
        Text("Features", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Version $versionName", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Text(
                    "New versions come out from time to time. Check the releases page on GitHub for the latest one and how to install it.",
                    style = MaterialTheme.typography.bodySmall
                )
                // Shown as text too, so it can be copied if no browser opens.
                SelectionContainer {
                    Text(WhatsNew.RELEASES_URL, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(onClick = { openReleasesPage(context) }) { Text("Open releases page") }
                if (notes.isNotEmpty()) OutlinedButton(onClick = { showWhatsNew = true }) { Text("What's new in this version") }
                Text("Get notified of new versions", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
                Text(
                    "• This app reminds you about once a month (you can turn it off in Settings).\n" +
                        "• On GitHub, tap Watch → Custom → Releases to get an email for each new version (free account; " +
                        "GitHub sends it, nobody else sees your email).\n" +
                        "• Or add the releases feed to any feed reader:",
                    style = MaterialTheme.typography.bodySmall
                )
                SelectionContainer {
                    Text(WhatsNew.RELEASES_FEED_URL, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Like Promise Keeper?", style = MaterialTheme.typography.titleMedium)
                Text(
                    "A star on GitHub works like a rating and helps others find it. No GitHub account? Sharing it with a friend helps just as much.",
                    style = MaterialTheme.typography.bodySmall
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { openInBrowser(context, Appreciation.REPO_URL) }) { Text("⭐ Star on GitHub") }
                    OutlinedButton(onClick = { Appreciation.shareWithFriend(context) }) { Text("Share") }
                }
            }
        }
        FEATURES.forEach { feature ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(feature.title, style = MaterialTheme.typography.titleMedium)
                    Text(feature.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (feature.examples.isNotEmpty()) Text(feature.examples, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        TextButton(onClick = back) { Text("← Back") }
    }
    if (showWhatsNew) WhatsNewDialog(notes) { showWhatsNew = false }
}

fun openReleasesPage(context: Context) = openInBrowser(context, WhatsNew.RELEASES_URL)

/** Opens [url] in the phone's browser, only when the user taps; the app itself has no internet access. */
fun openInBrowser(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No browser found. Open $url in a browser instead.", Toast.LENGTH_LONG).show()
    }
}
