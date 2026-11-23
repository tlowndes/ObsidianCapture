package uk.tomlowndes.capture.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uk.tomlowndes.capture.DeckConfig
import uk.tomlowndes.capture.HomeCard
import uk.tomlowndes.capture.ReadNote
import uk.tomlowndes.capture.Tags
import java.io.File
import java.time.LocalDate
import javax.swing.JFileChooser
import javax.swing.UIManager

@Composable
fun MainWindow(state: AppState) {
    // Piholegotchi shows the Focus session while it runs (windows-settings-gaps-v1).
    LaunchedEffect(state.focus) { DesktopPi.sendFocus(state) }
    Row(Modifier.fillMaxSize().background(Bg)) {
        Sidebar(state)
        Box(Modifier.fillMaxHeight().width(1.dp).background(Divider))
        Box(Modifier.weight(1f).fillMaxHeight()) {
            if (state.vault == null && state.tab != Tab.SETTINGS) Welcome(state)
            else when (state.tab) {
                Tab.DECK -> DeckPane(state)
                Tab.HANGAR -> HangarPane(state)
                // Fold the extra doors (fold-doors-v1): Upcoming is a switch inside Tasks.
                Tab.TASKS, Tab.UPCOMING -> Column(Modifier.fillMaxSize()) {
                    Pill(listOf("Today", "Upcoming"), if (state.tab == Tab.UPCOMING) 1 else 0, Modifier.padding(start = 28.dp, top = 14.dp).width(240.dp)) {
                        state.tab = if (it == 1) Tab.UPCOMING else Tab.TASKS
                    }
                    Box(Modifier.weight(1f)) { TasksPane(state, upcoming = state.tab == Tab.UPCOMING) }
                }
                // Projects, Read later, Inbox, Home and People are chips along the top of Vault.
                Tab.VAULT, Tab.PROJECTS, Tab.READ, Tab.INBOX, Tab.HOME, Tab.PEOPLE, Tab.FIND -> Column(Modifier.fillMaxSize()) {
                    VaultChips(state)
                    Box(Modifier.weight(1f)) {
                        when (state.tab) {
                            Tab.PROJECTS -> ProjectsPane(state)
                            Tab.READ -> ReadPane(state)
                            Tab.FIND -> FindPane(state)
                            Tab.HOME -> HomePane(state)
                            Tab.INBOX -> InboxPane(state)
                            Tab.PEOPLE -> PeoplePane(state)
                            else -> VaultPane(state)
                        }
                    }
                }
                Tab.SETTINGS -> SettingsPane(state)
                Tab.WEEKLY -> WeeklyPane(state)
                Tab.ROUTINES -> RoutinesPane(state)
                Tab.JOBS -> JobsPane(state)
                // Capture is a page now: a box that opens quick capture, with Tower below it.
                Tab.JARVIS -> Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.padding(start = 28.dp, end = 28.dp, top = 16.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Surface1)
                            .border(1.dp, Outline, RoundedCornerShape(14.dp)).hand().clickable { state.quickVisible = true }.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("＋ Add a task or a link…", fontSize = 14.sp, color = Muted, modifier = Modifier.weight(1f))
                        Kbd("Ctrl"); Kbd("N")
                    }
                    Box(Modifier.weight(1f)) { JarvisPane(state) }
                }
            }
            SnackbarHost(state.snack, Modifier.align(Alignment.BottomEnd).padding(24.dp).widthIn(max = 460.dp)) { data ->
                Snackbar(data, containerColor = SnackBg, contentColor = SnackText,
                    actionColor = SnackAction, shape = RoundedCornerShape(12.dp))
            }
        }
    }
    if (state.slipOpen) DailySlipDialog(state) { state.slipOpen = false }
    state.printSummaryOf?.let { src -> PrintSummaryDialog(state, src) { state.printSummaryOf = null } }
    // The focus timer from Deck: a tray notification when it runs out, whichever tab is open.
    LaunchedEffect(state.focus) {
        val f = state.focus ?: return@LaunchedEffect
        if (f.pausedLeft != null || f.asking) return@LaunchedEffect
        delay(f.left())
        state.notify("Focus time's up", f.task.text)
    }
    // Sidebar counts, refreshed whenever notes may have changed.
    LaunchedEffect(state.vault, state.changes) {
        val today = LocalDate.now()
        state.io("Couldn't read the vault") {
            val tasks = state.writer()?.listTasks().orEmpty()
            Triple(
                tasks.count { t -> t.due?.isBefore(today) == true } to Tags.filterCounts(tasks, 6),
                state.ideaStore()?.list()?.count { it.status.lowercase() == "active" } ?: 0,
                state.readStore()?.inbox()?.let { ReadNote.unread(it).first } ?: 0
            )
        }?.let { (o, i, u) -> state.overdue = o.first; state.tagCounts = o.second; state.activeProjects = i; state.unread = u }
        state.vault?.let { v -> state.hangar = state.io("Couldn't read Hangar") { loadDeskHangar(state, v) } }
    }
}

@Composable
private fun Sidebar(state: AppState) {
    Column(Modifier.width(220.dp).fillMaxHeight().background(SideBg).padding(horizontal = 12.dp, vertical = 18.dp)) {
        Column(Modifier.padding(start = 10.dp, end = 10.dp, bottom = 14.dp)) {
            Text("Flight Deck", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextMain)
            Text(state.prefs.vault?.absolutePath ?: "No vault picked", fontSize = 11.sp, color = Muted,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            HeadlessSync.sidebarLine()?.let { (line, good) ->
                Text(line, fontSize = 11.sp, color = if (good) TaskGreen else Amber, modifier = Modifier.padding(top = 3.dp),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        // The phone's four destinations plus Settings (fold-doors-v1).
        NavItem(state, Tab.DECK, "◎", "Deck")
        NavItem(state, Tab.JARVIS, "＋", "Capture") { Text("Ctrl N", fontSize = 11.sp, color = Faint, fontWeight = FontWeight.SemiBold) }
        NavItem(state, Tab.TASKS, "✓", "Tasks", also = setOf(Tab.UPCOMING)) { if (state.overdue > 0) Count(state.overdue, warn = true, text = "${state.overdue} overdue") }
        NavItem(state, Tab.VAULT, "🗂", "Vault", also = VAULT_CHIPS.map { it.first }.toSet() + Tab.FIND)
        // Hangar under Vault, on every page: the PC's version of the phone's Hangar (hangar-windows-v1).
        SidebarHangar(state)
        state.routine?.let { r ->
            NavItem(state, Tab.ROUTINES, "⏱", r.routine.name) { Text("${r.step + 1}/${r.routine.steps.size}", fontSize = 11.sp, color = Faint, fontWeight = FontWeight.SemiBold) }
        }
        Spacer(Modifier.weight(1f))
        NavItem(state, Tab.SETTINGS, "⚙", "Settings") { if (DesktopUpdate.waiting()) Box(Modifier.size(8.dp).clip(CircleShape).background(Primary)) }
        Text("🔍 Ctrl K finds a note", fontSize = 11.sp, color = Faint, modifier = Modifier.padding(start = 12.dp, top = 2.dp, bottom = 4.dp)
            .clip(RoundedCornerShape(8.dp)).hand().clickable { state.tab = Tab.FIND })
        Column(
            Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(1.dp, Outline, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("Quick capture from any app:", fontSize = 11.5.sp, color = Muted)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { state.hotkeyLabel.split(" + ").forEach { Kbd(it) } }
            Text(
                if (state.hotkeyFailed) "Another app is using this shortcut. Pick another in Settings."
                else "Closing the window keeps it in the tray.",
                fontSize = 11.5.sp, color = if (state.hotkeyFailed) Warn else Muted, lineHeight = 16.sp
            )
        }
    }
}

@Composable
private fun NavItem(state: AppState, tab: Tab, icon: String, name: String, also: Set<Tab> = emptySet(), trailing: @Composable () -> Unit = {}) {
    val on = state.tab == tab || state.tab in also
    Row(
        Modifier.fillMaxWidth().padding(bottom = 2.dp).clip(RoundedCornerShape(12.dp)).background(if (on) PrimaryTint else Color.Transparent)
            .hand().clickable { if (tab == Tab.TASKS && state.tab != Tab.TASKS) state.taskNote = null; state.tab = tab }.padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(icon, fontSize = 15.sp, color = if (on) Primary else Muted, modifier = Modifier.width(20.dp))
        Text(name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (on) Primary else Muted, modifier = Modifier.weight(1f))
        trailing()
    }
}

/** Vault's chips (fold-doors-v1), with the tab each opens. */
private val VAULT_CHIPS = listOf(Tab.VAULT to "Notes", Tab.PROJECTS to "💡 Projects", Tab.READ to "📖 Read later",
    Tab.INBOX to "✉ Inbox", Tab.HOME to "🏠 Home", Tab.PEOPLE to "👥 People")

@Composable
private fun VaultChips(state: AppState) {
    // Inbox and Home only show while their Deck cards are on (or you're on that page).
    var hidden by remember { mutableStateOf<Set<HomeCard>>(emptySet()) }
    LaunchedEffect(state.vault) { hidden = withContext(Dispatchers.IO) { state.vault?.let { v -> runCatching { DeckConfig.load(v).hidden }.getOrNull() } ?: emptySet() } }
    Row(Modifier.padding(start = 28.dp, end = 28.dp, top = 14.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        VAULT_CHIPS.forEach { (tab, label) ->
            val show = when (tab) { Tab.INBOX -> HomeCard.INBOX !in hidden; Tab.HOME -> HomeCard.CHORES !in hidden; else -> true } || state.tab == tab
            if (show) {
                val n = when (tab) { Tab.PROJECTS -> state.activeProjects; Tab.READ -> state.unread; Tab.INBOX -> state.inboxOpen; else -> 0 }
                Chip(if (n > 0) "$label · $n" else label, selected = state.tab == tab || (tab == Tab.VAULT && state.tab == Tab.FIND), small = true) { state.tab = tab }
            }
        }
    }
}

@Composable
private fun Welcome(state: AppState) {
    Column(Modifier.fillMaxSize().padding(60.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        H1("Pick your vault")
        Sub("The folder that holds your Obsidian vault (the one with .obsidian in it). Notes are read and written there directly.",
            Modifier.padding(top = 8.dp, bottom = 20.dp).widthIn(max = 460.dp))
        PrimaryButton("Choose folder…") { pickVault(state) }
    }
}

/** Folder picker; checks it looks like a vault. */
fun pickVault(state: AppState) {
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
    val chooser = JFileChooser(state.prefs.vault ?: File(System.getProperty("user.home"))).apply {
        dialogTitle = "Choose your Obsidian vault folder"
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
    }
    if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return
    val dir = chooser.selectedFile ?: return
    state.setVault(dir)
    if (!File(dir, ".obsidian").isDirectory) state.say("No .obsidian folder in ${dir.name}. Is this the vault's top folder?")
}
