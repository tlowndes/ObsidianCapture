package uk.tomlowndes.capture

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Reschedule sheet (tasks-swipe-v1): four quick picks with their real dates and how busy each day is, then a month with dots
 * (calendar events in purple, tasks in grey). A quick pick moves the task straight away; a day in the month needs Move.
 * [events] only reach as far as the calendar cache (the next 7 days), so later days show tasks only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RescheduleSheet(
    t: TaskItem, tasks: List<TaskItem>, events: List<CalEvent>,
    onMove: (LocalDate?) -> Unit, onDismiss: () -> Unit,
) {
    val today = LocalDate.now()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var picked by remember(t.raw) { mutableStateOf(t.due?.takeIf { !it.isBefore(today) } ?: today) }
    var month by remember(t.raw) { mutableStateOf(YearMonth.from(picked)) }
    val calKnown = today.plusDays((Upcoming.DAYS - 1).toLong())
    fun eventsOn(d: LocalDate) = if (d.isAfter(calKnown)) null else events.count { it.on(d) }
    fun tasksOn(d: LocalDate) = tasks.count { it.due == d && it.raw != t.raw }
    val dayFmt = DateTimeFormatter.ofPattern("EEE d", Locale.UK)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Surface1) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 18.dp, end = 18.dp, bottom = 26.dp)) {
            Text("MOVE", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Muted)
            Text(
                TaskViews.plainText(t), fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp, bottom = 14.dp)
            )
            Upcoming.presets(today).chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { (label, d) ->
                        val on = t.due == d
                        val ev = eventsOn(d)
                        val tk = tasksOn(d)
                        val busy = listOfNotNull(
                            ev?.takeIf { it > 0 }?.let { "$it event" + if (it == 1) "" else "s" },
                            tk.takeIf { it > 0 }?.let { "$it task" + if (it == 1) "" else "s" },
                        ).joinToString(" · ").ifEmpty { if (ev != null) "clear" else "" }
                        Column(
                            Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(if (on) PrimaryTint else Surface2)
                                .border(1.dp, if (on) Primary else Outline, RoundedCornerShape(14.dp))
                                .clickable { onMove(d) }.padding(horizontal = 12.dp, vertical = 11.dp)
                        ) {
                            Text(label, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (on) Primary else TextMain)
                            Text(
                                listOf(d.format(dayFmt), busy).filter { it.isNotEmpty() }.joinToString(" · "),
                                fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = if ((ev ?: 0) >= 3) Amber else Muted,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp)
                            )
                        }
                    }
                }
            }

            Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(month.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.UK)), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                val canBack = month.isAfter(YearMonth.from(today))
                Text("‹", fontSize = 18.sp, color = if (canBack) Muted else Outline,
                    modifier = Modifier.clip(CircleShape).clickable(enabled = canBack) { month = month.minusMonths(1) }.padding(horizontal = 12.dp))
                Text("›", fontSize = 18.sp, color = Muted,
                    modifier = Modifier.clip(CircleShape).clickable { month = month.plusMonths(1) }.padding(horizontal = 12.dp))
            }
            Row(Modifier.fillMaxWidth()) {
                listOf("M", "T", "W", "T", "F", "S", "S").forEach {
                    Text(it, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Muted, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                }
            }
            val first = month.atDay(1)
            val lead = first.dayOfWeek.value - 1
            val cells = (0 until lead).map { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }
            cells.chunked(7).forEach { week ->
                Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                    (0 until 7).forEach { i ->
                        val d = week.getOrNull(i)
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            if (d != null) DayCell(d, today, d == picked, eventsOn(d) ?: 0, tasksOn(d)) { picked = d }
                        }
                    }
                }
            }

            Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("No date", fontSize = 13.sp, color = Muted,
                    modifier = Modifier.clip(CircleShape).clickable { onMove(null) }.padding(horizontal = 4.dp, vertical = 8.dp))
                Spacer(Modifier.weight(1f))
                Text(
                    "Move", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = OnPrimary,
                    modifier = Modifier.clip(CircleShape).background(Primary).clickable { onMove(picked) }.padding(horizontal = 20.dp, vertical = 9.dp)
                )
            }
        }
    }
}

@Composable
private fun DayCell(d: LocalDate, today: LocalDate, selected: Boolean, events: Int, tasks: Int, onClick: () -> Unit) {
    val past = d.isBefore(today)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 2.dp).clip(RoundedCornerShape(10.dp))
            .background(if (selected) Primary else Color.Transparent)
            .clickable(enabled = !past, onClick = onClick).padding(top = 6.dp, bottom = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            d.dayOfMonth.toString(), fontSize = 13.sp,
            fontWeight = if (selected || d == today) FontWeight.Bold else FontWeight.Normal,
            color = when { selected -> OnPrimary; past -> Outline; d == today -> Primary; else -> TextMain }
        )
        Row(Modifier.height(6.dp).padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            repeat(minOf(events, 2)) { Dot(if (selected) OnPrimary else Purple) }
            repeat(minOf(tasks, 2 - minOf(events, 2).coerceAtMost(1))) { Dot(if (selected) OnPrimary else Muted) }
        }
    }
}

@Composable
internal fun Dot(c: Color) = Box(Modifier.size(4.dp).clip(CircleShape).background(c))
