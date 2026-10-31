package uk.tomlowndes.capture.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import uk.tomlowndes.capture.Habit
import uk.tomlowndes.capture.HabitKind
import uk.tomlowndes.capture.Habits
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val HABIT_ROW = 56.dp

/** What the Manage habits dialog is showing: the list, one habit's form, or the delete check. */
private sealed interface HabitsView {
    data object List : HabitsView
    data class Edit(val habit: Habit?) : HabitsView
    data class Delete(val habit: Habit) : HabitsView
}

/** Manage habits, as on the phone: drag ≡ to reorder (same order on Deck), click to edit, add, pause, resume, delete. */
@Composable
fun ManageHabitsDialog(state: AppState, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<Habit>?>(null) }
    var view by remember { mutableStateOf<HabitsView>(HabitsView.List) }
    val today = LocalDate.now()

    LaunchedEffect(state.vault) {
        val v = state.vault ?: return@LaunchedEffect
        list = state.io("Couldn't read Habits") { Habits.load(v).all }
    }

    fun save(l: List<Habit>, renames: Map<String, String> = emptyMap()) {
        list = l
        val v = state.vault ?: return
        scope.launch { state.io("Couldn't save Habits") { Habits.save(v, l, renames) } }
    }

    val all = list
    when (val vw = view) {
        HabitsView.List -> DialogCard("Manage habits", onClose) {
            if (all == null) return@DialogCard
            HabitList(
                all, onReorder = { list = it }, onDrop = { list?.let { save(it) } },
                onEdit = { view = HabitsView.Edit(it) },
                onResume = { h -> save(all.map { if (it.name == h.name) Habits.resume(it, today) else it }) },
            )
            Text(
                "＋ Add habit", color = Primary, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp).clip(CircleShape).border(1.dp, NextBorder, CircleShape)
                    .hand().clickable { view = HabitsView.Edit(null) }.padding(vertical = 10.dp)
            )
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                TextAction("Open Habits note in Obsidian", Muted) { state.openInObsidian(Habits.PATH) }
                Box(Modifier.weight(1f))
                PrimaryButton("Done", onClick = onClose)
            }
        }
        is HabitsView.Edit -> if (all != null) HabitForm(
            initial = vw.habit,
            taken = all.map { it.name.lowercase() }.filter { it != vw.habit?.name?.lowercase() }.toSet(),
            onSave = { h ->
                val old = vw.habit
                if (old == null) save(all.filter { !it.paused } + h + all.filter { it.paused })
                else save(all.map { if (it.name == old.name) h else it }, if (h.name != old.name) mapOf(old.name to h.name) else emptyMap())
                view = HabitsView.List
            },
            onPause = { h -> save(all.map { if (it.name == h.name) Habits.pause(it, today) else it }); view = HabitsView.List },
            onDelete = { view = HabitsView.Delete(it) },
            onBack = { view = HabitsView.List },
        )
        is HabitsView.Delete -> DialogCard("Delete “${vw.habit.name}”?", { view = HabitsView.List }) {
            Text("It leaves the app and the Habits note. Past ticks stay in the note's history table.", color = Muted, fontSize = 13.sp)
            Row(Modifier.fillMaxWidth().padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f))
                if (!vw.habit.paused) TextAction("Pause instead", TextMain) {
                    all?.let { a -> save(a.map { if (it.name == vw.habit.name) Habits.pause(it, today) else it }) }
                    view = HabitsView.List
                } else TextAction("Cancel", Muted) { view = HabitsView.List }
                Text(
                    "Delete", color = Warn, fontSize = 13.5.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(CircleShape).background(WarnTint).hand().clickable {
                        all?.let { a -> save(a.filter { it.name != vw.habit.name }) }
                        view = HabitsView.List
                    }.padding(horizontal = 20.dp, vertical = 9.dp)
                )
            }
        }
    }
}

@Composable
private fun HabitList(all: List<Habit>, onReorder: (List<Habit>) -> Unit, onDrop: () -> Unit, onEdit: (Habit) -> Unit, onResume: (Habit) -> Unit) {
    var dragging by remember { mutableStateOf<Int?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    val rowPx = with(LocalDensity.current) { HABIT_ROW.toPx() }
    val active = all.filter { !it.paused }
    val paused = all.filter { it.paused }
    // The drag handler outlives a recomposition, so it reads the latest list through this.
    var latest by remember { mutableStateOf(all) }
    latest = all

    Text("Order here = order on Deck", color = Muted, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(bottom = 8.dp))
    Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
        GroupLabel("Active · ${active.size}")
        Column(Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(14.dp)).background(Surface1).border(1.dp, Outline, RoundedCornerShape(14.dp))) {
            active.forEachIndexed { i, h -> key(h.name) {
                if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Outline))
                val lifted = dragging == i
                Row(
                    Modifier.fillMaxWidth().height(HABIT_ROW)
                        .graphicsLayer { if (lifted) { translationY = offset; shadowElevation = 12f } }
                        .background(if (lifted) Surface2 else Surface1).hand().clickable { onEdit(h) }.padding(end = 14.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "≡", fontSize = 18.sp, color = Muted,
                        modifier = Modifier.pointerInput(h.name) {
                            detectDragGestures(
                                onDragStart = { dragging = latest.filter { !it.paused }.indexOfFirst { it.name == h.name }; offset = 0f },
                                onDragEnd = { dragging = null; offset = 0f; onDrop() },
                                onDragCancel = { dragging = null; offset = 0f }
                            ) { change, drag ->
                                change.consume()
                                val on = latest.filter { !it.paused }.toMutableList()
                                val from = dragging ?: return@detectDragGestures
                                offset += drag.y
                                val to = when {
                                    offset > rowPx / 2 && from < on.lastIndex -> from + 1
                                    offset < -rowPx / 2 && from > 0 -> from - 1
                                    else -> from
                                }
                                if (to != from) {
                                    on.add(to, on.removeAt(from))
                                    latest = on + latest.filter { it.paused }
                                    onReorder(latest)
                                    dragging = to
                                    offset -= (to - from) * rowPx
                                }
                            }
                        }.padding(start = 14.dp, end = 2.dp, top = 10.dp, bottom = 10.dp)
                    )
                    HabitIcon(h.emoji)
                    Column(Modifier.weight(1f)) {
                        Text(h.name, fontSize = 14.sp, color = TextMain, maxLines = 1)
                        Text(Habits.summary(h), fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Muted, maxLines = 1)
                    }
                    Text("›", color = Muted)
                }
            } }
        }
        if (paused.isNotEmpty()) {
            Box(Modifier.padding(top = 16.dp)) { GroupLabel("Paused · ${paused.size}") }
            Column(Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(14.dp)).background(Surface1).border(1.dp, Outline, RoundedCornerShape(14.dp))) {
                paused.forEachIndexed { i, h ->
                    if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Outline))
                    Row(
                        Modifier.fillMaxWidth().hand().clickable { onEdit(h) }.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        HabitIcon(h.emoji)
                        Column(Modifier.weight(1f)) {
                            Text(h.name, fontSize = 14.sp, color = Muted, maxLines = 1)
                            Text("Paused ${h.pausedOn?.format(DateTimeFormatter.ofPattern("d MMM", Locale.UK))} · streak kept",
                                fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Muted)
                        }
                        TextAction("Resume") { onResume(h) }
                    }
                }
            }
        }
    }
}

@Composable
private fun HabitIcon(emoji: String) {
    Box(Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(Surface2), contentAlignment = Alignment.Center) {
        Text(emoji.ifEmpty { "•" }, fontSize = 14.sp, color = TextMain)
    }
}

private val DAY_LETTERS = listOf("M", "T", "W", "T", "F", "S", "S")
private val WEEKDAYS = DayOfWeek.entries.take(5).toSet()

/** Add or edit one habit: name, icon, Tick or Counter, days. Water keeps its name and type, and can't be deleted. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HabitForm(
    initial: Habit?, taken: Set<String>, onSave: (Habit) -> Unit, onPause: (Habit) -> Unit, onDelete: (Habit) -> Unit, onBack: () -> Unit,
) {
    val water = initial?.water == true
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var emoji by remember { mutableStateOf(initial?.emoji ?: Habits.ICONS.first()) }
    var typing by remember { mutableStateOf(false) }
    var counter by remember { mutableStateOf(initial?.counter == true) }
    var goal by remember { mutableStateOf((initial?.goal ?: 2).toString()) }
    var step by remember { mutableStateOf((initial?.step ?: 1).toString()) }
    var unit by remember { mutableStateOf(initial?.unit.orEmpty()) }
    var max by remember { mutableStateOf(initial?.max == true) }
    var days by remember { mutableStateOf(initial?.days ?: emptySet()) }

    val clean = Habits.cleanName(name)
    val dup = clean.isNotEmpty() && clean.lowercase() in taken
    val ok = clean.isNotEmpty() && !dup && (!counter || ((goal.toIntOrNull() ?: 0) > 0 && (step.toIntOrNull() ?: 0) > 0))
    fun built() = (initial ?: Habit(clean)).copy(
        name = if (water) initial!!.name else clean, emoji = emoji.trim(),
        kind = if (counter) HabitKind.COUNTER else HabitKind.TICK,
        goal = if (counter) goal.toInt() else 1, step = if (counter) step.toInt() else 1,
        unit = if (counter) unit.trim().substringBefore(' ').replace("+", "") else "",
        max = counter && max && !water, days = if (days.size == 7) emptySet() else days,
    )

    DialogCard(if (initial == null) "New habit" else "Edit habit", onBack) {
        FormLabel("Name")
        if (water) Text(name, color = Muted, fontSize = 14.sp, modifier = Modifier.padding(vertical = 6.dp))
        else Field(name, { name = it }, Modifier.fillMaxWidth(), placeholder = "e.g. Stretch")
        if (dup) Text("You already have a habit with this name", color = Warn, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))

        FormLabel("Icon")
        val icons = listOf(emoji).filter { it.isNotEmpty() && it !in Habits.ICONS } + Habits.ICONS
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            icons.forEach { e ->
                val on = e == emoji
                Box(
                    Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).background(if (on) TagTint else Surface2)
                        .border(1.dp, if (on) Purple else Surface2, RoundedCornerShape(9.dp)).hand().clickable { emoji = e },
                    contentAlignment = Alignment.Center
                ) { Text(e, fontSize = 15.sp) }
            }
            Box(
                Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).background(Surface2).hand().clickable { typing = !typing },
                contentAlignment = Alignment.Center
            ) { Text("＋", fontSize = 14.sp, color = Muted) }
        }
        if (typing) Field(emoji, { emoji = it.trim().take(8) }, Modifier.fillMaxWidth().padding(top = 8.dp), placeholder = "Type or paste any emoji (Win + .)")

        if (!water) {
            FormLabel("Type")
            Pill(listOf("✓ Tick", "＋ Counter"), if (counter) 1 else 0, Modifier.fillMaxWidth()) { counter = it == 1 }
        }
        if (counter) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) { FormLabel("Goal"); Field(goal, { v -> goal = v.filter { it.isDigit() }.take(6) }) }
                Column(Modifier.weight(1f)) { FormLabel("Each click"); Field(step, { v -> step = v.filter { it.isDigit() }.take(6) }) }
                Column(Modifier.weight(1.2f)) {
                    FormLabel("Unit")
                    if (water) Text("ml", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(vertical = 10.dp))
                    else Field(unit, { unit = it }, placeholder = "cups")
                }
            }
            if (!water) Row(Modifier.padding(top = 8.dp)) { Chip("Max, not min", max, small = true) { max = !max } }
        }

        FormLabel("Days")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DayOfWeek.entries.forEachIndexed { i, d ->
                val on = days.isEmpty() || d in days
                Text(
                    DAY_LETTERS[i], fontSize = 12.sp, fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center,
                    color = if (on) TextMain else Muted,
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(9.dp)).background(if (on) TagTint else Surface1)
                        .border(1.dp, if (on) Purple else Outline, RoundedCornerShape(9.dp)).hand()
                        .clickable {
                            val cur = if (days.isEmpty()) DayOfWeek.entries.toSet() else days
                            val next = if (d in cur) cur - d else cur + d
                            if (next.isNotEmpty()) days = if (next.size == 7) emptySet() else next
                        }.padding(vertical = 8.dp)
                )
            }
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("Every day", days.isEmpty(), small = true) { days = emptySet() }
            Chip("Weekdays", days == WEEKDAYS, small = true) { days = WEEKDAYS }
        }

        Row(Modifier.fillMaxWidth().padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (initial != null && !water) TextAction("Delete", Warn) { onDelete(initial) }
            if (initial != null && !initial.paused) TextAction("Pause", Muted) { onPause(initial) }
            Box(Modifier.weight(1f))
            TextAction("Cancel", Muted, onClick = onBack)
            PrimaryButton(if (initial == null) "Add" else "Save", enabled = ok) { onSave(built()) }
        }
    }
}

@Composable
private fun FormLabel(text: String) = Text(
    text.uppercase(Locale.UK), fontSize = 10.5.sp, letterSpacing = 1.1.sp, fontFamily = FontFamily.Monospace, color = Muted,
    modifier = Modifier.padding(top = 12.dp, bottom = 6.dp)
)
