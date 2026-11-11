package uk.tomlowndes.capture

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.delay
import java.time.LocalTime
import kotlin.math.roundToInt
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val HM = DateTimeFormatter.ofPattern("HH:mm")
private val NextGradient = Brush.linearGradient(listOf(Color(0xFF1C2A4D), Color(0xFF1A2033)))
private val GapTint = Color(0x0D7AA2FF)

/** The slim List · Upcoming switch under the Tasks title. */
@Composable
internal fun TasksViewSwitch(upcoming: Boolean, modifier: Modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp), onPick: (Boolean) -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(12.dp)).background(Surface1)
            .border(1.dp, Outline, RoundedCornerShape(12.dp)).padding(3.dp)
    ) {
        listOf(false to "List", true to "Upcoming").forEach { (v, label) ->
            val on = v == upcoming
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(9.dp)).background(if (on) PrimaryTint else Color.Transparent)
                    .clickable { onPick(v) }.padding(vertical = 7.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(label, fontSize = 13.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, color = if (on) Primary else Muted)
            }
        }
    }
}

/**
 * Upcoming (tasks-swipe-v1): a Next up countdown, a 7-day strip (tap a day to jump to it), then each day's tasks and
 * calendar events. Today also shows late tasks and free gaps with "＋ Fit a task". [taskRow] draws a task (with swipe).
 */
@Composable
internal fun UpcomingList(
    days: List<AgendaDay>, next: CalEvent?, now: LocalDateTime, colours: Map<String, Color>, names: Map<String, String>,
    travel: TravelLine?, onFit: (AgendaItem.Gap) -> Unit,
    top: (@Composable () -> Unit)? = null,
    onDrop: (TaskItem, SlotDrop) -> Unit = { _, _ -> },
    taskRow: @Composable (AgendaItem.Task, lifted: Boolean) -> Unit,
) {
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val base = (if (next != null) 2 else 1) + (if (top != null) 1 else 0)
    val drag = remember { SlotDrag() }
    var root by remember { mutableStateOf(Rect.Zero) }
    // Near the top or bottom edge while dragging, the list scrolls on its own.
    LaunchedEffect(drag.task) {
        val edge = with(density) { 72.dp.toPx() }
        while (drag.task != null) {
            val y = drag.pointer.y - root.top
            if (y < edge) state.scrollBy(-14f) else if (y > root.height - edge) state.scrollBy(14f)
            delay(16)
        }
    }
    Box(Modifier.fillMaxSize().onGloballyPositioned { root = it.boundsInWindow() }) {
    LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(bottom = 16.dp, top = 4.dp)) {
        if (top != null) item(key = "top") { top() }
        if (next != null) item(key = "next") { NextUpCard(next, now) }
        item(key = "strip") {
            WeekStrip(days, colours, drag) { i -> scope.launch { state.animateScrollToItem(base + i * 2) } }
        }
        days.forEachIndexed { i, d ->
            item(key = "dh-${d.date}") { DayHead(d.date, now.toLocalDate()) }
            item(key = "dc-${d.date}") {
                if (d.clear) Text("Clear day", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Outline, modifier = Modifier.padding(bottom = 8.dp))
                else ListCard {
                    if (d.allDay.isNotEmpty()) Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        d.allDay.forEach { e ->
                            Text(
                                e.title, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = OnPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(colours[e.calendarId] ?: Primary).padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                    d.items.forEachIndexed { j, it ->
                        if (j > 0 || d.allDay.isNotEmpty()) HorizontalDivider(color = Outline)
                        when (it) {
                            is AgendaItem.Task -> key(it.task.raw) {
                                // Hold, then drag onto a free gap or a day (drag-to-slot-v1).
                                val item = it
                                var origin by remember { mutableStateOf(Offset.Zero) }
                                Box(
                                    Modifier.onGloballyPositioned { c -> origin = c.positionInWindow() }
                                        .graphicsLayer { alpha = if (drag.task == item.task) 0.35f else 1f }
                                        .pointerInput(item.task.raw) {
                                            detectDragGesturesAfterLongPress(
                                                onDragStart = { o ->
                                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    drag.pointer = origin + o; drag.task = item.task
                                                },
                                                onDragEnd = {
                                                    val t = drag.task
                                                    val hit = drag.over()?.second
                                                    drag.task = null
                                                    if (t != null && hit != null && hit.fits(t)) onDrop(t, hit)
                                                },
                                                onDragCancel = { drag.task = null },
                                            ) { change, amount -> change.consume(); drag.pointer += amount }
                                        }
                                ) { taskRow(item, drag.task == item.task) }
                            }
                            is AgendaItem.Event -> EventRow(it.event, now, colours[it.event.calendarId] ?: Primary, names[it.event.calendarId],
                                travel?.takeIf { tr -> tr.event == it.event })
                            is AgendaItem.Gap -> GapRow(it, drag) { onFit(it) }
                        }
                    }
                }
            }
        }
    }
    // The task under your finger, with where it would land.
    drag.task?.let { t ->
        val hit = drag.over()?.second
        val w = with(density) { (root.width - 48.dp.toPx()).coerceAtLeast(0f).toDp() }
        Row(
            Modifier.offset { IntOffset(24.dp.roundToPx(), (drag.pointer.y - root.top - 28.dp.toPx()).roundToInt()) }.width(w)
                .graphicsLayer { rotationZ = -1.5f }.shadow(14.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp))
                .background(Surface2).border(1.5.dp, Primary, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(TaskViews.plainText(t), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    hit?.label(t) ?: TaskRules.minutesLabel(DayPlan.minutes(t)), fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                    color = if (hit?.fits(t) == false) Warn else Primary
                )
            }
        }
    }
    }
}

/** Where a dragged task can land (drag-to-slot-v1): a free gap (that day at [start]) or a week-strip day (no time). */
internal data class SlotDrop(val date: LocalDate, val start: LocalTime? = null, val minutes: Int? = null) {
    fun fits(t: TaskItem) = minutes == null || DayPlan.minutes(t) <= minutes
    fun label(t: TaskItem): String = when {
        !fits(t) -> "Too short · ${TaskRules.minutesLabel(DayPlan.minutes(t))}"
        start != null -> "${start.format(HM)}–${start.plusMinutes(DayPlan.minutes(t).toLong()).format(HM)} · ${TaskRules.minutesLabel(DayPlan.minutes(t))}"
        else -> "Move to " + date.format(DateTimeFormatter.ofPattern("EEE d", Locale.UK))
    }
}

/** The task being dragged, the finger (window coordinates) and the drop targets on screen. */
private class SlotDrag {
    var task by mutableStateOf<TaskItem?>(null)
    var pointer by mutableStateOf(Offset.Zero)
    val targets = mutableStateMapOf<String, Pair<Rect, SlotDrop>>()
    fun over(): Pair<String, SlotDrop>? = if (task == null) null else
        targets.entries.firstOrNull { it.value.first.contains(pointer) }?.let { it.key to it.value.second }
}

/** Registers this element as a drop target under [key] while it's on screen. */
@Composable
private fun Modifier.dropTarget(drag: SlotDrag, key: String, drop: SlotDrop): Modifier {
    DisposableEffect(key) { onDispose { drag.targets.remove(key) } }
    return onGloballyPositioned { drag.targets[key] = it.boundsInWindow() to drop }
}

@Composable
private fun NextUpCard(e: CalEvent, now: LocalDateTime) {
    val on = !e.start.isAfter(now)
    Row(
        Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(16.dp)).background(NextGradient)
            .border(1.dp, NextBorder, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(if (on) "ON NOW" else "NEXT UP", fontSize = 11.sp, fontFamily = FontFamily.Monospace, letterSpacing = 0.6.sp, color = Primary)
            Text(e.title, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(Upcoming.countdown(e, now), fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = Primary)
            Text(if (on) "until ${e.end.format(HM)}" else e.start.format(HM), fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Muted)
        }
    }
}

@Composable
private fun WeekStrip(days: List<AgendaDay>, colours: Map<String, Color>, drag: SlotDrag, onDay: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        days.forEachIndexed { i, d ->
            val on = i == 0
            val key = "day-${d.date}"
            val hot = drag.over()?.first == key
            Column(
                Modifier.weight(1f).padding(horizontal = 2.dp).dropTarget(drag, key, SlotDrop(d.date)).clip(RoundedCornerShape(12.dp))
                    .background(if (hot) DropTint else if (on) PrimaryTint else Color.Transparent)
                    .border(1.5.dp, if (hot) Primary else Color.Transparent, RoundedCornerShape(12.dp))
                    .clickable { onDay(i) }.padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(d.date.format(DateTimeFormatter.ofPattern("EEE", Locale.UK)), fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Muted)
                Text(d.date.dayOfMonth.toString(), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (on) Primary else TextMain)
                Row(Modifier.height(8.dp).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    val evColours = (d.allDay + d.items.filterIsInstance<AgendaItem.Event>().map { it.event }).map { colours[it.calendarId] ?: Purple }
                    evColours.take(3).forEach { Dot(it) }
                    repeat(minOf(d.tasks, 4 - minOf(evColours.size, 3))) { Dot(Muted) }
                }
            }
        }
    }
}

@Composable
private fun DayHead(d: LocalDate, today: LocalDate) {
    val title = when (d) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        else -> d.format(DateTimeFormatter.ofPattern("EEE d", Locale.UK))
    }
    Row(Modifier.padding(top = 12.dp, bottom = 6.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (d == today) Primary else TextMain)
        if (d == today || d == today.plusDays(1)) Text(d.format(DateTimeFormatter.ofPattern("EEE d", Locale.UK)), fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Muted)
    }
}

/** The time column every Upcoming line starts with. */
@Composable
private fun TimeCol(top: String, bottom: String? = null, colour: Color = Muted) = Column(Modifier.width(44.dp)) {
    Text(top, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = colour, lineHeight = 14.sp)
    bottom?.let { Text(it, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = colour, lineHeight = 14.sp) }
}

@Composable
private fun EventRow(e: CalEvent, now: LocalDateTime, colour: Color, calendar: String?, travel: TravelLine?) {
    val on = e.isNow(now)
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).background(if (on) NextBg else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        TimeCol(e.start.format(HM), e.end.format(HM), if (on) Primary else Muted)
        Box(Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(colour))
        Column(Modifier.weight(1f)) {
            Text(e.title, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val sub = listOfNotNull(calendar, travel?.let { "leave by ${it.leaveBy.format(HM)}" } ?: e.location).joinToString(" · ")
            if (sub.isNotEmpty()) Text(sub, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun GapRow(g: AgendaItem.Gap, drag: SlotDrag, onFit: () -> Unit) {
    val key = "gap-${g.start}"
    val drop = SlotDrop(g.start.toLocalDate(), g.start.toLocalTime(), g.minutes)
    val hot = drag.over()?.first == key
    val t = drag.task
    Row(
        Modifier.fillMaxWidth().dropTarget(drag, key, drop).background(if (hot) DropTint else GapTint)
            .border(if (hot) 1.5.dp else 0.dp, if (hot) Primary else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        TimeCol(g.start.format(HM))
        Text(
            if (hot && t != null) (if (drop.fits(t)) "Drop here · " else "") + drop.label(t) else Upcoming.gapLabel(g.minutes),
            fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = if (hot && t != null && !drop.fits(t)) Warn else if (hot) TextMain else Muted,
            modifier = Modifier.weight(1f)
        )
        if (!hot) Text(
            "＋ Fit a task", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Primary,
            modifier = Modifier.clip(CircleShape).background(PrimaryTint).clickable(onClick = onFit).padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

/** A task line in Upcoming: "late" or "any" where events show their time, then a small tick box and the task. */
@Composable
internal fun AgendaTaskRow(t: TaskItem, late: Boolean, ticked: Boolean, onTick: () -> Unit, onOpen: () -> Unit, at: TimedTask? = null) {
    val side = if (TaskViews.isWork(t)) WorkEdge else PersonalEdge
    Row(
        Modifier.fillMaxWidth().background(if (ticked) TaskTint else if (at != null) DropTint else Color.Transparent).heightIn(min = 44.dp)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // A planned task shows its slot where events show their time (drag-to-slot-v1).
        if (at != null) TimeCol(at.start.format(HM), at.end.format(HM), Primary)
        else TimeCol(if (late) "late" else "any", colour = if (late) Warn else Muted)
        TickBox(ticked, onTick, size = 20.dp, radius = 7.dp, outline = side)
        Column(Modifier.weight(1f).clickable(onClick = onOpen)) {
            Text(DayPlan.bareText(TaskViews.plainText(t)), fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, color = if (ticked) Muted else TextMain)
            val sub = listOfNotNull(
                if (late) t.due?.let { "from " + it.format(DateTimeFormatter.ofPattern("EEE d", Locale.UK)) } else null,
                Deck.minutes(t)?.let { TaskRules.minutesLabel(it) },
                TaskViews.tags(t).firstOrNull { it !in Tags.BUILT_IN },
            ).joinToString(" · ")
            if (sub.isNotEmpty()) Text(sub, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Muted, modifier = Modifier.padding(top = 2.dp))
        }
        if (ticked) Text("✓", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Green)
    }
}

/** "＋ Fit a task": open tasks whose estimate fits the gap. Picking one plans it at the gap's start. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FitTaskSheet(gap: AgendaItem.Gap, tasks: List<TaskItem>, onPick: (TaskItem) -> Unit, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val fits = Upcoming.fits(tasks, gap.minutes, gap.start.toLocalDate())
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Surface1) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 18.dp, end = 18.dp, bottom = 26.dp)) {
            Text("${gap.start.format(HM)}–${gap.end.format(HM)} · ${Upcoming.gapLabel(gap.minutes)}", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Muted)
            Text("Fit a task", fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 2.dp, bottom = 12.dp))
            if (fits.isEmpty()) Text("Nothing with an estimate fits", fontSize = 13.sp, color = Muted)
            else ListCard {
                fits.forEachIndexed { i, t ->
                    if (i > 0) HorizontalDivider(color = Outline)
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(t) }.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(TaskViews.plainText(t), fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Deck.minutes(t)?.let { Text(TaskRules.minutesLabel(it), fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Primary) }
                    }
                }
            }
        }
    }
}

private val DropTint = Color(0xFF16203A)
