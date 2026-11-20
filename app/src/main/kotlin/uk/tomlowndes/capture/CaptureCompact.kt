package uk.tomlowndes.capture

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/*
 * The compact Capture page (capture-compact-v3): one kind row, the box, then slim rows with a small header above
 * each, so task, event, idea and explore all fit without scrolling.
 */

/** The kinds on the Capture page, in order. Notes and ★ priority still come from typed marks and scans. */
internal val CAPTURE_KINDS = listOf(LogKind.TASK, LogKind.EVENT, LogKind.IDEA, LogKind.EXPLORE)

internal fun kindTint(k: LogKind): Color = when (k) {
    LogKind.EVENT -> Teal
    LogKind.IDEA, LogKind.PRIORITY -> Amber
    LogKind.EXPLORE -> Purple
    else -> TextMain
}

/** • ○ ! ? across the top: the kind for the line in the box. */
@Composable
internal fun KindRow(kind: LogKind, onPick: (LogKind) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        CAPTURE_KINDS.forEach { k ->
            val on = k == kind
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(11.dp)).background(if (on) PrimaryTint else Surface1)
                    .border(1.dp, if (on) Primary else Outline, RoundedCornerShape(11.dp)).clickable { onPick(k) }.padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(k.mark, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = if (on) Primary else kindTint(k))
                Text(k.label, fontSize = 9.5.sp, fontFamily = FontFamily.Monospace, color = Muted)
            }
        }
    }
}

/** The small header above a row. */
@Composable
internal fun MiniLabel(text: String) =
    Text(text.uppercase(Locale.UK), fontSize = 10.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp, color = Muted)

/** A header and one line of chips that scrolls sideways when it runs out of room. */
@Composable
internal fun MiniSection(label: String, content: @Composable RowScope.() -> Unit) {
    Column(Modifier.padding(top = 8.dp)) {
        MiniLabel(label)
        Row(
            Modifier.padding(top = 4.dp).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically, content = content
        )
    }
}

@Composable
internal fun MiniChip(label: String, on: Boolean, colour: Color = TextMain, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Text(
        label, fontSize = 13.sp, maxLines = 1, color = if (on) Primary else colour, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier.clip(shape).background(if (on) PrimaryTint else Color.Transparent)
            .border(1.dp, if (on) Primary else Outline, shape).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 5.dp)
    )
}

/** A gap between two groups sharing one row (Date · Length). */
@Composable
internal fun MiniGap() = androidx.compose.foundation.layout.Spacer(Modifier.width(6.dp))

/** This Sunday: "this week" for an idea to look at or something to explore. */
internal fun endOfWeek(today: LocalDate = LocalDate.now()): LocalDate = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))

/** Today / Tomorrow (or This week) / Pick. [none] adds a "No date" chip first. */
@Composable
internal fun RowScope.DateMini(due: LocalDate?, onDue: (LocalDate?) -> Unit, onPick: () -> Unit, week: Boolean = false, none: Boolean = false) {
    val today = LocalDate.now()
    val second = if (week) endOfWeek(today) else today.plusDays(1)
    if (none) MiniChip("No date", due == null) { onDue(null) }
    MiniChip("Today", due == today) { onDue(if (due == today) null else today) }
    MiniChip(if (week) "This week" else "Tomorrow", due == second && second != today) { onDue(if (due == second) null else second) }
    val custom = due != null && due != today && due != second
    MiniChip(if (custom) dueLabel(due!!) else "Pick", custom) { onPick() }
}

@Composable
internal fun RowScope.LengthMini(minutes: Int?, choices: List<Int>, onMinutes: (Int?) -> Unit) {
    choices.forEach { m -> MiniChip(if (m == 240) "Half day" else TaskRules.minutesLabel(m), minutes == m) { onMinutes(if (minutes == m) null else m) } }
}

/** None and the flags you see on tasks (!!! !! ! ↓ ↓↓). */
@Composable
internal fun PriorityMini(priority: Priority, onPick: (Priority) -> Unit) = MiniSection("Priority") {
    Priority.CHOICES.forEach { p ->
        val on = priority == p
        val shape = RoundedCornerShape(10.dp)
        Row(
            Modifier.clip(shape).background(if (on) PrimaryTint else Color.Transparent).border(1.dp, if (on) Primary else Outline, shape)
                .clickable { onPick(p) }.padding(horizontal = 9.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (p == Priority.NONE) Text("None", fontSize = 13.sp, color = if (on) Primary else TextMain) else PriorityFlag(p)
        }
    }
}

/** None / Daily / Weekly / Monthly / Custom… (custom opens the repeat sheet). */
@Composable
internal fun RepeatMini(repeat: String?, onRepeat: (String?) -> Unit) {
    var custom by remember { mutableStateOf(false) }
    val presets = REPEAT_PRESETS.map { it.second }
    val spec = repeat?.let { TaskRules.parseRepeat(it) }
    fun plain(unit: String) = spec != null && spec.unit == unit && spec.every == 1 && !spec.weekdays && !spec.whenDone
    MiniSection("Repeat") {
        MiniChip("None", repeat == null) { onRepeat(null) }
        REPEAT_PRESETS.forEach { (label, rule) ->
            val on = when (rule) { "every day" -> repeat == rule; "every week" -> plain("week"); else -> plain("month") }
            MiniChip(label, on) { onRepeat(if (on) null else rule) }
        }
        val customOn = repeat != null && repeat !in presets && !plain("week") && !plain("month")
        MiniChip(if (customOn) "↻ ${repeat}" else "Custom…", customOn) { custom = true }
    }
    if (custom) RepeatSheet(repeat, onPick = { onRepeat(it); custom = false }, onDismiss = { custom = false })
}

/** A slim one-line text field for the compact rows (address, notes, link). */
@Composable
internal fun CompactField(value: String, onValue: (String) -> Unit, hint: String, uri: Boolean = false) {
    val shape = RoundedCornerShape(10.dp)
    androidx.compose.foundation.text.BasicTextField(
        value = value, onValueChange = onValue, singleLine = true,
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = TextMain),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(Primary),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = if (uri) androidx.compose.ui.text.input.KeyboardType.Uri else androidx.compose.ui.text.input.KeyboardType.Text,
            capitalization = if (uri) androidx.compose.ui.text.input.KeyboardCapitalization.None else androidx.compose.ui.text.input.KeyboardCapitalization.Sentences
        ),
        modifier = Modifier.padding(top = 4.dp).fillMaxWidth(),
        decorationBox = { inner ->
            androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().clip(shape).border(1.dp, Outline, shape).padding(horizontal = 10.dp, vertical = 7.dp)) {
                if (value.isEmpty()) Text(hint, fontSize = 13.sp, color = Muted)
                inner()
            }
        }
    )
}

/** Up to five project names for an idea: active ones first, then the most recently changed. */
@Composable
internal fun rememberProjects(vault: android.net.Uri?, prefs: AppPrefs): List<String> {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var list by remember(vault) { mutableStateOf<List<String>>(emptyList()) }
    androidx.compose.runtime.LaunchedEffect(vault) {
        val uri = vault ?: return@LaunchedEffect
        list = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                IdeaStore(ctx, uri, prefs).list().filter { !it.shelved }
                    .sortedWith(compareByDescending<IdeaSummary> { it.status == "active" }.thenByDescending { it.modified })
                    .take(5).map { it.title }
            }.getOrDefault(emptyList())
        }
    }
    return list
}
