package uk.tomlowndes.capture

import android.app.TimePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val HM = DateTimeFormatter.ofPattern("HH:mm")
private const val DP_PER_MIN = 1.1f

/** The two views inside Upcoming: the 7-day list, or today as a timeline. */
@Composable
internal fun UpcomingModeSwitch(day: Boolean, onPick: (Boolean) -> Unit) {
    Row(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        AppChip(!day, { onPick(false) }, { Text("7 days") })
        AppChip(day, { onPick(true) }, { Text("Day view") })
    }
}

/**
 * Visual day (build-queue T2): today as coloured blocks with an emoji each and a red now-line. Tasks due today without
 * a time sit above; tap one to give it a time (it's written as `11:00 - 11:45` at the start of the task). Tap a timed
 * task to move it or clear its time.
 */
@Composable
internal fun DayTimeline(
    tasks: List<TaskItem>, events: List<CalEvent>, colours: Map<String, Color>, now: LocalDateTime,
    onTime: (TaskItem, LocalTime?) -> Unit,
) {
    val ctx = LocalContext.current
    val today = now.toLocalDate()
    val blocks = DayPlan.blocks(tasks, events, today)
    val untimed = DayPlan.untimed(tasks, today)
    val first = DayPlan.FIRST_HOUR
    val hours = DayPlan.LAST_HOUR - first
    val scroll = rememberScrollState()
    LaunchedEffect(Unit) { scroll.scrollTo(((now.hour - first - 1).coerceAtLeast(0) * 60 * DP_PER_MIN * ctx.resources.displayMetrics.density).toInt()) }

    fun pick(t: TaskItem, current: LocalTime?) {
        val start = current ?: now.toLocalTime().let { LocalTime.of(it.hour, 0).plusMinutes(((it.minute / 15) + 1) * 15L) }
        val dialog = TimePickerDialog(ctx, { _, h, m -> onTime(t, LocalTime.of(h, m)) }, start.hour, start.minute, true)
        if (current != null) dialog.setButton(android.content.DialogInterface.BUTTON_NEUTRAL, "No time") { _, _ -> onTime(t, null) }
        dialog.show()
    }

    Column(Modifier.fillMaxSize()) {
        if (untimed.isNotEmpty()) {
            Text("NOT TIMED YET · TAP TO GIVE IT A TIME", fontSize = 11.sp, fontFamily = FontFamily.Monospace, letterSpacing = 0.6.sp, color = Muted)
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                untimed.forEach { t ->
                    Text(
                        "⏰ " + TaskViews.plainText(t), fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(170.dp).clip(RoundedCornerShape(10.dp)).background(Surface1)
                            .border(1.dp, if (TaskViews.isWork(t)) WorkEdge else PersonalEdge, RoundedCornerShape(10.dp))
                            .clickable { pick(t, null) }.padding(horizontal = 10.dp, vertical = 7.dp)
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f).verticalScroll(scroll)) {
            Box(Modifier.fillMaxWidth().height((hours * 60 * DP_PER_MIN).dp + 16.dp)) {
                (0..hours).forEach { i ->
                    val y = (i * 60 * DP_PER_MIN).dp
                    Text("%02d:00".format(first + i), fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Outline, modifier = Modifier.offset(y = y - 6.dp))
                    HorizontalDivider(Modifier.padding(start = 44.dp).offset(y = y), color = Surface2)
                }
                blocks.forEach { b ->
                    val startMin = Duration.between(LocalTime.of(first, 0), b.start.coerceAtLeast(LocalTime.of(first, 0))).toMinutes()
                    val len = Duration.between(b.start, b.end).toMinutes().coerceAtLeast(20)
                    if (b.start.hour >= DayPlan.LAST_HOUR) return@forEach
                    val (bg, fg, emoji, title, done) = when (b) {
                        is DayBlock.Event -> {
                            val c = colours[b.event.calendarId] ?: Purple
                            Block(c.copy(alpha = 0.18f), c, "📅", b.event.title, false)
                        }
                        is DayBlock.Task -> {
                            val t = b.timed.task
                            val c = if (TaskViews.isWork(t)) Amber else Primary
                            Block(c.copy(alpha = 0.16f), c, emojiOf(t), DayPlan.bareText(TaskViews.plainText(t)), false)
                        }
                    }
                    val past = !b.end.isAfter(now.toLocalTime())
                    Row(
                        Modifier.padding(start = 48.dp).offset(y = (startMin * DP_PER_MIN).dp).fillMaxWidth()
                            .height((len * DP_PER_MIN).dp - 3.dp).clip(RoundedCornerShape(12.dp)).background(bg)
                            .then(if (b is DayBlock.Task) Modifier.clickable { pick(b.timed.task, b.start) } else Modifier)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(emoji, fontSize = 16.sp)
                        Column {
                            Text(title, fontSize = 13.sp, color = if (past) Muted else fg, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                textDecoration = if (done) TextDecoration.LineThrough else null)
                            Text("${b.start.format(HM)}–${b.end.format(HM)}" + if (past) " · done" else if (!b.start.isAfter(now.toLocalTime())) " · ${Duration.between(now.toLocalTime(), b.end).toMinutes()} min left" else "",
                                fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Muted)
                        }
                    }
                }
                val nowMin = Duration.between(LocalTime.of(first, 0), now.toLocalTime()).toMinutes()
                if (nowMin in 0..(hours * 60L)) {
                    Box(Modifier.padding(start = 44.dp).offset(y = (nowMin * DP_PER_MIN).dp).fillMaxWidth().height(2.dp).background(Warn))
                    Box(Modifier.padding(start = 40.dp).offset(y = (nowMin * DP_PER_MIN).dp - 3.dp).width(8.dp).height(8.dp).clip(CircleShape).background(Warn))
                }
            }
        }
    }
}

private data class Block(val bg: Color, val fg: Color, val emoji: String, val title: String, val done: Boolean)

/** The task's own leading emoji, or one from its tag. */
private fun emojiOf(t: TaskItem): String {
    val text = DayPlan.bareText(TaskViews.plainText(t))
    val cp = if (text.isEmpty()) 0 else text.codePointAt(0)
    if (!Character.isLetterOrDigit(cp) && cp >= 0x2000) return String(Character.toChars(cp))
    return when {
        TaskViews.tags(t).any { it == "#jobs" } -> "💼"
        TaskViews.isWork(t) -> "🖥️"
        else -> "•"
    }
}

/** Bills this month (build-queue O5): a small card at the top of Upcoming, only when the month has #bill tasks. */
@Composable
internal fun BillsMonthCard(m: MonthBills, open: Boolean, onToggle: () -> Unit, onPaid: (Bill) -> Unit) {
    val month = java.time.LocalDate.now().month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.UK).uppercase(java.util.Locale.UK)
    ListCard(Modifier.padding(bottom = 10.dp).clickable(onClick = onToggle)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
            Text("BILLS · $month", fontSize = 11.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp, color = Muted)
            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 2.dp)) {
                Text(TaskViews.money(m.left), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Amber)
                Text(" left to pay", fontSize = 12.sp, color = Muted, modifier = Modifier.weight(1f).padding(bottom = 3.dp))
                Text("of ${TaskViews.money(m.left + m.paidTotal)}", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Muted, modifier = Modifier.padding(bottom = 3.dp))
            }
            val total = (m.left + m.paidTotal).toFloat().coerceAtLeast(0.01f)
            val paidF = (m.paidTotal.toFloat() / total).coerceIn(0f, 1f)
            val weekF = (m.dueThisWeek.toFloat() / total).coerceIn(0f, 1f - paidF)
            Row(Modifier.fillMaxWidth().padding(top = 8.dp).height(6.dp).clip(CircleShape).background(Surface2)) {
                if (paidF > 0f) Box(Modifier.weight(paidF).height(6.dp).background(Green))
                if (weekF > 0f) Box(Modifier.weight(weekF).height(6.dp).background(Amber))
                if (1f - paidF - weekF > 0.001f) Box(Modifier.weight(1f - paidF - weekF))
            }
            Row(Modifier.fillMaxWidth().padding(top = 5.dp)) {
                Text("${TaskViews.money(m.paidTotal)} paid", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Green, modifier = Modifier.weight(1f))
                Text("${TaskViews.money(m.dueThisWeek)} due this week ${if (open) "▴" else "▾"}", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Muted)
            }
        }
        if (open) m.all.forEach { (b, paid) ->
            HorizontalDivider(color = Outline)
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(b.task.due?.let { dayChip(it) } ?: "", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Muted, modifier = Modifier.width(54.dp))
                Text(b.name, fontSize = 13.5.sp, color = if (paid) Muted else TextMain, modifier = Modifier.weight(1f),
                    textDecoration = if (paid) TextDecoration.LineThrough else null, maxLines = 1, overflow = TextOverflow.Ellipsis)
                b.amount?.let { Text(TaskViews.money(it), fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = if (paid) Muted else Amber,
                    textDecoration = if (paid) TextDecoration.LineThrough else null) }
                if (!paid) TickBox(done = false, onClick = { onPaid(b) })
            }
        }
    }
}
