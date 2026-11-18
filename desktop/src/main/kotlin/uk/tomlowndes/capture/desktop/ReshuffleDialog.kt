package uk.tomlowndes.capture.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uk.tomlowndes.capture.Reshuffle
import uk.tomlowndes.capture.TaskItem
import uk.tomlowndes.capture.TaskViews
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Energy reshuffle (energy-reshuffle-v1) as a dialog: the same dial, week strip and moves as the phone sheet. */
@Composable
fun ReshuffleDialog(tasks: List<TaskItem>, usual: Int, onMove: (List<Reshuffle.Move>) -> Unit, onDismiss: () -> Unit) {
    val today = LocalDate.now()
    var level by remember { mutableStateOf(Reshuffle.Level.HALF) }
    var kept by remember { mutableStateOf(emptySet<String>()) }
    val plan = remember(level, kept, tasks) { Reshuffle.plan(tasks, today, level, usual, kept) }
    val day = DateTimeFormatter.ofPattern("EEE", Locale.UK)
    DialogCard("How much have you got today?", onDismiss) {
        Text("${plan.load.getValue(today)} of ${plan.today} actions today · ${level.label}", fontSize = 14.sp, color = Amber, fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Reshuffle.Level.values().forEach { l ->
                val on = l == level
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(if (on) Amber else Color.Transparent)
                        .border(1.dp, if (on) Amber else Outline, RoundedCornerShape(12.dp)).hand().clickable { level = l }.padding(vertical = 7.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(l.label, fontSize = 13.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, color = if (on) Color(0xFF1B1305) else TextMain)
                    Text(l.fits(usual).toString(), fontSize = 10.5.sp, color = if (on) Color(0xFF4A3912) else Muted)
                }
            }
        }
        SmallLabel("THIS WEEK, AFTER THE MOVE")
        Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            plan.load.keys.sorted().forEach { d ->
                val diff = plan.load.getValue(d) - plan.before.getValue(d)
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(Surface1)
                        .border(1.dp, if (d == today) Amber else Outline, RoundedCornerShape(10.dp)).padding(vertical = 5.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(d.format(day), fontSize = 11.sp, color = Muted)
                    Text("${plan.load.getValue(d)}", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextMain)
                    Text(if (diff == 0) " " else if (diff > 0) "+$diff" else "−${-diff}", fontSize = 10.5.sp, color = if (d == today) Amber else TaskGreen)
                }
            }
        }
        val pinned = plan.stay.filter { it.raw in kept }
        SmallLabel(if (plan.moves.isEmpty() && pinned.isEmpty()) "NOTHING TO MOVE" else "MOVING ${plan.moves.size} · CLICK ONE TO KEEP IT TODAY")
        Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()).padding(top = 4.dp)) {
            plan.moves.forEach { m -> MoveLine(TaskViews.plainText(m.task), m.to.format(day), true) { kept = kept + m.task.raw } }
            pinned.forEach { t -> MoveLine(TaskViews.plainText(t), "today", false) { kept = kept - t.raw } }
        }
        val staying = plan.stay.filter { it.raw !in kept }
        if (staying.isNotEmpty()) Text(
            "Staying today: " + staying.take(3).joinToString(", ") { TaskViews.plainText(it) } + (if (staying.size > 3) " and ${staying.size - 3} more" else "") +
                ". High priority, bills, timed and repeating tasks never move.",
            fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 8.dp)
        )
        Box(Modifier.padding(top = 14.dp)) {
            DialogButtons(onDismiss) {
                val n = plan.moves.size
                PrimaryButton(if (n == 0) "Nothing to move" else "Move $n task" + if (n == 1) "" else "s", enabled = n > 0) { onMove(plan.moves) }
            }
        }
    }
}

@Composable
private fun MoveLine(text: String, to: String, on: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().hand().clickable(onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(16.dp).clip(RoundedCornerShape(4.dp)).background(if (on) Primary else Color.Transparent)
                .border(2.dp, if (on) Primary else Muted, RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center
        ) { if (on) Text("✓", fontSize = 11.sp, color = OnPrimary, fontWeight = FontWeight.Bold) }
        Text(text, fontSize = 13.5.sp, color = if (on) TextMain else Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
        Text(to, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = if (on) Primary else Muted)
    }
}
