package uk.tomlowndes.capture.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uk.tomlowndes.capture.Deck
import uk.tomlowndes.capture.RepeatSpec
import uk.tomlowndes.capture.TaskItem
import uk.tomlowndes.capture.TaskRules
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

private val ESTIMATES = TaskRules.ESTIMATE_CHOICES

/** Short label for the Repeat chip on the add row. */
fun repeatChipLabel(rule: String?): String {
    if (rule == null) return "Repeat"
    val spec = TaskRules.parseRepeat(rule)
    if (spec?.weekdays == true) return "🔁 Weekdays"
    spec?.weekday?.let { return "🔁 ${it.getDisplayName(TextStyle.SHORT, Locale.UK)}" }
    spec?.monthDay?.let { return "🔁 " + if (it < 0) "last" else TaskRules.ordinal(it) }
    return when (rule) {
        "every day" -> "🔁 Daily"
        "every week" -> "🔁 Weekly"
        "every month" -> "🔁 Monthly"
        else -> "🔁 Repeat"
    }
}

/** Add-flow repeat dialog: interval, weekday or day of the month, weekdays, and when-done. */
@Composable
fun RepeatDialog(current: String?, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    DialogCard("Repeat", onDismiss) {
        RepeatForm(current, onUse = onPick, onClear = { onPick(null) })
    }
}

/** Change the wording, due date, estimate and recurrence of a task already in the vault. */
@Composable
fun EditTaskDialog(
    t: TaskItem,
    onSave: (text: String, due: LocalDate?, recurrence: String?, minutes: Int?) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember(t.raw) { mutableStateOf(t.text) }
    var due by remember(t.raw) { mutableStateOf(t.due) }
    var minutes by remember(t.raw) { mutableStateOf(Deck.minutes(t)) }
    var repeat by remember(t.raw) { mutableStateOf(t.recurrence) }
    var picking by remember { mutableStateOf(false) }
    val today = LocalDate.now()
    DialogCard("Edit task", onDismiss) {
        Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
            Field(text, { text = it }, Modifier.fillMaxWidth().padding(bottom = 12.dp), "What needs doing?", singleLine = false, minLines = 2)
            SmallLabel("Due")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                Chip("Today", due == today, small = true) { due = if (due == today) null else today }
                Chip("Tomorrow", due == today.plusDays(1), small = true) { due = if (due == today.plusDays(1)) null else today.plusDays(1) }
                val custom = due != null && due != today && due != today.plusDays(1)
                Chip(if (custom) dayChip(due!!) else "Pick date", custom, small = true) { picking = true }
                if (due != null) Chip("No date", false, small = true) { due = null }
            }
            SmallLabel("Time")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                ESTIMATES.forEach { m ->
                    Chip(TaskRules.minutesLabel(m), minutes == m, small = true) { minutes = if (minutes == m) null else m }
                }
            }
            SmallLabel("Repeat")
            RepeatEditor(repeat) { repeat = it }
        }
        DialogButtons(onDismiss, onDelete = onRemove) {
            PrimaryButton("Save", enabled = text.isNotBlank()) { onSave(text.trim(), due, repeat, minutes) }
        }
    }
    if (picking) PickDateDialog(due, onPick = { picking = false; due = it }, onDismiss = { picking = false })
}

/** Presets, the day a weekly or monthly rule falls on, and the custom form. */
@Composable
private fun RepeatEditor(repeat: String?, onRepeat: (String?) -> Unit) {
    var custom by remember { mutableStateOf(false) }
    val spec = repeat?.let { TaskRules.parseRepeat(it) }
    val weekly = spec.isSimple("week")
    val monthly = spec.isSimple("month")
    val customRule = repeat != null && repeat != "every day" && !weekly && !monthly
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
        Chip("Daily", repeat == "every day", small = true) { onRepeat(if (repeat == "every day") null else "every day") }
        Chip("Weekly", weekly, small = true) { onRepeat(if (weekly) null else "every week") }
        Chip("Monthly", monthly, small = true) { onRepeat(if (monthly) null else "every month") }
        Chip(if (customRule) "Custom" else "Custom…", customRule || custom, small = true) { custom = !custom }
    }
    if (!custom && spec != null && !spec.weekdays && spec.unit == "week") {
        WeekdayRow(spec.weekday) { day -> onRepeat(TaskRules.repeatRule(spec.every, "week", day = day, whenDone = spec.whenDone)) }
    }
    if (!custom && spec != null && !spec.weekdays && spec.unit == "month") {
        MonthDayRow(spec.monthDay) { day -> onRepeat(TaskRules.repeatRule(spec.every, "month", monthDay = day, whenDone = spec.whenDone)) }
    }
    if (repeat != null) {
        Text("🔁 $repeat", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(bottom = 8.dp))
    }
    if (custom) RepeatForm(repeat, onUse = { onRepeat(it); custom = false }, onClear = { onRepeat(null); custom = false })
}

private fun RepeatSpec?.isSimple(unit: String) =
    this != null && this.unit == unit && every == 1 && !weekdays && !whenDone

@Composable
internal fun RepeatForm(current: String?, onUse: (String) -> Unit, onClear: () -> Unit) {
    val spec = remember(current) { current?.let { TaskRules.parseRepeat(it) } }
    var every by remember(current) { mutableIntStateOf(spec?.every ?: 1) }
    var unit by remember(current) { mutableStateOf(spec?.unit ?: "week") }
    var day by remember(current) { mutableStateOf(spec?.weekday) }
    var monthDay by remember(current) { mutableStateOf(spec?.monthDay) }
    var weekdays by remember(current) { mutableStateOf(spec?.weekdays == true) }
    var whenDone by remember(current) { mutableStateOf(spec?.whenDone == true) }
    val rule = TaskRules.repeatRule(every, unit, day = if (weekdays) null else day, weekdays = weekdays, whenDone = whenDone, monthDay = if (weekdays) null else monthDay)

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
        Text("Every", color = if (weekdays) Muted else TextMain, modifier = Modifier.padding(end = 10.dp))
        Stepper(every.toString(), { every = (every - 1).coerceAtLeast(1) }, { every = (every + 1).coerceAtMost(99) })
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
        listOf("day", "week", "month", "year").forEach { u ->
            Chip(if (every == 1) u else "${u}s", !weekdays && unit == u, small = true) {
                unit = u
                weekdays = false
                if (u != "week") day = null
                if (u != "month") monthDay = null
            }
        }
    }
    if (!weekdays && unit == "week") {
        SmallLabel("On")
        WeekdayRow(day) { picked ->
            day = picked
            if (picked != null) { weekdays = false; monthDay = null; unit = "week" }
        }
    }
    if (!weekdays && unit == "month") {
        SmallLabel("On the")
        MonthDayRow(monthDay) { picked ->
            monthDay = picked
            weekdays = false
            day = null
            unit = "month"
        }
    }
    Chip("Weekdays", weekdays, small = true) {
        weekdays = !weekdays
        if (weekdays) { day = null; monthDay = null }
    }
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp).clip(RoundedCornerShape(8.dp)).hand().clickable { whenDone = !whenDone }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("Count from when I tick it", fontSize = 14.sp)
            Text("Off: from the due date, like the Tasks plugin", fontSize = 12.sp, color = Muted)
        }
        Toggle(whenDone) { whenDone = it }
    }
    Text(
        "🔁 $rule", fontSize = 12.sp, color = Primary,
        modifier = Modifier.padding(bottom = 12.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Surface2).padding(horizontal = 12.dp, vertical = 10.dp)
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("Clear", false) { onClear() }
        PrimaryButton("Done") { onUse(rule) }
    }
}

@Composable
private fun WeekdayRow(selected: DayOfWeek?, onPick: (DayOfWeek?) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 8.dp)) {
        DayOfWeek.entries.forEach { d ->
            Chip(d.getDisplayName(TextStyle.SHORT, Locale.UK), selected == d, small = true) {
                onPick(if (selected == d) null else d)
            }
        }
    }
}

@Composable
private fun MonthDayRow(day: Int?, onDay: (Int?) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
        Stepper(
            when (day) {
                null -> "Any"
                -1 -> "last"
                else -> TaskRules.ordinal(day)
            },
            onMinus = {
                onDay(when (day) {
                    null -> null
                    -1 -> 31
                    1 -> null
                    else -> day - 1
                })
            },
            onPlus = {
                onDay(when (day) {
                    null -> 1
                    -1 -> -1
                    else -> (day + 1).coerceAtMost(31)
                })
            }
        )
        Chip("Last day", day != null && day < 0, small = true) { onDay(if (day != null && day < 0) null else -1) }
    }
}

@Composable
private fun Stepper(label: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(12.dp)).border(1.dp, Outline, RoundedCornerShape(12.dp)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("−", color = Muted, modifier = Modifier.hand().clickable(onClick = onMinus).padding(horizontal = 12.dp, vertical = 6.dp))
        Text(label, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp))
        Text("+", color = Muted, modifier = Modifier.hand().clickable(onClick = onPlus).padding(horizontal = 12.dp, vertical = 6.dp))
    }
}
