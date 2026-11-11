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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.style.TextDecoration
import kotlinx.coroutines.delay
import uk.tomlowndes.capture.CalEvent
import uk.tomlowndes.capture.CalendarSource
import uk.tomlowndes.capture.Deck
import uk.tomlowndes.capture.Priority
import uk.tomlowndes.capture.Step
import uk.tomlowndes.capture.Subtasks
import uk.tomlowndes.capture.TickedTask
import uk.tomlowndes.capture.Triage
import uk.tomlowndes.capture.WeeklyReview
import java.time.LocalDateTime
import java.time.LocalTime
import uk.tomlowndes.capture.Bucket
import uk.tomlowndes.capture.DateWords
import uk.tomlowndes.capture.TaskGroups
import uk.tomlowndes.capture.TaskItem
import uk.tomlowndes.capture.TaskViews
import uk.tomlowndes.capture.Tags
import uk.tomlowndes.capture.TaskRules
import uk.tomlowndes.capture.VaultTasks
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun TasksPane(state: AppState) {
    val scope = rememberCoroutineScope()
    var tasks by remember { mutableStateOf<List<TaskItem>?>(null) }
    var done by remember { mutableStateOf<List<TickedTask>>(emptyList()) }
    var order by remember { mutableStateOf(state.prefs.taskOrder) }
    var upcoming by remember { mutableStateOf(state.prefs.tasksUpcoming) }
    var urgent by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("") }
    var pinned by remember { mutableStateOf<List<String>>(emptyList()) }
    var openSteps by remember { mutableStateOf(setOf<String>()) }
    var calendar by remember { mutableStateOf<Pair<List<CalendarSource>, List<CalEvent>>>(emptyList<CalendarSource>() to emptyList()) }
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = LocalDateTime.now() } }

    fun reload() {
        scope.launch {
            state.io("Couldn't read tasks") { state.writer()?.listTaskLists() }?.let {
                tasks = it.open; done = it.done; state.tagCounts = Tags.filterCounts(it.open, 6)
            }
            state.vault?.let { v -> state.io { Tags.loadPinned(v) }?.let { pinned = it } }
        }
    }
    LaunchedEffect(state.vault, state.changes) { reload() }
    // Upcoming reads the phone's calendar links from the vault (windows-catchup-v1).
    LaunchedEffect(state.vault, upcoming) {
        val v = state.vault ?: return@LaunchedEffect
        if (upcoming) state.io { DesktopCalendar.load(v) }?.let { calendar = it }
    }

    fun tick(t: TaskItem) = scope.launch {
        val c = state.io { state.writer()!!.completeTask(t, state.prefs.recurring) } ?: return@launch
        reload()
        val next = c.nextLine?.let { TaskRules.due(it) }
        state.offerUndo(if (next != null) "Done · next “${t.text}”: ${dueLabel(next)}" else "Task completed") {
            state.io { state.writer()!!.restoreTask(t, c) }; reload()
        }
    }

    fun untick(d: TickedTask) = scope.launch {
        state.io { state.writer()!!.untickTask(d) } ?: return@launch
        reload()
        state.say("Back on the list")
    }

    fun move(t: TaskItem, date: LocalDate?) = scope.launch {
        val newRaw = state.io { state.writer()!!.rescheduleTask(t, date) } ?: return@launch
        reload()
        state.offerUndo(if (date == null) "Due date cleared" else "Moved to ${dueLabel(date)}") {
            state.io { state.writer()!!.restoreTask(t, null, newRaw) }; reload()
        }
    }

    fun plan(t: TaskItem, date: LocalDate, start: LocalTime) = scope.launch {
        val newRaw = state.io { state.writer()!!.planAt(t, date, start) } ?: return@launch
        reload()
        state.offerUndo("Planned for ${dueLabel(date)} at ${"%02d:%02d".format(start.hour, start.minute)}") {
            state.io { state.writer()!!.restoreTask(t, null, newRaw) }; reload()
        }
    }

    fun remove(t: TaskItem) = scope.launch {
        val at = state.io { state.writer()!!.deleteTask(t) } ?: return@launch
        reload()
        state.offerUndo("Task removed") { state.io { state.writer()!!.restoreTask(t, null, at = at) }; reload() }
    }

    fun drop(t: TaskItem) = scope.launch {
        val today = LocalDate.now()
        state.io { state.writer()!!.cancelTask(t, today) } ?: return@launch
        reload()
        state.offerUndo("Dropped") { state.io { state.writer()!!.restoreTask(t, null, WeeklyReview.cancelled(t.raw, today)) }; reload() }
    }

    fun edit(t: TaskItem, text: String, due: LocalDate?, rule: String?, minutes: Int?, priority: Priority? = null) = scope.launch {
        val newRaw = state.io { state.writer()!!.editTask(t, text, due, rule, minutes, priority) } ?: return@launch
        reload()
        state.offerUndo("Task updated") { state.io { state.writer()!!.restoreTask(t, null, newRaw) }; reload() }
    }

    fun setPriority(t: TaskItem, p: Priority) = edit(t, t.text, t.due, t.recurrence, Deck.minutes(t), p)

    fun addSteps(t: TaskItem, steps: List<Step>) = scope.launch {
        state.io { state.writer()!!.addSubtasks(t, steps) } ?: return@launch
        openSteps = openSteps + t.raw
        reload()
        state.say("Added ${steps.size} step${if (steps.size == 1) "" else "s"}")
    }

    var editing by remember { mutableStateOf<TaskItem?>(null) }
    var picking by remember { mutableStateOf<TaskItem?>(null) }
    var breaking by remember { mutableStateOf<TaskItem?>(null) }
    var triaging by remember { mutableStateOf(false) }
    var fitting by remember { mutableStateOf<Triple<LocalDate, LocalTime, Int>?>(null) }
    val actions = TaskActions(
        move = { t, d -> move(t, d) }, pickDate = { picking = it }, priority = { t, p -> setPriority(t, p) },
        breakDown = { breaking = it },
        focus = { t, m -> state.focus = FocusRun(t, m, System.currentTimeMillis() + m * 60_000L); state.tab = Tab.DECK },
        edit = { editing = it }, remove = { remove(it) },
    )
    val tag = state.taskTag
    val note = state.taskNote
    val all = tasks.orEmpty()
    val shown = all.filter {
        (note == null || it.path == note) && (tag == null || Tags.matches(it, tag)) &&
            (!urgent || Priority.of(it).urgent) && (filter.isBlank() || it.text.contains(filter.trim(), ignoreCase = true))
    }
    val nested = Subtasks.nest(shown, all)
    val today = LocalDate.now()

    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 26.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    H1("Tasks" + (tag?.let { " · ${Tags.label(it)}" } ?: ""))
                    if (tag != null) Chip("✕ Clear filter", small = true, color = Muted) { state.taskTag = null }
                    if (note != null) Chip("✕ " + note.substringAfterLast('/').removeSuffix(".md"), small = true, color = Muted) { state.taskNote = null }
                }
                Sub(
                    if (tasks == null) "Reading the vault…"
                    else if (upcoming) "Upcoming · next 7 days"
                    else if (tag != null) "${shown.size} open · ${if (Tags.kind(tag) == uk.tomlowndes.capture.TagKind.OTHER) "tagged $tag" else Tags.label(tag).lowercase() + " side"}"
                    else "Whole vault · ${all.size} open",
                    Modifier.padding(top = 2.dp)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Pill(listOf("List", "Upcoming"), if (upcoming) 1 else 0, Modifier.width(200.dp)) { upcoming = it == 1; state.prefs.tasksUpcoming = upcoming }
                if (!upcoming) {
                    Chip("📅 Due", order == "due", small = true) { order = "due"; state.prefs.taskOrder = order }
                    Chip("! Priority", order == "priority", small = true) { order = "priority"; state.prefs.taskOrder = order }
                    Chip("Note order", order == "note", small = true) { order = "note"; state.prefs.taskOrder = order }
                }
                Chip("High+", urgent, small = true) { urgent = !urgent }
                Field(filter, { filter = it }, Modifier.width(200.dp), "🔍  Filter tasks", shape = CircleShape)
            }
        }
        if (!upcoming) AddTaskBox(state) { reload() }

        if (upcoming) {
            val bills = TaskViews.billsMonth(all, done, today)
            if (!bills.empty) Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 10.dp), horizontalArrangement = Arrangement.End) {
                Chip("💷 ${TaskViews.money(bills.left)} left this month ›", small = true) { state.taskTag = "#bill"; upcoming = false; state.prefs.tasksUpcoming = false }
            } else Box(Modifier.padding(top = 14.dp))
            UpcomingWeek(
                shown, calendar.second, calendar.first, now,
                onMove = { t, d -> move(t, d) }, onPlan = { t, d, s -> plan(t, d, s) },
                onFit = { d, s, m -> fitting = Triple(d, s, m) }, onOpen = { editing = it },
            )
        } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            if (tasks != null && shown.isEmpty()) Sub(if (filter.isBlank()) "Nothing to do." else "No tasks match.", Modifier.padding(top = 24.dp))
            @Composable
            fun rows(items: List<TaskItem>) = items.forEach { t ->
                val kids = nested.children[t].orEmpty()
                TaskRow(t, ::tick, actions, steps = kids.size, stepsOpen = t.raw in openSteps,
                    onSteps = { openSteps = if (t.raw in openSteps) openSteps - t.raw else openSteps + t.raw })
                if (t.raw in openSteps) kids.forEach { c -> TaskRow(c, ::tick, actions, sub = true) }
            }
            when (order) {
                "note" -> rows(nested.top)
                "priority" -> TaskViews.priorityGroups(nested.top).forEach { (p, items) ->
                    PriorityHeader(p, items.size)
                    rows(items)
                }
                else -> {
                    // Groups flow into two columns, balanced by how many rows each holds.
                    val groups = TaskGroups.groups(nested.top, today)
                    val total = groups.sumOf { it.second.size + 2 }
                    var acc = 0
                    val split = groups.indexOfFirst { acc += it.second.size + 2; acc * 2 >= total }.let { if (it < 0) groups.size else it + 1 }
                    val triage = Triage.candidates(all, today).size
                    Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                        listOf(groups.take(split), groups.drop(split)).forEach { col ->
                            Column(Modifier.weight(1f)) {
                                col.forEach { (b, items) ->
                                    GroupHeader(b, items.size, triage = if (b == Bucket.OVERDUE && triage > 0) triage else 0) { triaging = true }
                                    rows(items)
                                }
                            }
                        }
                    }
                }
            }
            // Completed (tasks-v1): the last 5 ticked, newest first. Tap the box to put one back.
            val recent = TaskViews.recentDone(done.filter { note == null || it.path == note }, emptyMap())
            if (recent.isNotEmpty() && filter.isBlank()) {
                Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { GroupLabel("Completed") }
                    Count(recent.size, text = "last ${recent.size}")
                }
                recent.forEach { d -> DoneRow(d) { untick(d) } }
            }
        }
    }
    editing?.let { t ->
        EditTaskDialog(t, pinned, onSave = { text, due, rule, minutes, p -> editing = null; edit(t, text, due, rule, minutes, p) }, onRemove = { editing = null; remove(t) }, onDismiss = { editing = null })
    }
    picking?.let { t -> PickDateDialog(t.due, onPick = { picking = null; move(t, it) }, onDismiss = { picking = null }) }
    breaking?.let { t -> BreakdownDialog(t, onSave = { steps -> breaking = null; addSteps(t, steps) }, onDismiss = { breaking = null }) }
    if (triaging) TriageDialog(
        all, onToday = { move(it, LocalDate.now()) }, onLater = { t, d -> move(t, d) }, onDrop = { drop(it) },
        onPick = { picking = it }, onDismiss = { triaging = false },
    )
    fitting?.let { (d, s, m) -> FitDialog(shown, d, s, m, onPick = { t -> fitting = null; plan(t, d, s) }, onDismiss = { fitting = null }) }
}

@Composable
private fun GroupHeader(b: Bucket, count: Int, triage: Int = 0, onTriage: () -> Unit = {}) {
    val title = if (b == Bucket.TODAY) "Today · " + LocalDate.now().format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)) else b.title
    Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { GroupLabel(title, if (b == Bucket.OVERDUE) Warn else Muted) }
        if (triage > 0) TextAction("⚡ Triage ›", modifier = Modifier.padding(end = 8.dp), onClick = onTriage)
        Count(count, warn = b == Bucket.OVERDUE)
    }
}

@Composable
private fun PriorityHeader(p: Priority, count: Int) {
    Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        PriorityFlag(p, Modifier.padding(end = 8.dp))
        Box(Modifier.weight(1f)) { GroupLabel(p.title) }
        Count(count)
    }
}

/** A ticked task in Completed: muted and struck through; the box un-ticks it. */
@Composable
private fun DoneRow(d: TickedTask, onUntick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().padding(bottom = 6.dp).clip(shape).background(Surface1).padding(start = 13.dp, top = 7.dp, bottom = 7.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(Modifier.size(18.dp).clip(CircleShape).background(Color(0xFF2C5A47)).hand().clickable(onClick = onUntick), contentAlignment = Alignment.Center) {
            Text("✓", fontSize = 10.sp, color = Color(0xFFBFEEDD), fontWeight = FontWeight.Bold)
        }
        Text(d.text, fontSize = 13.sp, color = Muted, textDecoration = TextDecoration.LineThrough, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        d.done?.let { day ->
            val today = LocalDate.now()
            Text(when (day) { today -> "Today"; today.minusDays(1) -> "Yesterday"; else -> dayChip(day) }, fontSize = 11.sp, color = Faint)
        }
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
    var repeat by remember { mutableStateOf<String?>(null) }
    var repeatOpen by remember { mutableStateOf(false) }
    val today = LocalDate.now()
    val (body, typed) = DateWords.split(text, today)
    val due = if (noDate) null else picked ?: typed

    fun add() {
        val t = body.trim()
        if (t.isEmpty()) return
        scope.launch {
            state.io { state.writer()!!.addTask(t + (repeat?.let { " 🔁 $it" } ?: ""), due?.toString()) } ?: return@launch
            text = ""; picked = null; noDate = false; repeat = null
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
        Chip("No date", noDate, small = true) { noDate = true; picked = null }
        Chip(repeatChipLabel(repeat), repeat != null, small = true) { repeatOpen = true }
        PrimaryButton("Add", enabled = body.isNotBlank(), hint = "↵") { add() }
    }
    Text("Adds to ⚡ Quick Task · type a date like “fri” or “25 oct”", fontSize = 11.5.sp, color = Muted,
        modifier = Modifier.padding(start = 4.dp, top = 6.dp))
    if (picking) PickDateDialog(due, onPick = { picking = false; picked = it; noDate = it == null }, onDismiss = { picking = false })
    if (repeatOpen) RepeatDialog(repeat, onPick = { repeatOpen = false; repeat = it }, onDismiss = { repeatOpen = false })
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun TaskRow(
    t: TaskItem, onTick: (TaskItem) -> Unit, a: TaskActions, sub: Boolean = false,
    steps: Int = 0, stepsOpen: Boolean = false, onSteps: () -> Unit = {},
) {
    val today = LocalDate.now()
    val overdue = t.due?.isBefore(today) == true
    var menu by remember { mutableStateOf(false) }
    HoverRow(Modifier.fillMaxWidth().padding(start = if (sub) 34.dp else 0.dp, bottom = 6.dp)) { hovered ->
        val shape = RoundedCornerShape(12.dp)
        Row(
            Modifier.fillMaxWidth().clip(shape).background(if (hovered || menu) Surface2 else Surface1)
                .then(if (hovered || menu) Modifier.border(1.5.dp, Primary, shape) else Modifier)
                // Right-click opens the task menu (the desktop stand-in for swipes).
                .onPointerEvent(PointerEventType.Press) { if (it.buttons.isSecondaryPressed) menu = true }
                .padding(start = 13.dp, top = if (sub) 6.dp else 8.dp, bottom = if (sub) 6.dp else 8.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(Modifier.size(if (sub) 17.dp else 20.dp).clip(CircleShape).border(2.dp, if (sub) Muted else Primary, CircleShape).hand().clickable { onTick(t) })
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                PriorityFlag(Priority.of(t), Modifier.padding(end = 7.dp))
                Text(
                    Tags.strip(t.text).ifBlank { t.text }, fontSize = if (sub) 13.sp else 14.sp, color = TextMain, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).hand().clickable { a.edit(t) }
                )
                TaskViews.tags(t).take(3).forEach { TagMini(it) }
                Deck.minutes(t)?.let { Text(TaskRules.minutesLabel(it), fontSize = 11.5.sp, color = Muted, modifier = Modifier.padding(start = 8.dp)) }
                if (steps > 0) Text(
                    "$steps step${if (steps > 1) "s" else ""} ${if (stepsOpen) "▴" else "▾"}", fontSize = 11.sp, color = Primary, maxLines = 1,
                    modifier = Modifier.padding(start = 8.dp).clip(CircleShape).background(PrimaryTint).hand().clickable(onClick = onSteps)
                        .padding(horizontal = 8.dp, vertical = 1.dp)
                )
                t.recurrence?.let { Text("🔁 $it", fontSize = 11.5.sp, color = Muted, modifier = Modifier.padding(start = 8.dp), maxLines = 1) }
                if (t.path != VaultTasks.QUICK_TASK && !sub) Text(VaultTasks.noteName(t.path), fontSize = 11.sp, color = LinkBlue,
                    modifier = Modifier.padding(start = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box {
                if (hovered || menu) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    HoverAction("✎ Edit") { a.edit(t) }
                    HoverAction("⋯") { menu = true }
                    HoverAction("🗑") { a.remove(t) }
                }
                TaskMenu(t, menu, { menu = false }, a)
            }
            t.due?.let {
                Text(dayChip(it), fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = if (overdue) Warn else TextMain,
                    modifier = Modifier.clip(RoundedCornerShape(9.dp)).background(if (overdue) WarnTint else Surface2)
                        .padding(horizontal = 9.dp, vertical = 4.dp))
            }
        }
    }
}

@Composable
private fun HoverAction(text: String, onClick: () -> Unit) =
    Text(text, fontSize = 12.sp, color = Muted, modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Bg)
        .hand().clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 4.dp))
