package uk.tomlowndes.capture

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun SettingRow(title: String, sub: String? = null, trailing: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp)
            if (sub != null) Text(sub, fontSize = 12.sp, color = Muted)
        }
        trailing()
    }
}

@Composable
internal fun AppSwitch(checked: Boolean, onChange: (Boolean) -> Unit) = Switch(
    checked, onChange,
    colors = SwitchDefaults.colors(
        checkedTrackColor = Primary, checkedThumbColor = Color.White,
        uncheckedTrackColor = Outline, uncheckedThumbColor = Muted, uncheckedBorderColor = Outline
    )
)

@Composable
internal fun PathField(label: String, value: String, onChange: (String) -> Unit) {
    var v by remember(value) { mutableStateOf(value) }
    OutlinedTextField(
        v, { v = it; onChange(it) }, label = { Text(label) }, singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), colors = fieldColors()
    )
}

@Composable
fun SettingsScreen(vault: Uri?, prefs: AppPrefs, onPickVault: () -> Unit, onOpenCards: () -> Unit, onOpenHabits: () -> Unit, onOpenWeekly: () -> Unit) {
    val ctx = LocalContext.current
    var notesOpen by remember { mutableStateOf(false) }
    var cardsOn by remember { mutableStateOf<DeckLayout?>(null) }
    val secrets = remember { SecretStore(ctx) }
    var ghMasked by remember { mutableStateOf(secrets.githubToken?.let { "••••" + it.takeLast(4) }) }
    var editingGh by remember { mutableStateOf(false) }
    var jvMasked by remember { mutableStateOf(secrets.jarvisToken?.let { "••••" + it.takeLast(4) }) }
    var editingJv by remember { mutableStateOf(false) }
    var jvSpeak by remember { mutableStateOf(prefs.jarvisSpeak) }
    var jvPing by remember { mutableStateOf<Jarvis.Ping?>(null) }
    var jvTesting by remember { mutableStateOf(false) }
    var jvVoiceLabel by remember { mutableStateOf(prefs.jarvisVoiceLabel) }
    var jvPickVoice by remember { mutableStateOf(false) }
    var jvSpeed by remember { mutableStateOf(prefs.jarvisSpeed) }
    if (jvPickVoice) JarvisVoiceSheet(prefs, onPick = { id, label ->
        prefs.jarvisVoice = id; prefs.jarvisVoiceLabel = label; jvVoiceLabel = label; jvPickVoice = false
    }) { jvPickVoice = false }
    var checks by remember { mutableStateOf(prefs.updateChecks) }
    val installed = remember { AppUpdate.installed(ctx) }
    val scope = rememberCoroutineScope()
    if (editingGh) {
        var draft by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { editingGh = false }, containerColor = Surface1,
            title = { Text("GitHub token") },
            text = {
                OutlinedTextField(draft, { draft = it }, singleLine = true, colors = fieldColors(),
                    visualTransformation = PasswordVisualTransformation(), placeholder = { Text("github_pat_…") })
            },
            confirmButton = {
                TextButton(onClick = {
                    secrets.githubToken = draft
                    ghMasked = secrets.githubToken?.let { "••••" + it.takeLast(4) }
                    editingGh = false
                }, enabled = draft.isNotBlank()) { Text("Save", color = Primary) }
            },
            dismissButton = {
                TextButton(onClick = {
                    if (ghMasked != null) { secrets.githubToken = null; ghMasked = null }
                    editingGh = false
                }) { Text(if (ghMasked != null) "Remove" else "Cancel", color = Muted) }
            }
        )
    }
    if (editingJv) {
        var draft by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { editingJv = false }, containerColor = Surface1,
            title = { Text("Jarvis token") },
            text = {
                OutlinedTextField(draft, { draft = it }, singleLine = true, colors = fieldColors(),
                    visualTransformation = PasswordVisualTransformation(), placeholder = { Text("Bearer token from the Oracle box") })
            },
            confirmButton = {
                TextButton(onClick = {
                    secrets.jarvisToken = draft
                    jvMasked = secrets.jarvisToken?.let { "••••" + it.takeLast(4) }
                    editingJv = false; jvPing = null
                }, enabled = draft.isNotBlank()) { Text("Save", color = Primary) }
            },
            dismissButton = {
                TextButton(onClick = {
                    if (jvMasked != null) { secrets.jarvisToken = null; jvMasked = null }
                    editingJv = false
                }) { Text(if (jvMasked != null) "Remove" else "Cancel", color = Muted) }
            }
        )
    }
    LaunchedEffect(vault) {
        val uri = vault ?: return@LaunchedEffect
        cardsOn = withContext(Dispatchers.IO) { runCatching { DeckConfig.load(SafVault(ctx, uri)) }.getOrNull() }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp).verticalScroll(rememberScrollState())) {
        ScreenTitle("Settings")

        RemindersSection(prefs)

        Text("", Modifier.padding(top = 8.dp))
        CalendarSection(vault)

        Text("", Modifier.padding(top = 8.dp))
        GroupLabel("Flight Deck")
        SettingRow("Cards on home") {
            TextButton(onClick = onOpenCards) {
                Text(cardsOn?.let { "${it.onCount} of ${HomeCard.values().size} ›" } ?: "›", color = Muted)
            }
        }
        SettingRow("Weekly review") { TextButton(onClick = onOpenWeekly) { Text("›", color = Muted) } }
        SettingRow("Habits") { TextButton(onClick = onOpenHabits) { Text("›", color = Muted) } }

        Text("", Modifier.padding(top = 8.dp))
        GroupLabel("Jarvis")
        PathField("Address", prefs.jarvisAddress) { prefs.jarvisAddress = it; jvPing = null }
        Text(
            "Tailscale name of the Oracle box, like oracle.tail1234.ts.net:8443. Only works with Tailscale on. Type demo to try the tab without a server.",
            fontSize = 12.sp, color = Muted, modifier = Modifier.padding(bottom = 4.dp)
        )
        SettingRow("Token", jvMasked?.let { "$it · kept in secure storage" } ?: "Not set") {
            TextButton(onClick = { editingJv = true }) { Text(if (jvMasked == null) "Add" else "Change", color = Primary) }
        }
        SettingRow("Test connection", jvPing?.message ?: if (jvTesting) "Checking…" else null) {
            TextButton(onClick = {
                jvTesting = true; jvPing = null
                scope.launch {
                    jvPing = withContext(Dispatchers.IO) { Jarvis.ping(prefs.jarvisAddress, secrets.jarvisToken.orEmpty()) }
                    jvTesting = false
                }
            }, enabled = !jvTesting) { Text("Test", color = Primary) }
        }
        SettingRow("Play replies aloud", "When you talk. Typed messages stay quiet.") { AppSwitch(jvSpeak) { jvSpeak = it; prefs.jarvisSpeak = it } }
        SettingRow("Voice", "Made on the server. Tap to hear each one.") {
            TextButton(onClick = { jvPickVoice = true }) { Text((jvVoiceLabel.ifBlank { "Server default" }) + " ›", color = Muted) }
        }
        Text("Playback speed", fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp, bottom = 8.dp))
        PillSwitch(Jarvis.SPEEDS.map(Jarvis::speedLabel), Jarvis.SPEEDS.indexOfFirst { it == jvSpeed }.coerceAtLeast(0)) {
            jvSpeed = Jarvis.SPEEDS[it]; prefs.jarvisSpeed = jvSpeed
        }

        Text("", Modifier.padding(top = 8.dp))
        GroupLabel("Updates")
        SettingRow("Check for updates on launch") { AppSwitch(checks) { checks = it; prefs.updateChecks = it } }
        SettingRow("GitHub token", ghMasked ?: "Not set") {
            TextButton(onClick = { editingGh = true }) { Text(if (ghMasked == null) "Add" else "Change", color = Primary) }
        }
        Text(
            "Read-only, for the private repo. Also shows GitHub activity on projects. Make one at github.com › Settings › Developer settings › Fine-grained tokens: repo ObsidianCapture only, Contents read-only.",
            fontSize = 12.sp, color = Muted, modifier = Modifier.padding(bottom = 4.dp)
        )
        TextButton(onClick = {
            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/settings/personal-access-tokens/new"))) }
        }) { Text("Open GitHub", color = Primary) }
        SettingRow("Installed") { Text(installed, color = Muted, modifier = Modifier.padding(end = 12.dp)) }
        SettingRow("Latest") {
            val rel = AppUpdate.release
            val latest = rel?.version ?: prefs.updateLatest?.removePrefix("v")
            val newer = latest != null && Updater.isNewer(latest, installed)
            Text(
                when {
                    AppUpdate.phase == AppUpdate.Phase.CHECKING -> "Checking…"
                    latest == null -> "—"
                    newer -> "$latest available"
                    else -> "$latest · up to date"
                },
                color = if (newer) Green else Muted, modifier = Modifier.padding(end = 12.dp)
            )
        }
        SettingRow("Last checked") { Text(checkedAgo(prefs.updateCheckedAt), color = Muted, modifier = Modifier.padding(end = 12.dp)) }
        AppUpdate.error?.let { Text(it, fontSize = 12.sp, color = Amber, modifier = Modifier.padding(bottom = 4.dp)) }
        TextButton(onClick = {
            scope.launch {
                AppUpdate.check(ctx, force = true)
                if (AppUpdate.phase == AppUpdate.Phase.AVAILABLE) prefs.updateSkip = null
            }
        }, modifier = Modifier.fillMaxWidth()) { Text("Check now", color = Primary) }

        Text("", Modifier.padding(top = 8.dp))
        GroupLabel("Vault")
        SettingRow("Vault folder", vault?.let { Uri.decode(it.lastPathSegment ?: it.toString()) } ?: "Not selected") {
            TextButton(onClick = onPickVault) { Text(if (vault == null) "Choose" else "Change", color = Primary) }
        }
        SettingRow("Notes used") { TextButton(onClick = { notesOpen = !notesOpen }) { Text(if (notesOpen) "⌄" else "›", color = Muted) } }
        if (notesOpen) {
            PathField("Projects folder", prefs.ideasDir) { prefs.ideasDir = it }
            PathField("Images folder", prefs.imagesDir) { prefs.imagesDir = it }
            listOf(VaultTasks.QUICK_TASK, Jobs.DIR, FlightNotes.STUCK, FlightNotes.FOCUS_LOG, Habits.PATH, DeckConfig.PATH).forEach {
                Text(it, fontSize = 12.sp, color = Muted, modifier = Modifier.padding(vertical = 2.dp))
            }
        }
        Text("", Modifier.padding(bottom = 16.dp))
    }
}

/** "2 min ago" for Settings › Updates. */
private fun checkedAgo(at: Long): String {
    if (at <= 0) return "Never"
    val min = (System.currentTimeMillis() - at) / 60_000
    return when {
        min < 1 -> "Just now"
        min < 60 -> "$min min ago"
        min < 24 * 60 -> "${min / 60} h ago"
        else -> "${min / (24 * 60)} days ago"
    }
}
