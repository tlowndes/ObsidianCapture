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
import uk.tomlowndes.capture.ReadNote
import uk.tomlowndes.capture.Tags
import java.io.File
import java.time.LocalDate
import javax.swing.JFileChooser
import javax.swing.UIManager

@Composable
fun MainWindow(state: AppState) {
    Row(Modifier.fillMaxSize().background(Bg)) {
        Sidebar(state)
        Box(Modifier.fillMaxHeight().width(1.dp).background(Divider))
        Box(Modifier.weight(1f).fillMaxHeight()) {
            if (state.vault == null && state.tab != Tab.SETTINGS) Welcome(state)
            else when (state.tab) {
                Tab.DECK -> DeckPane(state)
                Tab.TASKS -> TasksPane(state)
                Tab.PROJECTS -> ProjectsPane(state)
                Tab.VAULT -> VaultPane(state)
                Tab.READ -> ReadPane(state)
                Tab.FIND -> FindPane(state)
                Tab.SETTINGS -> SettingsPane(state)
            }
            SnackbarHost(state.snack, Modifier.align(Alignment.BottomEnd).padding(24.dp).widthIn(max = 460.dp)) { data ->
                Snackbar(data, containerColor = SnackBg, contentColor = SnackText,
                    actionColor = SnackAction, shape = RoundedCornerShape(12.dp))
            }
        }
    }
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
    }
}

@Composable
private fun Sidebar(state: AppState) {
    Column(Modifier.width(220.dp).fillMaxHeight().background(SideBg).padding(horizontal = 12.dp, vertical = 18.dp)) {
        Column(Modifier.padding(start = 10.dp, end = 10.dp, bottom = 14.dp)) {
            Text("Flight Deck", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextMain)
            Text(state.prefs.vault?.absolutePath ?: "No vault picked", fontSize = 11.sp, color = Muted,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 2.dp).padding(bottom = 16.dp).clip(CircleShape).background(Primary)
                .hand().clickable { state.quickVisible = true }.padding(vertical = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically
        ) {
            Text("＋ Capture", color = OnPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text("Ctrl N", color = OnPrimary, fontSize = 10.5.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0x22000000)).padding(horizontal = 5.dp, vertical = 1.dp))
        }
        NavItem(state, Tab.DECK, "◎", "Deck")
        NavItem(state, Tab.TASKS, "✓", "Tasks") { if (state.overdue > 0) Count(state.overdue, warn = true, text = "${state.overdue} overdue") }
        TagNav(state)
        NavItem(state, Tab.PROJECTS, "💡", "Projects") { if (state.activeProjects > 0) Count(state.activeProjects) }
        NavItem(state, Tab.VAULT, "🗂", "Vault")
        NavItem(state, Tab.READ, "📖", "Read later") { if (state.unread > 0) Count(state.unread) }
        Spacer(Modifier.weight(1f))
        NavItem(state, Tab.FIND, "🔍", "Find a note") { Text("Ctrl K", fontSize = 11.sp, color = Faint, fontWeight = FontWeight.SemiBold) }
        NavItem(state, Tab.SETTINGS, "⚙", "Settings") { if (DesktopUpdate.waiting()) Box(Modifier.size(8.dp).clip(CircleShape).background(Primary)) }
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

/** TAGS under Tasks: Work, Personal and the most used tags, with counts. A click filters the Tasks pane. */
@Composable
private fun TagNav(state: AppState) {
    val tags = state.tagCounts.filter { it.second > 0 }
    if (tags.isEmpty()) return
    Text("TAGS", fontSize = 10.5.sp, letterSpacing = 1.3.sp, color = Faint, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 4.dp))
    tags.forEach { (tag, n) ->
        val on = state.tab == Tab.TASKS && state.taskTag == tag
        val (fg, _) = tagColour(tag)
        Row(
            Modifier.fillMaxWidth().padding(bottom = 1.dp).clip(RoundedCornerShape(10.dp)).background(if (on) PrimaryTint else Color.Transparent)
                .hand().clickable { state.taskTag = if (on) null else tag; state.taskNote = null; state.tab = Tab.TASKS }
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(Modifier.width(20.dp), contentAlignment = Alignment.Center) { Box(Modifier.size(8.dp).clip(CircleShape).background(fg)) }
            Text(Tags.label(tag), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (on) Primary else Muted,
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("$n", fontSize = 11.sp, color = Faint, fontWeight = FontWeight.SemiBold)
        }
    }
    Box(Modifier.height(8.dp))
}

@Composable
private fun NavItem(state: AppState, tab: Tab, icon: String, name: String, trailing: @Composable () -> Unit = {}) {
    val on = state.tab == tab
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
