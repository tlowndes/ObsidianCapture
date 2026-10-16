package uk.tomlowndes.capture.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch
import uk.tomlowndes.capture.DailyNote
import uk.tomlowndes.capture.Kind
import uk.tomlowndes.capture.LogField
import uk.tomlowndes.capture.Md
import uk.tomlowndes.capture.Slot
import uk.tomlowndes.capture.Value
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Today's daily note as a form, Morning and Evening side by side. Only changed sections are written. */
@Composable
fun LogPane(state: AppState) {
    val scope = rememberCoroutineScope()
    var fields by remember { mutableStateOf<List<LogField>>(emptyList()) }
    var headings by remember { mutableStateOf<List<String>>(emptyList()) }
    var notePath by remember { mutableStateOf("") }
    val values = remember { mutableStateMapOf<String, Value>() }
    // What today's note held when the form was filled from it: Save only writes fields that differ from this.
    var inNote by remember { mutableStateOf<Map<String, Value>>(emptyMap()) }
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }

    val changed = fields.filter { !DailyNote.same(values[it.id], inNote[it.id]) }

    fun load(keepEdits: Boolean) {
        scope.launch {
            val r = state.io("Couldn't read the daily note") {
                val s = state.logStore()!!
                val f = s.fields()
                Triple(f, s.read(f), s.headings() to s.todayPath())
            } ?: return@launch
            val (f, v, hp) = r
            // Coming back to the window shouldn't throw away what's been typed: keep edited fields as they are.
            val edits = if (keepEdits) changed.associate { it.id to values[it.id] } else emptyMap()
            fields = f; values.clear(); values.putAll(v); inNote = v; headings = hp.first; notePath = hp.second; loaded = true
            edits.forEach { (k, e) -> if (e != null) values[k] = e }
        }
    }
    LaunchedEffect(state.vault) { load(false) }
    LaunchedEffect(state.changes) { if (loaded && !busy) load(true) }

    fun save() {
        if (busy || !loaded) return
        busy = true
        val snapshot = values.toMap()
        val base = inNote
        scope.launch {
            val saved = state.io { state.logStore()!!.save(fields, snapshot, base) }
            busy = false
            saved ?: return@launch
            inNote = inNote + saved.inNote
            val name = notePath.substringAfterLast('/').removeSuffix(".md")
            when {
                saved.skipped.isNotEmpty() ->
                    state.say("Not saved: ${saved.skipped.joinToString { it.label }} changed on another device. Save again to replace it.")
                saved.created -> state.say("Saved. $name wasn't in the vault yet, so it was created here.")
                else -> state.say("Saved to $name")
            }
        }
    }

    Box(Modifier.fillMaxSize().onPreviewKeyEvent { e ->
        if (e.type == KeyEventType.KeyDown && e.isCtrlPressed && e.key == Key.S) { save(); true } else false
    }) {
        Column(Modifier.fillMaxSize().padding(horizontal = 32.dp).padding(top = 26.dp, bottom = 90.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    H1("Daily log")
                    val name = notePath.substringAfterLast('/').removeSuffix(".md")
                    Sub(LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.UK)) +
                        if (name.isNotEmpty()) " · saves into $name" else "", Modifier.padding(top = 2.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (notePath.isNotEmpty()) Chip("Open in Obsidian ↗") { state.openInObsidian(notePath) }
                    Chip("＋ Add section") { if (loaded) adding = true }
                }
            }
            if (loaded && fields.isEmpty()) Sub("No sections found. Check the Daily Note template path in Obsidian's daily notes settings.",
                Modifier.padding(top = 20.dp))
            Row(Modifier.fillMaxSize().padding(top = 18.dp).verticalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                Column(Modifier.weight(1f)) {
                    ColumnHead("☀ Morning", status(fields, Slot.MORNING, inNote))
                    fields.filter { it.slot == Slot.MORNING }.forEach { f -> FieldEditor(f, values[f.id]) { values[f.id] = it } }
                    val any = fields.filter { it.slot == Slot.ANYTIME }
                    if (any.isNotEmpty()) {
                        ColumnHead("Anytime", null)
                        any.forEach { f -> FieldEditor(f, values[f.id]) { values[f.id] = it } }
                    }
                }
                Column(Modifier.weight(1f)) {
                    ColumnHead("🌙 Evening", status(fields, Slot.EVENING, inNote))
                    fields.filter { it.slot == Slot.EVENING }.forEach { f -> FieldEditor(f, values[f.id]) { values[f.id] = it } }
                }
            }
        }
        val shape = RoundedCornerShape(14.dp)
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 32.dp, vertical = 22.dp)
                .clip(shape).background(Surface1).border(1.dp, Outline, shape).padding(start = 18.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                when (changed.size) {
                    0 -> "No changes"
                    1 -> "1 section changed · only that is written, the rest of the note is left alone"
                    else -> "${changed.size} sections changed · only those are written, the rest of the note is left alone"
                },
                fontSize = 13.sp, color = Muted, modifier = Modifier.weight(1f)
            )
            PrimaryButton(if (busy) "Saving…" else "Save", enabled = loaded && !busy && changed.isNotEmpty(), hint = "Ctrl S") { save() }
        }
    }

    if (adding) AddSectionDialog(
        headings, defaultAfter = headings.firstOrNull { Md.norm(it) == "winddown" } ?: headings.lastOrNull(),
        onDismiss = { adding = false },
        onCreate = { title, kind, slot, choices, after ->
            scope.launch {
                state.io("Couldn't add the section") { state.logStore()!!.addSection(title, kind, slot, choices, after) } ?: return@launch
                adding = false; load(true); state.say("Added “$title” to your template")
            }
        }
    )
}

private fun status(fields: List<LogField>, slot: Slot, inNote: Map<String, Value>): String =
    if (fields.filter { it.slot == slot }.any { inNote[it.id]?.isEmpty() == false }) "has entries" else "not filled in yet"

@Composable
private fun ColumnHead(title: String, status: String?) {
    Column(Modifier.fillMaxWidth().padding(bottom = 14.dp, top = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextMain, modifier = Modifier.weight(1f))
            if (status != null) Text(status, fontSize = 12.sp, color = Muted, fontWeight = FontWeight.SemiBold)
        }
        Box(Modifier.fillMaxWidth().padding(top = 10.dp).height(1.dp).background(Outline))
    }
}

@Composable
private fun FieldEditor(f: LogField, value: Value?, onChange: (Value) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
        if (f.kind == Kind.YESNO) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { LabelRow(f) }
                Toggle((value as? Value.Flag)?.on ?: false) { onChange(Value.Flag(it)) }
            }
            return@Column
        }
        LabelRow(f)
        when (f.kind) {
            Kind.TEXT -> Field((value as? Value.Text)?.s.orEmpty(), { onChange(Value.Text(it)) }, Modifier.fillMaxWidth(),
                "Write here…", singleLine = false)
            Kind.CHOICE -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                f.choices.forEach { c -> Chip(c, (value as? Value.Text)?.s.equals(c, true)) { onChange(Value.Text(c)) } }
            }
            Kind.BULLETS, Kind.NUMBERED -> ItemsEditor((value as? Value.Items)?.items.orEmpty(), f.kind == Kind.NUMBERED,
                if (f.id == "top3") 3 else 1) { onChange(Value.Items(it)) }
            Kind.CHECKLIST -> ChecksEditor((value as? Value.Checks)?.items.orEmpty()) { onChange(Value.Checks(it)) }
            else -> {}
        }
    }
}

@Composable
private fun LabelRow(f: LogField) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        SmallLabel(f.label)
        if (f.custom) Text("CUSTOM", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Primary,
            modifier = Modifier.padding(start = 8.dp, bottom = 6.dp).clip(RoundedCornerShape(7.dp)).background(PurpleTint)
                .padding(horizontal = 7.dp, vertical = 2.dp))
    }
}

@Composable
fun Toggle(on: Boolean, onChange: (Boolean) -> Unit) {
    Box(
        Modifier.size(46.dp, 26.dp).clip(CircleShape).background(if (on) Primary else Outline).hand().clickable { onChange(!on) }.padding(3.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart
    ) { Box(Modifier.size(20.dp).clip(CircleShape).background(if (on) Color.White else Muted)) }
}

@Composable
private fun ItemsEditor(items: List<String>, numbered: Boolean, minRows: Int, onChange: (List<String>) -> Unit) {
    val rows = items.toMutableList()
    while (rows.size < minRows) rows.add("")
    if (rows.last().isNotBlank()) rows.add("")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEachIndexed { i, s ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (numbered) "${i + 1}" else "•", color = Primary, fontWeight = FontWeight.Bold, modifier = Modifier.width(14.dp))
                Field(s, { nv -> onChange(rows.toMutableList().also { it[i] = nv }) }, Modifier.weight(1f),
                    if (i == rows.lastIndex) "Add another…" else "")
            }
        }
    }
}

@Composable
private fun ChecksEditor(items: List<Pair<Boolean, String>>, onChange: (List<Pair<Boolean, String>>) -> Unit) {
    val rows = items.toMutableList()
    if (rows.isEmpty() || rows.last().second.isNotBlank()) rows.add(false to "")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEachIndexed { i, (done, s) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val shape = RoundedCornerShape(6.dp)
                Box(Modifier.size(20.dp).clip(shape).background(if (done) Primary else Color.Transparent).border(2.dp, Primary, shape)
                    .hand().clickable { onChange(rows.toMutableList().also { it[i] = !done to s }) },
                    contentAlignment = Alignment.Center) { if (done) Text("✓", color = OnPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                Field(s, { nv -> onChange(rows.toMutableList().also { it[i] = done to nv }) }, Modifier.weight(1f),
                    if (i == rows.lastIndex) "Add another…" else "")
            }
        }
    }
}

@Composable
private fun AddSectionDialog(
    headings: List<String>, defaultAfter: String?, onDismiss: () -> Unit,
    onCreate: (String, Kind, Slot, List<String>, String?) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(Kind.BULLETS) }
    var slot by remember { mutableStateOf(Slot.EVENING) }
    var choices by remember { mutableStateOf("Low, Medium, High") }
    var after by remember { mutableStateOf(defaultAfter) }
    var menu by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        CaptureTheme {
            Column(Modifier.width(480.dp).clip(RoundedCornerShape(18.dp)).background(Popup).border(1.dp, Outline, RoundedCornerShape(18.dp)).padding(22.dp)) {
                Text("New section", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextMain, modifier = Modifier.padding(bottom = 14.dp))
                SmallLabel("Name")
                Field(title, { title = it }, Modifier.fillMaxWidth().padding(bottom = 12.dp), "🧘 Gratitude")
                SmallLabel("Type")
                Row(Modifier.padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Short text" to Kind.TEXT, "Bullets" to Kind.BULLETS, "Checklist" to Kind.CHECKLIST, "Choice" to Kind.CHOICE, "Yes / no" to Kind.YESNO)
                        .forEach { (l, k) -> Chip(l, kind == k, small = true) { kind = k } }
                }
                if (kind == Kind.CHOICE) {
                    SmallLabel("Options, comma separated")
                    Field(choices, { choices = it }, Modifier.fillMaxWidth().padding(bottom = 12.dp))
                }
                SmallLabel("Shows in")
                Row(Modifier.padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Morning" to Slot.MORNING, "Evening" to Slot.EVENING, "Anytime" to Slot.ANYTIME)
                        .forEach { (l, s) -> Chip(l, slot == s, small = true) { slot = s } }
                }
                SmallLabel("Place it after")
                Box {
                    Text("${after ?: "End of note"} ▾", color = TextMain, fontSize = 14.sp, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(Surface1).border(1.5.dp, Outline, RoundedCornerShape(12.dp)).hand().clickable { menu = true }.padding(12.dp))
                    DropdownMenu(menu, { menu = false }, Modifier.background(Menu)) {
                        headings.forEach { h -> DropdownMenuItem({ Text(h) }, { after = h; menu = false }) }
                    }
                }
                Text("Adds a ## heading to your Daily Note template and to today's note. Past notes aren't changed.",
                    fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 10.dp, bottom = 16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                    TextAction("Cancel", Muted, onClick = onDismiss)
                    PrimaryButton("Create section", enabled = title.isNotBlank() && (kind != Kind.CHOICE || choices.isNotBlank())) {
                        val opts = if (kind == Kind.CHOICE) choices.split(",").map { it.trim() }.filter { it.isNotEmpty() } else emptyList()
                        onCreate(title.trim(), kind, slot, opts, after)
                    }
                }
            }
        }
    }
}
