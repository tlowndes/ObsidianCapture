package uk.tomlowndes.capture

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
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
import androidx.compose.ui.text.font.FontFamily
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
    var ckMasked by remember { mutableStateOf(secrets.claudeKey?.let { "••••" + it.takeLast(4) }) }
    var editingCk by remember { mutableStateOf(false) }
    var brainOn by remember { mutableStateOf(prefs.phoneBrain) }
    var clever by remember { mutableStateOf(prefs.brainClever) }
    var phSpeech by remember { mutableStateOf(prefs.phoneSpeech) }
    var nanoOn by remember { mutableStateOf(prefs.nanoBrain) }
    var nanoToBox by remember { mutableStateOf(prefs.nanoToBox) }
    var nanoState by remember { mutableStateOf(PhoneNano.state) }
    LaunchedEffect(nanoOn) { if (nanoOn) nanoState = withContext(Dispatchers.IO) { PhoneNano.ensure() } }
    var phVoice by remember { mutableStateOf(prefs.phoneVoice) }
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
    if (editingCk) {
        var draft by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { editingCk = false }, containerColor = Surface1,
            title = { Text("Claude key") },
            text = {
                Column {
                    OutlinedTextField(draft, { draft = it }, singleLine = true, colors = fieldColors(),
                        visualTransformation = PasswordVisualTransformation(), placeholder = { Text("sk-ant-…") })
                    Text("From console.anthropic.com › API keys. Kept encrypted on this phone only.", fontSize = 12.sp, color = Muted,
                        modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    secrets.claudeKey = draft
                    ckMasked = secrets.claudeKey?.let { "••••" + it.takeLast(4) }
                    editingCk = false
                }, enabled = draft.isNotBlank()) { Text("Save", color = Primary) }
            },
            dismissButton = {
                TextButton(onClick = {
                    if (ckMasked != null) { secrets.claudeKey = null; ckMasked = null }
                    editingCk = false
                }) { Text(if (ckMasked != null) "Remove" else "Cancel", color = Muted) }
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
        cardsOn = withContext(Dispatchers.IO) { runCatching { DeckConfig.load(appVault(ctx, uri)) }.getOrNull() }
    }

    var page by remember { mutableStateOf(SettingsPage.MAIN) }
    BackHandler(enabled = page != SettingsPage.MAIN) { page = SettingsPage.MAIN }
    val back = { page = SettingsPage.MAIN }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp).verticalScroll(rememberScrollState())) {
        when (page) {
        // Settings tidy-v1: one short main page, the technical bits on their own pages.
        SettingsPage.MAIN -> {
        ScreenTitle("Settings")
        // Vault status on top: can the app see your notes?
        val vaultName = vault?.let { Uri.decode(it.lastPathSegment ?: it.toString()).substringAfterLast(':').substringAfterLast('/') }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Brush.linearGradient(listOf(Color(0xFF1C2744), Surface1)))
                .border(1.dp, NextBorder, RoundedCornerShape(18.dp)).clickable(onClick = onPickVault).padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(Modifier.size(9.dp).clip(CircleShape).background(if (vault != null) Green else Amber))
            Column(Modifier.weight(1f)) {
                Text(vaultName ?: "No vault chosen", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(if (vault != null) "vault folder" else "tap to pick your vault", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Muted)
            }
            Text(if (vault == null) "Choose ›" else "Change ›", fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, color = Muted)
        }

        SectionLabel("Everyday")
        RowsCard(listOf(
            { QuietRow("🔔", "Reminders", "›", onClick = { page = SettingsPage.REMINDERS }) },
            { QuietRow("📅", "Calendars", "›", onClick = { page = SettingsPage.CALENDARS }) },
            { QuietRow("◎", "Cards on home", (cardsOn?.let { "${it.onCount} of ${HomeCard.values().size} " } ?: "") + "›", onClick = onOpenCards) },
            { QuietRow("✅", "Habits", "›", onClick = onOpenHabits) },
            { QuietRow("🗓", "Weekly review", "›", onClick = onOpenWeekly) },
        ))

        SectionLabel("Tasks")
        var swipe by remember { mutableStateOf(prefs.taskSwipe) }
        var inline by remember { mutableStateOf(prefs.subtasksInline) }
        DeckCard {
            Box(Modifier.padding(horizontal = 14.dp)) {
                SettingRow("Swipe on tasks", "Right ticks, left reschedules") { AppSwitch(swipe) { swipe = it; prefs.taskSwipe = it } }
            }
            HorizontalDivider(color = Outline)
            Box(Modifier.padding(horizontal = 14.dp)) {
                SettingRow("Subtasks open") {
                    Segmented(listOf("Under task", "Sheet"), if (inline) 0 else 1) { inline = it == 0; prefs.subtasksInline = inline }
                }
            }
        }

        SectionLabel("Connections")
        val jvSet = prefs.jarvisAddress.isNotBlank() && (jvMasked != null || prefs.jarvisAddress.trim() == "demo")
        val latest = AppUpdate.release?.version ?: prefs.updateLatest?.removePrefix("v")
        val newer = latest != null && Updater.isNewer(latest, installed)
        RowsCard(listOf(
            { QuietRow("🎙", "Jarvis", (if (jvSet) "● set up" else "not set up") + " ›", if (jvSet) Green else Muted, onClick = { page = SettingsPage.JARVIS }) },
            { QuietRow("⬇", "Updates", (if (newer) "$latest available" else "$installed · up to date") + " ›", if (newer) Green else Muted, onClick = { page = SettingsPage.UPDATES }) },
            { QuietRow("🗄", "Vault & sync", (if (prefs.serverFallback) "fallback on" else "fallback off") + " ›", onClick = { page = SettingsPage.VAULT }) },
        ))
        Box(Modifier.height(20.dp))
        }

        SettingsPage.REMINDERS -> {
            SubPageTitle("Reminders", back)
            RemindersSection(prefs)
        }
        SettingsPage.CALENDARS -> {
            SubPageTitle("Calendars", back)
            CalendarSection(vault)
        }
        SettingsPage.JARVIS -> {
            SubPageTitle("Jarvis", back)
        GroupLabel("Brain")
        SettingRow("📱 Phone brain", "Gemini Nano · " + if (nanoOn) PhoneNano.label(nanoState) else "off") {
            AppSwitch(nanoOn) { nanoOn = it; prefs.nanoBrain = it }
        }
        SettingRow("🛰 Send to the box when needed", "Vault, research, coding, long rambles") {
            AppSwitch(nanoToBox) { nanoToBox = it; prefs.nanoToBox = it }
        }
        Text(
            "Nano is free and works offline. The box is used only when the ask needs it, or when you tap Ask the box." +
                if (nanoState == PhoneNano.State.GETTING_READY) " Until Nano is ready, everything goes to the box." else "",
            fontSize = 12.sp, color = Muted, lineHeight = 17.sp, modifier = Modifier.padding(bottom = 4.dp)
        )
        Spacer(Modifier.height(8.dp))
        GroupLabel("Voice")
        SettingRow("Phone speech-to-text", "Android hears you, so messages don't wait on Whisper") { AppSwitch(phSpeech) { phSpeech = it; prefs.phoneSpeech = it } }
        SettingRow("Phone voice", "Android reads replies. Off keeps the server's voice (slower).") { AppSwitch(phVoice) { phVoice = it; prefs.phoneVoice = it } }
        Spacer(Modifier.height(8.dp))
        GroupLabel("Oracle box")
        PathField("Address", prefs.jarvisAddress) { prefs.jarvisAddress = it; jvPing = null }
        Text(
            "Tailscale name of the Oracle box (Tailscale on). Type demo to try without a server.",
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
        if (!phVoice) SettingRow("Voice", "Made on the server. Tap to hear each one.") {
            TextButton(onClick = { jvPickVoice = true }) { Text((jvVoiceLabel.ifBlank { "Server default" }) + " ›", color = Muted) }
        }
        Text("Playback speed", fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp, bottom = 8.dp))
        PillSwitch(Jarvis.SPEEDS.map(Jarvis::speedLabel), Jarvis.SPEEDS.indexOfFirst { it == jvSpeed }.coerceAtLeast(0)) {
            jvSpeed = Jarvis.SPEEDS[it]; prefs.jarvisSpeed = jvSpeed
        }
        Spacer(Modifier.height(16.dp))
        GroupLabel("Advanced")
        SettingRow("⚡ Claude from the phone", if (ckMasked == null) "Add a Claude key to think on the phone. First words in about a second."
            else "Claude straight from the app instead of Nano. Pay as you go.") {
            AppSwitch(brainOn && ckMasked != null) { brainOn = it; prefs.phoneBrain = it; if (it && ckMasked == null) editingCk = true }
        }
        SettingRow("Claude key", ckMasked?.let { "$it · kept in secure storage" } ?: "Not set") {
            TextButton(onClick = { editingCk = true }) { Text(if (ckMasked == null) "Add" else "Change", color = Primary) }
        }
        Text("Model", fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp, bottom = 8.dp))
        PillSwitch(listOf("Fast", "Clever"), if (clever) 1 else 0) { clever = it == 1; prefs.brainClever = clever }
        Text(if (clever) "Clever is Claude Sonnet: better at tricky asks, a bit slower." else "Fast is Claude Haiku: quickest replies, costs very little.",
            fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 6.dp))
        SettingRow("This month", "Worked out on the phone from what Claude reports") {
            Text(remember { prefs.brainSpend.label() }, fontSize = 13.sp, color = Muted, fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.height(8.dp))
        }
        SettingsPage.UPDATES -> {
            SubPageTitle("Updates", back)
        SettingRow("Check for updates on launch") { AppSwitch(checks) { checks = it; prefs.updateChecks = it } }
        SettingRow("GitHub token", ghMasked ?: "Not set") {
            TextButton(onClick = { editingGh = true }) { Text(if (ghMasked == null) "Add" else "Change", color = Primary) }
        }
        Text(
            "Read-only: ObsidianCapture repo only, Contents read.",
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

        }
        SettingsPage.VAULT -> {
            SubPageTitle("Vault & sync", back)
        SettingRow("Vault folder", vault?.let { Uri.decode(it.lastPathSegment ?: it.toString()) } ?: "Not selected") {
            TextButton(onClick = onPickVault) { Text(if (vault == null) "Choose" else "Change", color = Primary) }
        }
        var fallbackOn by remember { mutableStateOf(prefs.serverFallback) }
        var staleMin by remember { mutableStateOf(prefs.fallbackStaleMin) }
        var checking by remember { mutableStateOf(false) }
        SettingRow("Server fallback", "If this phone hasn't synced for a while, read and save through the Jarvis server's copy instead.") {
            AppSwitch(fallbackOn) { on ->
                fallbackOn = on; prefs.serverFallback = on
                vault?.let { uri -> scope.launch { withContext(Dispatchers.IO) { VaultRoute.check(ctx, uri, force = true) } } }
            }
        }
        if (fallbackOn) {
            SettingRow("Counts as stale after", "Server copy newer than the phone's by this much") {
                TextButton(onClick = {
                    staleMin = STALE_STEPS.getOrNull(STALE_STEPS.indexOf(staleMin) + 1) ?: STALE_STEPS[0]
                    prefs.fallbackStaleMin = staleMin
                }) { Text("$staleMin min ▾", color = Muted, fontFamily = FontFamily.Monospace, fontSize = 12.5.sp) }
            }
            val rs = VaultRoute.state
            val hm = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
            fun clock(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).format(hm)
            val lastSub = when {
                rs.error != null -> "Can't reach the server: ${rs.error}"
                rs.checkedAt == 0L -> "Not checked yet"
                rs.serverAt == 0L -> "Checked ${clock(rs.checkedAt)}"
                else -> "Phone " + (rs.phoneAt?.let { clock(it) } ?: "missing") + " · Server ${clock(rs.serverAt)}" +
                    if (rs.useServer) " · using server" else ""
            }
            SettingRow("Last check", lastSub) {
                TextButton(enabled = vault != null && !checking, onClick = {
                    val uri = vault ?: return@TextButton
                    checking = true
                    scope.launch { withContext(Dispatchers.IO) { VaultRoute.check(ctx, uri, force = true) }; checking = false }
                }) { Text(if (checking) "Checking…" else "Check now", color = Primary) }
            }
            Text("Uses the Jarvis address and token above. Nothing new to type.", fontSize = 12.sp, color = Muted,
                modifier = Modifier.padding(bottom = 6.dp))
        }
        SettingRow("Notes used") { TextButton(onClick = { notesOpen = !notesOpen }) { Text(if (notesOpen) "⌄" else "›", color = Muted) } }
        if (notesOpen) {
            PathField("Projects folder", prefs.ideasDir) { prefs.ideasDir = it }
            PathField("Images folder", prefs.imagesDir) { prefs.imagesDir = it }
            listOf(VaultTasks.QUICK_TASK, Jobs.DIR, FlightNotes.STUCK, FlightNotes.FOCUS_LOG, Habits.PATH, DeckConfig.PATH).forEach {
                Text(it, fontSize = 12.sp, color = Muted, modifier = Modifier.padding(vertical = 2.dp))
            }
        }
        }
        }
        Text("", Modifier.padding(bottom = 16.dp))
    }
}

private enum class SettingsPage { MAIN, REMINDERS, CALENDARS, JARVIS, UPDATES, VAULT }

/** "‹ Jarvis": a settings sub-page title that goes back to the main page. */
@Composable
private fun SubPageTitle(title: String, onBack: () -> Unit) {
    Text(
        "‹ $title", fontSize = 22.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 12.dp, bottom = 12.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onBack).padding(vertical = 4.dp)
    )
}

/** A small two-or-more option switch, like "Under task | Sheet". */
@Composable
private fun Segmented(options: List<String>, selected: Int, onPick: (Int) -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(10.dp)).background(Surface2).padding(2.dp)) {
        options.forEachIndexed { i, o ->
            val on = i == selected
            Text(
                o, fontSize = 12.sp, color = if (on) Primary else Muted, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (on) PrimaryTint else Color.Transparent)
                    .clickable { onPick(i) }.padding(horizontal = 9.dp, vertical = 4.dp)
            )
        }
    }
}

private val STALE_STEPS = listOf(2, 5, 15, 30)

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
