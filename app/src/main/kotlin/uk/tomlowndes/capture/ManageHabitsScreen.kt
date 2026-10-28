package uk.tomlowndes.capture

import android.net.Uri
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate

private val HABIT_ROW = 60.dp

/** Manage habits: drag ≡ to reorder (same order on home), tap to edit, add, pause and resume. Saved to Habits.md. */
@Composable
fun ManageHabitsScreen(vault: Uri?, snack: SnackbarHostState, onOpenNote: () -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<Habit>?>(null) }
    var editing by remember { mutableStateOf<Habit?>(null) }
    var adding by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf<Int?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    val rowPx = with(LocalDensity.current) { HABIT_ROW.toPx() }
    val today = LocalDate.now()

    LaunchedEffect(vault) {
        val uri = vault ?: return@LaunchedEffect
        list = withContext(Dispatchers.IO) { runCatching { Habits.load(SafVault(ctx, uri)).all }.getOrNull() }
    }

    fun save(l: List<Habit>, renames: Map<String, String> = emptyMap()) {
        list = l
        val uri = vault ?: return
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { Habits.save(SafVault(ctx, uri), l, renames) } }
                .onFailure { snack.showSnackbar("Couldn't save: ${it.message}") }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = Muted) }
            Text("Manage habits", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = onBack) { Text("Done", color = Primary) }
        }
        Text("Order here = order on home", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Muted,
            modifier = Modifier.padding(start = 4.dp, bottom = 10.dp))
        val all = list ?: return@Column
        val active = all.filter { !it.paused }
        val paused = all.filter { it.paused }

        GroupLabel("Active · ${active.size}")
        ListCard(Modifier.padding(top = 6.dp)) {
            active.forEachIndexed { i, h -> key(h.name) {
                if (i > 0) HorizontalDivider(color = Outline)
                val lifted = dragging == i
                Row(
                    Modifier.fillMaxWidth().height(HABIT_ROW)
                        .graphicsLayer { if (lifted) { translationY = offset; shadowElevation = 12f } }
                        .background(if (lifted) Surface2 else Surface1)
                        .clickable { editing = h }.padding(end = 14.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "≡", fontSize = 20.sp, color = Muted,
                        modifier = Modifier.pointerInput(h.name) {
                            detectDragGestures(
                                onDragStart = { dragging = list?.filter { !it.paused }?.indexOfFirst { it.name == h.name }; offset = 0f },
                                onDragEnd = { dragging = null; offset = 0f; list?.let { save(it) } },
                                onDragCancel = { dragging = null; offset = 0f }
                            ) { change, drag ->
                                change.consume()
                                val cur = list ?: return@detectDragGestures
                                val on = cur.filter { !it.paused }.toMutableList()
                                val from = dragging ?: return@detectDragGestures
                                offset += drag.y
                                val to = when {
                                    offset > rowPx / 2 && from < on.lastIndex -> from + 1
                                    offset < -rowPx / 2 && from > 0 -> from - 1
                                    else -> from
                                }
                                if (to != from) {
                                    on.add(to, on.removeAt(from))
                                    list = on + cur.filter { it.paused }
                                    dragging = to
                                    offset -= (to - from) * rowPx
                                }
                            }
                        }.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp)
                    )
                    HabitIcon(h.emoji)
                    Column(Modifier.weight(1f)) {
                        Text(h.name, fontSize = 14.5.sp, maxLines = 1)
                        Text(Habits.summary(h), fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Muted, maxLines = 1)
                    }
                    Text("›", color = Muted, fontSize = 16.sp)
                }
            } }
        }

        Text(
            "＋ Add habit", color = Primary, fontSize = 14.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clip(CircleShape).border(1.dp, NextBorder, CircleShape)
                .clickable { adding = true }.padding(vertical = 12.dp)
        )

        if (paused.isNotEmpty()) {
            Box(Modifier.padding(top = 20.dp)) { GroupLabel("Paused · ${paused.size}") }
            ListCard(Modifier.padding(top = 6.dp)) {
                paused.forEachIndexed { i, h ->
                    if (i > 0) HorizontalDivider(color = Outline)
                    Row(
                        Modifier.fillMaxWidth().clickable { editing = h }.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        HabitIcon(h.emoji, dim = true)
                        Column(Modifier.weight(1f)) {
                            Text(h.name, fontSize = 14.5.sp, color = Muted, maxLines = 1)
                            Text("Paused ${h.pausedOn?.let { shortDate(it) }} · streak kept", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Muted)
                        }
                        Text("Resume", color = Primary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable { save(all.map { if (it.name == h.name) Habits.resume(it, today) else it }) })
                    }
                }
            }
        }

        Text(
            "Open Habits note in Obsidian", color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 24.dp).clickable(onClick = onOpenNote).padding(8.dp)
        )
    }

    val all = list
    if (all != null && (adding || editing != null)) {
        val old = editing
        HabitSheet(
            initial = old,
            taken = all.map { it.name.lowercase() }.filter { it != old?.name?.lowercase() }.toSet(),
            onSave = { h ->
                if (old == null) save(all.filter { !it.paused } + h + all.filter { it.paused })
                else save(all.map { if (it.name == old.name) h else it }, if (h.name != old.name) mapOf(old.name to h.name) else emptyMap())
                adding = false; editing = null
            },
            onPause = { h -> save(all.map { if (it.name == h.name) Habits.pause(it, today) else it }); editing = null },
            onDelete = { h -> save(all.filter { it.name != h.name }); editing = null },
            onDismiss = { adding = false; editing = null }
        )
    }
}

@Composable
private fun HabitIcon(emoji: String, dim: Boolean = false) {
    Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(Surface2), contentAlignment = Alignment.Center) {
        Text(emoji.ifEmpty { "•" }, fontSize = 15.sp, color = if (dim) Muted else TextMain)
    }
}

private val DAY_LETTERS = listOf("M", "T", "W", "T", "F", "S", "S")
private val WEEKDAYS = DayOfWeek.entries.take(5).toSet()

/** Add or edit one habit: name, icon, Tick or Counter, days. Water keeps its name and type, and can't be deleted. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun HabitSheet(
    initial: Habit?,
    taken: Set<String>,
    onSave: (Habit) -> Unit,
    onPause: (Habit) -> Unit,
    onDelete: (Habit) -> Unit,
    onDismiss: () -> Unit,
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
    var confirming by remember { mutableStateOf(false) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val clean = Habits.cleanName(name)
    val ok = clean.isNotEmpty() && clean.lowercase() !in taken &&
        (!counter || ((goal.toIntOrNull() ?: 0) > 0 && (step.toIntOrNull() ?: 0) > 0))
    fun built() = (initial ?: Habit(clean)).copy(
        name = if (water) initial!!.name else clean, emoji = emoji.trim(),
        kind = if (counter) HabitKind.COUNTER else HabitKind.TICK,
        goal = if (counter) goal.toInt() else 1, step = if (counter) step.toInt() else 1,
        unit = if (counter) unit.trim().substringBefore(' ').replace("+", "") else "",
        max = counter && max && !water, days = if (days.size == 7) emptySet() else days,
    )

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Surface1) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (initial == null) "New habit" else "Edit habit", fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(if (initial == null) "Add" else "Save", color = if (ok) Primary else Muted, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(CircleShape).clickable(enabled = ok) { onSave(built()) }.padding(horizontal = 10.dp, vertical = 6.dp))
            }

            val dupNote: (@Composable () -> Unit)? =
                if (clean.isNotEmpty() && clean.lowercase() in taken) { { Text("You already have a habit with this name", color = Warn) } } else null
            SheetLabel("Name")
            OutlinedTextField(
                value = name, onValueChange = { name = it }, enabled = !water, singleLine = true,
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), colors = fieldColors(),
                placeholder = { Text("e.g. Stretch") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                supportingText = dupNote
            )

            SheetLabel("Icon")
            val icons = (listOf(emoji).filter { it.isNotEmpty() && it !in Habits.ICONS } + Habits.ICONS)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                icons.forEach { e ->
                    val on = e == emoji
                    Box(
                        Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(if (on) TagTint else Surface2)
                            .border(1.dp, if (on) Purple else Surface2, RoundedCornerShape(10.dp)).clickable { emoji = e },
                        contentAlignment = Alignment.Center
                    ) { Text(e, fontSize = 17.sp) }
                }
                Box(
                    Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(Surface2).clickable { typing = !typing },
                    contentAlignment = Alignment.Center
                ) { Text("＋", fontSize = 15.sp, color = Muted) }
            }
            if (typing) OutlinedTextField(
                value = emoji, onValueChange = { emoji = it.trim().take(8) }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp), shape = RoundedCornerShape(14.dp), colors = fieldColors(),
                placeholder = { Text("Type any emoji") }
            )

            if (!water) {
                SheetLabel("Type")
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Surface2).padding(3.dp)) {
                    listOf(false to "✓ Tick", true to "＋ Counter").forEach { (c, label) ->
                        val on = counter == c
                        Text(
                            label, fontSize = 13.sp, textAlign = TextAlign.Center, color = if (on) TextMain else Muted,
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.weight(1f).clip(RoundedCornerShape(9.dp)).background(if (on) TagTint else Surface2)
                                .clickable { counter = c }.padding(vertical = 9.dp)
                        )
                    }
                }
            }

            if (counter) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("Goal", goal, Modifier.weight(1f)) { goal = it }
                    NumberField("Each tap", step, Modifier.weight(1f)) { step = it }
                    Column(Modifier.weight(1.2f)) {
                        SheetLabel("Unit")
                        OutlinedTextField(unit, { unit = it }, enabled = !water, singleLine = true, shape = RoundedCornerShape(14.dp),
                            colors = fieldColors(), placeholder = { Text("cups") })
                    }
                }
                if (!water) Row(Modifier.padding(top = 8.dp)) {
                    AppChip(max, { max = !max }, { Text("Max, not min") }, shape = CircleShape)
                }
            }

            SheetLabel("Days")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DayOfWeek.entries.forEachIndexed { i, d ->
                    val on = days.isEmpty() || d in days
                    Text(
                        DAY_LETTERS[i], fontSize = 12.sp, fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center,
                        color = if (on) TextMain else Muted,
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(if (on) TagTint else Surface1)
                            .border(1.dp, if (on) Purple else Outline, RoundedCornerShape(10.dp))
                            .clickable {
                                val cur = if (days.isEmpty()) DayOfWeek.entries.toSet() else days
                                val next = if (d in cur) cur - d else cur + d
                                if (next.isNotEmpty()) days = if (next.size == 7) emptySet() else next
                            }.padding(vertical = 9.dp)
                    )
                }
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AppChip(days.isEmpty(), { days = emptySet() }, { Text("Every day") }, shape = CircleShape)
                AppChip(days == WEEKDAYS, { days = WEEKDAYS }, { Text("Weekdays") }, shape = CircleShape)
                AppChip(days.isNotEmpty() && days != WEEKDAYS, {}, { Text("Custom") }, shape = CircleShape)
            }

            if (initial != null) Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                if (!initial.paused) Text("Pause", color = Muted, fontSize = 14.sp,
                    modifier = Modifier.clip(CircleShape).clickable { onPause(initial) }.padding(8.dp))
                else Box(Modifier)
                if (!water) Text("Delete", color = Warn, fontSize = 14.sp,
                    modifier = Modifier.clip(CircleShape).clickable { confirming = true }.padding(8.dp))
            }
        }
    }

    if (confirming && initial != null) AlertDialog(
        onDismissRequest = { confirming = false },
        containerColor = Surface2,
        title = { Text("Delete “${initial.name}”?") },
        text = { Text("It leaves the app and the Habits note. Past ticks stay in the note's history table.", color = Muted) },
        confirmButton = { TextButton(onClick = { confirming = false; onDelete(initial) }) { Text("Delete", color = Warn) } },
        dismissButton = {
            if (!initial.paused) TextButton(onClick = { confirming = false; onPause(initial) }) { Text("Pause instead", color = TextMain) }
            else TextButton(onClick = { confirming = false }) { Text("Cancel", color = TextMain) }
        }
    )
}

@Composable
private fun SheetLabel(text: String) {
    Text(text.uppercase(), fontSize = 10.5.sp, letterSpacing = 1.1.sp, fontFamily = FontFamily.Monospace, color = Muted,
        modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
}

@Composable
private fun NumberField(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    Column(modifier) {
        SheetLabel(label)
        OutlinedTextField(
            value, { v -> onChange(v.filter { it.isDigit() }.take(6)) }, singleLine = true, shape = RoundedCornerShape(14.dp),
            colors = fieldColors(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
    }
}
