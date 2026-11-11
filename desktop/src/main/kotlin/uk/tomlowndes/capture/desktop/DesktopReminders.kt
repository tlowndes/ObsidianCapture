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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.tomlowndes.capture.DayPlan
import uk.tomlowndes.capture.ReminderPlan
import uk.tomlowndes.capture.ReminderSlot
import uk.tomlowndes.capture.TaskItem
import uk.tomlowndes.capture.TaskViews
import uk.tomlowndes.capture.TimedTask
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** A pop-up card in the bottom-right corner (windows-reminders-v1). */
sealed class Reminder {
    val id: Long = System.nanoTime()
    data class Timed(val t: TimedTask) : Reminder()
    data class Due(val tasks: List<TaskItem>) : Reminder()
    data class Daily(val slot: ReminderSlot, val line: String, val left: List<TaskItem> = emptyList()) : Reminder()
    data class Test(val note: String = "This is how reminders look.") : Reminder()
}

/**
 * Checks every 30 seconds while the app runs (it sits in the tray) and shows Flight Deck's own pop-up cards,
 * which, unlike Windows' notifications, can have Done and Snooze buttons.
 */
object DesktopReminders {
    val showing = mutableStateListOf<Reminder>()
    private val snoozed = mutableListOf<Pair<Long, Reminder>>()
    private const val MAX = 4
    private val HM = DateTimeFormatter.ofPattern("HH:mm")

    suspend fun run(state: AppState) {
        delay(5_000) // let the window and vault settle first
        while (true) {
            runCatching { check(state) }
            delay(30_000)
        }
    }

    fun show(state: AppState, r: Reminder) {
        showing.add(r)
        while (showing.size > MAX) showing.removeAt(0)
        if (state.prefs.reminderSound) runCatching { (Toolkit.getDefaultToolkit().getDesktopProperty("win.sound.default") as? Runnable)?.run() }
    }

    fun close(r: Reminder) { showing.removeAll { it.id == r.id } }

    fun snooze(r: Reminder, minutes: Long) {
        close(r)
        snoozed.add(System.currentTimeMillis() + minutes * 60_000 to r)
    }

    private suspend fun check(state: AppState) {
        val writer = state.writer() ?: return
        val now = LocalDateTime.now()
        val today = now.toLocalDate()
        val s = state.prefs.reminders
        var shown = ReminderPlan.prune(state.prefs.remindersShown, today)
        val tasks = withContext(Dispatchers.IO) { runCatching { writer.listTasks() }.getOrNull() } ?: return

        ReminderPlan.slotDue(s, now, shown)?.let { (slot, keys) ->
            shown = shown + keys
            daily(state, slot, tasks, now)?.let { show(state, it) }
        }
        ReminderPlan.timedDue(tasks, s, now, shown).forEach { t ->
            shown = shown + ReminderPlan.taskKey(t, today)
            show(state, Reminder.Timed(t))
        }
        val due = snoozed.filter { it.first <= System.currentTimeMillis() }
        snoozed.removeAll(due)
        due.forEach { show(state, it.second) }
        state.prefs.remindersShown = shown
    }

    private suspend fun daily(state: AppState, slot: ReminderSlot, tasks: List<TaskItem>, now: LocalDateTime): Reminder? {
        val today = now.toLocalDate()
        val due = ReminderPlan.dueToday(tasks, today)
        return when (slot) {
            ReminderSlot.DUE -> if (due.isEmpty()) null else Reminder.Due(due)
            ReminderSlot.MORNING -> {
                val v = state.vault
                val next = if (v == null) null else withContext(Dispatchers.IO) {
                    runCatching { DesktopCalendar.load(v).second }.getOrDefault(emptyList())
                        .filter { !it.allDay && it.start.toLocalDate() == today && it.start.isAfter(now) }.minByOrNull { it.start }
                }
                val bits = listOfNotNull(
                    "${due.size} due today",
                    next?.let { "${it.title} ${it.start.format(HM)}" },
                )
                Reminder.Daily(slot, bits.joinToString(" · "))
            }
            ReminderSlot.EVENING -> {
                val left = due.filter { it.due == today }
                Reminder.Daily(slot, if (left.isEmpty()) "All clear today" else "${left.size} left today · tick off or move the rest", left)
            }
            ReminderSlot.WEEKLY -> Reminder.Daily(slot, "Done, carried over, next week")
        }
    }
}

private const val CARD_W = 380
private const val GAP = 10
private const val EDGE = 16

private fun height(r: Reminder): Int = when (r) {
    is Reminder.Timed -> 150
    is Reminder.Due -> 130 + 28 * r.tasks.take(5).size + if (r.tasks.size > 5) 22 else 0
    is Reminder.Daily -> 130
    is Reminder.Test -> 130
}

/** The stack of reminder cards, in an always-on-top window above the clock that doesn't take focus. */
@Composable
fun ReminderWindow(state: AppState, icon: Painter) {
    val list = DesktopReminders.showing.toList()
    val h = list.sumOf { height(it) } + GAP * (list.size - 1).coerceAtLeast(0) + EDGE * 2
    val w = CARD_W + EDGE * 2
    val winState = rememberWindowState(size = DpSize(w.dp, h.dp), position = cornerPosition(w, h))
    LaunchedEffect(h) {
        winState.size = DpSize(w.dp, h.dp)
        winState.position = cornerPosition(w, h)
    }
    Window(
        onCloseRequest = { DesktopReminders.showing.clear() }, state = winState, title = "Flight Deck reminders", icon = icon,
        undecorated = true, transparent = true, alwaysOnTop = true, resizable = false, focusable = false,
    ) {
        DisposableEffect(window) {
            runCatching { window.type = java.awt.Window.Type.UTILITY } // no taskbar button
            onDispose { }
        }
        CaptureTheme {
            Column(Modifier.fillMaxSize().padding(EDGE.dp), verticalArrangement = Arrangement.spacedBy(GAP.dp, Alignment.Bottom)) {
                list.forEach { r -> ReminderCard(state, r, Modifier.height(height(r).dp)) }
            }
        }
    }
}

private fun cornerPosition(w: Int, h: Int): WindowPosition {
    val conf = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
    val ins = Toolkit.getDefaultToolkit().getScreenInsets(conf)
    val b = conf.bounds
    return WindowPosition((b.x + b.width - ins.right - w).dp, (b.y + b.height - ins.bottom - h).dp)
}

private class Action(val label: String, val color: Color, val run: () -> Unit)

@Composable
private fun ReminderCard(state: AppState, r: Reminder, modifier: Modifier) {
    val scope = state.scope
    fun open(tab: Tab) { DesktopReminders.close(r); state.tab = tab; state.windowVisible = true; state.changed() }
    fun moveAll(tasks: List<TaskItem>) = scope.launch {
        DesktopReminders.close(r)
        val tomorrow = LocalDate.now().plusDays(1)
        state.io("Couldn't move them") { tasks.forEach { state.writer()!!.rescheduleTask(it, tomorrow) } }
        state.changed()
        state.notify("Moved to tomorrow", "${tasks.size} task" + if (tasks.size == 1) "" else "s")
    }

    val head: String; val title: String; val sub: String; val edge: Color
    var rows: List<TaskItem> = emptyList()
    val actions: List<Action>
    val onBody: () -> Unit
    when (r) {
        is Reminder.Timed -> {
            val t = r.t
            val mins = Duration.between(t.start, t.end).toMinutes().toInt().coerceAtLeast(5)
            val left = Duration.between(LocalDateTime.now().toLocalTime(), t.start).toMinutes().coerceAtLeast(0)
            head = if (left <= 0) "now" else "in $left min"
            title = TaskViews.plainText(t.task).let(DayPlan::bareText)
            sub = "${t.start.format(DateTimeFormatter.ofPattern("HH:mm"))}–${t.end.format(DateTimeFormatter.ofPattern("HH:mm"))} · $mins min"
            edge = if (t.task.raw.contains("#work")) WorkEdge else PersonalEdge
            onBody = { open(Tab.TASKS) }
            actions = listOf(
                Action("✓ Done", TaskGreen) {
                    DesktopReminders.close(r)
                    scope.launch { state.io("Couldn't tick it") { state.writer()!!.completeTask(t.task, state.prefs.recurring) }; state.changed() }
                },
                Action("▶ Focus", Primary) {
                    state.focus = FocusRun(t.task, mins, System.currentTimeMillis() + mins * 60_000L)
                    open(Tab.DECK)
                },
                Action("Snooze 10m", Muted) { DesktopReminders.snooze(r, 10) },
            )
        }
        is Reminder.Due -> {
            val today = LocalDate.now()
            val late = r.tasks.count { it.due?.isBefore(today) == true }
            head = "Due today"; title = "${r.tasks.size} due today"
            sub = if (late > 0) "$late overdue" else "Nothing overdue"
            edge = Primary; rows = r.tasks
            onBody = { open(Tab.TASKS) }
            actions = listOf(Action("Open Tasks", Primary) { open(Tab.TASKS) }, Action("Snooze 1h", Muted) { DesktopReminders.snooze(r, 60) })
        }
        is Reminder.Daily -> {
            sub = r.line
            when (r.slot) {
                ReminderSlot.MORNING -> {
                    head = "Morning"; title = "☀ Today's plan is ready"; edge = Amber; onBody = { open(Tab.DECK) }
                    actions = listOf(Action("Open Deck", Primary) { open(Tab.DECK) }, Action("Snooze 1h", Muted) { DesktopReminders.snooze(r, 60) })
                }
                ReminderSlot.EVENING -> {
                    head = "Evening"; title = "🌙 Evening wrap-up"; edge = Purple; onBody = { open(Tab.TASKS) }
                    actions = listOfNotNull(
                        Action("Open Tasks", Primary) { open(Tab.TASKS) },
                        if (r.left.isNotEmpty()) Action("Move all to tomorrow", Primary) { moveAll(r.left) } else null,
                    )
                }
                ReminderSlot.WEEKLY -> {
                    head = "Sunday"; title = "Weekly review"; edge = Purple; onBody = { open(Tab.WEEKLY) }
                    actions = listOf(Action("Start review", Primary) { open(Tab.WEEKLY) }, Action("Snooze 1h", Muted) { DesktopReminders.snooze(r, 60) })
                }
                ReminderSlot.DUE -> {
                    head = "Due today"; title = "Tasks due today"; edge = Primary; onBody = { open(Tab.TASKS) }
                    actions = listOf(Action("Open Tasks", Primary) { open(Tab.TASKS) })
                }
            }
        }
        is Reminder.Test -> {
            head = "Test"; title = "Reminders are working"; sub = r.note; edge = Primary; onBody = { DesktopReminders.close(r) }
            actions = listOf(Action("Close", Muted) { DesktopReminders.close(r) })
        }
    }

    val shape = RoundedCornerShape(14.dp)
    Column(modifier.fillMaxWidth().shadow(10.dp, shape).clip(shape).background(Surface1).border(1.dp, Outline, shape)) {
        Column(Modifier.weight(1f).fillMaxWidth().hand().clickable(onClick = onBody)) {
            Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(13.dp).clip(RoundedCornerShape(4.dp)).background(Brush.linearGradient(listOf(Color(0xFF9BB8FF), Color(0xFF4A6FD6)))))
                Spacer(Modifier.width(8.dp))
                Text("Flight Deck · $head", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Muted, modifier = Modifier.weight(1f))
                Text("✕", fontSize = 12.sp, color = Muted, modifier = Modifier.clip(RoundedCornerShape(6.dp)).hand()
                    .clickable { DesktopReminders.close(r) }.padding(horizontal = 8.dp, vertical = 4.dp))
            }
            Row(Modifier.padding(top = 8.dp)) {
                Box(Modifier.width(3.dp).height(44.dp).background(edge))
                Column(Modifier.padding(start = 11.dp, end = 14.dp)) {
                    Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextMain, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(sub, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 3.dp))
                }
            }
            if (rows.isNotEmpty()) Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                val today = LocalDate.now()
                rows.take(5).forEach { t ->
                    Row(Modifier.height(28.dp), verticalAlignment = Alignment.CenterVertically) {
                        val c = if (t.raw.contains("#work")) WorkEdge else PersonalEdge
                        Box(Modifier.size(14.dp).clip(RoundedCornerShape(5.dp)).border(1.5.dp, c, RoundedCornerShape(5.dp)))
                        Text(TaskViews.plainText(t), fontSize = 13.sp, color = TextMain, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(start = 8.dp))
                        if (t.due?.isBefore(today) == true) Text("overdue", fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, color = Warn)
                    }
                }
                if (rows.size > 5) Text("+ ${rows.size - 5} more", fontSize = 11.5.sp, color = Muted, modifier = Modifier.height(22.dp))
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Outline))
        Row(Modifier.fillMaxWidth().height(38.dp)) {
            actions.forEachIndexed { i, a ->
                if (i > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(Outline))
                Box(Modifier.weight(1f).fillMaxSize().hand().clickable(onClick = a.run), contentAlignment = Alignment.Center) {
                    Text(a.label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = a.color, maxLines = 1)
                }
            }
        }
    }
}
