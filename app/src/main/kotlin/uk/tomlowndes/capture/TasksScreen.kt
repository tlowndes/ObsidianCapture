package uk.tomlowndes.capture

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private sealed interface Row2 {
    data class Header(val bucket: Bucket, val count: Int, val title: String) : Row2
    data class Task(val item: TaskItem) : Row2
}

private fun layout(tasks: List<TaskItem>, byDue: Boolean): List<Row2> {
    if (!byDue) return tasks.map { Row2.Task(it) }
    val today = LocalDate.now()
    val out = mutableListOf<Row2>()
    TaskGroups.groups(tasks, today).forEach { (b, items) ->
        val title = if (b == Bucket.TODAY) "Today · " + today.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)) else b.title
        out.add(Row2.Header(b, items.size, title))
        items.forEach { out.add(Row2.Task(it)) }
    }
    return out
}

@Composable
fun TasksScreen(vault: Uri?, prefs: AppPrefs, snack: SnackbarHostState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val tasks = remember { mutableStateListOf<TaskItem>() }
    var loaded by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var byDue by remember { mutableStateOf(prefs.sortByDue) }
    var menu by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<TaskItem?>(null) }

    /** Quick Task first (quick to read), then the rest of the vault once the scan is done. */
    fun reload() {
        val uri = vault ?: return
        scope.launch {
            if (!loaded) {
                withContext(Dispatchers.IO) { runCatching { VaultWriter(ctx, uri).listQuickTasks() } }
                    .onSuccess { tasks.clear(); tasks.addAll(it) }
            }
            scanning = true
            val r = withContext(Dispatchers.IO) { runCatching { VaultWriter(ctx, uri).listTasks() } }
            scanning = false
            r.onSuccess { tasks.clear(); tasks.addAll(it); loaded = true }
                .onFailure { snack.showSnackbar("Couldn't read tasks: ${it.message}") }
        }
    }
    LaunchedEffect(vault) { reload() }

    fun tick(t: TaskItem) {
        val uri = vault ?: return
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { VaultWriter(ctx, uri).completeTask(t, prefs.recurring) } }
            reload()
            r.onFailure { snack.showSnackbar("Failed: ${it.message}") }
            r.onSuccess { c ->
                val nextDue = c.nextLine?.let { TaskRules.due(it) }
                val msg = if (nextDue != null) "Done · next: ${dueLabel(nextDue)}" else "Task completed"
                if (snack.showSnackbar(msg, "Undo", duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) {
                    withContext(Dispatchers.IO) { runCatching { VaultWriter(ctx, uri).restoreTask(t, c) } }
                    reload()
                }
            }
        }
    }

    fun reschedule(t: TaskItem, date: LocalDate?) {
        val uri = vault ?: return
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { VaultWriter(ctx, uri).rescheduleTask(t, date) } }
            reload()
            r.onFailure { snack.showSnackbar("Failed: ${it.message}") }
            r.onSuccess { newRaw ->
                val msg = if (date == null) "Due date cleared" else "Moved to ${dueLabel(date)}"
                if (snack.showSnackbar(msg, "Undo", duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) {
                    withContext(Dispatchers.IO) { runCatching { VaultWriter(ctx, uri).restoreTask(t, null, newRaw) } }
                    reload()
                }
            }
        }
    }

    fun remove(t: TaskItem) {
        val uri = vault ?: return
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { VaultWriter(ctx, uri).deleteTask(t) } }
            reload()
            r.onFailure { snack.showSnackbar("Failed: ${it.message}") }
            r.onSuccess { at ->
                if (snack.showSnackbar("Task removed", "Undo", duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) {
                    withContext(Dispatchers.IO) { runCatching { VaultWriter(ctx, uri).restoreTask(t, null, at = at) } }
                    reload()
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Tasks", fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { reload() }) { Text("↻", color = Muted, fontSize = 20.sp) }
                Column {
                    AppChip(true, { menu = true }, { Text(if (byDue) "By due date ▾" else "Note order ▾") },
                        shape = RoundedCornerShape(12.dp))
                    DropdownMenu(menu, { menu = false }, modifier = Modifier.background(Surface2)) {
                        DropdownMenuItem(text = { Text("By due date") }, onClick = { byDue = true; prefs.sortByDue = true; menu = false })
                        DropdownMenuItem(text = { Text("Note order") }, onClick = { byDue = false; prefs.sortByDue = false; menu = false })
                    }
                }
            }
        }
        Text(
            when {
                vault == null -> "Pick your vault in Settings"
                scanning && !loaded -> "Quick Task · ${tasks.size} open · reading the vault…"
                else -> "Whole vault · ${tasks.size} open" + if (scanning) " · refreshing…" else ""
            },
            color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 3.dp, bottom = 6.dp))

        val rows = layout(tasks, byDue)
        if (loaded && rows.isEmpty()) {
            Text("Nothing to do. Add something from Capture.", color = Muted, modifier = Modifier.padding(top = 24.dp))
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            itemsIndexed(rows) { _, row ->
                when (row) {
                    is Row2.Header -> Row(
                        Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
                    ) {
                        GroupLabel(row.title, if (row.bucket == Bucket.OVERDUE) Warn else Muted)
                        Text("${row.count}", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                            color = if (row.bucket == Bucket.OVERDUE) Warn else Primary,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp))
                                .background(if (row.bucket == Bucket.OVERDUE) Color(0xFF3A1D22) else Color(0xFF2A2250))
                                .padding(horizontal = 8.dp, vertical = 2.dp))
                    }
                    is Row2.Task -> TaskCard(row.item, { tick(row.item) }, { editing = row.item })
                }
            }
        }
    }

    editing?.let { t ->
        RescheduleDialog(
            t,
            onDate = { d -> editing = null; reschedule(t, d) },
            onRemove = { editing = null; remove(t) },
            onDismiss = { editing = null }
        )
    }
}

@Composable
private fun TaskCard(t: TaskItem, onTick: () -> Unit, onChip: () -> Unit) {
    val overdue = t.due != null && t.due.isBefore(LocalDate.now())
    Row(
        Modifier.fillMaxWidth().padding(bottom = 7.dp).clip(RoundedCornerShape(16.dp)).background(Surface1)
            .padding(start = 13.dp, top = 9.dp, bottom = 9.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.size(24.dp).clip(CircleShape).border(2.dp, Primary, CircleShape).clickable(onClick = onTick)) {}
        Column(Modifier.weight(1f)) {
            Text(t.text, fontSize = 14.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val from = if (t.path != VaultTasks.QUICK_TASK) VaultTasks.noteName(t.path) else null
            val meta = listOfNotNull(t.recurrence?.let { "🔁 $it" }, from?.let { "📄 $it" }).joinToString("  ·  ")
            if (meta.isNotEmpty()) Text(meta, fontSize = 11.5.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            t.due?.let { dayChip(it) } ?: "＋ date", fontSize = 11.5.sp, fontWeight = FontWeight.Bold,
            color = if (overdue) Warn else if (t.due == null) Muted else TextMain,
            modifier = Modifier.clip(RoundedCornerShape(10.dp))
                .background(if (overdue) Color(0xFF3A1D22) else Surface2)
                .clickable(onClick = onChip).padding(horizontal = 9.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun RescheduleDialog(t: TaskItem, onDate: (LocalDate?) -> Unit, onRemove: () -> Unit, onDismiss: () -> Unit) {
    var picking by remember { mutableStateOf(false) }
    val today = LocalDate.now()
    if (picking) {
        PickDateDialog(t.due, onPick = { onDate(it) }, onDismiss = { picking = false })
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface1,
        title = { Text(t.text, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Move due date", color = Muted, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppChip(false, { onDate(today) }, { Text("Today") }, shape = RoundedCornerShape(12.dp))
                    AppChip(false, { onDate(today.plusDays(1)) }, { Text("Tomorrow") }, shape = RoundedCornerShape(12.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppChip(false, { onDate(today.plusDays(7)) }, { Text("Next week") }, shape = RoundedCornerShape(12.dp))
                    AppChip(false, { picking = true }, { Text("Pick date…") }, shape = RoundedCornerShape(12.dp))
                }
                if (t.due != null) TextButton(onClick = { onDate(null) }) { Text("Clear due date", color = Primary) }
            }
        },
        confirmButton = { TextButton(onClick = onRemove) { Text("Remove task", color = Warn) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close", color = Muted) } }
    )
}
