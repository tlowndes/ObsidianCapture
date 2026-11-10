package uk.tomlowndes.capture.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uk.tomlowndes.capture.CalEvent
import uk.tomlowndes.capture.CalendarSource
import uk.tomlowndes.capture.DayPlan
import uk.tomlowndes.capture.Priority
import uk.tomlowndes.capture.TaskItem
import uk.tomlowndes.capture.TaskViews
import uk.tomlowndes.capture.Upcoming
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** Where a dragged task can land: a day (just the date) or a free gap (date and start time). */
private data class DropTarget(val date: LocalDate, val start: LocalTime? = null)

/** One thing in a day column, in time order. */
private sealed class Slot(val at: LocalTime) {
    class Task(val t: TaskItem, at: LocalTime, val timed: Boolean, val late: Boolean = false) : Slot(at)
    class Event(val e: CalEvent) : Slot(e.start.toLocalTime())
    class Gap(val date: LocalDate, val start: LocalTime, val end: LocalTime) : Slot(start) {
        val minutes get() = java.time.Duration.between(start, end).toMinutes().toInt()
    }
}

private val TIME = DateTimeFormatter.ofPattern("HH:mm")
private val EventTeal = Color(0xFF5AD1D1)

/** "#7aa2ff" → colour, teal when it can't be read. */
private fun calColour(hex: String?): Color = runCatching {
    val h = hex!!.trim().removePrefix("#")
    Color(0xFF000000 or h.take(6).toLong(16))
}.getOrDefault(EventTeal)

/** The day's column: untimed tasks first, then events, timed tasks and free gaps (09:00–18:00) in time order. */
private fun slots(day: LocalDate, tasks: List<TaskItem>, events: List<CalEvent>, now: LocalDateTime): List<Slot> {
    val today = now.toLocalDate()
    val due = tasks.filter { it.due == day }.sortedWith(TaskViews.byPriority)
    val late = if (day == today) tasks.filter { it.due != null && it.due.isBefore(today) }.sortedWith(TaskViews.byPriority) else emptyList()
    val timed = due.mapNotNull { DayPlan.timeOf(it) }
    val untimed = late.map { Slot.Task(it, LocalTime.MIN, false, late = true) } +
        due.filter { t -> timed.none { it.task == t } }.map { Slot.Task(it, LocalTime.MIN, false) }
    val dayEvents = events.filter { !it.allDay && it.on(day) }
    // Gaps fit around both events and tasks that already have a time.
    val busy = dayEvents.map { maxOf(it.start, day.atStartOfDay()) to minOf(it.end, day.plusDays(1).atStartOfDay()) } +
        timed.map { day.atTime(it.start) to day.atTime(it.end) }
    val from = if (day == today) maxOf(day.atTime(Upcoming.DAY_START), now.withSecond(0).withNano(0)) else day.atTime(Upcoming.DAY_START)
    val end = day.atTime(Upcoming.DAY_END)
    val gaps = mutableListOf<Slot.Gap>()
    var free = from
    for ((s, e) in busy.sortedBy { it.first }) {
        val gapEnd = minOf(s, end)
        if (java.time.Duration.between(free, gapEnd).toMinutes() >= Upcoming.MIN_GAP) gaps += Slot.Gap(day, free.toLocalTime(), gapEnd.toLocalTime())
        if (e.isAfter(free)) free = e
    }
    if (java.time.Duration.between(free, end).toMinutes() >= Upcoming.MIN_GAP) gaps += Slot.Gap(day, free.toLocalTime(), end.toLocalTime())
    val timeline = dayEvents.map { Slot.Event(it) } + timed.map { Slot.Task(it.task, it.start, true) } + gaps
    return untimed + timeline.sortedBy { it.at }
}

/**
 * Upcoming as a 7-day week (windows-catchup-v1): tasks and calendar events side by side, free gaps between them.
 * Drag a task onto a gap to give it that day and time, or onto a day's header to move just the date.
 */
@Composable
fun UpcomingWeek(
    tasks: List<TaskItem>, events: List<CalEvent>, sources: List<CalendarSource>, now: LocalDateTime,
    onMove: (TaskItem, LocalDate) -> Unit, onPlan: (TaskItem, LocalDate, LocalTime) -> Unit,
    onFit: (LocalDate, LocalTime, Int) -> Unit, onOpen: (TaskItem) -> Unit,
) {
    val colours = remember(sources) { sources.associate { it.id to calColour(it.colour) } }
    val targets = remember(tasks, events) { mutableStateMapOf<String, Pair<Rect, DropTarget>>() }
    var dragging by remember { mutableStateOf<TaskItem?>(null) }
    var pointer by remember { mutableStateOf(Offset.Zero) }
    var root by remember { mutableStateOf(Offset.Zero) }
    val hit = dragging?.let { targets.values.firstOrNull { it.first.contains(pointer) }?.second }
    val today = now.toLocalDate()

    fun drop() {
        val t = dragging ?: return
        val to = targets.values.firstOrNull { it.first.contains(pointer) }?.second
        dragging = null
        if (to == null) return
        if (to.start != null) onPlan(t, to.date, to.start) else if (to.date != t.due) onMove(t, to.date)
    }

    Box(Modifier.fillMaxSize().onGloballyPositioned { root = it.positionInWindow() }) {
        Column(Modifier.fillMaxSize()) {
            Upcoming.nextUp(events, now)?.let { e ->
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(RoundedCornerShape(14.dp)).background(Surface1)
                        .border(1.dp, Outline, RoundedCornerShape(14.dp)).padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(Modifier.size(9.dp).clip(CircleShape).background(colours[e.calendarId] ?: EventTeal))
                    Text(Upcoming.countdown(e, now).let { c -> if (c == "now") "Now: ${e.title}" else "Next up: ${e.title} in $c" }, fontSize = 13.5.sp, color = TextMain, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(e.start.format(TIME) + (e.location?.let { " · $it" } ?: ""), fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Muted, maxLines = 1)
                }
            }
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                (0 until Upcoming.DAYS).forEach { i ->
                    val day = today.plusDays(i.toLong())
                    val isToday = i == 0
                    val shape = RoundedCornerShape(14.dp)
                    val dayHit = hit != null && hit.date == day && hit.start == null
                    Column(
                        Modifier.weight(1f).fillMaxHeight().clip(shape).background(if (dayHit) PrimaryTint else Surface1)
                            .border(1.dp, if (isToday || dayHit) Primary else Outline, shape).padding(10.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(bottom = 8.dp).onGloballyPositioned { targets["d$day"] = it.boundsInWindow() to DropTarget(day) },
                            verticalAlignment = Alignment.Bottom
                        ) {
                            Text(if (isToday) "Today" else day.format(DateTimeFormatter.ofPattern("EEE", Locale.UK)), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextMain, modifier = Modifier.weight(1f))
                            Text(day.dayOfMonth.toString(), fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, color = Muted)
                        }
                        events.filter { it.allDay && it.on(day) }.forEach { e -> EventChip(e, colours[e.calendarId] ?: EventTeal, allDay = true) }
                        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            slots(day, tasks, events, now).forEach { s ->
                                when (s) {
                                    is Slot.Event -> EventChip(s.e, colours[s.e.calendarId] ?: EventTeal)
                                    is Slot.Task -> WeekTask(
                                        s.t, if (s.timed) s.at.format(TIME) else null, s.late, faded = dragging == s.t, onOpen = { onOpen(s.t) },
                                        onDragStart = { origin -> dragging = s.t; pointer = origin },
                                        onDrag = { pointer += it }, onDragEnd = ::drop, onDragCancel = { dragging = null },
                                    )
                                    is Slot.Gap -> {
                                        val key = "g${s.date}${s.start}"
                                        val over = hit != null && hit.date == s.date && hit.start == s.start
                                        GapChip(s, over, dragging != null,
                                            Modifier.onGloballyPositioned { targets[key] = it.boundsInWindow() to DropTarget(s.date, s.start) }
                                        ) { onFit(s.date, s.start, s.minutes) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        // The task under the pointer while dragging.
        dragging?.let { t ->
            val d = LocalDensity.current
            Text(
                "⠿ " + TaskViews.plainText(t), fontSize = 12.sp, color = TextMain, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.offset { IntOffset((pointer.x - root.x + 8).roundToInt(), (pointer.y - root.y - with(d) { 14.dp.toPx() }).roundToInt()) }
                    .widthIn(max = 220.dp).rotate(-2f).clip(RoundedCornerShape(8.dp)).background(Color(0xFF26304A))
                    .border(1.dp, Primary, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
private fun EventChip(e: CalEvent, colour: Color, allDay: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(bottom = if (allDay) 6.dp else 0.dp).clip(RoundedCornerShape(8.dp)).background(colour.copy(alpha = 0.12f))) {
        Box(Modifier.width(3.dp).heightIn(min = 34.dp).background(colour))
        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(e.title, fontSize = 12.sp, color = TextMain, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(if (allDay) "all day" else "${e.start.format(TIME)} – ${e.end.format(TIME)}", fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, color = Muted)
        }
    }
}

@Composable
private fun WeekTask(
    t: TaskItem, time: String?, late: Boolean, faded: Boolean, onOpen: () -> Unit,
    onDragStart: (Offset) -> Unit, onDrag: (Offset) -> Unit, onDragEnd: () -> Unit, onDragCancel: () -> Unit,
) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    val edge = if (TaskViews.isWork(t)) WorkEdge else PersonalEdge
    Row(
        Modifier.fillMaxWidth().onGloballyPositioned { origin = it.positionInWindow() }
            .clip(RoundedCornerShape(8.dp)).background(Surface2.copy(alpha = if (faded) 0.4f else 1f))
            .pointerInput(t.raw) {
                detectDragGestures(
                    onDragStart = { onDragStart(origin + it) }, onDragEnd = onDragEnd, onDragCancel = onDragCancel,
                ) { change, amount -> change.consume(); onDrag(amount) }
            }
            .hand().clickable(onClick = onOpen).padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Box(Modifier.padding(top = 2.dp).size(13.dp).clip(RoundedCornerShape(4.dp)).border(1.5.dp, if (late) Warn else edge, RoundedCornerShape(4.dp)))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PriorityFlag(Priority.of(t), Modifier.padding(end = 5.dp))
                Text(DayPlan.bareText(TaskViews.plainText(t)), fontSize = 12.sp, color = if (late) Warn else TextMain, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (time != null || late) Text(
                if (late) "late · ${t.due?.let(::dayChip) ?: ""}" else time!!,
                fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, color = Muted
            )
        }
    }
}

@Composable
private fun GapChip(g: Slot.Gap, over: Boolean, dragging: Boolean, modifier: Modifier, onFit: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier.fillMaxWidth().clip(shape).background(if (over) Color(0xFF18223D) else Color.Transparent)
            .border(1.5.dp, if (over) Primary else Color(0xFF2E3A58), shape).hand().clickable(onClick = onFit)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("${g.start.format(TIME)} – ${g.end.format(TIME)} · ${Upcoming.gapLabel(g.minutes).removeSuffix(" free")}",
            fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, color = if (over) TextMain else Muted, modifier = Modifier.weight(1f))
        Text(if (dragging) "drop" else "＋ Fit", fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, color = Primary, fontWeight = FontWeight.SemiBold)
    }
}
