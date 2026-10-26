package uk.tomlowndes.capture

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

internal val REPEAT_PRESETS = listOf("Daily" to "every day", "Weekly" to "every week", "Monthly" to "every month")
private val REPEAT_UNITS = listOf("day", "week", "month", "year")

/** Repeat chips, and the day a weekly or monthly rule falls on. [inlineCustom] keeps the custom form in place. */
@Composable
internal fun RepeatPicker(repeat: String?, due: LocalDate?, inlineCustom: Boolean = false, onRepeat: (String?) -> Unit) {
    var custom by remember { mutableStateOf(false) }
    val spec = repeat?.let { TaskRules.parseRepeat(it) }
    val weekly = spec != null && spec.unit == "week" && spec.every == 1 && !spec.weekdays && !spec.whenDone
    val monthly = spec != null && spec.unit == "month" && spec.every == 1 && !spec.weekdays && !spec.whenDone
    val customRule = repeat != null && repeat != "every day" && !weekly && !monthly

    Box(Modifier.padding(top = 10.dp)) { GroupLabel("Repeat") }
    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        REPEAT_PRESETS.forEach { (label, rule) ->
            val on = when (rule) {
                "every day" -> repeat == rule
                "every week" -> weekly
                else -> monthly
            }
            AppChip(on, { onRepeat(if (on) null else rule) }, { Text(if (on) "↻ $label" else label) })
        }
        AppChip(customRule, { custom = true }, { Text(if (customRule) customLabel(repeat) else "Custom…") })
    }
    if (!custom && spec != null && !spec.weekdays && spec.unit == "week") {
        Box(Modifier.padding(top = 8.dp)) { GroupLabel("On") }
        WeekdayChips(spec.weekday) { day ->
            onRepeat(TaskRules.repeatRule(spec.every, "week", day = day, whenDone = spec.whenDone))
        }
    }
    if (!custom && spec != null && !spec.weekdays && spec.unit == "month") {
        Box(Modifier.padding(top = 8.dp)) { GroupLabel("On the") }
        MonthDayStepper(spec.monthDay) { day ->
            onRepeat(TaskRules.repeatRule(spec.every, "month", monthDay = day, whenDone = spec.whenDone))
        }
    }
    repeat?.let {
        val plain = it in REPEAT_PRESETS.map { p -> p.second }
        Text(
            (if (plain) "" else "🔁 $it · ") + "Next one appears when you tick it · first due ${dueLabel(due ?: LocalDate.now())}",
            fontSize = 12.5.sp, color = Muted, modifier = Modifier.padding(top = 6.dp)
        )
    }
    if (custom && inlineCustom) {
        val draft = rememberRepeatDraft(repeat)
        RepeatControls(draft)
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SheetButton("Clear", filled = false) { onRepeat(null); custom = false }
            SheetButton("Use this", filled = true) { onRepeat(draft.rule); custom = false }
        }
    }
    if (custom && !inlineCustom) {
        RepeatSheet(repeat, onPick = { onRepeat(it); custom = false }, onDismiss = { custom = false })
    }
}

private fun customLabel(rule: String?): String {
    val spec = rule?.let { TaskRules.parseRepeat(it) } ?: return "↻ Custom"
    if (spec.weekdays) return "↻ Weekdays"
    spec.weekday?.let { return "↻ ${it.getDisplayName(TextStyle.SHORT, Locale.UK)}" }
    spec.monthDay?.let { return "↻ " + if (it < 0) "last" else TaskRules.ordinal(it) }
    return "↻ Custom"
}

/** Holds the custom repeat form. Constructed from [rememberRepeatDraft] so edits recompose. */
internal class RepeatDraft(spec: RepeatSpec?) {
    var every by mutableIntStateOf(spec?.every ?: 1)
    var unit by mutableStateOf(spec?.unit ?: "week")
    var day by mutableStateOf(spec?.weekday)
    var monthDay by mutableStateOf(spec?.monthDay)
    var weekdays by mutableStateOf(spec?.weekdays == true)
    var whenDone by mutableStateOf(spec?.whenDone == true)

    val rule: String
        get() = TaskRules.repeatRule(
            every, unit, day = if (weekdays) null else day, weekdays = weekdays, whenDone = whenDone,
            monthDay = if (weekdays) null else monthDay,
        )

    fun nudge(delta: Int) { every = (every + delta).coerceIn(1, 99) }

    fun chooseUnit(u: String) {
        unit = u
        weekdays = false
        if (u != "week") day = null
        if (u != "month") monthDay = null
    }

    fun pickDay(d: DayOfWeek?) {
        day = d
        if (d != null) {
            weekdays = false
            monthDay = null
            unit = "week"
        }
    }

    fun pickMonthDay(d: Int?) {
        monthDay = d
        weekdays = false
        day = null
        unit = "month"
    }

    fun toggleWeekdays() {
        weekdays = !weekdays
        if (weekdays) { day = null; monthDay = null }
    }
}

@Composable
internal fun rememberRepeatDraft(initial: String?): RepeatDraft =
    remember(initial) { RepeatDraft(initial?.let { TaskRules.parseRepeat(it) }) }

@Composable
internal fun RepeatControls(draft: RepeatDraft) {
    val byUnit = !draft.weekdays
    Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Every", fontSize = 15.sp, color = if (byUnit) TextMain else Muted)
        Stepper(draft.every.toString(), { draft.nudge(-1) }, { draft.nudge(1) }, Modifier.padding(start = 10.dp))
    }
    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        REPEAT_UNITS.forEach { u ->
            AppChip(byUnit && draft.unit == u, { draft.chooseUnit(u) }, { Text(if (draft.every == 1) u else "${u}s") })
        }
    }
    if (!draft.weekdays && draft.unit == "week") {
        Box(Modifier.padding(top = 14.dp)) { GroupLabel("On") }
        WeekdayChips(draft.day, draft::pickDay)
    }
    if (!draft.weekdays && draft.unit == "month") {
        Box(Modifier.padding(top = 14.dp)) { GroupLabel("On the") }
        MonthDayStepper(draft.monthDay, draft::pickMonthDay)
    }
    AppChip(draft.weekdays, { draft.toggleWeekdays() }, { Text("Weekdays") })
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp).clickable { draft.whenDone = !draft.whenDone }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("Count from when I tick it", fontSize = 14.sp)
            Text("Off: from the due date, like the Tasks plugin", fontSize = 12.sp, color = Muted)
        }
        Switch(draft.whenDone, { draft.whenDone = it })
    }
    Text(
        "🔁 ${draft.rule}", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Primary,
        modifier = Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(PrimaryTint)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    )
}

@Composable
internal fun WeekdayChips(selected: DayOfWeek?, onPick: (DayOfWeek?) -> Unit) {
    Row(
        Modifier.padding(top = 4.dp).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        DayOfWeek.entries.forEach { d ->
            AppChip(selected == d, { onPick(if (selected == d) null else d) }, {
                Text(d.getDisplayName(TextStyle.NARROW, Locale.UK))
            })
        }
    }
}

@Composable
internal fun MonthDayStepper(day: Int?, onDay: (Int?) -> Unit) {
    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
        AppChip(day != null && day < 0, { onDay(if (day != null && day < 0) null else -1) }, { Text("Last day") })
    }
}

@Composable
private fun Stepper(label: String, onMinus: () -> Unit, onPlus: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(12.dp)).border(1.dp, Outline, RoundedCornerShape(12.dp)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("−", color = Muted, fontSize = 16.sp, modifier = Modifier.clickable(onClick = onMinus).padding(horizontal = 14.dp, vertical = 7.dp))
        Text(label, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 8.dp))
        Text("+", color = Muted, fontSize = 16.sp, modifier = Modifier.clickable(onClick = onPlus).padding(horizontal = 14.dp, vertical = 7.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RepeatSheet(current: String?, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    val draft = rememberRepeatDraft(current)
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Surface1) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            Text("Repeat", fontSize = 19.sp, fontWeight = FontWeight.Bold)
            RepeatControls(draft)
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SheetButton("Clear", filled = false) { onPick(null) }
                SheetButton("Done", filled = true) { onPick(draft.rule) }
            }
        }
    }
}

@Composable
internal fun RowScope.SheetButton(label: String, filled: Boolean, onClick: () -> Unit) {
    Text(
        label, color = if (filled) OnPrimary else Muted, fontSize = 14.sp,
        fontWeight = if (filled) FontWeight.Bold else FontWeight.Normal, textAlign = TextAlign.Center,
        modifier = Modifier.weight(1f).clip(CircleShape)
            .then(if (filled) Modifier.background(Primary) else Modifier.border(1.dp, Outline, CircleShape))
            .clickable(onClick = onClick).padding(vertical = 13.dp)
    )
}
