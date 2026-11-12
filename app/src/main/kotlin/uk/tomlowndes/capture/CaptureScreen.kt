package uk.tomlowndes.capture

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Capture: Task, Read later, or Jarvis (talk or type). When Jarvis sorts a ramble into tasks, ideas or a tidied
 * note, it waits in [AppPrefs.jarvisFound] until Check and save. Nothing reaches the vault until Save.
 */
@Composable
fun CaptureScreen(vault: Uri?, prefs: AppPrefs, snack: SnackbarHostState, onScan: () -> Unit = {}, onPrintToday: () -> Unit = {}, onOpenSettings: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableIntStateOf(prefs.captureMode.coerceIn(0, 2)) }
    var found by remember { mutableStateOf(prefs.jarvisFound) }
    var checking by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var waiting by remember { mutableIntStateOf(0) }
    fun refreshWaiting() { scope.launch { waiting = withContext(Dispatchers.IO) { PhoneOutbox.pendingCount(ctx) } } }
    LaunchedEffect(Unit) { refreshWaiting() }

    fun setFound(r: SortResult?) { found = r; prefs.jarvisFound = r }

    val pill: @Composable () -> Unit = {
        Column {
            PillSwitch(listOf("Task", "Read later", "Jarvis"), mode) { mode = it; prefs.captureMode = it }
            if (waiting > 0) Text(
                if (waiting == 1) "1 thing waiting to save or send. It will be tried again on its own."
                else "$waiting things waiting to save or send. They will be tried again on their own.",
                fontSize = 12.5.sp, color = Muted, modifier = Modifier.padding(top = 8.dp)
            )
        }
    }

    val f = found
    if (checking && f != null) {
        BackHandler { checking = false }
        CheckAndSave(
            f,
            onBack = { checking = false },
            onDiscard = { setFound(null); checking = false },
            onSave = { r ->
                val uri = vault ?: run { scope.launch { snack.showSnackbar("Pick your vault folder in Settings first") }; return@CheckAndSave }
                if (saving) return@CheckAndSave
                saving = true
                scope.launch {
                    withContext(Dispatchers.IO) { runCatching { Sorting.save(r, VaultWriter(ctx, uri), IdeaStore(ctx, uri, prefs)) } }
                        .onSuccess {
                            withContext(Dispatchers.IO) { Outbox(java.io.File(ctx.filesDir, "outbox.json")).succeed(Outbox.id(Outbox.SAVE_SORT, Sorting.toJson(r))) }
                            setFound(null); checking = false; snack.showSnackbar(if (r.noteTitle != null) "Saved · note in ${VaultWriter.INBOX}" else "Saved")
                        }
                        .onFailure {
                            PhoneOutbox.keepSort(ctx, r)
                            refreshWaiting()
                            snack.showSnackbar("Couldn't save that. It's kept on the phone and will be tried again.")
                        }
                    saving = false
                }
            }
        )
        return
    }

    if (mode == 2) {
        JarvisScreen(prefs, snack, onOpenSettings, title = "Capture", header = pill, found = found,
            // Tasks from several answers wait on one Check and save card until it's dealt with.
            onFound = { r -> setFound(found?.let { o -> if (r.note == null && o.note == null) o.copy(tasks = o.tasks + r.tasks, ideas = o.ideas + r.ideas) else null } ?: r) },
            onCheck = { checking = true }, onPrintToday = onPrintToday, onQueued = { refreshWaiting() })
        return
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Capture", fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp, modifier = Modifier.weight(1f))
            // Scan a notebook page (paper-hybrid) as an icon, tidy-v1.
            Text("📷", fontSize = 20.sp, modifier = Modifier.clip(CircleShape).clickable(onClick = onScan).padding(8.dp))
        }
        pill()
        Box(Modifier.height(14.dp))
        QuickAdd(vault, link = mode == 1, snack = snack, onQueued = { refreshWaiting() })
        Box(Modifier.height(16.dp))
    }
}

@Composable
private fun CheckAndSave(result: SortResult, onBack: () -> Unit, onDiscard: () -> Unit, onSave: (SortResult) -> Unit) {
    var r by remember(result) { mutableStateOf(result) }
    var editing by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = Muted) }
            Text("Check and save", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        val title = r.noteTitle
        r.note?.let { note ->
            if (title != null) {
                GroupLabel("Note")
                NoteCard(title, note, onEdit = { editing = true }, onRemove = { r = r.copy(note = null, noteTitle = null) })
            } else {
                GroupLabel("Note")
                ListCard(Modifier.padding(top = 6.dp)) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(note, fontSize = 13.5.sp, modifier = Modifier.weight(1f))
                        Text("✕", color = Muted, fontSize = 14.sp, modifier = Modifier.clickable { r = r.copy(note = null) }.padding(start = 10.dp))
                    }
                }
            }
        }
        if (editing && title != null) EditNoteDialog(title, r.note.orEmpty(), onDismiss = { editing = false }) { t, md ->
            val body = md.trim().ifEmpty { null }
            r = r.copy(note = body, noteTitle = if (body == null) null else Sorting.safeTitle(t) ?: title)
            editing = false
        }
        if (r.tasks.isNotEmpty()) {
            GroupLabel("Tasks · ${r.tasks.size}")
            ListCard(Modifier.padding(top = 6.dp)) {
                r.tasks.forEachIndexed { i, t ->
                    if (i > 0) HorizontalDivider(color = Outline)
                    SortedRow(
                        work = t.tag in TaskViews.WORK_TAGS, text = t.text, tag = t.tag,
                        trailing = t.due?.let { d -> dueChip(d) },
                        onRemove = { r = r.copy(tasks = r.tasks - t) }
                    )
                }
            }
        }
        if (r.ideas.isNotEmpty()) {
            GroupLabel("Project idea · ${r.ideas.size}")
            ListCard(Modifier.padding(top = 6.dp)) {
                r.ideas.forEachIndexed { i, idea ->
                    if (i > 0) HorizontalDivider(color = Outline)
                    SortedRow(work = false, text = idea, tag = null, trailing = "idea", onRemove = { r = r.copy(ideas = r.ideas - idea) })
                }
            }
        }
        if (r.isEmpty) Text("Nothing left to save", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 12.dp))
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Discard", color = Muted, fontSize = 14.sp, textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).clip(CircleShape).border(1.dp, Outline, CircleShape).clickable(onClick = onDiscard).padding(vertical = 13.dp))
            Text("Save", color = OnPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).clip(CircleShape).background(if (r.isEmpty) Primary.copy(alpha = 0.4f) else Primary)
                    .clickable(enabled = !r.isEmpty) { onSave(r) }.padding(vertical = 13.dp))
        }
    }
}

/** A tidied note from a ramble: title, the start of the note, where it will be saved, and Edit. */
@Composable
private fun NoteCard(title: String, markdown: String, onEdit: () -> Unit, onRemove: () -> Unit) {
    var all by remember { mutableStateOf(false) }
    val lines = markdown.lines()
    val long = lines.size > 12
    Column(
        Modifier.padding(top = 6.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surface1)
            .border(1.dp, Outline, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("✕", color = Muted, fontSize = 14.sp, modifier = Modifier.clickable(onClick = onRemove).padding(start = 10.dp))
        }
        (if (long && !all) lines.take(12) else lines).forEach { l ->
            val t = l.trimEnd()
            when {
                t.isBlank() -> Box(Modifier.height(4.dp))
                t.startsWith("#") -> Text(t.trimStart('#').trim(), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
                t.trimStart().startsWith("- ") || t.trimStart().startsWith("* ") ->
                    Text("• " + t.trimStart().drop(2), fontSize = 12.5.sp, color = Muted, lineHeight = 18.sp, modifier = Modifier.padding(start = 6.dp))
                else -> Text(t, fontSize = 12.5.sp, color = Muted, lineHeight = 18.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
        if (long) Text(if (all) "Show less" else "Show all", fontSize = 12.5.sp, color = Primary,
            modifier = Modifier.clickable { all = !all }.padding(top = 6.dp))
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("→ ${VaultWriter.INBOX}/$title.md", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Primary,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("Edit ✎", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Primary,
                modifier = Modifier.clickable(onClick = onEdit).padding(start = 10.dp))
        }
        Text("Your own words are kept, folded at the bottom of the note.", fontSize = 11.5.sp, color = Muted, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun EditNoteDialog(title: String, markdown: String, onDismiss: () -> Unit, onDone: (String, String) -> Unit) {
    var t by remember { mutableStateOf(title) }
    var md by remember { mutableStateOf(markdown) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Surface1,
        title = { Text("Edit note") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(t, { t = it }, singleLine = true, label = { Text("Title") }, colors = fieldColors(), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(md, { md = it }, minLines = 8, maxLines = 16, colors = fieldColors(), modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = { TextButton(onClick = { onDone(t, md) }, enabled = t.isNotBlank()) { Text("Done", color = Primary) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Muted) } }
    )
}

private fun dueChip(d: LocalDate): String {
    val today = LocalDate.now()
    return when (d) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        else -> d.format(DateTimeFormatter.ofPattern("EEE d", Locale.UK))
    }
}

@Composable
private fun SortedRow(work: Boolean, text: String, tag: String?, trailing: String?, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(4.dp).fillMaxHeight().clip(RoundedCornerShape(topEnd = 3.dp, bottomEnd = 3.dp)).background(if (work) WorkEdge else PersonalEdge))
        Row(Modifier.weight(1f).padding(start = 12.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, fontSize = 13.5.sp, modifier = Modifier.weight(1f, fill = false))
            tag?.let { Text(" $it", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = if (it == "#bill") Amber else Purple) }
        }
        trailing?.let {
            Text(it, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Muted,
                modifier = Modifier.clip(CircleShape).border(1.dp, Outline, CircleShape).padding(horizontal = 7.dp, vertical = 2.dp))
        }
        Text("✕", color = Muted, fontSize = 14.sp, modifier = Modifier.clickable(onClick = onRemove).padding(start = 10.dp))
    }
}

private data class Added(val isLink: Boolean, val text: String, val sub: String)

private enum class CaptureSheet { KIND, DATE, TIME, REPEAT, TAGS }

/** A small chip along the bottom of the capture box; [on] when it holds a value. */
@Composable
private fun ToolChip(label: String, on: Boolean, onClick: () -> Unit) {
    Text(
        label, fontSize = 12.5.sp, maxLines = 1, color = if (on) Primary else Muted,
        modifier = Modifier.clip(CircleShape).background(if (on) PrimaryTint else Color.Transparent)
            .border(1.dp, if (on) Primary else Outline, CircleShape).clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    )
}

/** Task and Read later, as Capture had them before Flight Deck: one field, due chips for tasks, Just added. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickAdd(vault: Uri?, link: Boolean, snack: SnackbarHostState, onQueued: () -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val history = remember { mutableStateListOf<Added>() }
    var text by remember(link) { mutableStateOf("") }
    var due by remember { mutableStateOf<LocalDate?>(null) }
    var minutes by remember { mutableStateOf<Int?>(null) }
    var repeat by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<CaptureSheet?>(null) }
    var busy by remember { mutableStateOf(false) }
    // Tags picked on the Tag row; ones typed as #word in the box count too.
    var picked by remember { mutableStateOf<List<String>>(emptyList()) }
    // Rapid log (paper-hybrid-v1): the kind chip, overridden by a mark typed at the start of the line.
    var kindPick by remember { mutableStateOf(LogKind.TASK) }
    val today0 = LocalDate.now()
    val entry = if (link) null else RapidLog.parse(text, today0, kindPick)
    val kind = entry?.kind ?: LogKind.TASK
    val tagData = rememberTagData(vault)
    // A link is saved as typed, so only the chips tag it.
    val typedTags = if (link) emptyList() else Tags.inText(text)
    val onTags = (picked + typedTags).distinct()

    fun toggleTag(tag: String) {
        when {
            tag in typedTags -> text = Tags.remove(text, tag)
            tag in picked -> picked = picked - tag
            else -> picked = picked + tag
        }
    }

    /** Notes and events go to the inbox note as one line each. */
    fun submitLog(e: LogEntry) {
        val uri = vault ?: run { scope.launch { snack.showSnackbar("Pick your vault folder in Settings first") }; return }
        busy = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { VaultWriter(ctx, uri).appendInbox(listOf(RapidLog.inboxLine(e))) } }
            busy = false
            r.onSuccess {
                history.add(0, Added(false, e.text, "${e.kind.mark} ${e.kind.label.replaceFirstChar { it.uppercase() }} · ${VaultWriter.INBOX}/Inbox"))
                text = ""; picked = emptyList()
                snack.showSnackbar(if (e.kind == LogKind.EVENT) "Event logged" else "Note logged")
            }.onFailure {
                PhoneOutbox.keepLines(ctx, listOf(RapidLog.inboxLine(e)))
                onQueued()
                snack.showSnackbar("Couldn't save that. It's kept on the phone and will be tried again.")
            }
        }
    }

    fun submit() {
        if (busy) return
        if (entry != null && !entry.kind.isTask) { if (entry.text.isNotBlank()) submitLog(entry); return }
        // Typed date and estimate fill in what the chips left empty; the mark and those words come off the task.
        val value = (entry?.text ?: text).trim()
        val uri = vault
        if (value.isEmpty() || busy) return
        if (uri == null) { scope.launch { snack.showSnackbar("Pick your vault folder in Settings first") }; return }
        busy = true
        val wasRule = repeat
        // A repeating task needs a first date for the series to count from.
        val wasDue = due ?: entry?.due ?: if (wasRule != null) LocalDate.now() else null
        val wasMin = minutes ?: entry?.minutes
        val star = if (entry?.kind == LogKind.PRIORITY) " ⏫" else ""
        val wasTags = picked.filter { it !in typedTags }
        val wasTyped = typedTags
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    if (link) {
                        val url = if (Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(value)) value else "https://$value"
                        ReadLater.save(ctx, uri, url, null, wasTags)
                    } else { VaultWriter(ctx, uri).addTask(Tags.withTags(value, wasTags) + (wasMin?.let { " ⏱ ${it}m" } ?: "") + star + (wasRule?.let { " 🔁 $it" } ?: ""), wasDue?.toString()); true }
                }
            }
            busy = false
            r.onSuccess { full ->
                val tagNote = (wasTags + wasTyped).distinct().joinToString("") { " · ${Tags.label(it)}" }
                history.add(0, Added(link, if (link) value else Tags.strip(value).ifBlank { value },
                    (if (link) (if (full) "Read later · full page" else "Read later · link only")
                    else "Task" + (wasDue?.let { " · due ${dueLabel(it)}" } ?: "") + (wasMin?.let { " · ${TaskRules.minutesLabel(it)}" } ?: "") +
                        (wasRule?.let { " · ↻ $it" } ?: "")) + tagNote))
                text = ""; due = null; minutes = null; repeat = null; picked = emptyList(); kindPick = LogKind.TASK
                snack.showSnackbar(if (!link) "Task added" else if (full) "Saved page to Read It Later" else "Saved link only (couldn't fetch the page)")
            }.onFailure {
                if (link) {
                    val url = if (Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(value)) value else "https://$value"
                    PhoneOutbox.keepLink(ctx, url, wasTags)
                } else {
                    PhoneOutbox.keepTask(ctx, Tags.withTags(value, wasTags) + (wasMin?.let { " ⏱ ${it}m" } ?: "") + star + (wasRule?.let { " 🔁 $it" } ?: ""), wasDue?.toString())
                }
                onQueued()
                snack.showSnackbar("Couldn't save that. It's kept on the phone and will be tried again.")
            }
        }
    }

    // The text box is the hero (tidy-v1): kind, date, time, repeat and tags sit as chips along its bottom edge.
    val canSend = text.isNotBlank() && !busy
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Surface1)
            .border(1.5.dp, if (text.isNotBlank()) Primary else Outline, RoundedCornerShape(20.dp))
            .padding(start = 4.dp, end = 10.dp, top = 4.dp, bottom = 10.dp)
    ) {
        OutlinedTextField(
            value = text, onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(if (link) "Paste a link" else when (kind) {
                LogKind.NOTE -> "A thought, a quote, anything"
                LogKind.EVENT -> "What happened, or what's on"
                else -> "What needs doing? Try \"fri 15m\""
            }) },
            minLines = if (link) 1 else 3,
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp),
            keyboardOptions = if (link) KeyboardOptions(keyboardType = KeyboardType.Uri) else KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent, cursorColor = Primary
            )
        )
        HorizontalDivider(color = Outline, modifier = Modifier.padding(start = 10.dp, bottom = 10.dp))
        Row(Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!link) ToolChip("${kind.mark} ${kind.label.replaceFirstChar { it.uppercase() }}", kind != LogKind.TASK) { sheet = CaptureSheet.KIND }
                if (!link && kind.isTask) {
                    ToolChip(due?.let { dueLabel(it) } ?: "Date", due != null) { sheet = CaptureSheet.DATE }
                    ToolChip(minutes?.let { "⏱ " + TaskRules.minutesLabel(it) } ?: "⏱", minutes != null) { sheet = CaptureSheet.TIME }
                    ToolChip(if (repeat != null) "↻ on" else "↻", repeat != null) { sheet = CaptureSheet.REPEAT }
                }
                ToolChip(if (onTags.isEmpty()) "#" else onTags.joinToString(" ") { Tags.label(it) }, onTags.isNotEmpty()) { sheet = CaptureSheet.TAGS }
            }
            Box(
                Modifier.padding(start = 8.dp).size(38.dp).clip(CircleShape).background(if (canSend) Primary else Primary.copy(alpha = 0.35f))
                    .clickable(enabled = canSend) { submit() },
                contentAlignment = Alignment.Center
            ) { Text("↑", color = OnPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp) }
        }
    }
    if (entry != null && text.isNotBlank()) {
        val bits = listOfNotNull(
            "${entry.kind.mark} ${entry.kind.label}",
            entry.due?.takeIf { due == null }?.let { dueChip(it) },
            entry.minutes?.takeIf { minutes == null }?.let { TaskRules.minutesLabel(it) },
        ) + Tags.inText(text)
        Text("→ " + bits.joinToString(" · "), fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Green,
            modifier = Modifier.padding(top = 8.dp, start = 4.dp))
    }
    if (sheet != null) ModalBottomSheet(onDismissRequest = { sheet = null }, containerColor = Bg) {
        Column(Modifier.padding(start = 18.dp, end = 18.dp, bottom = 28.dp)) {
            when (sheet) {
                CaptureSheet.KIND -> KindChips(kind) { kindPick = it; text = RapidLog.mark(text).second; sheet = null }
                CaptureSheet.DATE -> {
                    val today = LocalDate.now()
                    val custom = due != null && due != today && due != today.plusDays(1)
                    Text("Due", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppChip(due == today, { due = if (due == today) null else today; sheet = null }, { Text("Today") })
                        AppChip(due == today.plusDays(1), { due = if (due == today.plusDays(1)) null else today.plusDays(1); sheet = null }, { Text("Tomorrow") })
                        AppChip(custom, { picking = true; sheet = null }, { Text(if (custom) dueLabel(due!!) else "Pick date") })
                    }
                }
                CaptureSheet.TIME -> {
                    Text("How long?", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ESTIMATES.forEach { m -> AppChip(minutes == m, { minutes = if (minutes == m) null else m; sheet = null }, { Text(TaskRules.minutesLabel(m)) }) }
                    }
                }
                CaptureSheet.REPEAT -> RepeatPicker(repeat, due, inlineCustom = true) { repeat = it }
                CaptureSheet.TAGS -> TagRow(tagData, onTags, ::toggleTag)
                null -> {}
            }
        }
    }
    if (history.isNotEmpty()) {
        Box(Modifier.height(16.dp))
        GroupLabel("Just added")
        Column(Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surface1).border(1.dp, Outline, RoundedCornerShape(16.dp))) {
            history.take(5).forEachIndexed { i, e ->
                if (i > 0) HorizontalDivider(color = Outline)
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(if (e.isLink) LinkTint else TaskTint), contentAlignment = Alignment.Center) {
                        Text(if (e.isLink) "🔗" else if (e.sub.startsWith("–")) "–" else if (e.sub.startsWith("○")) "○" else "✓")
                    }
                    Column {
                        Text(e.text, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp)
                        Text(e.sub, fontSize = 12.sp, color = Muted)
                    }
                }
            }
        }
    }
    if (picking) PickDateDialog(due, onPick = { due = it; picking = false }, onDismiss = { picking = false })
}

/** • task ○ event – note ★ priority: the rapid-log kind for the line in the box. */
@Composable
private fun KindChips(kind: LogKind, onPick: (LogKind) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        LogKind.values().forEach { k ->
            val on = k == kind
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(if (on) PrimaryTint else Surface1)
                    .border(1.dp, if (on) Primary else Outline, RoundedCornerShape(12.dp)).clickable { onPick(k) }.padding(vertical = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(k.mark, fontSize = 17.sp, color = if (on) Primary else when (k) { LogKind.PRIORITY -> Amber; LogKind.EVENT -> Teal; else -> TextMain })
                Text(k.label, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Muted)
            }
        }
    }
}
