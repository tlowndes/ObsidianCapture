package uk.tomlowndes.capture.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import kotlinx.coroutines.launch
import uk.tomlowndes.capture.Updater
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import uk.tomlowndes.capture.CalendarConfig
import uk.tomlowndes.capture.CalendarSource
import uk.tomlowndes.capture.DeckConfig
import uk.tomlowndes.capture.DeckLayout
import uk.tomlowndes.capture.ReminderPlan
import uk.tomlowndes.capture.ReminderSettings

/** Settings pages down the left (windows-settings-v1), like the phone's tidied Settings. */
enum class SettingsPage(val label: String) {
    GENERAL("General"), VAULT("Vault & folders"), DECK("Deck & weather"), CALENDARS("Calendars"),
    REMINDERS("Reminders"), JARVIS("Jarvis"), PRINTER("Printer"), UPDATES("Updates")
}

@Composable
fun SettingsPane(state: AppState) {
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(220.dp).fillMaxHeight().padding(horizontal = 12.dp, vertical = 22.dp)) {
            SettingsPage.values().forEach { pg ->
                val on = state.settingsPage == pg
                Text(
                    pg.label, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = if (on) Primary else Muted,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp).clip(RoundedCornerShape(10.dp))
                        .background(if (on) PrimaryTint else Color.Transparent).hand().clickable { state.settingsPage = pg }
                        .padding(horizontal = 12.dp, vertical = 9.dp)
                )
            }
        }
        Box(Modifier.fillMaxHeight().width(1.dp).background(Outline))
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 26.dp)) {
            H1(state.settingsPage.label, size = 26)
            Column(Modifier.widthIn(max = 760.dp).padding(top = 10.dp)) {
                when (state.settingsPage) {
                    SettingsPage.GENERAL -> GeneralPage(state)
                    SettingsPage.VAULT -> VaultPage(state)
                    SettingsPage.DECK -> DeckPage(state)
                    SettingsPage.CALENDARS -> CalendarsPage(state)
                    SettingsPage.REMINDERS -> RemindersPage(state)
                    SettingsPage.JARVIS -> JarvisSettingsPage(state)
                    SettingsPage.PRINTER -> PrinterSettingsPage(state)
                    SettingsPage.UPDATES -> UpdatesPage(state)
                }
            }
        }
    }
}

@Composable
private fun GeneralPage(state: AppState) {
    val p = state.prefs
    var recurring by remember { mutableStateOf(p.recurring) }
    var autostart by remember { mutableStateOf(Autostart.enabled()) }
    Setting("Quick capture shortcut",
        if (state.hotkeyFailed) "Another app is using this one, pick another" else "Opens the capture popup from any app") {
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Hotkey.CHOICES.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { c -> Chip(c.label, c.label == state.hotkeyLabel, small = true) { p.hotkey = c.label; state.hotkeyLabel = c.label } }
                }
            }
        }
    }
    Setting("Start with Windows", "Sits in the tray, ready for the shortcut and reminders") {
        Toggle(autostart) { on -> runCatching { Autostart.set(on) }.onSuccess { autostart = on }.onFailure { state.say("Couldn't change it: ${it.message}") } }
    }
    Setting("Repeat recurring tasks", "Ticking a 🔁 task adds its next one, like the Tasks plugin") {
        Toggle(recurring) { recurring = it; p.recurring = it }
    }
}

@Composable
private fun VaultPage(state: AppState) {
    val p = state.prefs
    var ideas by remember { mutableStateOf(p.ideasDir) }
    var images by remember { mutableStateOf(p.imagesDir) }
    Setting("Vault folder", p.vault?.absolutePath ?: "Not picked yet") { Chip("Change…", small = true) { pickVault(state) } }
    Text("FOLDERS", fontSize = 12.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold, color = Muted, modifier = Modifier.padding(top = 26.dp, bottom = 4.dp))
    FolderSetting("Projects", ideas, "03 - Projects") { ideas = it; p.ideasDir = it }
    FolderSetting("Images", images, "99 - Meta/Assets") { images = it; p.imagesDir = it }
    Sub("The same folders as the phone app.", Modifier.padding(top = 10.dp))
    Text("QUICK TASK BACKUPS", fontSize = 12.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold, color = Muted, modifier = Modifier.padding(top = 26.dp, bottom = 4.dp))
    QuickBackupsSettings(state)
}

@Composable
private fun DeckPage(state: AppState) {
    val p = state.prefs
    var town by remember { mutableStateOf(p.weatherPlace) }
    var finding by remember { mutableStateOf(false) }
    var layout by remember { mutableStateOf<DeckLayout?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(state.vault) { layout = state.vault?.let { v -> state.io { DeckConfig.load(v) } } }

    fun findTown() {
        if (town.isBlank() || finding) return
        finding = true
        scope.launch {
            val hit = state.io("Couldn't reach the weather service") { DesktopWeather.find(town) }
            finding = false
            if (hit == null) { state.say("Couldn't find “$town”"); return@launch }
            p.weatherPlace = hit.third; p.weatherLatLon = hit.first to hit.second; p.weatherJson = null
            town = hit.third
            state.say("Weather set to ${hit.third}")
            state.changed()
        }
    }
    fun save(l: DeckLayout) {
        layout = l
        val v = state.vault ?: return
        scope.launch { state.io("Couldn't save the card order") { DeckConfig.save(v, l) }; state.changed() }
    }

    Setting("Weather", if (p.weatherLatLon != null) "Deck shows today's forecast for ${p.weatherPlace}" else "The town Deck shows the forecast for") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(town, { town = it }, Modifier.width(220.dp).onKeys(enter = ::findTown), "Manchester")
            Chip(if (finding) "Finding…" else "Set", small = true) { findTown() }
        }
    }
    Text("CARDS ON THE DECK", fontSize = 12.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold, color = Muted, modifier = Modifier.padding(top = 26.dp, bottom = 4.dp))
    Sub("Shared with the phone through the vault. ↑ ↓ to reorder.", Modifier.padding(bottom = 6.dp))
    val l = layout
    if (l == null) Sub("Pick a vault first.") else l.order.forEachIndexed { i, card ->
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(card.label, fontSize = 14.sp, color = if (card in l.hidden) Muted else TextMain, modifier = Modifier.weight(1f))
            TextAction("↑", if (i > 0) Primary else Faint) { if (i > 0) save(l.moved(i, i - 1)) }
            TextAction("↓", if (i < l.order.lastIndex) Primary else Faint) { if (i < l.order.lastIndex) save(l.moved(i, i + 1)) }
            Spacer(Modifier.width(10.dp))
            Toggle(card !in l.hidden) { save(l.toggled(card)) }
        }
    }
}

private val CAL_COLOURS = listOf("#7aa2ff", "#f5c06a", "#5fd39a", "#b39dff", "#ff8a80")

private fun hex(c: String): Color = runCatching { Color(0xFF000000 or c.removePrefix("#").toLong(16)) }.getOrDefault(Primary)

@Composable
private fun CalendarsPage(state: AppState) {
    var sources by remember { mutableStateOf<List<CalendarSource>?>(null) }
    var counts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var url by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(state.vault, state.changes) {
        val v = state.vault ?: return@LaunchedEffect
        sources = state.io("Couldn't read the calendars") { CalendarConfig.load(v) }
        counts = state.io { DesktopCalendar.load(v).second.groupingBy { it.calendarId }.eachCount() } ?: emptyMap()
    }
    fun save(list: List<CalendarSource>) {
        val v = state.vault ?: return
        sources = list
        scope.launch {
            state.io("Couldn't save the calendars") { CalendarConfig.save(v, list) }
            DesktopCalendar.forget(); state.changed()
        }
    }
    fun add() {
        if (url.isBlank() || name.isBlank() || adding) return
        adding = true
        scope.launch {
            val ok = state.io("Couldn't read that link") { DesktopCalendar.fetch(url.trim()); true } == true
            adding = false
            if (!ok) return@launch
            val list = sources.orEmpty()
            save(list + CalendarSource(System.currentTimeMillis().toString(), name.trim(), url.trim(), CAL_COLOURS[list.size % CAL_COLOURS.size]))
            url = ""; name = ""
            state.say("Calendar added")
        }
    }
    Sub("The Proton calendars the Deck, Upcoming and reminders use. Shared with the phone through the vault.", Modifier.padding(bottom = 6.dp))
    val list = sources
    if (list == null) Sub("Pick a vault first.")
    else if (list.isEmpty()) Sub("No calendars yet.", Modifier.padding(vertical = 10.dp))
    else list.forEach { c ->
        Column {
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).clip(RoundedCornerShape(4.dp)).background(hex(c.colour)))
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(c.name, fontSize = 14.5.sp, color = TextMain)
                    Text(if (!c.enabled) "Proton · switched off" else "Proton · ${counts[c.id] ?: 0} events this week", fontSize = 12.sp, color = Muted)
                }
                Toggle(c.enabled) { on -> save(list.map { if (it.id == c.id) it.copy(enabled = on) else it }) }
                TextAction("Remove", Muted, Modifier.padding(start = 12.dp)) { save(list.filter { it.id != c.id }) }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Outline))
        }
    }
    Text("ADD A PROTON CALENDAR", fontSize = 12.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold, color = Muted, modifier = Modifier.padding(top = 26.dp, bottom = 8.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Field(url, { url = it }, Modifier.weight(1f), "https://calendar.proton.me/api/calendar/v1/url/…")
        Field(name, { name = it }, Modifier.width(150.dp).onKeys(enter = ::add), "Name")
        PrimaryButton(if (adding) "Checking…" else "Add", enabled = !adding) { add() }
    }
    Sub("In Proton Calendar: Share › Full view › copy the link.", Modifier.padding(top = 8.dp))
}

private fun hm(m: Int) = "%02d:%02d".format(m / 60, m % 60)
private fun parseHm(s: String): Int? = Regex("^(\\d{1,2}):?(\\d{2})$").find(s.trim())?.let { m ->
    val h = m.groupValues[1].toInt(); val mi = m.groupValues[2].toInt()
    if (h in 0..23 && mi in 0..59) h * 60 + mi else null
}

/** A HH:mm box that saves when the time is valid. */
@Composable
private fun TimeBox(minute: Int, onChange: (Int) -> Unit) {
    var text by remember(minute) { mutableStateOf(hm(minute)) }
    Field(text, { t -> text = t; parseHm(t)?.let(onChange) }, Modifier.width(86.dp), "09:00")
}

@Composable
private fun RemindersPage(state: AppState) {
    val p = state.prefs
    var s by remember { mutableStateOf(p.reminders) }
    var sound by remember { mutableStateOf(p.reminderSound) }
    fun set(n: ReminderSettings) { s = n; p.reminders = n }
    Setting("Before a timed task", "Tasks with a start time, like ones dragged into Upcoming") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ReminderPlan.LEADS.forEach { m -> Chip("${m}m", s.beforeMinutes == m, small = true) { set(s.copy(beforeMinutes = m)) } }
            Spacer(Modifier.width(8.dp))
            Toggle(s.beforeOn) { set(s.copy(beforeOn = it)) }
        }
    }
    Setting("Task due alerts", "One card listing what's due today") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TimeBox(s.dueMinute) { set(s.copy(dueMinute = it)) }; Toggle(s.dueOn) { set(s.copy(dueOn = it)) }
        }
    }
    Setting("Morning briefing", "Today's tasks and next event") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TimeBox(s.morningMinute) { set(s.copy(morningMinute = it)) }; Toggle(s.morningOn) { set(s.copy(morningOn = it)) }
        }
    }
    Setting("Evening wrap-up", "Tick off or move the rest") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TimeBox(s.eveningMinute) { set(s.copy(eveningMinute = it)) }; Toggle(s.eveningOn) { set(s.copy(eveningOn = it)) }
        }
    }
    Setting("Weekly review", "Sunday") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TimeBox(s.weeklyMinute) { set(s.copy(weeklyMinute = it)) }; Toggle(s.weeklyOn) { set(s.copy(weeklyOn = it)) }
        }
    }
    Setting("Play a sound", "Windows' default notification sound") { Toggle(sound) { sound = it; p.reminderSound = it } }
    Setting("Send a test", "Shows one now in the bottom-right") { Chip("Show one now", small = true) { DesktopReminders.show(state, Reminder.Test()) } }
    Sub("Reminders only show while Flight Deck is running (it sits in the tray). Set separately on the phone and the PC.", Modifier.padding(top = 12.dp))
}

@Composable
private fun UpdatesPage(state: AppState) {
    val p = state.prefs
    var hasToken by remember { mutableStateOf(p.githubToken != null) }
    var token by remember { mutableStateOf("") }
    var checks by remember { mutableStateOf(p.updateChecks) }
    val latest = DesktopUpdate.release?.tag ?: p.updateLatest
    val scope = rememberCoroutineScope()
    Setting("GitHub token", if (hasToken) "Saved, encrypted for your Windows account" else "Read-only, for app updates and the GitHub line on projects. Fine-grained: ObsidianCapture, Contents read") {
        if (hasToken) Chip("Remove", small = true) { p.githubToken = null; hasToken = false }
        else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(token, { token = it }, Modifier.width(220.dp), "github_pat_…")
            Chip("Save", small = true) {
                if (token.isNotBlank()) runCatching { p.githubToken = token }
                    .onSuccess { token = ""; hasToken = true; state.changed() }
                    .onFailure { state.say("Couldn't save it: ${it.message}") }
            }
        }
    }
    Setting("Check for updates on launch", "Asks GitHub for a newer release when the app opens. Uses the GitHub token above") {
        Toggle(checks) { checks = it; p.updateChecks = it }
    }
    Setting("Installed", "Latest: " + when {
        DesktopUpdate.phase == DesktopUpdate.Phase.CHECKING -> "checking…"
        latest == null -> "not checked yet"
        Updater.isNewer(latest, DesktopUpdate.installed) -> "${latest.removePrefix("v")} available"
        else -> "${latest.removePrefix("v")}, up to date"
    } + (DesktopUpdate.error?.let { " · $it" } ?: "")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(DesktopUpdate.installed, fontSize = 13.sp, color = Muted)
            val newer = latest != null && Updater.isNewer(latest, DesktopUpdate.installed)
            when (DesktopUpdate.phase) {
                DesktopUpdate.Phase.DOWNLOADING -> {
                    val t = DesktopUpdate.total
                    Text(if (t > 0) "Downloading ${(DesktopUpdate.done * 100 / t).toInt()}%" else "Downloading…", fontSize = 13.sp, color = Muted)
                }
                DesktopUpdate.Phase.READY -> PrimaryButton("Restart and install") { DesktopUpdate.restartAndInstall() }
                DesktopUpdate.Phase.CHECKING -> {}
                else -> if (newer) PrimaryButton("Update") { scope.launch { DesktopUpdate.update(p) } }
            }
            val busy = DesktopUpdate.phase == DesktopUpdate.Phase.DOWNLOADING || DesktopUpdate.phase == DesktopUpdate.Phase.READY
            if (!busy) Chip("Check now", small = true) {
                scope.launch {
                    DesktopUpdate.check(p, force = true)
                    if (DesktopUpdate.phase == DesktopUpdate.Phase.AVAILABLE) { p.updateSkip = null; DesktopUpdate.skip = null }
                }
            }
        }
    }
}

@Composable
internal fun Setting(title: String, sub: String, control: @Composable () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 20.dp)) {
                Text(title, fontSize = 14.5.sp, color = TextMain)
                Text(sub, fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 2.dp))
            }
            control()
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Outline))
    }
}

@Composable
private fun FolderSetting(title: String, value: String, placeholder: String, onChange: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 14.sp, color = TextMain, modifier = Modifier.width(140.dp))
        Field(value, onChange, Modifier.weight(1f), placeholder)
    }
}

/** The per-user Run key, so the app starts (hidden, in the tray) when you sign in. */
object Autostart {
    private const val KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    private const val NAME = "Vault Capture"

    /** The installed launcher (set by the packaged app); null when run from Gradle. */
    private val exe: String? get() = System.getProperty("jpackage.app-path")

    fun enabled(): Boolean = runCatching { Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, KEY, NAME) }.getOrDefault(false)

    fun set(on: Boolean) {
        if (on) {
            val path = exe ?: error("only works in the installed app")
            Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, KEY, NAME, "\"$path\" --hidden")
        } else if (enabled()) {
            Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, KEY, NAME)
        }
    }
}

@Composable
private fun JarvisSettingsPage(state: AppState) {
    val p = state.prefs
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf(p.jarvisAddress) }
    var hasToken by remember { mutableStateOf(p.jarvisToken != null) }
    var token by remember { mutableStateOf("") }
    var speak by remember { mutableStateOf(p.jarvisSpeak) }
    var speed by remember { mutableStateOf(p.jarvisSpeed) }
    var hold by remember { mutableStateOf(p.jarvisHoldSpace) }
    var ping by remember { mutableStateOf<uk.tomlowndes.capture.Jarvis.Ping?>(null) }
    var testing by remember { mutableStateOf(false) }
    Setting("Address", "Your Jarvis box on Tailscale, like http://jarvis:8787. Type demo to try it without one") {
        Field(address, { address = it; p.jarvisAddress = it; ping = null }, Modifier.width(300.dp), "http://jarvis:8787")
    }
    Setting("Token", if (hasToken) "Saved, encrypted for this Windows user. Never written to the vault" else "The same token as the phone") {
        if (hasToken) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Saved ✓", fontSize = 13.sp, color = TaskGreen)
            Chip("Replace", small = true) { hasToken = false }
            Chip("Remove", small = true, color = Warn) { p.jarvisToken = null; hasToken = false; token = "" }
        } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(token, { token = it }, Modifier.width(220.dp), "Paste the token")
            PrimaryButton("Save", enabled = token.isNotBlank()) { p.jarvisToken = token; token = ""; hasToken = true; ping = null }
        }
    }
    Setting("Speak replies", "Read Jarvis's answers out with the Windows voice") {
        Toggle(speak) { speak = it; p.jarvisSpeak = it }
    }
    Setting("Speed", "How fast replies are read out") {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            uk.tomlowndes.capture.Jarvis.SPEEDS.forEach { sp ->
                Chip(uk.tomlowndes.capture.Jarvis.speedLabel(sp), kotlin.math.abs(speed - sp) < 0.01f, small = true) { speed = sp; p.jarvisSpeed = sp }
            }
        }
    }
    Setting("Hold Space to talk", "In the Jarvis pane, when the text box is empty") {
        Toggle(hold) { hold = it; p.jarvisHoldSpace = it }
    }
    Setting("Test connection", ping?.message ?: "Checks the box answers and the token is right") {
        Chip(if (testing) "Testing…" else "Test", small = true, color = ping?.let { if (it.ok) TaskGreen else Warn }) {
            if (testing) return@Chip
            val a = p.jarvisAddress
            val t = p.jarvisToken
            if (a.isBlank()) { ping = uk.tomlowndes.capture.Jarvis.Ping(false, 0, "Add the address first"); return@Chip }
            if (t == null && !uk.tomlowndes.capture.Jarvis.isDemo(a)) { ping = uk.tomlowndes.capture.Jarvis.Ping(false, 0, "Add the token first"); return@Chip }
            testing = true
            scope.launch {
                ping = state.io { uk.tomlowndes.capture.Jarvis.ping(a, t.orEmpty()) }
                testing = false
            }
        }
    }
}

@Composable
private fun PrinterSettingsPage(state: AppState) {
    val p = state.prefs
    val scope = rememberCoroutineScope()
    var choosing by remember { mutableStateOf(false) }
    var port by remember { mutableStateOf(p.printerAddress) }
    var printing by remember { mutableStateOf(false) }
    if (choosing) {
        Box(Modifier.padding(top = 10.dp)) { PrinterChooser(state) { choosing = false; port = p.printerAddress } }
        return
    }
    Setting(port?.let { "${p.printerName} · $it" } ?: "No printer yet", "Phomemo M02 Pro, paired in Windows Bluetooth. Used by Daily Slip and Print summary") {
        Chip(if (port == null) "Choose" else "Change", small = true) { choosing = true }
    }
    Setting("Test print", "Prints a short slip to check it works") {
        Chip(if (printing) "Printing…" else "Print a test", small = true) {
            if (printing) return@Chip
            if (port == null) { choosing = true; return@Chip }
            printing = true
            scope.launch { testPrint(state); printing = false }
        }
    }
    Setting("Bluetooth", "Pair the printer here first if it isn't listed") {
        Chip("Open Bluetooth settings", small = true) { state.browse("ms-settings:bluetooth") }
    }
}
