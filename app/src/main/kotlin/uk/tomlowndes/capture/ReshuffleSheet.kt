package uk.tomlowndes.capture

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Energy reshuffle (energy-reshuffle-v1): a dial for how much you've got today, the week after the move and the tasks
 * that would move. Untick a row to keep it today. Nothing is written until Move.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReshuffleSheet(
    tasks: List<TaskItem>, usual: Int, start: Reshuffle.Level,
    onMove: (Reshuffle.Level, List<Reshuffle.Move>) -> Unit, onDismiss: () -> Unit,
) {
    val today = LocalDate.now()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var level by remember { mutableStateOf(start) }
    // Rows unticked to stay today, by their raw line.
    val kept = remember { mutableStateListOf<String>() }
    val plan = remember(level, kept.toList(), tasks) { Reshuffle.plan(tasks, today, level, usual, kept.toSet()) }
    val keptHere = plan.stay.filter { it.raw in kept }
    val shownToday = plan.today
    val after = plan.load.getValue(today)
    val day = DateTimeFormatter.ofPattern("EEE", Locale.UK)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Surface2) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 18.dp, end = 18.dp, bottom = 24.dp)) {
            Text("How much have you got today?", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(
                "Sets how many actions fit today. The rest move to lighter days.",
                fontSize = 12.5.sp, color = Muted, modifier = Modifier.padding(top = 2.dp, bottom = 12.dp)
            )
            // The dial: an arc filled to the level, with today's count after the move.
            Box(Modifier.fillMaxWidth().padding(bottom = 10.dp), contentAlignment = Alignment.BottomCenter) {
                Canvas(Modifier.size(200.dp, 108.dp)) {
                    val w = 14.dp.toPx()
                    val r = size.width / 2 - w
                    val tl = Offset(size.width / 2 - r, size.height - r - w / 2)
                    drawArc(Outline, 180f, 180f, false, tl, Size(r * 2, r * 2), style = Stroke(w, cap = StrokeCap.Round))
                    val sweep = (180f * level.share.toFloat()).coerceAtLeast(1f)
                    drawArc(Amber, 180f, sweep, false, tl, Size(r * 2, r * 2), style = Stroke(w, cap = StrokeCap.Round))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("$after of $shownToday", fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "ACTIONS TODAY · ${level.label.uppercase()}", fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                        color = Muted, letterSpacing = 0.8.sp
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Reshuffle.Level.values().forEach { l ->
                    val on = l == level
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(if (on) Amber else Color.Transparent)
                            .border(1.dp, if (on) Amber else Outline, RoundedCornerShape(12.dp))
                            .clickable { level = l }.padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(l.label, fontSize = 13.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, color = if (on) Color(0xFF1B1305) else TextMain)
                        Text(l.fits(usual).toString(), fontSize = 10.5.sp, color = if (on) Color(0xFF4A3912) else Muted)
                    }
                }
            }

            Label("This week, after the move")
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                plan.load.keys.sorted().forEach { d ->
                    val n = plan.load.getValue(d)
                    val diff = n - plan.before.getValue(d)
                    val isToday = d == today
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(Surface1)
                            .border(1.dp, if (isToday) Amber else Outline, RoundedCornerShape(10.dp)).padding(top = 6.dp, bottom = 5.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(d.format(day), fontSize = 11.sp, color = Muted)
                        Text("$n", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text(
                            if (diff == 0) " " else if (diff > 0) "+$diff" else "−${-diff}",
                            fontSize = 10.5.sp, color = if (isToday) Amber else Green
                        )
                    }
                }
            }

            if (plan.moves.isEmpty() && keptHere.isEmpty()) Text(
                if (shownToday == 0) "Nothing on today." else "Everything fits. Nothing to move.",
                fontSize = 13.sp, color = Muted, modifier = Modifier.padding(vertical = 8.dp)
            ) else Label("Moving ${plan.moves.size}")
            plan.moves.forEachIndexed { i, m ->
                if (i > 0) HorizontalDivider(color = Outline)
                MoveRow(TaskViews.plainText(m.task), m.to.format(day), on = true) { kept.add(m.task.raw) }
            }
            keptHere.forEach { t ->
                HorizontalDivider(color = Outline)
                MoveRow(TaskViews.plainText(t), "today", on = false) { kept.remove(t.raw) }
            }
            val staying = plan.stay.filter { it.raw !in kept }.map { TaskViews.plainText(it) }
            if (staying.isNotEmpty()) Text(
                "Staying today: " + staying.take(3).joinToString(", ") + (if (staying.size > 3) " and ${staying.size - 3} more" else "") +
                    ". High priority, bills, timed and repeating tasks never move, nor do events.",
                fontSize = 12.sp, color = Muted, lineHeight = 17.sp, modifier = Modifier.padding(top = 10.dp)
            )
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SheetButton("Cancel", primary = false, Modifier.weight(1f), onClick = onDismiss)
                val n = plan.moves.size
                SheetButton(if (n == 0) "Done" else "Move $n task" + if (n == 1) "" else "s", primary = true, Modifier.weight(1f)) {
                    onMove(level, plan.moves)
                }
            }
        }
    }
}

@Composable
private fun Label(text: String) = Text(
    text.uppercase(), fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, color = Muted, letterSpacing = 1.1.sp,
    modifier = Modifier.padding(top = 4.dp, bottom = 6.dp)
)

@Composable
private fun MoveRow(text: String, to: String, on: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp, horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(18.dp).clip(RoundedCornerShape(5.dp)).background(if (on) Primary else Color.Transparent)
                .border(2.dp, if (on) Primary else Muted, RoundedCornerShape(5.dp)),
            contentAlignment = Alignment.Center
        ) { if (on) Text("✓", fontSize = 12.sp, color = OnPrimary, fontWeight = FontWeight.Bold) }
        Text(
            text, fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (on) TextMain else Muted,
            modifier = Modifier.weight(1f).padding(start = 8.dp, end = 8.dp)
        )
        Text(
            to, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = if (on) Primary else Muted,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (on) PrimaryTint else Color.Transparent).padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

@Composable
private fun SheetButton(text: String, primary: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(999.dp)).background(if (primary) Primary else Color.Transparent)
            .border(1.dp, if (primary) Primary else Outline, RoundedCornerShape(999.dp)).clickable(onClick = onClick).padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (primary) OnPrimary else TextMain) }
}
