package uk.tomlowndes.capture

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * Full calendar view (calendar-view-v1): Upcoming gets Week and Month next to the 2-week list and the Day timeline.
 * Proton calendars, the app's own events (teal ○) and dated tasks show together.
 */

internal enum class UpcomingMode(val label: String) { LIST("2 weeks"), DAY("Day"), WEEK("Week"), MONTH("Month") }

@Composable
internal fun UpcomingModes(mode: UpcomingMode, onPick: (UpcomingMode) -> Unit) {
    Row(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        UpcomingMode.values().forEach { m -> AppChip(m == mode, { onPick(m) }, { Text(m.label) }) }
    }
}

private val HM = DateTimeFormatter.ofPattern("HH:mm")
private val MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.UK)
private val DAY_HEAD = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)
private val SHORT = DateTimeFormatter.ofPattern("d MMM", Locale.UK)
internal val AppEventColour = Color(0xFF5FD3C8)

private fun dotColour(d: Dot) = when (d) { Dot.EVENT -> AppEventColour; Dot.TASK -> TextMain; Dot.BILL -> Amber; Dot.BIRTHDAY -> Warn }

/** Swipe left or right to step: [onStep] gets +1 or -1. */
private fun Modifier.swipeStep(key: Any, onStep: (Int) -> Unit) = pointerInput(key) {
    var total = 0f
    detectHorizontalDragGestures(
        onDragStart = { total = 0f },
        onDragEnd = { if (total < -120f) onStep(1) else if (total > 120f) onStep(-1) },
    ) { _, dx -> total += dx }
}

/** ‹ Title › */
@Composable
private fun StepHead(title: String, onStep: (Int) -> Unit, onToday: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("‹", fontSize = 22.sp, color = Muted, modifier = Modifier.clickable { onStep(-1) }.padding(horizontal = 10.dp))
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
        if (onToday != null) Text("Today", fontSize = 12.sp, color = Primary, modifier = Modifier.clickable { onToday() }.padding(horizontal = 6.dp))
        Text("›", fontSize = 22.sp, color = Muted, modifier = Modifier.clickable { onStep(1) }.padding(horizontal = 10.dp))
    }
}

/** Month: a grid with dots, the picked day's list below, and "＋ Add on this day". */
@Composable
internal fun MonthView(
    month: YearMonth, onMonth: (YearMonth) -> Unit, picked: LocalDate, onPick: (LocalDate) -> Unit,
    tasks: List<TaskItem>, events: List<CalEvent>, birthdays: Map<LocalDate, List<String>>, colours: Map<String, Color>,
    onTask: (TaskItem) -> Unit, onEvent: (CalEvent) -> Unit, onAdd: (LocalDate) -> Unit,
) {
    val today = LocalDate.now()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        StepHead(month.format(MONTH), { onMonth(month.plusMonths(it.toLong())) }, if (month != YearMonth.from(today)) ({ onMonth(YearMonth.from(today)); onPick(today) }) else null)
        Column(Modifier.swipeStep(month) { onMonth(month.plusMonths(it.toLong())) }) {
            Row(Modifier.fillMaxWidth()) {
                listOf("M", "T", "W", "T", "F", "S", "S").forEach {
                    Text(it, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Muted, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                }
            }
            CalendarGrid.monthDays(month).chunked(7).forEach { week ->
                Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    week.forEach { d ->
                        val isToday = d == today
                        val other = d.month != month.month
                        val shape = RoundedCornerShape(10.dp)
                        Column(
                            Modifier.weight(1f).height(44.dp).clip(shape).background(if (isToday) Primary else Color.Transparent)
                                .then(if (d == picked && !isToday) Modifier.border(2.dp, Primary, shape) else Modifier)
                                .clickable { onPick(d); if (other) onMonth(YearMonth.from(d)) }.padding(top = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("${d.dayOfMonth}", fontSize = 13.sp, fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                                color = if (isToday) Bg else if (other) Outline else TextMain)
                            if (!other) Row(Modifier.padding(top = 5.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                CalendarGrid.dots(d, tasks, events, birthdays).forEach { dot ->
                                    Box(Modifier.size(5.dp).clip(CircleShape).background(if (isToday && dot == Dot.TASK) Bg else dotColour(dot)))
                                }
                            }
                        }
                    }
                }
            }
        }
        Row(Modifier.padding(top = 8.dp, bottom = 10.dp, start = 2.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(Dot.EVENT to "event", Dot.TASK to "task", Dot.BILL to "bill", Dot.BIRTHDAY to "birthday").forEach { (d, l) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(dotColour(d)))
                    Text(l, fontSize = 11.sp, color = Muted)
                }
            }
        }
        val items = CalendarGrid.day(picked, tasks, events, birthdays)
        ListCard {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(picked.format(DAY_HEAD), fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(if (items.size == 1) "1 item" else "${items.size} items", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Muted)
            }
            items.birthdays.forEach { DayLine("🎂", Warn, "$it's birthday") {} }
            items.allDay.forEach { e -> DayLine("all day", colours[e.calendarId] ?: Purple, e.title, e.location) { onEvent(e) } }
            items.timed.forEach { e -> DayLine(e.start.format(HM), colours[e.calendarId] ?: Purple, e.title, e.location) { onEvent(e) } }
            items.tasks.forEach { t ->
                DayLine(DayPlan.timeOf(t)?.start?.format(HM) ?: "task", if (Deck.isBill(t)) Amber else TextMain, DayPlan.bareText(TaskViews.plainText(t))) { onTask(t) }
            }
            HorizontalDivider(color = Outline)
            Text("＋ Add on this day", fontSize = 13.sp, color = Primary, modifier = Modifier.fillMaxWidth().clickable { onAdd(picked) }.padding(14.dp), textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun DayLine(time: String, bar: Color, title: String, place: String? = null, onClick: () -> Unit) {
    HorizontalDivider(color = Outline)
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(time, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, color = Muted, modifier = Modifier.width(52.dp))
        Box(Modifier.width(4.dp).height(18.dp).clip(RoundedCornerShape(4.dp)).background(bar))
        Text(title, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 10.dp).weight(1f, fill = false))
        if (place != null) Text(" · 📍 $place", fontSize = 12.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private const val HOUR_DP = 40

/** Week: seven columns of time blocks, the all-day row on top, a red now-line, untimed tasks as a count per day. */
@Composable
internal fun WeekView(
    monday: LocalDate, onWeek: (LocalDate) -> Unit, tasks: List<TaskItem>, events: List<CalEvent>, colours: Map<String, Color>,
    now: LocalDateTime, onTask: (TaskItem) -> Unit, onEvent: (CalEvent) -> Unit, onDay: (LocalDate) -> Unit,
) {
    val today = now.toLocalDate()
    val sunday = monday.plusDays(6)
    val title = if (monday.month == sunday.month) "${monday.dayOfMonth} – ${sunday.format(SHORT)}" else "${monday.format(SHORT)} – ${sunday.format(SHORT)}"
    val blocks = CalendarGrid.weekBlocks(monday, tasks, events)
    val allDay = CalendarGrid.weekAllDay(monday, events)
    val untimed = CalendarGrid.weekUntimed(monday, tasks)
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    LaunchedEffect(Unit) { scroll.scrollTo(with(density) { (CalendarGrid.WEEK_SCROLL_TO * HOUR_DP).dp.roundToPx() }) }
    Column(Modifier.fillMaxSize().swipeStep(monday) { onWeek(monday.plusWeeks(it.toLong())) }) {
        StepHead(title, { onWeek(monday.plusWeeks(it.toLong())) }, if (CalendarGrid.weekStart(today) != monday) ({ onWeek(CalendarGrid.weekStart(today)) }) else null)
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(30.dp))
            (0 until 7).forEach { i ->
                val d = monday.plusDays(i.toLong())
                Column(Modifier.weight(1f).clickable { onDay(d) }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("MTWTFSS"[i].toString(), fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Muted)
                    Text("${d.dayOfMonth}", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (d == today) Primary else TextMain)
                    Text(if (untimed[i] > 0) "${untimed[i]} task${if (untimed[i] > 1) "s" else ""}" else " ", fontSize = 9.sp, color = Muted)
                }
            }
        }
        if (allDay.any { it.isNotEmpty() }) Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Spacer(Modifier.width(30.dp))
            allDay.forEach { list ->
                Column(Modifier.weight(1f).padding(horizontal = 1.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    list.take(2).forEach { e ->
                        val c = colours[e.calendarId] ?: Purple
                        Text(e.title, fontSize = 9.5.sp, maxLines = 1, overflow = TextOverflow.Clip, color = c,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(5.dp)).background(c.copy(alpha = 0.18f)).clickable { onEvent(e) }.padding(horizontal = 3.dp, vertical = 2.dp))
                    }
                }
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).verticalScroll(scroll)) {
            val col = (maxWidth - 30.dp) / 7
            Box(Modifier.fillMaxWidth().height((24 * HOUR_DP).dp)) {
                (0 until 24).forEach { h ->
                    val y = (h * HOUR_DP).dp
                    HorizontalDivider(Modifier.padding(start = 30.dp).offset(y = y), color = Surface2)
                    Text("%02d".format(h), fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = Outline, modifier = Modifier.offset(y = y + 1.dp))
                }
                (0..7).forEach { i -> Box(Modifier.offset(x = 30.dp + col * i).width(1.dp).fillMaxSize().background(Surface2)) }
                blocks.forEach { b ->
                    val top = b.start.toSecondOfDay() / 60f / 60f * HOUR_DP
                    val len = (Duration.between(b.start, b.end).toMinutes().coerceAtLeast(20)) / 60f * HOUR_DP
                    val c = b.event?.let { colours[it.calendarId] ?: Purple } ?: if (b.task?.let { TaskViews.isWork(it) } == true) Amber else Primary
                    Text(
                        b.title, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = Bg, lineHeight = 12.sp, overflow = TextOverflow.Clip,
                        modifier = Modifier.offset(x = 30.dp + col * b.day + 1.dp, y = top.dp).width(col - 3.dp).height(len.dp - 2.dp)
                            .clip(RoundedCornerShape(6.dp)).background(c)
                            .clickable { b.event?.let(onEvent) ?: b.task?.let(onTask) }.padding(horizontal = 3.dp, vertical = 2.dp)
                    )
                }
                val di = java.time.temporal.ChronoUnit.DAYS.between(monday, today).toInt()
                if (di in 0..6) {
                    val y = now.toLocalTime().toSecondOfDay() / 3600f * HOUR_DP
                    Box(Modifier.offset(x = 30.dp + col * di, y = y.dp).width(col).height(2.dp).background(Warn))
                }
            }
        }
    }
}

/** Tapping an event: the app's own can be edited, moved or deleted (Events.md); Proton ones are read only. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EventSheet(
    e: CalEvent, calendar: String, colour: Color, own: Boolean, onDismiss: () -> Unit,
    onDelete: () -> Unit, onMove: (LocalDate) -> Unit, onSave: (title: String, place: String?, note: String?) -> Unit,
    proton: String? = null, onProton: (() -> Unit)? = null,
) {
    var editing by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf(e.title) }
    var place by remember { mutableStateOf(e.location.orEmpty()) }
    var note by remember { mutableStateOf(e.note.orEmpty()) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Surface2) {
        Column(Modifier.padding(start = 18.dp, end = 18.dp, bottom = 28.dp)) {
            Text("○ EVENT · ${calendar.uppercase(Locale.UK)}", fontSize = 11.sp, fontFamily = FontFamily.Monospace, letterSpacing = 0.8.sp, color = colour)
            if (editing) {
                MiniLabel("Title"); CompactField(title, { title = it }, "Event")
            } else Text(e.title, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
            Text(
                e.start.toLocalDate().format(DAY_HEAD) + if (e.allDay) " · all day" else " · ${e.start.format(HM)} – ${e.end.format(HM)}",
                fontSize = 14.sp, color = Muted, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
            )
            if (editing) {
                MiniLabel("Location"); CompactField(place, { place = it }, "Where")
                Spacer(Modifier.height(6.dp))
                MiniLabel("Notes"); CompactField(note, { note = it }, "Anything to remember")
            } else {
                e.location?.let { InfoBox("Location", "📍 $it") }
                e.note?.let { InfoBox("Notes", it) }
                // events-to-proton-v1: where its invite is, or a button to send one.
                if (own) Row(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(12.dp)).background(Surface1)
                        .border(1.dp, Outline, RoundedCornerShape(12.dp))
                        .then(if (proton == null && onProton != null) Modifier.clickable { onProton() } else Modifier)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Proton Calendar", fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Text(
                        proton ?: "Add to Proton", fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                        color = when { proton == null -> Primary; proton.endsWith("✓") -> Green; else -> Amber }
                    )
                }
            }
            if (own) Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (editing) {
                    SheetButton("Cancel", false, Modifier.weight(1f)) { editing = false }
                    SheetButton("Save", true, Modifier.weight(1f)) { onSave(title.trim().ifEmpty { e.title }, place.trim().ifEmpty { null }, note.trim().ifEmpty { null }) }
                } else {
                    SheetButton("Delete", false, Modifier.weight(1f), Warn) { onDelete() }
                    SheetButton("Move", false, Modifier.weight(1f)) { moving = true }
                    SheetButton("Edit", true, Modifier.weight(1f)) { editing = true }
                }
            } else Text("From your Proton calendar, so it's read only here.", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 10.dp))
        }
    }
    if (moving) PickDateDialog(e.start.toLocalDate(), onPick = { d -> moving = false; if (d != null) onMove(d) }, onDismiss = { moving = false })
}

@Composable
private fun InfoBox(label: String, text: String) {
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(12.dp)).background(Surface1).border(1.dp, Outline, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 9.dp)) {
        MiniLabel(label)
        Text(text, fontSize = 14.sp, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun SheetButton(label: String, primary: Boolean, modifier: Modifier, colour: Color = TextMain, onClick: () -> Unit) {
    val shape = RoundedCornerShape(999.dp)
    Text(
        label, fontSize = 14.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, color = if (primary) Bg else colour,
        modifier = modifier.clip(shape).background(if (primary) Primary else Color.Transparent).border(1.dp, if (primary) Primary else Outline, shape)
            .clickable(onClick = onClick).padding(vertical = 12.dp)
    )
}

/** "＋ Add on this day": Capture opens on Task with this date picked. */
internal object CapturePreset {
    var date by mutableStateOf<LocalDate?>(null)
}
