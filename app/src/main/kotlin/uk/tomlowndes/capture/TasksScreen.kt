package uk.tomlowndes.capture

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    val ticked = remember { mutableStateListOf<TickedTask>() }
    // Rows mid-tick: green and struck through for a moment, then folding away before the note is written.
    val ticking = remember { mutableStateMapOf<TaskItem, Job>() }
    val leaving = remember { mutableStateListOf<TaskItem>() }
    val haptics = LocalHapticFeedback.current
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
            val r = withContext(Dispatchers.IO) { runCatching { VaultWriter(ctx, uri).listTaskLists() } }
            r.onSuccess {
                tasks.clear(); tasks.addAll(it.open); loaded = true
                ticked.clear(); ticked.addAll(it.done)
                leaving.retainAll(it.open.toSet())
            }
                .onFailure { snack.showSnackbar("Couldn't read tasks: ${it.message}") }
        }
    }
    LaunchedEffect(vault) { reload() }

    /** Writes the tick, after the row has shown it. */
    fun complete(t: TaskItem) {
        val uri = vault ?: return
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { VaultWriter(ctx, uri).completeTask(t, prefs.recurring) } }
            r.onSuccess { c -> prefs.logTick(c.doneLine, c.nextLine) }
            r.onFailure { leaving.remove(t) }
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

    /** Ticks [t] on screen at once; a second tap within the hold takes it back before anything is written. */
    fun tick(t: TaskItem) {
        if (t in leaving) return
        ticking.remove(t)?.let { it.cancel(); return }
        if (vault == null) return
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        ticking[t] = scope.launch {
            delay(1100)
            leaving.add(t)
            delay(320)
            ticking.remove(t)
            complete(t)
        }
    }

    fun untick(d: TickedTask) {
        val uri = vault ?: return
        val next = prefs.tickLog.firstOrNull { it.first == d.raw }?.third?.ifBlank { null }
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { VaultWriter(ctx, uri).untickTask(d, next) } }
            r.onSuccess { prefs.tickLog = prefs.tickLog.filter { it.first != d.raw } }
            reload()
            r.onFailure { snack.showSnackbar("Failed: ${it.message}") }
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
        val todayCount = tasks.count { TaskViews.groupOf(it.due, today) == TaskGroup.TODAY }
        val overdueCount = tasks.count { TaskViews.groupOf(it.due, today) == TaskGroup.OVERDUE }
        Text(
            listOfNotNull(
                "${tasks.size} open",
                todayCount.takeIf { it > 0 }?.let { "$it today" },
                overdueCount.takeIf { it > 0 }?.let { "$it overdue" }
            ).joinToString(" · "),
            color = Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
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

        val stamps = remember(ticked.toList()) { prefs.tickLog.associate { it.first to it.second } }
        val recent = TaskViews.recentDone(ticked.filter { note == null || it.path == note }, stamps)
        LazyColumn(Modifier.fillMaxSize(), state = rememberLazyListState(), contentPadding = PaddingValues(bottom = 16.dp, top = 4.dp)) {
            groups.forEach { (g, items) ->
                item(key = "h-$g") { GroupHead(g.title, items.size.toString(), red = g == TaskGroup.OVERDUE) }
                item(key = "c-$g") {
                    ListCard(border = if (g == TaskGroup.OVERDUE) OverdueLine else Outline) {
                        items.forEachIndexed { i, t ->
                            AnimatedVisibility(
                                t !in leaving, enter = EnterTransition.None,
                                exit = shrinkVertically(tween(300)) + fadeOut(tween(250))
                            ) {
                                Column {
                                    if (i > 0) HorizontalDivider(color = Outline)
                                    TaskRow(t, ticked = t in ticking || t in leaving, onTick = { tick(t) }, onOpen = { editing = t }) {
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
            if (recent.isNotEmpty()) {
                item(key = "h-done") { GroupHead("Completed", "last ${recent.size}", muted = true) }
                item(key = "c-done") {
                    ListCard {
                        recent.forEachIndexed { i, d ->
                            if (i > 0) HorizontalDivider(color = Outline)
                            TickedRow(d) { untick(d) }
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

/** A group's bold title, its count bubble and a hairline to the edge. Overdue is red. */
@Composable
private fun GroupHead(title: String, count: String, red: Boolean = false, muted: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = when { red -> Warn; muted -> Muted; else -> TextMain })
        Text(
            count, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = if (red) Warn else Muted,
            modifier = Modifier.clip(CircleShape).background(if (red) OverdueTint else Surface2).padding(horizontal = 8.dp, vertical = 1.dp)
        )
        HorizontalDivider(Modifier.weight(1f), color = Outline)
    }
}
