package uk.tomlowndes.capture.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uk.tomlowndes.capture.Bill
import uk.tomlowndes.capture.CalEvent
import uk.tomlowndes.capture.DayEvents
import uk.tomlowndes.capture.DayPlan
import uk.tomlowndes.capture.Deck
import uk.tomlowndes.capture.Mood
import uk.tomlowndes.capture.Priority
import uk.tomlowndes.capture.TaskItem
import uk.tomlowndes.capture.TaskRules
import uk.tomlowndes.capture.TaskViews
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** One thing waiting at the top of the Deck (tidy-v1 "Now" line): plan, shut down or mood. */
sealed class NowItem {
    data class Line(val text: String, val action: String, val onClick: () -> Unit) : NowItem()
    data class Mood(val onPick: (Int) -> Unit) : NowItem()
}

/** The slim Now line: the first waiting item, with "+N" to step through the rest. */
@Composable
fun NowBar(items: List<NowItem>, index: Int, onNext: () -> Unit) {
    if (items.isEmpty()) return
    val item = items[index % items.size]
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().padding(bottom = 14.dp).clip(shape).background(PrimaryTint).border(1.dp, NextBorder, shape)
            .then(if (item is NowItem.Line) Modifier.hand().clickable(onClick = item.onClick) else Modifier)
            .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        when (item) {
            is NowItem.Line -> {
                Text(item.text, fontSize = 14.sp, color = TextMain, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(item.action, fontSize = 13.sp, color = Primary, fontWeight = FontWeight.SemiBold)
            }
            is NowItem.Mood -> {
                Text("🌙 How was today?", fontSize = 14.sp, color = TextMain, modifier = Modifier.weight(1f))
                Mood.FACES.forEachIndexed { i, f ->
                    Text(f, fontSize = 20.sp, modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Surface2).hand()
                        .clickable { item.onPick(i + 1) }.padding(horizontal = 8.dp, vertical = 2.dp))
                }
            }
        }
        if (items.size > 1) Text(
            "+${items.size - 1}", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Muted,
            modifier = Modifier.clip(CircleShape).background(Surface2).hand().clickable(onClick = onNext).padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

/**
 * Plan today (morning: pick up to 3, they move to today) and Shut down (evening: each task left goes to Tomorrow,
 * a date, or is dropped). The same as the phone's Plan screen.
 */
@Composable
fun PlanDialog(
    all: List<TaskItem>, events: List<CalEvent>, evening: Boolean,
    onPlan: (List<TaskItem>) -> Unit, onMove: (TaskItem, LocalDate) -> Unit, onDrop: (TaskItem) -> Unit,
    onShutDown: () -> Unit, onDismiss: () -> Unit,
) {
    val today = LocalDate.now()
    val now = LocalDateTime.now()
    val picked = remember { mutableStateListOf<String>().apply { addAll(all.filter { it.due == today && !Deck.isBill(it) }.take(DayPlan.PICK_MAX).map { it.raw }) } }
    val sorted = remember { mutableStateListOf<String>() }
    var dating by remember { mutableStateOf<TaskItem?>(null) }
    DialogCard(if (evening) "Shut down" else "Plan today", onDismiss) {
        if (!evening) {
            val list = DayPlan.candidates(all, today)
            val chosen = list.filter { it.raw in picked }
            Sub("Top ${DayPlan.PICK_MAX} · ${TaskRules.minutesLabel(DayPlan.planned(chosen).coerceAtLeast(0))} planned · ${TaskRules.minutesLabel(DayPlan.freeMinutes(events, now).coerceAtLeast(0))} free", Modifier.padding(bottom = 10.dp))
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                list.forEach { t ->
                    val on = t.raw in picked
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 6.dp).clip(RoundedCornerShape(10.dp)).background(if (on) PrimaryTint else Surface1)
                            .hand().clickable { if (on) picked.remove(t.raw) else if (picked.size < DayPlan.PICK_MAX) picked.add(t.raw) }
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(Modifier.size(17.dp).clip(RoundedCornerShape(6.dp)).background(if (on) Primary else Color.Transparent).border(1.5.dp, if (on) Primary else Muted, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
                            if (on) Text("✓", fontSize = 11.sp, color = OnPrimary, fontWeight = FontWeight.Bold)
                        }
                        PriorityFlag(Priority.of(t))
                        Text(TaskViews.plainText(t), fontSize = 13.5.sp, color = TextMain, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        t.due?.let { Text(dayChip(it), fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = if (it.isBefore(today)) Warn else Muted) }
                        Text(TaskRules.minutesLabel(DayPlan.minutes(t)), fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Muted)
                    }
                }
            }
            Sub("Picked tasks move to today. Shut down shows from 17:00.", Modifier.padding(top = 6.dp, bottom = 12.dp))
            DialogButtons(onDismiss) { PrimaryButton("Plan my day", enabled = chosen.isNotEmpty()) { onPlan(chosen) } }
        } else {
            val left = DayPlan.leftToday(all, today)
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                if (left.isEmpty()) Sub("Nothing left for today. Nice one.", Modifier.padding(vertical = 10.dp))
                left.forEach { t ->
                    val done = t.raw in sorted
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 6.dp).clip(RoundedCornerShape(10.dp)).background(Surface1).padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(TaskViews.plainText(t), fontSize = 13.5.sp, color = if (done) Muted else TextMain, modifier = Modifier.weight(1f), maxLines = 2,
                            overflow = TextOverflow.Ellipsis, textDecoration = if (done) TextDecoration.LineThrough else null)
                        if (!done) {
                            Chip("Tomorrow", small = true) { sorted.add(t.raw); onMove(t, today.plusDays(1)) }
                            Chip("Date", small = true) { dating = t }
                            Chip("✕", small = true, color = Warn) { sorted.add(t.raw); onDrop(t) }
                        }
                    }
                }
            }
            Box(Modifier.height(12.dp))
            DialogButtons(onDismiss) { PrimaryButton("Done for today") { onShutDown() } }
        }
    }
    dating?.let { t -> PickDateDialog(today.plusDays(1), onPick = { d -> dating = null; if (d != null) { sorted.add(t.raw); onMove(t, d) } }, onDismiss = { dating = null }) }
}

/**
 * Today (tidy-v1, past-events-v1): the day's calendar events and bills due, one quiet row each. Finished events fold
 * into one "✓ 2 earlier · Gym, Standup" row that [onTogglePast] opens; the event on [now] is highlighted with NOW.
 */
@Composable
fun TodayRows(
    events: List<CalEvent>, bills: List<Bill>, onBills: () -> Unit,
    now: LocalDateTime = LocalDateTime.now(), showPast: Boolean = false, onTogglePast: () -> Unit = {}
) {
    val today = now.toLocalDate()
    val time = DateTimeFormatter.ofPattern("HH:mm")
    val day = DayEvents.of(events, now)
    val due = bills.filter { it.task.due?.isAfter(today) == false }
    if (day.allDay.isEmpty() && day.earlier.isEmpty() && day.rest.isEmpty() && due.isEmpty()) {
        Text("Nothing in the calendar today.", fontSize = 13.sp, color = Muted, modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp))
        return
    }
    @Composable
    fun EventRow(e: CalEvent, past: Boolean = false) {
        val live = e.isNow(now)
        Box(Modifier.fillMaxWidth().height(1.dp).background(Outline))
        Row(
            Modifier.fillMaxWidth().background(if (live) PrimaryTint else Color.Transparent).padding(horizontal = 18.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (past) Faint else if (live) Primary else LinkBlue))
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(e.title, fontSize = 13.5.sp, color = if (past) Faint else TextMain, modifier = Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (live) Text(
                    "NOW", fontSize = 9.5.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = OnPrimary,
                    modifier = Modifier.padding(start = 6.dp).clip(CircleShape).background(Primary).padding(horizontal = 6.dp, vertical = 1.dp)
                )
            }
            Text(
                if (e.allDay) "all day" else "${e.start.format(time)} – ${e.end.format(time)}", fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                color = if (past) Faint else if (live) Primary else Muted
            )
        }
    }
    day.allDay.forEach { EventRow(it) }
    day.foldLabel?.let { label ->
        Box(Modifier.fillMaxWidth().height(1.dp).background(Outline))
        Row(
            Modifier.fillMaxWidth().hand().clickable(onClick = onTogglePast).padding(horizontal = 18.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("✓ $label", fontSize = 12.5.sp, color = Muted, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (showPast) "hide ‹" else "show ›", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Muted)
        }
        if (showPast) day.earlier.forEach { EventRow(it, past = true) }
    }
    day.rest.forEach { EventRow(it) }
    due.forEach { b ->
        Box(Modifier.fillMaxWidth().height(1.dp).background(Outline))
        Row(Modifier.fillMaxWidth().hand().clickable(onClick = onBills).padding(horizontal = 18.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("💷", fontSize = 13.sp)
            Text(b.name + (b.amount?.let { " " + TaskViews.money(it) } ?: ""), fontSize = 13.5.sp, color = TextMain, modifier = Modifier.weight(1f), maxLines = 1)
            Text(if (b.task.due == today) "due today" else "overdue", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = if (b.task.due == today) Muted else Warn)
        }
    }
}
