package uk.tomlowndes.capture

import android.net.Uri
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val CAPS = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)

@Composable
fun LogScreen(vault: Uri?, prefs: AppPrefs, snack: SnackbarHostState, slot: String?) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember(slot) {
        mutableStateOf(when (slot) { "morning" -> 0; "evening" -> 1; else -> if (LocalTime.now().hour < 15) 0 else 1 })
    }
    var fields by remember { mutableStateOf<List<LogField>>(emptyList()) }
    var headings by remember { mutableStateOf<List<String>>(emptyList()) }
    var noteName by remember { mutableStateOf("") }
    val values = remember { mutableStateMapOf<String, Value>() }
    // What today's note held when the form was filled from it: Save only writes fields that differ from this.
    var inNote by remember { mutableStateOf<Map<String, Value>>(emptyMap()) }
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }

    fun load() {
        val uri = vault ?: return
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val s = DailyLogStore(ctx, uri, prefs)
                    val f = s.fields()
                    Triple(f, s.read(f), s.headings() to s.todayName())
                }
            }
            r.onSuccess { (f, v, hn) ->
                fields = f; values.clear(); values.putAll(v); inNote = v; headings = hn.first; noteName = hn.second; loaded = true
            }.onFailure { snack.showSnackbar("Couldn't read the daily note: ${it.message}") }
        }
    }
    LaunchedEffect(vault) { load() }

    val visible = fields.filter { f ->
        when (tab) { 0 -> f.slot != Slot.EVENING; 1 -> f.slot != Slot.MORNING; else -> true }
    }

    fun save() {
        val uri = vault ?: return
        if (busy) return
        busy = true
        val snapshot = values.toMap()
        scope.launch {
            val base = inNote
            val r = withContext(Dispatchers.IO) { runCatching { DailyLogStore(ctx, uri, prefs).save(visible, snapshot, base) } }
            busy = false
            r.onSuccess { saved ->
                inNote = inNote + saved.inNote
                if (saved.skipped.isEmpty() && saved.created) snack.showSnackbar(
                    "Saved. $noteName wasn't on the phone yet, so it was created here. Open Obsidian first next time so it can sync."
                )
                else if (saved.skipped.isEmpty()) snack.showSnackbar("Saved to $noteName")
                else snack.showSnackbar(
                    "Not saved: ${saved.skipped.joinToString { it.label }} changed on another device. Save again to replace it."
                )
            }.onFailure { snack.showSnackbar("Failed: ${it.message}") }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp).verticalScroll(rememberScrollState())) {
        Column(Modifier.padding(top = 8.dp)) {
            ScreenTitle(
                "Daily log",
                if (vault == null) "Pick your vault in Settings"
                else LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.UK)) +
                    if (noteName.isNotEmpty()) " · saves into $noteName" else ""
            )
        }
        PillSwitch(listOf("☀ Morning", "🌙 Evening", "All"), tab) { tab = it }
        Box(Modifier.height(16.dp))

        if (vault != null && loaded && visible.isEmpty()) {
            Text("No sections found. Check the Daily Note template path in Obsidian's daily notes settings.", color = Muted)
        }
        visible.forEach { f -> FieldEditor(f, values[f.id]) { values[f.id] = it } }

        Button(
            onClick = ::save, enabled = vault != null && loaded && !busy, shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = OnPrimary),
            modifier = Modifier.fillMaxWidth().height(54.dp).padding(top = 0.dp)
        ) {
            Text(if (busy) "Saving…" else when (tab) { 0 -> "Save morning log"; 1 -> "Save evening log"; else -> "Save log" },
                fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
        Box(
            Modifier.padding(top = 14.dp, bottom = 24.dp).fillMaxWidth().height(50.dp).clip(CircleShape)
                .border(1.5.dp, Outline, CircleShape).clickable(enabled = vault != null && loaded) { adding = true },
            contentAlignment = Alignment.Center
        ) { Text("＋ Add section", color = Primary, fontWeight = FontWeight.SemiBold) }
    }

    if (adding) AddSectionSheet(
        headings, defaultAfter = headings.firstOrNull { Md.norm(it) == "winddown" } ?: headings.lastOrNull(),
        onDismiss = { adding = false },
        onCreate = { title, kind, slotSel, choices, after ->
            val uri = vault ?: return@AddSectionSheet
            scope.launch {
                val r = withContext(Dispatchers.IO) { runCatching { DailyLogStore(ctx, uri, prefs).addSection(title, kind, slotSel, choices, after) } }
                r.onSuccess { adding = false; load(); snack.showSnackbar("Added “$title” to your template") }
                    .onFailure { snack.showSnackbar(it.message ?: "Failed") }
            }
        }
    )
}

// ---------------------------------------------------------------- field widgets

@Composable
private fun FieldEditor(f: LogField, value: Value?, onChange: (Value) -> Unit) {
    when (f.kind) {
        Kind.YESNO -> Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            LabelRow(f)
            AppSwitch((value as? Value.Flag)?.on ?: false) { onChange(Value.Flag(it)) }
        }
        else -> Column(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
            LabelRow(f)
            when (f.kind) {
                Kind.TEXT -> OutlinedTextField(
                    (value as? Value.Text)?.s.orEmpty(), { onChange(Value.Text(it)) }, placeholder = { Text("Tap to write…") },
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = fieldColors(), keyboardOptions = CAPS
                )
                Kind.CHOICE -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    f.choices.forEach { c ->
                        AppChip((value as? Value.Text)?.s.equals(c, true), { onChange(Value.Text(c)) }, { Text(c) },
                            shape = RoundedCornerShape(12.dp))
                    }
                }
                Kind.BULLETS, Kind.NUMBERED -> ItemsEditor((value as? Value.Items)?.items.orEmpty(), f.kind == Kind.NUMBERED,
                    if (f.id == "top3") 3 else 1) { onChange(Value.Items(it)) }
                Kind.CHECKLIST -> ChecksEditor((value as? Value.Checks)?.items.orEmpty()) { onChange(Value.Checks(it)) }
                else -> {}
            }
        }
    }
}

@Composable
private fun LabelRow(f: LogField) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = if (f.kind == Kind.YESNO) 0.dp else 6.dp)) {
        Text(f.label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Primary)
        if (f.custom) Text("CUSTOM", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Primary,
            modifier = Modifier.padding(start = 8.dp).clip(RoundedCornerShape(7.dp)).background(Color(0xFF2A2250)).padding(horizontal = 7.dp, vertical = 2.dp))
    }
}

@Composable
private fun ItemsEditor(items: List<String>, numbered: Boolean, minRows: Int, onChange: (List<String>) -> Unit) {
    val rows = items.toMutableList()
    while (rows.size < minRows) rows.add("")
    if (rows.last().isNotBlank()) rows.add("")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEachIndexed { i, s ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (numbered) "${i + 1}" else "•", color = Primary, fontWeight = FontWeight.Bold, modifier = Modifier.width(16.dp))
                OutlinedTextField(
                    s, { nv -> onChange(rows.toMutableList().also { it[i] = nv }) },
                    placeholder = { Text(if (s.isBlank() && i == rows.lastIndex) "Add another…" else "") }, singleLine = true,
                    modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp), colors = fieldColors(), keyboardOptions = CAPS
                )
            }
        }
    }
}

@Composable
private fun ChecksEditor(items: List<Pair<Boolean, String>>, onChange: (List<Pair<Boolean, String>>) -> Unit) {
    val rows = items.toMutableList()
    if (rows.isEmpty() || rows.last().second.isNotBlank()) rows.add(false to "")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEachIndexed { i, (done, s) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Checkbox(done, { c -> onChange(rows.toMutableList().also { it[i] = c to s }) },
                    colors = CheckboxDefaults.colors(checkedColor = Primary, uncheckedColor = Primary, checkmarkColor = OnPrimary))
                OutlinedTextField(
                    s, { nv -> onChange(rows.toMutableList().also { it[i] = done to nv }) },
                    placeholder = { Text(if (s.isBlank() && i == rows.lastIndex) "Add another…" else "") }, singleLine = true,
                    modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp), colors = fieldColors(), keyboardOptions = CAPS
                )
            }
        }
    }
}

// ---------------------------------------------------------------- add section

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddSectionSheet(
    headings: List<String>, defaultAfter: String?, onDismiss: () -> Unit,
    onCreate: (String, Kind, Slot, List<String>, String?) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(Kind.BULLETS) }
    var slot by remember { mutableStateOf(Slot.EVENING) }
    var choices by remember { mutableStateOf("Low, Medium, High") }
    var after by remember { mutableStateOf(defaultAfter) }
    var menu by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color(0xFF1B1827)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState())) {
            Text("New section", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 14.dp))
            SmallLabel("Name")
            OutlinedTextField(title, { title = it }, placeholder = { Text("🧘 Gratitude") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), shape = RoundedCornerShape(16.dp), colors = fieldColors(), keyboardOptions = CAPS)

            SmallLabel("Type")
            ChipRow(
                listOf("Short text" to Kind.TEXT, "Bullets" to Kind.BULLETS, "Checklist" to Kind.CHECKLIST, "Choice" to Kind.CHOICE, "Yes / no" to Kind.YESNO),
                kind
            ) { kind = it }
            if (kind == Kind.CHOICE) OutlinedTextField(choices, { choices = it }, label = { Text("Options, comma separated") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), shape = RoundedCornerShape(16.dp), colors = fieldColors())

            Box(Modifier.height(12.dp))
            SmallLabel("Shows in")
            ChipRow(listOf("Morning" to Slot.MORNING, "Evening" to Slot.EVENING, "Anytime" to Slot.ANYTIME), slot) { slot = it }

            Box(Modifier.height(12.dp))
            SmallLabel("Place it after")
            Box {
                Text("${after ?: "End of note"} ▾", modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surface1)
                    .clickable { menu = true }.padding(14.dp))
                DropdownMenu(menu, { menu = false }, modifier = Modifier.background(Surface2)) {
                    headings.forEach { h -> DropdownMenuItem(text = { Text(h) }, onClick = { after = h; menu = false }) }
                }
            }
            Text(
                "Adds a ## heading to your Daily Note template and to today's note. Past notes aren't changed.",
                fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 10.dp, bottom = 14.dp)
            )
            Button(
                onClick = {
                    val opts = if (kind == Kind.CHOICE) choices.split(",").map { it.trim() }.filter { it.isNotEmpty() } else emptyList()
                    onCreate(title.trim(), kind, slot, opts, after)
                },
                enabled = title.isNotBlank() && (kind != Kind.CHOICE || choices.isNotBlank()), shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = OnPrimary),
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) { Text("Create section", fontWeight = FontWeight.Bold) }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun <T> ChipRow(options: List<Pair<String, T>>, selected: T, onSelect: (T) -> Unit) {
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (label, v) ->
            AppChip(v == selected, { onSelect(v) }, { Text(label) }, shape = RoundedCornerShape(12.dp))
        }
    }
}
