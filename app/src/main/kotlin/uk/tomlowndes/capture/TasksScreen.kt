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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** Tasks tab: every open `#task` in the vault, filtered by tag, side or length, grouped by when it's due. */
@Composable
fun TasksScreen(
    vault: Uri?, prefs: AppPrefs, snack: SnackbarHostState, note: String? = null, onClearNote: () -> Unit = {},
    onStartFocus: (TaskItem, Int) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val tasks = remember { mutableStateListOf<TaskItem>() }
    var loaded by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<TaskItem?>(null) }
    var tag by remember { mutableStateOf(prefs.tasksTag) }
    var filter by remember { mutableStateOf(TaskFilter.values().firstOrNull { it.name == prefs.tasksFilter }) }
    fun pickTag(t: String?) { tag = t; prefs.tasksTag = t }
    fun pickFilter(f: TaskFilter?) { filter = f; prefs.tasksFilter = f?.name }
    val tagData = rememberTagData(vault)
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

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

    fun save(t: TaskItem, text: String, due: LocalDate?, recurrence: String?, minutes: Int?) {
        val uri = vault ?: return
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { VaultWriter(ctx, uri).editTask(t, text, due, recurrence, minutes) } }
            reload()
            r.onFailure { snack.showSnackbar("Failed: ${it.message}") }
            r.onSuccess { newRaw ->
                if (snack.showSnackbar("Task updated", "Undo", duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) {
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
    val shown = tasks.filter { (note == null || it.path == note) && TaskViews.matches(it, tag, filter, query) }
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
        // All, Work and Personal (with counts), the most used other tags, then short tasks.
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically
        ) {
            // From a note in Vault: just that note's tasks until the chip is tapped.
            if (note != null) AppChip(true, onClearNote, { Text("✕ " + VaultTasks.noteName(note)) })
            AppChip(tag == null && filter == null, { pickTag(null); pickFilter(null) }, { Text("All") })
            listOf(TaskFilter.WORK to Tags.WORK, TaskFilter.PERSONAL to Tags.PERSONAL).forEach { (f, chipTag) ->
                val n = tasks.count { TaskViews.matches(it, null, f) }
                TagChip(chipTag, filter == f, onClick = { pickFilter(if (filter == f) null else f) }, count = n)
            }
            TaskViews.topTags(tasks, 8).filter { it !in Tags.BUILT_IN }.forEach { t ->
                TagChip(t, tag == t, onClick = { pickTag(if (tag == t) null else t) }, count = tasks.count { t in TaskViews.tags(it) })
            }
            AppChip(filter == TaskFilter.SHORT, { pickFilter(if (filter == TaskFilter.SHORT) null else TaskFilter.SHORT) },
                { Text("≤ ${TaskViews.SHORT_MIN} min") })
        }

        LazyColumn(Modifier.fillMaxSize(), state = rememberLazyListState(), contentPadding = PaddingValues(bottom = 16.dp, top = 4.dp)) {
            groups.forEach { (g, items) ->
                item(key = "h-$g") {
                    GroupLabel("${g.title} · ${items.size}", if (g == TaskGroup.OVERDUE) Warn else Muted)
                }
                item(key = "c-$g") {
                    ListCard(Modifier.padding(top = 6.dp)) {
                        items.forEachIndexed { i, t ->
                            if (i > 0) HorizontalDivider(color = Outline)
                            TaskLine(t, edge = true, onTick = { tick(t) }, onOpen = { editing = t }) {
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
        EditTaskSheet(
            t, tagData,
            onSave = { text, due, rule, minutes -> editing = null; save(t, text, due, rule, minutes) },
            onRemove = { editing = null; remove(t) },
            onDismiss = { editing = null }
        )
    }
}
