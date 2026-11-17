package uk.tomlowndes.capture

import android.app.TimePickerDialog
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/*
 * New event, New priority and New note (tower-bar-events-v1). Picking ○ ★ or – on Capture opens one of these,
 * laid out like Edit task: title, text box, when, details, Tag, Place, then Save.
 */

private val HM = DateTimeFormatter.ofPattern("HH:mm")
private val EVENT_LENGTHS = listOf(30, 60, 120, 240)

@Composable
private fun SheetLabel(text: String) =
    Text(text, fontSize = 11.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp, color = Muted, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))

@Composable
private fun SheetHeader(mark: String, colour: Color, title: String) = Row(verticalAlignment = Alignment.CenterVertically) {
    Text(mark, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = colour, modifier = Modifier.padding(end = 8.dp))
    Text(title, fontSize = 19.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun SheetText(text: String, onText: (String) -> Unit, hint: String, lines: Int = 2) = OutlinedTextField(
    value = text, onValueChange = onText,
    modifier = Modifier.fillMaxWidth().padding(top = 12.dp), shape = RoundedCornerShape(20.dp),
    placeholder = { Text(hint) }, minLines = lines,
    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
    colors = fieldColors()
)

@Composable
private fun DateChips(due: LocalDate?, onDue: (LocalDate?) -> Unit, onPick: () -> Unit, tomorrow: Boolean = true) {
    val today = LocalDate.now()
    val custom = due != null && due != today && due != today.plusDays(1)
    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AppChip(due == today, { onDue(if (due == today) null else today) }, { Text("Today") })
        if (tomorrow) AppChip(due == today.plusDays(1), { onDue(if (due == today.plusDays(1)) null else today.plusDays(1)) }, { Text("Tomorrow") })
        AppChip(custom, onPick, { Text(if (custom) dueLabel(due!!) else "Pick date") })
    }
}

@Composable
private fun SaveButton(label: String, enabled: Boolean, onClick: () -> Unit) = Text(
    label, color = OnPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
    modifier = Modifier.padding(top = 18.dp).fillMaxWidth().clip(CircleShape)
        .background(if (enabled) Primary else Primary.copy(alpha = 0.4f))
        .clickable(enabled = enabled, onClick = onClick).padding(vertical = 14.dp)
)

/** ○ New event: date, time or all day, length, location, notes, tags. Saved to 99 - Meta/Events.md. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun NewEventSheet(
    vault: Uri?, tags: TagData, initial: String, initialDate: LocalDate?, busy: Boolean,
    onSave: (AppEvent) -> Unit, onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf(initial) }
    var date by remember { mutableStateOf(initialDate ?: LocalDate.now()) }
    var time by remember { mutableStateOf<LocalTime?>(null) }
    var minutes by remember { mutableStateOf(60) }
    var placeTags by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf(false) }
    val places = rememberPlaces(vault).first
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Surface1) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            SheetHeader("○", Teal, "New event")
            SheetText(text, { text = it }, "What's on?")
            DateChips(date, { date = it ?: LocalDate.now() }, { picking = true })
            SheetLabel("TIME")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppChip(time == null, { time = null }, { Text("All day") })
                AppChip(time != null, {
                    val t = time ?: LocalTime.of(9, 0)
                    TimePickerDialog(ctx, { _, h, m -> time = LocalTime.of(h, m) }, t.hour, t.minute, true).show()
                }, { Text(time?.format(HM) ?: "Pick time") })
            }
            if (time != null) Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                EVENT_LENGTHS.forEach { m ->
                    AppChip(minutes == m, { minutes = m }, { Text(if (m == 240) "Half day" else TaskRules.minutesLabel(m)) })
                }
            }
            PlaceRow(vault, placeTags, { placeTags = it }, title = "Location", hint = null)
            OutlinedTextField(
                address, { address = it }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                shape = RoundedCornerShape(14.dp), placeholder = { Text("Or type an address") }, colors = fieldColors()
            )
            SheetLabel("NOTES")
            OutlinedTextField(
                note, { note = it }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), minLines = 2,
                placeholder = { Text("Anything to remember") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences), colors = fieldColors()
            )
            TagRow(tags, Tags.inText(text)) { tag -> text = Tags.toggle(text, tag) }
            val ok = text.isNotBlank() && !busy
            SaveButton("Save event", ok) {
                val tag = Errands.tags(placeTags).firstOrNull()
                val place = address.trim().ifEmpty { null } ?: tag?.let { t -> places.firstOrNull { it.tag == t }?.label ?: t }
                onSave(AppEvent(date, text.trim(), time, if (time != null) minutes else null, place, note.trim().ifEmpty { null }))
            }
        }
    }
    if (picking) PickDateDialog(date, onPick = { d -> d?.let { date = it }; picking = false }, onDismiss = { picking = false })
}

/** ★ New priority: Edit task's layout with ⏫ High already picked. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun NewPrioritySheet(
    vault: Uri?, tags: TagData, initial: String, initialDue: LocalDate?, initialMinutes: Int?, busy: Boolean,
    onSave: (text: String, due: LocalDate?, repeat: String?, minutes: Int?, priority: Priority) -> Unit, onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    var due by remember { mutableStateOf(initialDue) }
    var minutes by remember { mutableStateOf(initialMinutes) }
    var repeat by remember { mutableStateOf<String?>(null) }
    var priority by remember { mutableStateOf(Priority.HIGH) }
    var picking by remember { mutableStateOf(false) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Surface1) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            SheetHeader("★", Amber, "New priority")
            SheetText(text, { text = it }, "What needs doing?")
            DateChips(due, { due = it }, { picking = true })
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ESTIMATES.forEach { m -> AppChip(minutes == m, { minutes = if (minutes == m) null else m }, { Text(TaskRules.minutesLabel(m)) }) }
            }
            SheetLabel("PRIORITY")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Priority.CHOICES.forEach { p ->
                    AppChip(priority == p, { priority = p }, {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            PriorityFlag(p)
                            Text(if (p == Priority.NONE) "None" else p.title)
                        }
                    })
                }
            }
            RepeatPicker(repeat, due, inlineCustom = true) { repeat = it }
            TagRow(tags, Tags.inText(text)) { tag -> text = Tags.toggle(text, tag) }
            PlaceRow(vault, text, { text = it })
            SaveButton("Save task", text.isNotBlank() && !busy) { onSave(text.trim(), due, repeat, minutes, priority) }
        }
    }
    if (picking) PickDateDialog(due, onPick = { due = it; picking = false }, onDismiss = { picking = false })
}

/** Where a new note goes: a dated line in Inbox.md, its own note, or the end of a note you pick. */
internal enum class NoteDest { LOG, NEW, ADD }

/** – New note: Save to (Inbox log / New note / Add to a note), date for the log line, tags. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun NewNoteSheet(
    vault: Uri?, tags: TagData, initial: String, busy: Boolean,
    onSave: (text: String, dest: NoteDest, date: LocalDate, target: NoteInfo?) -> Unit, onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf(initial) }
    var dest by remember { mutableStateOf(NoteDest.LOG) }
    var date by remember { mutableStateOf<LocalDate?>(LocalDate.now()) }
    var target by remember { mutableStateOf<NoteInfo?>(null) }
    var all by remember { mutableStateOf<List<NoteInfo>>(emptyList()) }
    var choosing by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    LaunchedEffect(vault, dest) {
        val uri = vault ?: return@LaunchedEffect
        if (dest == NoteDest.ADD && all.isEmpty())
            all = withContext(Dispatchers.IO) { runCatching { NoteIndex.paths(appVault(ctx, uri)) }.getOrDefault(emptyList()) }
    }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Surface1) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            SheetHeader("–", TextMain, "New note")
            SheetText(text, { text = it }, "A thought, a quote, anything", lines = 3)
            SheetLabel("SAVE TO")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppChip(dest == NoteDest.LOG, { dest = NoteDest.LOG }, { Text("Inbox log") })
                AppChip(dest == NoteDest.NEW, { dest = NoteDest.NEW }, { Text("New note") })
                AppChip(dest == NoteDest.ADD, { dest = NoteDest.ADD; if (target == null) choosing = true }, { Text("Add to a note") })
            }
            Text(
                when (dest) {
                    NoteDest.LOG -> "One dated line at the end of ${VaultWriter.INBOX}/Inbox.md"
                    NoteDest.NEW -> "Its own note in ${VaultWriter.INBOX}"
                    NoteDest.ADD -> target?.let { "Added to the end of ${it.title} · tap to change" } ?: "Pick a note"
                },
                fontSize = 12.5.sp, color = if (dest == NoteDest.ADD && target == null) Amber else Muted,
                modifier = Modifier.padding(top = 8.dp).let { if (dest == NoteDest.ADD) it.clickable { choosing = true } else it }
            )
            if (dest == NoteDest.LOG) {
                SheetLabel("DATE")
                DateChips(date, { date = it ?: LocalDate.now() }, { picking = true }, tomorrow = false)
            }
            TagRow(tags, Tags.inText(text)) { tag -> text = Tags.toggle(text, tag) }
            val ok = text.isNotBlank() && !busy && (dest != NoteDest.ADD || target != null)
            SaveButton("Save note", ok) { onSave(text.trim(), dest, date ?: LocalDate.now(), target) }
        }
    }
    if (choosing) PickNoteDialog(all, onDismiss = { choosing = false }) { n -> target = n; choosing = false }
    if (picking) PickDateDialog(date, onPick = { date = it ?: LocalDate.now(); picking = false }, onDismiss = { picking = false })
}
