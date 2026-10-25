package uk.tomlowndes.capture

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** Tasks tab: every open `#task` in the vault, filtered by tag, side or length, grouped by when it's due. */
@Composable
fun TasksScreen(vault: Uri?, prefs: AppPrefs, snack: SnackbarHostState, onStartFocus: (TaskItem, Int) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val tasks = remember { mutableStateListOf<TaskItem>() }
    var loaded by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<TaskItem?>(null) }
    var tag by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf<TaskFilter?>(null) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var laterOpen by remember { mutableStateOf(false) }

    /** Quick Task first (quick to read), then the rest of the vault once the scan is done. */
    fun reload() {
        val uri = vault ?: return
        scope.launch {
            if (!loaded) {
                withContext(Dispatchers.IO) { runCatching { VaultWriter(ctx, uri).listQuickTasks() } }
                    .onSuccess { tasks.clear(); tasks.addAll(it) }
            }
            val r = withContext(Dispatchers.IO) { runCatching { VaultWriter(ctx, uri).listTasks() } }
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

    val today = LocalDate.now()
    val shown = tasks.filter { TaskViews.matches(it, tag, filter, query) }
    val groups = TaskViews.groups(shown, today)

    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Tasks", fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp)
            IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                Icon(if (searching) Icons.Outlined.Close else Icons.Outlined.Search, "Search", tint = Muted)
            }
        }
        Text(
            "Vault-wide #task · ${tasks.size} open", color = Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(top = 2.dp, bottom = 10.dp)
        )
        if (searching) OutlinedTextField(
            query, { query = it }, singleLine = true, shape = RoundedCornerShape(16.dp), colors = fieldColors(),
            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AppChip(tag == null, { tag = null }, { Text("All") })
            TaskViews.topTags(tasks).forEach { t -> AppChip(tag == t, { tag = if (tag == t) null else t }, { Text(t) }) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(TaskFilter.WORK to "Work", TaskFilter.PERSONAL to "Personal", TaskFilter.SHORT to "≤ ${TaskViews.SHORT_MIN} min")
                .forEach { (f, label) -> AppChip(filter == f, { filter = if (filter == f) null else f }, { Text(label) }) }
        }

        LazyColumn(Modifier.fillMaxSize(), state = rememberLazyListState(), contentPadding = PaddingValues(bottom = 16.dp, top = 4.dp)) {
            groups.forEach { (g, items) ->
                if (g == TaskGroup.LATER && !laterOpen) {
                    item(key = "later") {
                        Text(
                            "Later · ${items.size} ›", textAlign = TextAlign.Center, color = Muted, fontSize = 13.sp,
                            modifier = Modifier.fillMaxWidth().clickable { laterOpen = true }.padding(vertical = 12.dp)
                        )
                    }
                    return@forEach
                }
                item(key = "h-$g") {
                    GroupLabel("${g.title} · ${items.size}", if (g == TaskGroup.OVERDUE) Warn else Muted)
                }
                item(key = "c-$g") {
                    ListCard(Modifier.padding(top = 6.dp)) {
                        items.forEachIndexed { i, t ->
                            if (i > 0) HorizontalDivider(color = Outline)
                            TaskLine(t, edge = true, onTick = { tick(t) }) {
                                if (g == TaskGroup.TODAY) {
                                    (Deck.minutes(t) ?: TaskViews.SHORT_MIN).let { m -> StartPill(m) { onStartFocus(t, m) } }
                                } else {
                                    t.due?.let { DuePill(it) { editing = t } }
                                        ?: Text("＋ date", fontSize = 12.sp, color = Muted, modifier = Modifier.clickable { editing = t })
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    editing?.let { t ->
        RescheduleDialog(
            t,
            onDate = { d -> editing = null; reschedule(t, d) },
            onMinutes = { m ->
                editing = null
                val uri = vault
                if (uri != null) scope.launch {
                    withContext(Dispatchers.IO) { runCatching { VaultWriter(ctx, uri).setEstimate(t, m) } }
                        .onFailure { snack.showSnackbar("Failed: ${it.message}") }
                    reload()
                }
            },
            onRemove = { editing = null; remove(t) },
            onDismiss = { editing = null }
        )
    }
}

@Composable
private fun RescheduleDialog(t: TaskItem, onDate: (LocalDate?) -> Unit, onMinutes: (Int?) -> Unit, onRemove: () -> Unit, onDismiss: () -> Unit) {
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
                Text("Time", color = Muted, fontSize = 12.sp)
                val current = Deck.minutes(t)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ESTIMATES.forEach { m ->
                        AppChip(current == m, { onMinutes(if (current == m) null else m) }, { Text(TaskRules.minutesLabel(m)) }, shape = RoundedCornerShape(12.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onRemove) { Text("Remove task", color = Warn) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close", color = Muted) } }
    )
}
