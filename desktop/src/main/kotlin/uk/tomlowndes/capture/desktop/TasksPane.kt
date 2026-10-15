package uk.tomlowndes.capture.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import uk.tomlowndes.capture.Bucket
import uk.tomlowndes.capture.DateWords
import uk.tomlowndes.capture.TaskGroups
import uk.tomlowndes.capture.TaskItem
import uk.tomlowndes.capture.TaskRules
import uk.tomlowndes.capture.VaultTasks
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun TasksPane(state: AppState) {
    val scope = rememberCoroutineScope()
    var tasks by remember { mutableStateOf<List<TaskItem>?>(null) }
    var byDue by remember { mutableStateOf(state.prefs.sortByDue) }
    var filter by remember { mutableStateOf("") }

    fun reload() {
        scope.launch { state.io("Couldn't read tasks") { state.writer()?.listTasks() }?.let { tasks = it } }
    }
    LaunchedEffect(state.vault, state.changes) { reload() }

    fun tick(t: TaskItem) = scope.launch {
        val c = state.io { state.writer()!!.completeTask(t, state.prefs.recurring) } ?: return@launch
        reload()
        val next = c.nextLine?.let { TaskRules.due(it) }
        state.offerUndo(if (next != null) "Done · next “${t.text}”: ${dueLabel(next)}" else "Task completed") {
            state.io { state.writer()!!.restoreTask(t, c) }; reload()
        }
    }

    fun move(t: TaskItem, date: LocalDate?) = scope.launch {
        val newRaw = state.io { state.writer()!!.rescheduleTask(t, date) } ?: return@launch
        reload()
        state.offerUndo(if (date == null) "Due date cleared" else "Moved to ${dueLabel(date)}") {
            state.io { state.writer()!!.restoreTask(t, null, newRaw) }; reload()
        }
    }

    fun remove(t: TaskItem) = scope.launch {
        val at = state.io { state.writer()!!.deleteTask(t) } ?: return@launch
        reload()
        state.offerUndo("Task removed") { state.io { state.writer()!!.restoreTask(t, null, at = at) }; reload() }
    }

    val shown = tasks.orEmpty().filter { filter.isBlank() || it.text.contains(filter.trim(), ignoreCase = true) }

    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 26.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                H1("Tasks")
                Sub(if (tasks == null) "Reading the vault…" else "Whole vault · ${tasks!!.size} open", Modifier.padding(top = 2.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Chip("By due date", byDue) { byDue = true; state.prefs.sortByDue = true }
                Chip("Note order", !byDue) { byDue = false; state.prefs.sortByDue = false }
                Field(filter, { filter = it }, Modifier.width(240.dp), "🔍  Filter tasks", shape = CircleShape)
            }
        }
        AddTaskBox(state) { reload() }

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            if (tasks != null && shown.isEmpty()) Sub(if (filter.isBlank()) "Nothing to do." else "No tasks match.", Modifier.padding(top = 24.dp))
            if (!byDue) {
                shown.forEach { TaskRow(it, ::tick, ::move, ::remove) }
            } else {
                // Groups flow into two columns, balanced by how many rows each holds.
                val groups = TaskGroups.groups(shown, LocalDate.now())
                val total = groups.sumOf { it.second.size + 2 }
                var acc = 0
                val split = groups.indexOfFirst { acc += it.second.size + 2; acc * 2 >= total }.let { if (it < 0) groups.size else it + 1 }
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    listOf(groups.take(split), groups.drop(split)).forEach { col ->
                        Column(Modifier.weight(1f)) {
                            col.forEach { (b, items) ->
                                GroupHeader(b, items.size)
                                items.forEach { TaskRow(it, ::tick, ::move, ::remove) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupHeader(b: Bucket, count: Int) {
    val title = if (b == Bucket.TODAY) "Today · " + LocalDate.now().format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)) else b.title
    Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { GroupLabel(title, if (b == Bucket.OVERDUE) Warn else Muted) }
        Count(count, warn = b == Bucket.OVERDUE)
    }
}

/** The add box: type a task, end it with a date ("fri", "25 oct") or pick one with the chips. Enter adds it. */
@Composable
private fun AddTaskBox(state: AppState, onAdded: () -> Unit) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<LocalDate?>(null) }
    var noDate by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    val today = LocalDate.now()
    val (body, typed) = DateWords.split(text, today)
    val due = if (noDate) null else picked ?: typed

    fun add() {
        val t = body.trim()
        if (t.isEmpty()) return
        scope.launch {
            state.io { state.writer()!!.addTask(t, due?.toString()) } ?: return@launch
            text = ""; picked = null; noDate = false
            onAdded()
            state.say("Added to Quick Task" + (due?.let { " · due ${dueLabel(it)}" } ?: ""))
        }
    }

    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().padding(top = 16.dp).clip(shape).background(Surface1).border(1.5.dp, Primary, shape)
            .padding(start = 4.dp, top = 5.dp, bottom = 5.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Field(
            text, { text = it }, Modifier.weight(1f).onKeys(enter = ::add),
            "Add a task…", fontSize = 15, focusBorder = false, shape = RoundedCornerShape(10.dp)
        )
        Chip("Today", !noDate && due == today, small = true) { picked = today; noDate = false }
        Chip("📅 " + (due?.takeIf { it != today }?.let(::dayChip) ?: "Pick date"), !noDate && due != null && due != today, small = true) { picking = true }
        Chip("No date", noDate || due == null, small = true) { noDate = true; picked = null }
        PrimaryButton("Add", enabled = body.isNotBlank(), hint = "↵") { add() }
    }
    Text("Adds to ⚡ Quick Task · type a date like “fri” or “25 oct”", fontSize = 11.5.sp, color = Muted,
        modifier = Modifier.padding(start = 4.dp, top = 6.dp))
    if (picking) PickDateDialog(due, onPick = { picking = false; picked = it; noDate = it == null }, onDismiss = { picking = false })
}

@Composable
private fun TaskRow(t: TaskItem, onTick: (TaskItem) -> Unit, onMove: (TaskItem, LocalDate?) -> Unit, onRemove: (TaskItem) -> Unit) {
    val today = LocalDate.now()
    val overdue = t.due != null && t.due!!.isBefore(today)
    var menu by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    HoverRow(Modifier.fillMaxWidth().padding(bottom = 6.dp)) { hovered ->
        val shape = RoundedCornerShape(12.dp)
        Row(
            Modifier.fillMaxWidth().clip(shape).background(if (hovered || menu) Surface2 else Surface1)
                .then(if (hovered || menu) Modifier.border(1.5.dp, Primary, shape) else Modifier)
                .padding(start = 13.dp, top = 8.dp, bottom = 8.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(Modifier.size(20.dp).clip(CircleShape).border(2.dp, Primary, CircleShape).hand().clickable { onTick(t) })
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(t.text, fontSize = 14.sp, color = TextMain, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                t.recurrence?.let { Text("🔁 $it", fontSize = 11.5.sp, color = Muted, modifier = Modifier.padding(start = 8.dp), maxLines = 1) }
                if (t.path != VaultTasks.QUICK_TASK) Text(VaultTasks.noteName(t.path), fontSize = 11.sp, color = LinkBlue,
                    modifier = Modifier.padding(start = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (hovered || menu) Box {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    HoverAction("📅 Move") { menu = true }
                    HoverAction("🗑") { onRemove(t) }
                }
                DropdownMenu(menu, { menu = false }, Modifier.background(Menu)) {
                    DropdownMenuItem({ Text("Today") }, { menu = false; onMove(t, today) })
                    DropdownMenuItem({ Text("Tomorrow") }, { menu = false; onMove(t, today.plusDays(1)) })
                    DropdownMenuItem({ Text("Next week") }, { menu = false; onMove(t, today.plusDays(7)) })
                    DropdownMenuItem({ Text("Pick date…") }, { menu = false; picking = true })
                    if (t.due != null) DropdownMenuItem({ Text("Clear due date") }, { menu = false; onMove(t, null) })
                }
            }
            t.due?.let {
                Text(dayChip(it), fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = if (overdue) Warn else TextMain,
                    modifier = Modifier.clip(RoundedCornerShape(9.dp)).background(if (overdue) WarnTint else Surface2)
                        .padding(horizontal = 9.dp, vertical = 4.dp))
            }
        }
    }
    if (picking) PickDateDialog(t.due, onPick = { picking = false; onMove(t, it) }, onDismiss = { picking = false })
}

@Composable
private fun HoverAction(text: String, onClick: () -> Unit) =
    Text(text, fontSize = 12.sp, color = Muted, modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Bg)
        .hand().clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 4.dp))
