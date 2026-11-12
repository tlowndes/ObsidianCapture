package uk.tomlowndes.capture.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import uk.tomlowndes.capture.QuickGuard
import uk.tomlowndes.capture.Subtasks
import uk.tomlowndes.capture.TaskNote
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Quick Task guard (quick-task-guard-v1), Windows side: the Tasks banner, Show me, and Settings › Quick Task backups. */

private val AmberLine = Color(0xFF5A4A26)

@Composable
private fun GhostButton(text: String, onClick: () -> Unit) = Text(
    text, color = Primary, fontSize = 13.sp, fontWeight = FontWeight.Bold,
    modifier = Modifier.clip(CircleShape).border(1.dp, Outline, CircleShape).hand().clickable(onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 8.dp)
)

@Composable
fun QuickGuardBanner(a: QuickGuard.Alert, onFix: () -> Unit, onShow: () -> Unit, onFine: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(shape).background(AmberTint).border(1.dp, AmberLine, shape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("⚠ Quick Task changed outside the app", color = Amber, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp)
        Text(a.headline() + ".", color = TextMain, fontSize = 13.sp, modifier = Modifier.weight(1f))
        if (a.fixable) PrimaryButton("Fix it", onClick = onFix)
        if (a.fixable) GhostButton("Show me", onShow) else PrimaryButton("Show me", onClick = onShow)
        TextAction("It's fine", Muted, onClick = onFine)
    }
}

@Composable
private fun Mono(text: String, color: Color) =
    Text(text, color = color, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, maxLines = 1)

@Composable
private fun GuardBox(title: String, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(shape).background(Surface1).border(1.dp, Outline, shape).padding(12.dp)) {
        Text(title, color = Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 4.dp))
        content()
    }
}

/** Show me: just the odd changes, before and after. */
@Composable
fun QuickGuardDialog(a: QuickGuard.Alert, since: Long?, onFix: () -> Unit, onRestore: () -> Unit, onDismiss: () -> Unit) {
    DialogCard("What changed in Quick Task", onDismiss) {
        Sub((since?.let { "Since ${copyTime(it)} · " } ?: "") + "compared with the app's last copy", Modifier.padding(bottom = 12.dp))
        if (a.emptied) GuardBox("Note emptied or missing") { Mono("Restore puts the last good copy back.", Muted) }
        if (a.flattened.isNotEmpty()) GuardBox("Steps lost their indent (${a.flattened.size})") {
            a.flattened.take(4).forEach { f -> Mono(f.was.replace("\t", "    "), TaskGreen); Mono(f.now, Warn) }
            if (a.flattened.size > 4) Mono("  + ${a.flattened.size - 4} more", Muted)
        }
        if (a.missing.isNotEmpty()) GuardBox("Open tasks missing (${a.missing.size})") {
            a.missing.take(6).forEach { Mono("- [ ] $it", Warn) }
            if (a.missing.size > 6) Mono("  + ${a.missing.size - 6} more", Muted)
        }
        DialogButtons(onDismiss) {
            TextAction("Restore whole note…", onClick = onRestore)
            if (a.fixable) PrimaryButton("Fix the ${a.flattened.size} step${if (a.flattened.size == 1) "" else "s"}", onClick = onFix)
        }
    }
}

fun copyTime(ms: Long): String {
    val t = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
    val today = LocalDate.now()
    val day = when (t.toLocalDate()) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> t.toLocalDate().format(DateTimeFormatter.ofPattern("EEE d MMM"))
    }
    return "$day ${t.format(DateTimeFormatter.ofPattern("HH:mm"))}"
}

/** Settings › Quick Task backups: the switch and this PC's last 10 copies, with View and Restore. */
@Composable
fun QuickBackupsSettings(state: AppState) {
    val p = state.prefs
    val scope = rememberCoroutineScope()
    var on by remember { mutableStateOf(p.quickGuard) }
    var copies by remember { mutableStateOf<List<QuickGuard.Copy>>(emptyList()) }
    var viewing by remember { mutableStateOf<QuickGuard.Copy?>(null) }
    var restoring by remember { mutableStateOf<QuickGuard.Copy?>(null) }
    var refresh by remember { mutableStateOf(0) }
    LaunchedEffect(state.vault, on, refresh) { copies = state.io { state.writer()?.quickCopies() }.orEmpty() }

    Setting("Watch Quick Task for odd changes", "Keeps the last ${QuickGuard.KEEP} copies on this PC and flags flattened steps, lots of tasks gone, or the note emptied") {
        Toggle(on) { on = it; p.quickGuard = it }
    }
    if (on && copies.isNotEmpty()) {
        val good = copies.firstOrNull { it.good }?.let { runCatching { it.text() }.getOrNull() }
        val shape = RoundedCornerShape(12.dp)
        Column(Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(shape).background(Surface1).border(1.dp, Outline, shape)) {
            copies.forEach { c ->
                val text = remember(c) { runCatching { c.text() }.getOrDefault("") }
                val open = TaskNote.list(text.split("\n"))
                val steps = open.count { Subtasks.depth(it.raw) > 0 }
                val tag = if (c.good) "good" else good?.let { QuickGuard.check(it, text) }?.let { a ->
                    when {
                        a.emptied -> "note emptied"
                        a.flattened.isNotEmpty() -> "${a.flattened.size} flattened"
                        a.missing.isNotEmpty() -> "${a.missing.size} missing"
                        else -> "odd"
                    }
                } ?: "odd"
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(copyTime(c.at), color = TextMain, fontSize = 13.5.sp, modifier = Modifier.weight(1f))
                    Text("${open.size - steps} open" + if (steps > 0) " · $steps steps" else "", color = Muted, fontSize = 12.sp)
                    Text(tag, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, color = if (c.good) Muted else Amber,
                        modifier = Modifier.clip(CircleShape).background(if (c.good) Surface2 else AmberTint).padding(horizontal = 7.dp, vertical = 1.dp))
                    TextAction("View") { viewing = c }
                    TextAction("Restore") { restoring = c }
                }
            }
        }
    }

    viewing?.let { c ->
        DialogCard(copyTime(c.at), { viewing = null }) {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
                Text(runCatching { c.text() }.getOrDefault(""), color = TextMain, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace)
            }
            DialogButtons({ viewing = null }) { PrimaryButton("Restore this") { viewing = null; restoring = c } }
        }
    }
    restoring?.let { c ->
        DialogCard("Restore Quick Task?", { restoring = null }) {
            Sub("Quick Task goes back to the copy from ${copyTime(c.at)}. The note as it is now is kept as a copy first.", Modifier.padding(bottom = 14.dp))
            DialogButtons({ restoring = null }) {
                PrimaryButton("Restore") {
                    restoring = null
                    scope.launch {
                        state.io("Couldn't restore") { state.writer()!!.restoreQuick(c) } ?: return@launch
                        state.changes++
                        state.say("Quick Task restored")
                        refresh++
                    }
                }
            }
        }
    }
}
