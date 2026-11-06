package uk.tomlowndes.capture.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.tomlowndes.capture.DataViews
import uk.tomlowndes.capture.QueryCell
import uk.tomlowndes.capture.QueryTask
import uk.tomlowndes.capture.QueryView
import uk.tomlowndes.capture.MdBlock
import uk.tomlowndes.capture.MdBlocks
import uk.tomlowndes.capture.NoteChunk
import uk.tomlowndes.capture.NoteChunks
import uk.tomlowndes.capture.NoteEdit
import uk.tomlowndes.capture.NoteIndex
import uk.tomlowndes.capture.NoteInfo
import uk.tomlowndes.capture.NoteTags
import uk.tomlowndes.capture.NoteText
import uk.tomlowndes.capture.Tags
import uk.tomlowndes.capture.VaultBrowse
import uk.tomlowndes.capture.VaultSearch
import uk.tomlowndes.capture.VaultTasks
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private fun parentOf(path: String) = path.substringBeforeLast('/', "")
private fun nameOf(path: String) = path.substringAfterLast('/')

private fun shortDate(millis: Long): String {
    val d = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
    val today = LocalDate.now()
    return when (d) {
        today -> "today"
        today.minusDays(1) -> "yesterday"
        else -> d.format(DateTimeFormatter.ofPattern(if (d.year == today.year) "d MMM" else "d MMM yyyy", Locale.UK))
    }
}

/** One line of the folder tree: a folder (open or shut) or a note, at a depth. */
private data class TreeRow(val path: String, val isFolder: Boolean, val depth: Int, val count: Int = 0)

private fun tree(all: List<NoteInfo>, open: Set<String>, showHidden: Boolean): List<TreeRow> {
    val out = mutableListOf<TreeRow>()
    fun walk(dir: String, depth: Int) {
        val v = VaultBrowse.folder(all, dir, byName = true, showHidden = showHidden)
        v.folders.forEach { (f, n) ->
            out.add(TreeRow(f, true, depth, n))
            if (f in open) walk(f, depth + 1)
        }
        if (dir.isNotEmpty()) v.notes.forEach { out.add(TreeRow(it.path, false, depth)) }
    }
    walk("", 0)
    // Notes at the vault root go after its folders.
    VaultBrowse.folder(all, "", byName = true).notes.forEach { out.add(TreeRow(it.path, false, 0)) }
    return out
}

/** Vault pane: folder tree and search on the left, the note on the right with quick edits in place. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VaultPane(state: AppState) {
    val scope = rememberCoroutineScope()
    var notes by remember { mutableStateOf<List<NoteInfo>?>(null) }
    var query by remember { mutableStateOf("") }
    var tag by remember { mutableStateOf<String?>(null) }
    val open = remember { mutableStateListOf<String>() }
    var showHidden by remember { mutableStateOf(false) }

    LaunchedEffect(state.vault, state.changes) {
        val v = state.vault ?: return@LaunchedEffect
        if (notes == null) notes = state.io("Couldn't read the vault") { NoteIndex.paths(v) }
        state.io("Couldn't read the vault") { NoteIndex.build(v, File(state.prefs.cacheDir, "vault-index.json")) }?.let { notes = it }
    }
    LaunchedEffect(notes) { if (state.vaultNote == null) state.vaultNote = notes?.let { VaultBrowse.recent(it, 1).firstOrNull()?.path } }

    val all = notes.orEmpty()
    val tags = remember(all) { VaultBrowse.tagCounts(all) }
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(300.dp).fillMaxHeight().background(Color(0xFF10141E)).padding(horizontal = 12.dp, vertical = 16.dp)) {
            Field(query, { v ->
                val (left, taken) = VaultSearch.takeTags(v)
                if (taken.isNotEmpty()) tag = taken.last()
                query = left
            }, Modifier.fillMaxWidth().padding(bottom = 10.dp), "🔍  Search vault or #tag", shape = RoundedCornerShape(10.dp), fontSize = 13)
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (Tags.BUILT_IN.filter { b -> tags.any { it.first == b } } + tags.map { it.first }.filter { it !in Tags.BUILT_IN }.take(8))
                    .forEach { t -> TagChip(t, tag == t, tags.firstOrNull { it.first == t }?.second) { tag = if (tag == t) null else t } }
            }
            if (notes == null) Sub("Reading the vault…")
            val searching = query.isNotBlank() || tag != null
            if (searching) {
                val hits = remember(all, query, tag) { VaultSearch.search(all, query, listOfNotNull(tag)) }
                Text("${hits.size} notes" + (tag?.let { " · $it" } ?: ""), fontSize = 11.sp, color = Faint, modifier = Modifier.padding(start = 6.dp, bottom = 6.dp))
                LazyColumn(Modifier.weight(1f)) {
                    items(hits, key = { it.note.path }) { h ->
                        val on = state.vaultNote == h.note.path
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (on) PrimaryTint else Color.Transparent)
                                .hand().clickable { open.clear(); state.vaultNote = h.note.path }.padding(horizontal = 8.dp, vertical = 7.dp)
                        ) {
                            Text(h.note.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (on) Primary else TextMain, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (h.snippet.isNotBlank()) Text(marked(h.snippet, h.marks), fontSize = 12.sp, color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(h.note.folder.ifEmpty { "Vault" }, fontSize = 10.5.sp, color = Faint, fontFamily = FontFamily.Monospace, maxLines = 1)
                        }
                    }
                }
            } else {
                val expanded = remember { mutableStateListOf<String>() }
                LaunchedEffect(state.vaultNote) {
                    // Open the folders down to the note shown, so it can be seen in the tree.
                    var p = parentOf(state.vaultNote ?: return@LaunchedEffect)
                    while (p.isNotEmpty()) { if (p !in expanded) expanded.add(p); p = parentOf(p) }
                }
                val rows = remember(all, expanded.toList(), showHidden) { tree(all, expanded.toSet(), showHidden) }
                LazyColumn(Modifier.weight(1f)) {
                    items(rows, key = { (if (it.isFolder) "f-" else "n-") + it.path }) { r ->
                        val on = !r.isFolder && state.vaultNote == r.path
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (on) PrimaryTint else Color.Transparent).hand()
                                .clickable {
                                    if (r.isFolder) { if (r.path in expanded) expanded.remove(r.path) else expanded.add(r.path) }
                                    else { open.clear(); state.vaultNote = r.path }
                                }
                                .padding(start = (8 + r.depth * 16).dp, end = 8.dp, top = 5.dp, bottom = 5.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (r.isFolder) Text(if (r.path in expanded) "▾" else "▸", fontSize = 11.sp, color = Muted, modifier = Modifier.width(10.dp))
                            Text(if (r.isFolder) nameOf(r.path) else nameOf(r.path).removeSuffix(".md"), fontSize = 13.sp,
                                color = if (on) Primary else if (r.isFolder) TextMain else Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f))
                            if (r.isFolder) Text("${r.count}", fontSize = 10.5.sp, color = Faint, fontFamily = FontFamily.Monospace)
                        }
                    }
                    item {
                        TextAction(if (showHidden) "Hide 99 folders" else "Show hidden folders", Muted, Modifier.padding(top = 10.dp)) { showHidden = !showHidden }
                    }
                }
            }
        }
        Box(Modifier.fillMaxHeight().width(1.dp).background(Divider))
        Box(Modifier.weight(1f).fillMaxHeight()) {
            val path = open.lastOrNull() ?: state.vaultNote
            if (path == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Sub("Pick a note on the left.") }
            else NoteView(state, path, all, tags, canGoBack = open.isNotEmpty(), onBack = { open.removeAt(open.size - 1) },
                onOpen = { open.add(it) }) { info -> notes = all.filter { it.path != info.path } + info }
        }
    }
}

private fun marked(s: String, marks: List<IntRange>): AnnotatedString = buildAnnotatedString {
    var at = 0
    for (r in marks) {
        if (r.first < at || r.last >= s.length) continue
        append(s.substring(at, r.first))
        withStyle(SpanStyle(color = Amber, background = Color(0xFF3A3220))) { append(s.substring(r.first, r.last + 1)) }
        at = r.last + 1
    }
    append(s.substring(at))
}

private data class Clash(val theirs: String, val mine: String, val myLines: String)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NoteView(
    state: AppState, path: String, all: List<NoteInfo>, known: List<Pair<String, Int>>, canGoBack: Boolean,
    onBack: () -> Unit, onOpen: (String) -> Unit, onSaved: (NoteInfo) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var text by remember(path) { mutableStateOf<String?>(null) }
    var editing by remember(path) { mutableStateOf<IntRange?>(null) }
    var base by remember(path) { mutableStateOf("") }
    var draft by remember(path) { mutableStateOf("") }
    var tagging by remember(path) { mutableStateOf(false) }
    var clash by remember(path) { mutableStateOf<Clash?>(null) }

    LaunchedEffect(path, state.changes) { if (editing == null) text = state.io("Couldn't open it") { state.vault?.read(path) } }

    fun landed(t: String, msg: String = "Saved · syncs via Obsidian") {
        text = t
        editing = null
        onSaved(NoteIndex.info(path, state.vault?.entry(path)?.modified ?: System.currentTimeMillis(), t))
        state.say(msg)
    }

    fun save() {
        val r = editing ?: return
        val original = NoteText.lines(base).let { ls -> if (r.first <= r.last) ls.subList(r.first, r.last + 1).joinToString("\n") else "" }
        if (draft == original) { editing = null; return }
        val mine = draft
        scope.launch {
            when (val res = state.io("Couldn't save") { NoteEdit.saveLines(state.vault!!, path, base, r, mine) } ?: return@launch) {
                is NoteEdit.Result.Saved -> landed(res.text)
                is NoteEdit.Result.Conflict -> clash = Clash(res.theirs, res.mine, mine)
            }
        }
    }

    fun change(msg: String, block: (MutableList<String>) -> Unit) = scope.launch {
        state.io("Couldn't save") { NoteEdit.edit(state.vault!!, path, block) }?.let { landed(it, msg) }
    }

    /** A tick on a Tasks or Dataview card: written to the task's own note, which may not be this one. */
    fun tickQuery(qt: QueryTask) = scope.launch {
        val v = state.vault ?: return@launch
        val msg = if (qt.done) "Unticked" else "Ticked · ${qt.note}"
        state.io("Couldn't save") { DataViews.toggle(v, qt) to (v.entry(qt.path)?.modified ?: System.currentTimeMillis()) }?.let { (txt, mod) ->
            if (qt.path == path) landed(txt, msg) else { onSaved(NoteIndex.info(qt.path, mod, txt)); state.say(msg) }
        }
    }

    fun startEdit(range: IntRange) {
        val t = text ?: return
        val ls = NoteText.lines(t)
        base = t
        draft = if (range.first <= range.last) ls.subList(range.first, range.last + 1).joinToString("\n") else ""
        editing = range
    }

    val t = text
    if (t == null) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Sub("Opening…") }; return }
    val lines = remember(t) { NoteText.lines(t) }
    val chunks = remember(t) { NoteChunks.split(lines) }
    val props = remember(t) { NoteChunks.properties(lines) }
    val noteTags = remember(t) { NoteTags.of(lines) }
    val openTasks = remember(t) { lines.count { NoteChunks.checkbox(it) == false && Regex("(^|\\s)#task(\\s|$)").containsMatchIn(it) } }
    val body = TextStyle(fontSize = 15.sp, lineHeight = 24.sp, color = TextMain)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 34.dp, vertical = 22.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (canGoBack) TextAction("‹ Back", Muted) { onBack() }
            Box(Modifier.weight(1f)) { H1(VaultTasks.noteName(path), 24) }
            Chip("✎ Edit note", small = true) {
                val start = chunks.firstOrNull { it.kind == NoteChunk.Kind.FRONTMATTER }?.lines?.last?.plus(1) ?: 0
                startEdit(start..lines.indexOfLast { it.isNotBlank() }.coerceAtLeast(start - 1))
            }
            Chip("＃ Tag", small = true) { tagging = !tagging }
            Chip("↗ Open in Obsidian", small = true) { state.openInObsidian(path) }
        }
        Text(path.removeSuffix(".md") + " · updated " + shortDate(state.vault?.entry(path)?.modified ?: 0), fontSize = 12.sp, color = Muted,
            fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            noteTags.forEach { TagChip(it, true) { tagging = true } }
            Text("＋ Tag", fontSize = 13.sp, color = Muted,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).border(1.5.dp, Outline, RoundedCornerShape(12.dp))
                    .hand().clickable { tagging = true }.padding(horizontal = 11.dp, vertical = 5.dp))
        }
        if (tagging) TagEditor(noteTags, known, onCancel = { tagging = false }) { wanted, toProps ->
            tagging = false
            change(if (wanted.size == 1) "Saved 1 tag" else "Saved ${wanted.size} tags") { NoteTags.set(it, wanted, toProps) }
        }
        clash?.let { c ->
            Column(Modifier.padding(top = 12.dp).widthIn(max = 760.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(WarnTint)
                .border(1.dp, OverdueLine, RoundedCornerShape(12.dp)).padding(14.dp)) {
                Text("This note changed on another device while you were editing.", color = TextMain, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton("Keep mine") {
                        clash = null
                        scope.launch { state.io("Couldn't save") { NoteEdit.force(state.vault!!, path, c.mine) }?.let { landed(it) } }
                    }
                    Chip("Both (mine at the end)", small = true) {
                        clash = null
                        val both = NoteEdit.both(c.theirs, c.myLines, LocalDate.now().toString())
                        scope.launch { state.io("Couldn't save") { NoteEdit.force(state.vault!!, path, both) }?.let { landed(it) } }
                    }
                    Chip("Keep theirs", small = true, color = Muted) { clash = null; editing = null; text = c.theirs }
                }
            }
        }
        if (props.isNotEmpty()) Column(
            Modifier.padding(top = 12.dp).widthIn(max = 560.dp).clip(RoundedCornerShape(12.dp)).background(Surface1)
                .border(1.dp, Outline, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            props.forEach { (k, v) ->
                Row(Modifier.padding(vertical = 2.dp)) {
                    Text(k, fontSize = 12.5.sp, color = Muted, fontFamily = FontFamily.Monospace, modifier = Modifier.width(100.dp), maxLines = 1)
                    Text(v, fontSize = 12.5.sp, color = TextMain, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Column(Modifier.widthIn(max = 760.dp).padding(top = 8.dp)) {
            val edit = editing
            val shown = chunks.filter { it.kind != NoteChunk.Kind.FRONTMATTER }
            var placed = false
            shown.forEach { c ->
                if (edit != null && c.lines.first >= edit.first && c.lines.last <= edit.last) {
                    if (!placed) { placed = true; Editor(draft, known, { draft = it }, ::save) { editing = null } }
                } else if (c.kind == NoteChunk.Kind.CODE && DataViews.of(c.text) != null) QueryCard(
                    c.text, all, path, onEdit = { startEdit(c.lines) }, onOpen = onOpen, onTick = { tickQuery(it) },
                    onAllTasks = { state.taskNote = null; state.taskTag = null; state.tab = Tab.TASKS },
                    onObsidian = { state.openInObsidian(path) }
                ) else Chunk(c, lines, body, onEdit = { startEdit(c.lines) },
                    onLink = { l -> VaultBrowse.resolve(all, l, path)?.let { onOpen(it.path) } ?: state.say("No note called $l yet") },
                    onUrl = { state.browse(it) },
                    onTick = { i ->
                        val was = lines[i]
                        change(if (NoteChunks.checkbox(was) == true) "Unticked" else "Ticked") { ls ->
                            val at = if (i < ls.size && ls[i] == was) i else ls.indexOf(was)
                            if (at < 0) error("that line changed on another device")
                            ls[at] = NoteChunks.toggleCheckbox(ls[at], LocalDate.now())
                        }
                    })
            }
            if (edit != null && !placed) Editor(draft, known, { draft = it }, ::save) { editing = null }
            if (shown.isEmpty() && edit == null) Sub("Empty note. Edit note to write something.", Modifier.padding(top = 12.dp))
            if (openTasks > 0) Row(
                Modifier.padding(top = 14.dp).widthIn(max = 360.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(PrimaryTint).hand()
                    .clickable { state.taskNote = path; state.taskTag = null; state.tab = Tab.TASKS }.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("✓ $openTasks open task${if (openTasks == 1) "" else "s"}", color = Primary, fontSize = 13.5.sp)
                    Text("Show in Tasks", color = Muted, fontSize = 11.5.sp)
                }
                Text("›", color = Primary, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

/** The block being edited, as markdown. Ctrl+Enter saves, Esc cancels; typing #… offers vault tags. */
@Composable
private fun Editor(value: String, known: List<Pair<String, Int>>, onChange: (String) -> Unit, onSave: () -> Unit, onCancel: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    var field by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(value, androidx.compose.ui.text.TextRange(value.length))) }
    val typing = Tags.typing(field.text, field.selection.min)
    val suggestions = typing?.let { (_, q) -> Tags.suggest(q, known, 5) }.orEmpty()
    Column(Modifier.padding(vertical = 6.dp)) {
        Row {
            Text("EDITING · MARKDOWN", fontSize = 10.5.sp, letterSpacing = 1.sp, color = Primary, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
            Text("Ctrl Enter save · Esc cancel", fontSize = 10.5.sp, color = Primary, fontFamily = FontFamily.Monospace)
        }
        BasicTextField(
            field, { field = it; onChange(it.text) },
            textStyle = TextStyle(color = TextMain, fontSize = 13.5.sp, lineHeight = 21.sp, fontFamily = FontFamily.Monospace),
            cursorBrush = SolidColor(Primary),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp).focusRequester(focus).onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when {
                    e.key == Key.Escape -> { onCancel(); true }
                    e.isCtrlPressed && (e.key == Key.Enter || e.key == Key.NumPadEnter) -> { onSave(); true }
                    e.key == Key.Tab && typing != null && suggestions.isNotEmpty() -> {
                        val (t, cur) = Tags.complete(field.text, typing.first, suggestions.first().first)
                        field = androidx.compose.ui.text.input.TextFieldValue(t, androidx.compose.ui.text.TextRange(cur)); onChange(t); true
                    }
                    else -> false
                }
            },
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Surface1)
                    .border(1.5.dp, Primary, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 10.dp)) { inner() }
            }
        )
        if (typing != null && suggestions.isNotEmpty()) Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            suggestions.forEach { (tag, _) ->
                TagChip(tag, false) {
                    val (t, cur) = Tags.complete(field.text, typing.first, tag)
                    field = androidx.compose.ui.text.input.TextFieldValue(t, androidx.compose.ui.text.TextRange(cur)); onChange(t)
                }
            }
            Text("Tab", fontSize = 11.sp, color = Faint, modifier = Modifier.padding(top = 6.dp))
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            PrimaryButton("Save", hint = "Ctrl Enter", onClick = onSave)
            TextAction("Cancel", Muted, onClick = onCancel)
        }
    }
}

/** Tags on the note: current ones (click to drop), Work/Personal, vault suggestions, and where new ones go. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagEditor(current: List<String>, known: List<Pair<String, Int>>, onCancel: () -> Unit, onSave: (List<String>, Boolean) -> Unit) {
    val chosen = remember { mutableStateListOf<String>().apply { addAll(current) } }
    var query by remember { mutableStateOf("") }
    var toProps by remember { mutableStateOf(true) }
    val typed = Tags.norm(query)
    val matches = Tags.suggest(query, known.filter { it.first !in chosen }, 6)
    fun add(t: String) { if (t !in chosen) chosen.add(t); query = "" }
    Column(
        Modifier.padding(top = 12.dp).widthIn(max = 560.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Popup)
            .border(1.dp, Color(0xFF33405C), RoundedCornerShape(14.dp)).padding(16.dp)
    ) {
        Text("Tags on this note", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextMain)
        FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            chosen.toList().forEach { t -> TagChip(t, true) { chosen.remove(t) } }
            Tags.BUILT_IN.filter { it !in chosen }.forEach { t -> TagChip(t, false) { chosen.add(t) } }
        }
        Field(query, { query = it }, Modifier.fillMaxWidth().padding(top = 10.dp).onKeys(enter = {
            (matches.firstOrNull { it.first == typed }?.first ?: typed)?.let(::add)
        }), "# add a tag", fontSize = 13)
        matches.forEach { (tag, n) ->
            Row(Modifier.fillMaxWidth().hand().clickable { add(tag) }.padding(vertical = 7.dp, horizontal = 4.dp)) {
                Text(tag, fontSize = 13.sp, color = TextMain, modifier = Modifier.weight(1f))
                Text(if (n == 1) "1 note" else "$n notes", fontSize = 11.sp, color = Muted, fontFamily = FontFamily.Monospace)
            }
        }
        if (typed != null && typed !in chosen && matches.none { it.first == typed }) TextAction("＋ Create “$typed”") { add(typed) }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Note tags (properties)", fontSize = 13.sp, color = TextMain)
                Text(if (toProps) "New tags go in tags: at the top. Inline #tags stay where they are." else "New tags go on a line at the end of the note.",
                    fontSize = 11.5.sp, color = Muted)
            }
            Toggle(toProps) { toProps = it }
        }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            PrimaryButton(if (chosen.size == 1) "Save 1 tag" else "Save ${chosen.size} tags") { onSave(chosen.toList(), toProps) }
            TextAction("Cancel", Muted, onClick = onCancel)
        }
    }
}

private val TAG_IN_TEXT = Regex("(?<![\\w#/&])#[\\p{L}\\p{N}_/-]*[\\p{L}_/-][\\p{L}\\p{N}_/-]*")
private val CHECK = Regex("^\\[([ xX])]\\s+")

private fun noteInline(s: String): AnnotatedString = buildAnnotatedString {
    fun plain(p: String) {
        var at = 0
        for (m in TAG_IN_TEXT.findAll(p)) {
            append(p.substring(at, m.range.first))
            val tag = Tags.norm(m.value)
            if (tag == null || tag == "#task") append(m.value) else withStyle(SpanStyle(color = tagColour(tag).first)) { append(m.value) }
            at = m.range.last + 1
        }
        append(p.substring(at))
    }
    fun inline(p: String) {
        var at = 0
        for (m in MdBlocks.INLINE.findAll(p)) {
            plain(p.substring(at, m.range.first))
            val g = m.groups
            when {
                m.value.startsWith("!") -> Unit
                g[1] != null -> {
                    pushStringAnnotation("url", g[2]!!.value)
                    withStyle(SpanStyle(color = Primary, textDecoration = TextDecoration.Underline)) { append(g[1]!!.value) }
                    pop()
                }
                g[3] != null || g[4] != null -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { inline((g[3] ?: g[4])!!.value) }
                g[5] != null -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Surface2)) { append(g[5]!!.value) }
                g[6] != null -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { inline(g[6]!!.value) }
                g[7] != null -> {
                    pushStringAnnotation("wiki", g[7]!!.value)
                    withStyle(SpanStyle(color = Primary, textDecoration = TextDecoration.Underline)) { append((g[8] ?: g[7])!!.value) }
                    pop()
                }
            }
            at = m.range.last + 1
        }
        plain(p.substring(at))
    }
    inline(s)
}

@Suppress("DEPRECATION")
@Composable
private fun Rich(text: String, style: TextStyle, modifier: Modifier, onTap: () -> Unit, onLink: (String) -> Unit, onUrl: (String) -> Unit) {
    val s = remember(text) { noteInline(text) }
    ClickableText(s, modifier.hand(), style) { off ->
        s.getStringAnnotations("wiki", off, off).firstOrNull()?.let { onLink(it.item); return@ClickableText }
        s.getStringAnnotations("url", off, off).firstOrNull()?.let { onUrl(it.item); return@ClickableText }
        onTap()
    }
}

/** One block of the note. Click it to edit; click a checkbox to tick it. */
@Composable
private fun Chunk(c: NoteChunk, lines: List<String>, body: TextStyle, onEdit: () -> Unit, onLink: (String) -> Unit, onUrl: (String) -> Unit, onTick: (Int) -> Unit) {
    val blocks = remember(c) { MdBlocks.parse(c.text.split("\n")) }
    val items = remember(c) { NoteChunks.itemLines(lines, c) }
    var item = 0
    HoverRow(Modifier.fillMaxWidth().padding(vertical = 3.dp)) { hovered ->
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (hovered) Color(0xFF141A26) else Color.Transparent)
            .padding(horizontal = 6.dp, vertical = 2.dp)) {
            if (c.kind == NoteChunk.Kind.CODE) {
                Text(c.text.lines().drop(1).dropLastWhile { it.trim().startsWith("```") }.joinToString("\n"), fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp, lineHeight = 19.sp, color = TextMain,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Surface1).hand().clickable(onClick = onEdit).padding(12.dp))
                return@Column
            }
            blocks.forEach { b ->
                when (b) {
                    is MdBlock.Heading -> Rich(b.text, body.copy(fontSize = when (b.level) { 1 -> 21.sp; 2 -> 18.sp; else -> 16.sp },
                        fontWeight = FontWeight.Bold, lineHeight = 26.sp), Modifier.padding(top = 8.dp), onEdit, onLink, onUrl)
                    is MdBlock.Para -> Rich(b.text, body.copy(color = Color(0xFFD5DBE8)), Modifier, onEdit, onLink, onUrl)
                    is MdBlock.Item -> {
                        val line = items.getOrNull(item++)
                        val check = CHECK.find(b.text)
                        Row(Modifier.padding(start = (b.indent * 20).dp, top = 2.dp, bottom = 2.dp)) {
                            if (check != null && line != null) {
                                val ticked = check.groupValues[1] != " "
                                Box(Modifier.padding(top = 3.dp, end = 10.dp).size(18.dp).clip(RoundedCornerShape(6.dp))
                                    .then(if (ticked) Modifier.background(TaskGreen) else Modifier.border(1.5.dp, Muted, RoundedCornerShape(6.dp)))
                                    .hand().clickable { onTick(line) }, contentAlignment = Alignment.Center) {
                                    if (ticked) Text("✓", fontSize = 11.sp, color = Color(0xFF0D2A1D), fontWeight = FontWeight.ExtraBold)
                                }
                                Rich(b.text.substring(check.range.last + 1),
                                    if (ticked) body.copy(color = Muted, textDecoration = TextDecoration.LineThrough) else body,
                                    Modifier.weight(1f), onEdit, onLink, onUrl)
                            } else {
                                Text(b.marker, style = body.copy(color = Muted), modifier = Modifier.width(24.dp))
                                Rich(b.text, body, Modifier.weight(1f), onEdit, onLink, onUrl)
                            }
                        }
                    }
                    is MdBlock.Quote -> Row(Modifier.padding(vertical = 2.dp)) {
                        Box(Modifier.width(3.dp).height(22.dp).clip(RoundedCornerShape(2.dp)).background(Primary))
                        Rich(b.text, body.copy(color = Muted, fontStyle = FontStyle.Italic), Modifier.padding(start = 12.dp), onEdit, onLink, onUrl)
                    }
                    is MdBlock.Code -> Text(b.text, fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = TextMain,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Surface1).padding(12.dp))
                    is MdBlock.Image -> Text("🖼 " + b.src.substringAfterLast('/'), fontSize = 13.sp, color = Muted, modifier = Modifier.padding(vertical = 4.dp))
                    MdBlock.Rule -> Box(Modifier.fillMaxWidth().padding(vertical = 8.dp).height(1.dp).background(Outline))
                }
            }
        }
    }
}

// ---------------------------------------------------------------- query blocks

/** "today", "yesterday", "tomorrow" or "5 Nov". */
private fun dayWord(d: LocalDate, today: LocalDate): String = when (d) {
    today -> "today"
    today.minusDays(1) -> "yesterday"
    today.plusDays(1) -> "tomorrow"
    else -> DataViews.shortDate(d, today)
}

/**
 * A ```tasks or ```dataview block, run against the vault index ([DataViews]) and shown as a card. Ticks write to
 * the task's own note; ✎ edits the query. Blocks the app can't run get a card with Open in Obsidian.
 */
@Composable
private fun QueryCard(
    chunk: String, all: List<NoteInfo>, path: String,
    onEdit: () -> Unit, onOpen: (String) -> Unit, onTick: (QueryTask) -> Unit, onAllTasks: () -> Unit, onObsidian: () -> Unit,
) {
    val q = remember(chunk) { DataViews.of(chunk) } ?: return
    var view by remember(chunk) { mutableStateOf<QueryView?>(null) }
    var showCode by remember(chunk) { mutableStateOf(false) }
    val ready = remember(all) { all.any { it.text.isNotEmpty() } }
    LaunchedEffect(chunk, all) {
        if (ready || q.first == "dataviewjs") view = withContext(Dispatchers.Default) { DataViews.run(q.first, q.second, all, path) }
    }
    val today = remember { LocalDate.now() }
    val v = view
    val shape = RoundedCornerShape(14.dp)
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(shape).background(Surface1).border(1.dp, Outline, shape)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(v?.kind ?: q.first.uppercase(), fontFamily = FontFamily.Monospace, fontSize = 10.5.sp, color = Primary,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(PrimaryTint).padding(horizontal = 6.dp, vertical = 1.dp))
            Text(v?.title ?: "", fontSize = 12.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp).weight(1f))
            val count = when (v) {
                is QueryView.Tasks -> if (v.tasks.size < v.total) "${v.tasks.size} of ${v.total}" else "${v.total}"
                is QueryView.Table -> "${v.rows.size} row${if (v.rows.size == 1) "" else "s"}"
                is QueryView.Items -> "${v.items.size}"
                is QueryView.Unsupported -> if (v.script) "" else if (showCode) "query ▴" else "query ▾"
                null -> ""
            }
            Text(count, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Faint,
                modifier = if (v is QueryView.Unsupported) Modifier.hand().clickable { showCode = !showCode } else Modifier)
            Text("✎", fontSize = 13.sp, color = Muted, modifier = Modifier.padding(start = 10.dp).hand().clickable(onClick = onEdit))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Outline))
        when (v) {
            null -> Sub(if (ready) "Running…" else "Reading the vault…", Modifier.padding(12.dp))
            is QueryView.Tasks -> {
                if (v.tasks.isEmpty()) Sub("No matching tasks", Modifier.padding(12.dp))
                v.tasks.forEachIndexed { i, qt ->
                    if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Outline))
                    QueryTaskRow(qt, today, onTick = { onTick(qt) }, onOpen = { onOpen(qt.path) })
                }
                Row(Modifier.fillMaxWidth().background(Color(0xFF131823)).padding(horizontal = 12.dp, vertical = 7.dp)) {
                    Text(v.footer.ifEmpty { "${v.total} task${if (v.total == 1) "" else "s"}" }, fontSize = 12.sp, color = Muted, modifier = Modifier.weight(1f))
                    Text("All in Tasks ›", fontSize = 12.sp, color = Primary, fontWeight = FontWeight.SemiBold, modifier = Modifier.hand().clickable(onClick = onAllTasks))
                }
            }
            is QueryView.Table -> BoxWithConstraints(Modifier.fillMaxWidth()) {
                val firstMin = if (v.headers.size > 1) maxWidth * 0.5f else maxWidth
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    v.headers.forEachIndexed { ci, h ->
                        Column(Modifier.width(IntrinsicSize.Max).then(if (ci == 0) Modifier.widthIn(min = firstMin) else Modifier)) {
                            Text(h.uppercase(), fontFamily = FontFamily.Monospace, fontSize = 10.5.sp, color = Muted, maxLines = 1,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp))
                            v.rows.forEach { row ->
                                Box(Modifier.fillMaxWidth().height(1.dp).background(Outline))
                                QueryCellText(row.getOrNull(ci) ?: QueryCell(""), today, onOpen, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp))
                            }
                        }
                    }
                }
                if (v.rows.isEmpty()) Sub("No results", Modifier.padding(start = 12.dp, top = 34.dp, bottom = 10.dp))
            }
            is QueryView.Items -> Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                if (v.items.isEmpty()) Sub("No results", Modifier.padding(vertical = 6.dp))
                v.items.forEach { (main, extra) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("•", color = Faint, modifier = Modifier.padding(end = 8.dp))
                        QueryCellText(main, today, onOpen, Modifier.weight(1f))
                        if (extra != null) Text(extra.text, fontFamily = FontFamily.Monospace, fontSize = 11.5.sp, maxLines = 1,
                            color = if (extra.date != null && extra.date!! < today) Warn else Faint, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
            is QueryView.Unsupported -> Column(Modifier.padding(12.dp)) {
                Text(
                    buildAnnotatedString {
                        if (v.script) { withStyle(SpanStyle(color = TextMain, fontWeight = FontWeight.Bold)) { append("Runs in Obsidian only. ") }; append(v.reason) }
                        else withStyle(SpanStyle(color = TextMain)) { append(v.reason) }
                    },
                    fontSize = 13.sp, color = Muted, lineHeight = 19.sp
                )
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("↗ Open in Obsidian", small = true) { onObsidian() }
                    if (v.script) Chip(if (showCode) "Hide code" else "Show code", small = true) { showCode = !showCode }
                }
            }
        }
        if (showCode) Text(q.second, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp, color = Primary,
            modifier = Modifier.fillMaxWidth().background(PrimaryTint).padding(horizontal = 12.dp, vertical = 10.dp))
    }
}

@Composable
private fun QueryCellText(c: QueryCell, today: LocalDate, onOpen: (String) -> Unit, modifier: Modifier) {
    val link = c.link
    when {
        link != null -> Text(c.text, fontSize = 14.sp, color = Primary, textDecoration = TextDecoration.Underline, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = modifier.hand().clickable { onOpen(link) })
        c.date != null -> Text(c.text, fontSize = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 1,
            color = if (c.date!! < today) Warn else Muted, modifier = modifier)
        else -> Text(c.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (c.text == "—") Muted else TextMain, modifier = modifier)
    }
}

@Composable
private fun QueryTaskRow(qt: QueryTask, today: LocalDate, onTick: () -> Unit, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier.padding(top = 1.dp, end = 10.dp).size(18.dp).clip(RoundedCornerShape(6.dp))
                .then(if (qt.done) Modifier.background(TaskGreen) else Modifier.border(1.5.dp, Muted, RoundedCornerShape(6.dp)))
                .hand().clickable(onClick = onTick),
            contentAlignment = Alignment.Center
        ) { if (qt.done) Text("✓", fontSize = 11.sp, color = Color(0xFF0D2A1D), fontWeight = FontWeight.ExtraBold) }
        Column(Modifier.weight(1f).hand().clickable(onClick = onOpen)) {
            val s = remember(qt.text) { noteInline(qt.text) }
            Text(s, fontSize = 14.sp, lineHeight = 20.sp, color = if (qt.done) Muted else TextMain,
                textDecoration = if (qt.done) TextDecoration.LineThrough else null)
            val (label, late) = when {
                qt.due != null -> "due ${dayWord(qt.due!!, today)}" to (!qt.done && qt.due!! <= today)
                qt.scheduled != null -> "scheduled ${dayWord(qt.scheduled!!, today)}" to false
                qt.start != null -> "starts ${dayWord(qt.start!!, today)}" to false
                else -> "" to false
            }
            Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                val mark = qt.priorityMark
                val meta = listOf(mark, label).filter { it.isNotEmpty() }.joinToString(" ")
                if (meta.isNotEmpty()) Text(meta, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = if (late) Warn else Muted)
                Text((if (meta.isNotEmpty()) " · " else "") + qt.note, fontSize = 11.5.sp, color = Faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
