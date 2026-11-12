package uk.tomlowndes.capture

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Quick Task guard (quick-task-guard-v1), phone side: the Tasks banner, the Show me sheet and Settings › backups. */

private val AmberLine = Color(0xFF5A4A26)

@Composable
private fun Pill(label: String, filled: Boolean = false, quiet: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(99.dp)
    Text(
        label, fontSize = 13.sp, fontWeight = FontWeight.Bold,
        color = when { filled -> OnPrimary; quiet -> Muted; else -> Primary },
        modifier = Modifier.clip(shape)
            .then(if (filled) Modifier.background(Primary) else if (quiet) Modifier else Modifier.border(1.dp, Outline, shape))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 7.dp)
    )
}

/** "⚠ Quick Task changed outside the app" with Fix it / Show me / It's fine. */
@Composable
internal fun QuickGuardBanner(a: QuickGuard.Alert, onFix: () -> Unit, onShow: () -> Unit, onFine: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(RoundedCornerShape(16.dp)).background(AmberTint)
            .border(1.dp, AmberLine, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text("⚠ Quick Task changed outside the app", color = Amber, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(a.headline() + ".", fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp, bottom = 10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (a.fixable) Pill("Fix it", filled = true, onClick = onFix)
            Pill("Show me", filled = !a.fixable, onClick = onShow)
            Pill("It's fine", quiet = true, onClick = onFine)
        }
    }
}

@Composable
private fun DiffBox(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(14.dp)).background(Surface1)
            .border(1.dp, Outline, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(title, color = Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 4.dp))
        content()
    }
}

@Composable
private fun Mono(text: String, color: Color) = Text(
    text, color = color, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1, softWrap = false,
    modifier = Modifier.horizontalScroll(rememberScrollState())
)

/** Show me: just the odd changes, before and after. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QuickGuardSheet(a: QuickGuard.Alert, since: Long?, onFix: () -> Unit, onRestore: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Bg) {
        Column(Modifier.padding(start = 18.dp, end = 18.dp, bottom = 28.dp)) {
            Text("What changed in Quick Task", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(
                (since?.let { "since ${copyTime(it)} · " } ?: "") + "compared with the app's last copy",
                fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Muted, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
            )
            if (a.emptied) DiffBox("Note emptied or missing") { Mono("Restore puts the last good copy back.", Muted) }
            if (a.flattened.isNotEmpty()) DiffBox("Steps lost their indent (${a.flattened.size})") {
                a.flattened.take(3).forEach { f ->
                    Mono(f.was.replace("\t", "    "), Green)
                    Mono(f.now, Warn)
                }
                if (a.flattened.size > 3) Mono("  + ${a.flattened.size - 3} more", Muted)
            }
            if (a.missing.isNotEmpty()) DiffBox("Open tasks missing (${a.missing.size})") {
                a.missing.take(5).forEach { Mono("- [ ] $it", Warn) }
                if (a.missing.size > 5) Mono("  + ${a.missing.size - 5} more", Muted)
            }
            if (a.flattened.isEmpty() || a.missing.isEmpty()) DiffBox(
                listOfNotNull(
                    if (a.missing.isEmpty()) "Open tasks missing (0)" else null,
                    if (a.flattened.isEmpty()) "Steps flattened (0)" else null,
                ).joinToString(" · ")
            ) { Mono("Nothing else looks off.", Muted) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                if (a.fixable) Pill("Fix the ${a.flattened.size} step${if (a.flattened.size == 1) "" else "s"}", filled = true, onClick = onFix)
                Pill("Restore whole note…", filled = !a.fixable, onClick = onRestore)
            }
        }
    }
}

private val hm = DateTimeFormatter.ofPattern("HH:mm")

internal fun copyTime(ms: Long): String {
    val t = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
    val today = LocalDate.now()
    val day = when (t.toLocalDate()) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> t.toLocalDate().format(DateTimeFormatter.ofPattern("EEE d MMM"))
    }
    return "$day ${t.format(hm)}"
}

/** Settings › Quick Task backups: the on/off switch and this phone's last 10 copies, with View and Restore. */
@Composable
internal fun QuickBackupsSection(vault: Uri?, prefs: AppPrefs, snack: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var on by remember { mutableStateOf(prefs.quickGuard) }
    var copies by remember { mutableStateOf<List<QuickGuard.Copy>>(emptyList()) }
    var viewing by remember { mutableStateOf<QuickGuard.Copy?>(null) }
    var restoring by remember { mutableStateOf<QuickGuard.Copy?>(null) }
    var refresh by remember { mutableStateOf(0) }
    LaunchedEffect(vault, on, refresh) {
        val uri = vault ?: return@LaunchedEffect
        copies = withContext(Dispatchers.IO) { runCatching { VaultWriter(ctx, uri).quickCopies() }.getOrDefault(emptyList()) }
    }
    DeckCard {
        Box(Modifier.padding(horizontal = 14.dp)) {
            SettingRow("Watch for odd changes", "Flattened steps, lots of tasks gone, note moved or emptied") {
                AppSwitch(on) { on = it; prefs.quickGuard = it }
            }
        }
    }
    GroupLabel("Copies on this phone · last ${QuickGuard.KEEP}")
    if (copies.isEmpty()) Text("No copies yet. One is saved the next time Tasks opens.", fontSize = 13.sp, color = Muted)
    else DeckCard {
        Column {
            val good = copies.firstOrNull { it.good }?.let { runCatching { it.text() }.getOrNull() }
            copies.forEachIndexed { i, c ->
                if (i > 0) HorizontalDivider(color = Outline)
                val text = remember(c) { runCatching { c.text() }.getOrDefault("") }
                val lines = text.split("\n")
                val open = TaskNote.list(lines)
                val steps = open.count { Subtasks.depth(it.raw) > 0 }
                val tag = if (c.good) "good" else good?.let { QuickGuard.check(it, text) }?.let { a ->
                    when {
                        a.emptied -> "note emptied"
                        a.flattened.isNotEmpty() -> "${a.flattened.size} flattened"
                        a.missing.isNotEmpty() -> "${a.missing.size} missing"
                        else -> "odd"
                    }
                } ?: "odd"
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(copyTime(c.at), fontSize = 14.sp)
                        Text("${open.size - steps} open" + if (steps > 0) " · $steps steps" else "", fontSize = 12.sp, color = Muted)
                    }
                    Text(tag, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, color = if (c.good) Muted else Amber,
                        modifier = Modifier.clip(RoundedCornerShape(99.dp)).background(if (c.good) Surface2 else AmberTint).padding(horizontal = 7.dp, vertical = 1.dp))
                    Text(if (c.good) "Restore" else "View", color = Primary, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable { if (c.good) restoring = c else viewing = c }.padding(4.dp))
                }
            }
        }
    }
    Text("Restore puts the old copy back into the vault (keeping a copy of the current one first), so Sync carries it to your other devices. Copies stay on this phone.",
        fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 8.dp))

    viewing?.let { c ->
        AlertDialog(
            onDismissRequest = { viewing = null }, containerColor = Bg,
            title = { Text(copyTime(c.at)) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text(runCatching { c.text() }.getOrDefault(""), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            },
            confirmButton = { TextButton(onClick = { viewing = null; restoring = c }) { Text("Restore this", color = Primary) } },
            dismissButton = { TextButton(onClick = { viewing = null }) { Text("Close", color = Muted) } },
        )
    }
    restoring?.let { c ->
        AlertDialog(
            onDismissRequest = { restoring = null }, containerColor = Bg,
            title = { Text("Restore Quick Task?") },
            text = { Text("Quick Task goes back to the copy from ${copyTime(c.at)}. The note as it is now is kept as a copy first.") },
            confirmButton = {
                TextButton(onClick = {
                    val uri = vault ?: return@TextButton
                    restoring = null
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { runCatching { VaultWriter(ctx, uri).restoreQuick(c) } }
                        snack(r.fold({ "Quick Task restored" }, { "Couldn't restore: ${it.message}" }))
                        refresh++
                    }
                }) { Text("Restore", color = Primary) }
            },
            dismissButton = { TextButton(onClick = { restoring = null }) { Text("Cancel", color = Muted) } },
        )
    }
}
