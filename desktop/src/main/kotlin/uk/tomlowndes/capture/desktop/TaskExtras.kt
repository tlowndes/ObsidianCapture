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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uk.tomlowndes.capture.Breakdown
import uk.tomlowndes.capture.Deck
import uk.tomlowndes.capture.Priority
import uk.tomlowndes.capture.Step
import uk.tomlowndes.capture.TaskItem
import uk.tomlowndes.capture.TaskRules
import uk.tomlowndes.capture.TaskViews
import uk.tomlowndes.capture.Triage
import uk.tomlowndes.capture.Upcoming
import java.time.LocalDate
import java.time.LocalTime

/** Flag colours (priority-flags-v1), the same as the phone. */
fun priorityColours(p: Priority): Pair<Color, Color> = when (p) {
    Priority.HIGHEST -> Color(0xFFFF6B6B) to Color(0xFF3A1D22)
    Priority.HIGH -> Color(0xFFFFA36B) to Color(0xFF3A2618)
    Priority.MEDIUM -> Color(0xFFF5C06A) to Color(0xFF2E2616)
    Priority.LOW -> Color(0xFF7AA2FF) to Color(0xFF1A2340)
    Priority.LOWEST -> Color(0xFF5C6478) to Color(0xFF1C212C)
    Priority.NONE -> Muted to Color.Transparent
}

/** The small "!!" / "↓" flag before a task's text. Nothing for no priority. */
@Composable
fun PriorityFlag(p: Priority, modifier: Modifier = Modifier) {
    if (p == Priority.NONE) return
    val (fg, tint) = priorityColours(p)
    Text(
        p.flag, fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = fg, maxLines = 1,
        modifier = modifier.clip(RoundedCornerShape(5.dp)).background(tint).padding(horizontal = 5.dp)
    )
}

/** Priority chips for the edit dialog and the task menu: None, Highest … Lowest. */
@Composable
fun PriorityRow(current: Priority, onPick: (Priority) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Priority.CHOICES.forEach { p ->
            val on = p == current
            val shape = RoundedCornerShape(10.dp)
            Row(
                Modifier.clip(shape).background(if (on) PrimaryTint else Color.Transparent)
                    .border(1.5.dp, if (on) Primary else Outline, shape).hand().clickable { onPick(p) }
                    .padding(horizontal = 9.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                PriorityFlag(p)
                Text(if (p == Priority.NONE) "None" else p.title, fontSize = 12.5.sp, color = if (on) Primary else TextMain)
            }
        }
    }
}

/** What the task menu (right-click or ⋯) can do. */
class TaskActions(
    val move: (TaskItem, LocalDate?) -> Unit,
    val pickDate: (TaskItem) -> Unit,
    val priority: (TaskItem, Priority) -> Unit,
    val breakDown: (TaskItem) -> Unit,
    val focus: (TaskItem, Int) -> Unit,
    val edit: (TaskItem) -> Unit,
    val remove: (TaskItem) -> Unit,
)

/** The desktop stand-in for swipes (windows-catchup-v1): reschedule presets, priority, break it down, focus, edit, remove. */
@Composable
fun TaskMenu(t: TaskItem, open: Boolean, onClose: () -> Unit, a: TaskActions) {
    val today = LocalDate.now()
    DropdownMenu(open, onClose, Modifier.background(Menu)) {
        Text("RESCHEDULE", fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, color = Muted, modifier = Modifier.padding(start = 14.dp, top = 6.dp, bottom = 2.dp))
        Upcoming.presets(today).forEach { (label, d) ->
            DropdownMenuItem({
                Row(Modifier.fillMaxWidth()) {
                    Text(label, modifier = Modifier.weight(1f))
                    Text(dayChip(d), fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Faint, modifier = Modifier.padding(start = 16.dp))
                }
            }, { onClose(); a.move(t, d) })
        }
        DropdownMenuItem({ Text("Pick date…") }, { onClose(); a.pickDate(t) })
        if (t.due != null) DropdownMenuItem({ Text("Clear due date") }, { onClose(); a.move(t, null) })
        HorizontalDivider(color = Outline, modifier = Modifier.padding(vertical = 4.dp))
        Text("PRIORITY", fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, color = Muted, modifier = Modifier.padding(start = 14.dp, top = 2.dp, bottom = 4.dp))
        val cur = Priority.of(t)
        Row(Modifier.padding(horizontal = 10.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Priority.CHOICES.forEach { p ->
                val on = p == cur
                Box(
                    Modifier.clip(RoundedCornerShape(8.dp)).background(if (on) PrimaryTint else Color.Transparent)
                        .border(1.dp, if (on) Primary else Outline, RoundedCornerShape(8.dp)).hand()
                        .clickable { onClose(); a.priority(t, p) }.padding(horizontal = 7.dp, vertical = 5.dp),
                    contentAlignment = Alignment.Center
                ) { if (p == Priority.NONE) Text("–", fontSize = 11.sp, color = Muted) else PriorityFlag(p) }
            }
        }
        HorizontalDivider(color = Outline, modifier = Modifier.padding(vertical = 4.dp))
        DropdownMenuItem({ Text("🌶 Break it down") }, { onClose(); a.breakDown(t) })
        val m = Deck.minutes(t) ?: TaskViews.SHORT_MIN
        DropdownMenuItem({ Text("▶ Start focus · ${TaskRules.minutesLabel(m)}") }, { onClose(); a.focus(t, m) })
        DropdownMenuItem({ Text("Edit…") }, { onClose(); a.edit(t) })
        DropdownMenuItem({ Text("Remove", color = Warn) }, { onClose(); a.remove(t) })
    }
}

/**
 * Break it down on Windows: type the small steps, one per line, with minutes on the end if you like ("Open Figma 2m").
 * They're saved as indented subtasks, the same as the phone's Jarvis steps.
 */
@Composable
fun BreakdownDialog(t: TaskItem, onSave: (List<Step>) -> Unit, onDismiss: () -> Unit) {
    var text by remember(t.raw) { mutableStateOf("") }
    val steps = Breakdown.parse(text.lines().filter { it.isNotBlank() }.joinToString("\n") { l -> if (Regex("^\\s*(?:[-*•]|\\d+[.)])\\s").containsMatchIn(l)) l else "- $l" })
    DialogCard("Break it down", onDismiss) {
        Text(TaskViews.plainText(t), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TextMain, modifier = Modifier.padding(bottom = 4.dp))
        Sub("One small step per line. End a line with “5m” to give it a time.", Modifier.padding(bottom = 10.dp))
        Field(text, { text = it }, Modifier.fillMaxWidth().padding(bottom = 8.dp), "Open the file\nWrite the first line 5m", singleLine = false, minLines = 5)
        Sub("${steps.size} step${if (steps.size == 1) "" else "s"}", Modifier.padding(bottom = 12.dp))
        DialogButtons(onDismiss) { PrimaryButton("Add steps", enabled = steps.isNotEmpty()) { onSave(steps) } }
    }
}

/** "＋ Fit a task" for a free gap: open tasks whose estimate fits, longest first. Picking one plans it there. */
@Composable
fun FitDialog(tasks: List<TaskItem>, date: LocalDate, start: LocalTime, minutes: Int, onPick: (TaskItem) -> Unit, onDismiss: () -> Unit) {
    val fits = Upcoming.fits(tasks, minutes, LocalDate.now())
    DialogCard("Fit a task · ${dayChip(date)} ${"%02d:%02d".format(start.hour, start.minute)}", onDismiss) {
        Sub(Upcoming.gapLabel(minutes) + " · tasks with a time estimate that fits", Modifier.padding(bottom = 10.dp))
        Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
            if (fits.isEmpty()) Sub("No task with an estimate fits. Give a task a time in Edit, or drag one here.", Modifier.padding(vertical = 8.dp))
            fits.forEach { t ->
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 6.dp).clip(RoundedCornerShape(10.dp)).background(Surface1).hand().clickable { onPick(t) }
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PriorityFlag(Priority.of(t))
                    Text(TaskViews.plainText(t), fontSize = 13.5.sp, color = TextMain, modifier = Modifier.weight(1f))
                    Deck.minutes(t)?.let { Text(TaskRules.minutesLabel(it), fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Muted) }
                }
            }
        }
        DialogButtons(onDismiss) {}
    }
}

/**
 * Triage (build-queue O1) on Windows: overdue and undated tasks one at a time. Today, a later preset, Pick date,
 * or Drop (marks it cancelled).
 */
@Composable
fun TriageDialog(
    tasks: List<TaskItem>, onToday: (TaskItem) -> Unit, onLater: (TaskItem, LocalDate?) -> Unit, onDrop: (TaskItem) -> Unit,
    onPick: (TaskItem) -> Unit, onDismiss: () -> Unit,
) {
    val today = LocalDate.now()
    val queue = remember { Triage.candidates(tasks, today) }
    var i by remember { mutableIntStateOf(0) }
    DialogCard("Triage", onDismiss) {
        if (i >= queue.size) {
            Text("All clear ✓", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextMain, modifier = Modifier.padding(vertical = 12.dp))
            Sub("Nothing overdue or undated left.", Modifier.padding(bottom = 14.dp))
            DialogButtons(onDismiss) { PrimaryButton("Done") { onDismiss() } }
            return@DialogCard
        }
        val t = queue[i]
        Sub("Overdue + no date · ${i + 1} of ${queue.size}", Modifier.padding(bottom = 12.dp))
        Column(
            Modifier.fillMaxWidth().padding(bottom = 14.dp).clip(RoundedCornerShape(16.dp)).background(Surface1)
                .border(1.dp, Outline, RoundedCornerShape(16.dp)).padding(18.dp)
        ) {
            Text(Triage.label(t, today), fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = if (t.due != null) Warn else Muted)
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                PriorityFlag(Priority.of(t), Modifier.padding(end = 8.dp))
                Text(TaskViews.plainText(t), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextMain)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
            Chip("☀ Today") { onToday(t); i++ }
            Upcoming.presets(today).drop(1).forEach { (label, d) -> Chip(label) { onLater(t, d); i++ } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 14.dp)) {
            Chip("📅 Pick date…") { onPick(t); i++ }
            Chip("No date") { onLater(t, null); i++ }
            Chip("✕ Drop", color = Warn) { onDrop(t); i++ }
            Chip("Skip", color = Muted) { i++ }
        }
        DialogButtons(onDismiss) {}
    }
}
