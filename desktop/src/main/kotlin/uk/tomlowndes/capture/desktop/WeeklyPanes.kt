package uk.tomlowndes.capture.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import uk.tomlowndes.capture.FlightNotes
import uk.tomlowndes.capture.Habit
import uk.tomlowndes.capture.Habits
import uk.tomlowndes.capture.Job
import uk.tomlowndes.capture.Jobs
import uk.tomlowndes.capture.Recap
import uk.tomlowndes.capture.Recaps
import uk.tomlowndes.capture.Review
import uk.tomlowndes.capture.Routine
import uk.tomlowndes.capture.Routines
import uk.tomlowndes.capture.TaskItem
import uk.tomlowndes.capture.TaskViews
import uk.tomlowndes.capture.WeeklyReview
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

// Weekly review, Routines and Jobs (windows-weekly-routines-jobs-v1): the phone screens Windows didn't have.

private val DAY = DateTimeFormatter.ofPattern("EEE d", Locale.UK)
private val SHORT = DateTimeFormatter.ofPattern("d MMM", Locale.UK)

@Composable
private fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(modifier.clip(shape).background(Surface1).border(1.dp, Outline, shape).padding(horizontal = 18.dp, vertical = 16.dp), content = content)
}

@Composable
private fun PanelHead(title: String, trailing: String) {
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextMain, modifier = Modifier.weight(1f))
        Text(trailing, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Muted)
    }
}

@Composable
private fun Line(content: @Composable RowScope.() -> Unit) {
    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Outline))
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { content() }
    }
}

/** Sunday review on one screen: Done, Carried over (move or drop one), Next week (pick up to 3), Save recap. */
@Composable
fun WeeklyPane(state: AppState) {
    val today = LocalDate.now()
    val mon = WeeklyReview.nextMonday(today)
    var review by remember { mutableStateOf<Review?>(null) }
    var recap by remember { mutableStateOf<Recap?>(null) }
    var moved by remember { mutableStateOf<Map<String, LocalDate>>(emptyMap()) }
    var dropped by remember { mutableStateOf(false) }
    var picks by remember { mutableStateOf<List<String>>(emptyList()) }
    var allDone by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(state.vault) {
        val v = state.vault ?: return@LaunchedEffect
        val w = state.writer() ?: return@LaunchedEffect
        state.io("Couldn't read the week") {
            val done = WeeklyReview.done(v, today)
            val rc = Recaps.build(done, FlightNotes.loadSince(v, today.minusDays(6)), runCatching { Habits.load(v) }.getOrNull(),
                runCatching { Jobs.list(v) }.getOrDefault(emptyList()), today)
            WeeklyReview.build(done, w.listTasks(), today) to rc
        }?.let { (r, rc) -> review = r; recap = rc }
    }

    fun move(t: TaskItem, d: LocalDate) = scope.launch {
        state.io("Couldn't move it") { state.writer()!!.rescheduleTask(t, d) } ?: return@launch
        moved = moved + (t.raw to d); state.changed()
    }
    fun save() = scope.launch {
        val rc = recap ?: return@launch
        val r = review ?: return@launch
        val body = Recaps.note(rc) + if (picks.isEmpty()) "" else
            "\n## Focus next week\n" + picks.mapIndexed { i, p -> "${i + 1}. $p" }.joinToString("\n") + "\n"
        state.io("Couldn't save the recap") { state.writer()!!.putInboxNote(Recaps.noteName(rc), body) } ?: return@launch
        state.say("Saved to 01 - Inbox/${Recaps.noteName(rc)}")
        allDone = true
        if (r.carried.isEmpty()) state.tab = Tab.DECK
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 26.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                H1("Weekly review", 28)
                Text("${today.minusDays(6).format(DAY)} – ${today.format(DAY)}" + (recap?.let { " · focus ${Recaps.hours(it.focusMin)} · habits ${it.habitsHit}%" } ?: ""),
                    color = Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
            }
            Chip("Skip", small = true) { state.tab = Tab.DECK }
            Spacer(Modifier.width(10.dp))
            PrimaryButton(if (allDone) "Saved ✓" else "Save recap", enabled = recap != null && !allDone) { save() }
        }
        val r = review
        if (r == null) { Sub("Reading the week…"); return@Column }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
            Panel(Modifier.weight(1f)) {
                PanelHead("Done", "${r.done.size}")
                if (r.done.isEmpty()) Sub("Nothing ticked this week. Next week's a fresh one.")
                r.done.take(12).forEach { d ->
                    Line {
                        Box(Modifier.size(16.dp).clip(RoundedCornerShape(5.dp)).background(TaskTint))
                        Text(d.text, fontSize = 13.sp, color = TextMain, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 10.dp))
                        Text(d.done.format(DAY), fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, color = Faint)
                    }
                }
                if (r.done.size > 12) Text("+ ${r.done.size - 12} more", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 6.dp))
            }
            Panel(Modifier.weight(1f)) {
                PanelHead("Carried over", "${r.carried.size}")
                if (r.carried.isEmpty() && r.drop == null) Sub("Nothing carried over. Nice.")
                r.carried.forEach { t ->
                    var menu by remember { mutableStateOf(false) }
                    Line {
                        Box(Modifier.size(16.dp).clip(CircleShape).border(1.5.dp, Muted, CircleShape))
                        Text(TaskViews.plainText(t), fontSize = 13.sp, color = if (t.raw in moved) Muted else TextMain, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(start = 10.dp))
                        val m = moved[t.raw]
                        if (m != null) Text("→ ${m.format(DAY)}", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = TaskGreen)
                        else Box {
                            TextAction("Move ▾") { menu = true }
                            DropdownMenu(menu, { menu = false }) {
                                listOf("Tomorrow" to today.plusDays(1), "Monday" to mon, "In a week" to today.plusDays(7)).forEach { (label, d) ->
                                    DropdownMenuItem({ Text(label) }, { menu = false; move(t, d) })
                                }
                            }
                        }
                    }
                }
                r.drop?.let { t ->
                    Line {
                        Box(Modifier.size(16.dp).clip(CircleShape).border(if (dropped) 5.dp else 1.5.dp, Warn, CircleShape))
                        Text(TaskViews.plainText(t), fontSize = 13.sp, color = if (dropped) Muted else TextMain, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            textDecoration = if (dropped) TextDecoration.LineThrough else null, modifier = Modifier.weight(1f).padding(start = 10.dp))
                        if (!dropped) TextAction("Drop", Warn) {
                            scope.launch {
                                state.io("Couldn't drop it") { state.writer()!!.cancelTask(t, today) } ?: return@launch
                                dropped = true; state.changed()
                            }
                        }
                    }
                }
                val left = r.carried.filter { it.raw !in moved }
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (r.drop != null) "One to drop · overdue over a week" else "Move each to a day", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Muted)
                    if (left.size > 1) TextAction("Move all to Monday") { left.forEach { move(it, mon) } }
                }
            }
            Panel(Modifier.weight(1f)) {
                PanelHead("Next week", "pick up to 3")
                val choices = r.nextTasks.map { TaskViews.plainText(it) } + r.carried.filter { it.raw in moved }.map { TaskViews.plainText(it) }
                if (choices.isEmpty()) Sub("Nothing due next week yet.")
                choices.distinct().take(10).forEach { text ->
                    val i = picks.indexOf(text)
                    Line {
                        Box(
                            Modifier.size(18.dp).clip(RoundedCornerShape(6.dp)).background(if (i >= 0) Primary else Color.Transparent)
                                .border(1.5.dp, Primary, RoundedCornerShape(6.dp)).hand()
                                .clickable { picks = if (i >= 0) picks - text else if (picks.size < 3) picks + text else picks },
                            contentAlignment = Alignment.Center
                        ) { if (i >= 0) Text("${i + 1}", fontSize = 10.sp, color = OnPrimary, fontWeight = FontWeight.Bold) }
                        Text(text, fontSize = 13.sp, color = TextMain, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 10.dp))
                    }
                }
                if (r.nextBills.isNotEmpty()) Text("Bills: " + r.nextBills.joinToString(" · ") { it.name }, fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 10.dp))
                Text("Your picks go in the recap note as next week's focus.", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 10.dp))
            }
        }
    }
}

/** A routine that's running; it keeps going while you switch panes. */
data class RoutineRun(val routine: Routine, val step: Int, val stepEndsAt: Long, val startedAt: LocalTime, val done: Set<Int> = emptySet())

/** Routines from 99 - Meta/Routines.md, and the running one step by step with a timer. Space = Done. */
@Composable
fun RoutinesPane(state: AppState) {
    var routines by remember { mutableStateOf<List<Routine>?>(null) }
    var habits by remember { mutableStateOf<List<Habit>>(emptyList()) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    LaunchedEffect(state.vault, state.changes) {
        val v = state.vault ?: return@LaunchedEffect
        routines = state.io("Couldn't read Routines.md") { Routines.load(v) }
        habits = state.io { Habits.load(v).all } ?: emptyList()
    }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(500) } }
    LaunchedEffect(state.routine != null) { runCatching { focus.requestFocus() } }

    fun start(r: Routine) {
        state.routine = RoutineRun(r, 0, System.currentTimeMillis() + r.steps[0].minutes * 60_000L, LocalTime.now())
    }
    fun next(ticked: Boolean) {
        val run = state.routine ?: return
        val s = run.routine.steps[run.step]
        if (ticked) Routines.habitFor(s, habits)?.let { h ->
            val v = state.vault
            if (v != null) scope.launch { state.io("Couldn't tick ${h.name}") { Habits.update(v, LocalDate.now()) { d -> if (h.name in d.done) d else d.toggle(h) } }; state.changed() }
        }
        val done = if (ticked) run.done + run.step else run.done
        val n = run.step + 1
        if (n >= run.routine.steps.size) { state.routine = null; state.say("${run.routine.name} done · ${done.size} of ${run.routine.steps.size}"); return }
        state.routine = run.copy(step = n, stepEndsAt = System.currentTimeMillis() + run.routine.steps[n].minutes * 60_000L, done = done)
    }

    Row(
        Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 26.dp).focusRequester(focus).focusable()
            .onPreviewKeyEvent { e -> if (e.type == KeyEventType.KeyDown && e.key == Key.Spacebar && state.routine != null) { next(true); true } else false },
        horizontalArrangement = Arrangement.spacedBy(26.dp)
    ) {
        Column(Modifier.width(300.dp)) {
            H1("Routines", 26)
            Row(Modifier.padding(top = 4.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${routines?.size ?: 0} routines · ", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Muted)
                Text("✎ edit in Obsidian", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Primary,
                    modifier = Modifier.hand().clickable { state.openInObsidian(Routines.PATH) })
            }
            routines.orEmpty().forEach { r ->
                val on = state.routine?.routine?.name == r.name
                val shape = RoundedCornerShape(14.dp)
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(shape).background(if (on) PrimaryTint else Surface1)
                        .border(1.dp, if (on) Primary else Outline, shape).hand().clickable { if (!on) start(r) }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(r.name, fontSize = 14.sp, color = if (on) Primary else TextMain, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text("${r.minutes}m", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Muted)
                }
            }
            if (routines?.isEmpty() == true) Sub("No routines yet. Add some to Routines.md.")
        }
        val run = state.routine
        Panel(Modifier.weight(1f).fillMaxHeight()) {
            if (run == null) {
                Sub("Pick a routine to start it. Each step gets a timer; Space ticks it off. A step named like a habit ticks that habit.")
                return@Panel
            }
            val r = run.routine
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                Text(r.name, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = TextMain, modifier = Modifier.weight(1f))
                Text("Started ${run.startedAt.format(DateTimeFormatter.ofPattern("HH:mm"))} · ${run.step} of ${r.steps.size} · done by ${Routines.doneBy(r, run.step, LocalTime.now()).format(DateTimeFormatter.ofPattern("HH:mm"))}",
                    fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, color = Muted)
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                r.steps.forEachIndexed { i, s ->
                    val current = i == run.step
                    val past = i < run.step
                    val shape = RoundedCornerShape(14.dp)
                    Row(
                        Modifier.fillMaxWidth().clip(shape).background(if (current) PrimaryTint else Surface2)
                            .border(1.dp, if (current) Primary else Outline, shape).padding(horizontal = 16.dp, vertical = if (current) 14.dp else 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(s.label, fontSize = 14.sp, color = if (past) Muted else TextMain, modifier = Modifier.weight(1f),
                            textDecoration = if (past && i in run.done) TextDecoration.LineThrough else null)
                        if (current) {
                            val left = ((run.stepEndsAt - now) / 1000).toInt()
                            val shown = if (left >= 0) "%02d:%02d".format(left / 60, left % 60) else "+%02d:%02d".format(-left / 60, -left % 60)
                            Text(shown, fontSize = 32.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = if (left >= 0) TextMain else Amber)
                        } else Text("${s.minutes}m" + (Routines.habitFor(s, habits)?.let { " · ticks habit" } ?: ""), fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Muted)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton("Done ›", hint = "Space") { next(true) }
                Chip("Skip", small = true) { next(false) }
                Spacer(Modifier.weight(1f))
                Chip("Stop", small = true) { state.routine = null }
            }
        }
    }
}

/** Job applications from the Job Hunt folder: status and next date edited in the row, + Add job. */
@Composable
fun JobsPane(state: AppState) {
    var jobs by remember { mutableStateOf<List<Job>?>(null) }
    var filter by remember { mutableStateOf("Open") }
    var showClosed by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var company by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("") }
    var dating by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val today = LocalDate.now()
    fun reload() = scope.launch { state.vault?.let { v -> jobs = state.io("Couldn't read the Job Hunt folder") { Jobs.list(v) } } }
    LaunchedEffect(state.vault, state.changes) { reload() }

    fun edit(work: (uk.tomlowndes.capture.Vault) -> Unit) = scope.launch {
        val v = state.vault ?: return@launch
        state.io("Couldn't save it") { work(v) }; reload()
    }

    val all = jobs.orEmpty()
    val (open, closed) = Jobs.sorted(all)
    val shown = when (filter) { "Interview" -> open.filter { it.status.equals("Interview", true) }; "Applied" -> open.filter { it.status.equals("Applied", true) }; else -> open }
    val thisWeek = open.count { j -> j.next?.let { !it.isAfter(today.plusDays(6)) } == true }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 26.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextAction("‹ Vault", Muted) { state.tab = Tab.VAULT }
                }
                H1("💼 Jobs", 28)
                Text("${open.size} open · $thisWeek this week · ${closed.size} closed", color = Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
            }
            PrimaryButton("＋ Add job") { adding = !adding }
        }
        if (adding) Row(Modifier.padding(bottom = 14.dp).widthIn(max = 760.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(company, { company = it }, Modifier.weight(1f), "Company")
            Field(role, { role = it }, Modifier.weight(1f), "Role")
            PrimaryButton("Add", enabled = company.isNotBlank() || role.isNotBlank()) {
                val c = company; val r = role
                edit { v -> Jobs.create(v, c, r) }
                company = ""; role = ""; adding = false
            }
            Chip("Cancel", small = true) { adding = false }
        }
        Row(Modifier.padding(bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Open", "Interview", "Applied").forEach { f -> Chip(f, filter == f, small = true) { filter = f } }
        }
        if (jobs == null) { Sub("Reading the Job Hunt folder…"); return@Column }
        Panel(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                listOf("COMPANY" to 1.2f, "ROLE" to 2f, "STATUS" to 1.1f, "NEXT" to 1.1f, "APPLIED" to 0.7f).forEach { (h, w) ->
                    Text(h, fontSize = 10.5.sp, letterSpacing = 1.2.sp, fontFamily = FontFamily.Monospace, color = Muted, modifier = Modifier.weight(w))
                }
            }
            if (shown.isEmpty()) Sub("No applications here yet.", Modifier.padding(vertical = 8.dp))
            (shown + if (showClosed) closed else emptyList()).forEach { j -> JobRow(state, j, today, onStatus = { s -> edit { v -> Jobs.setStatus(v, j.path, s) } }, onNext = { dating = j }) }
            if (closed.isNotEmpty()) Text(
                (if (showClosed) "▾ " else "▸ ") + "${closed.size} closed", fontSize = 12.5.sp, color = Muted,
                modifier = Modifier.padding(top = 10.dp).hand().clickable { showClosed = !showClosed }
            )
        }
    }
    dating?.let { j ->
        PickDateDialog(j.next ?: today.plusDays(3), onPick = { d -> dating = null; edit { v -> Jobs.setNext(v, j.path, d) } }, onDismiss = { dating = null })
    }
}

@Composable
private fun JobRow(state: AppState, j: Job, today: LocalDate, onStatus: (String) -> Unit, onNext: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val (fg, bg) = when (j.status.lowercase()) {
        "interview" -> Purple to TagTint
        "applied" -> Primary to PrimaryTint
        "offer" -> TaskGreen to TaskTint
        "rejected", "withdrawn" -> Warn to WarnTint
        else -> Muted to Surface2
    }
    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Outline))
        Row(Modifier.fillMaxWidth().hand().clickable { state.vaultNote = j.path; state.tab = Tab.VAULT }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(j.company.ifBlank { "–" }, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = TextMain, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1.2f))
            Text(j.role, fontSize = 13.5.sp, color = TextMain, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(2f))
            Box(Modifier.weight(1.1f)) {
                Text("${j.status} ▾", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = fg,
                    modifier = Modifier.clip(CircleShape).background(bg).hand().clickable { menu = true }.padding(horizontal = 9.dp, vertical = 2.dp))
                DropdownMenu(menu, { menu = false }) {
                    Jobs.STATUSES.forEach { s -> DropdownMenuItem({ Text(s) }, { menu = false; onStatus(s) }) }
                }
            }
            Text(j.next?.format(DAY) ?: "–", fontSize = 13.sp, color = if (j.next != null && !j.next.isAfter(today)) Amber else if (j.next == null) Muted else TextMain,
                modifier = Modifier.weight(1.1f).hand().clickable(onClick = onNext))
            Text(j.applied?.format(SHORT) ?: "–", fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, color = Muted, modifier = Modifier.weight(0.7f))
        }
    }
}
