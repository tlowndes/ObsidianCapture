package uk.tomlowndes.capture

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as DayStyle
import java.util.Locale

private fun parentOf(path: String) = path.substringBeforeLast('/', "")
private fun nameOf(path: String) = path.substringAfterLast('/')

/** The last index, kept while the app runs so coming back to Vault is instant. */
private object VaultCache {
    var tree: Uri? = null
    var notes: List<NoteInfo> = emptyList()
    var full = false
}

/** "2h ago", "yesterday", "Wed", "3 Nov". */
internal fun agoLabel(millis: Long, now: Long = System.currentTimeMillis()): String {
    val mins = (now - millis) / 60_000
    if (mins < 1) return "just now"
    if (mins < 60) return "${mins}m ago"
    if (mins < 24 * 60) return "${mins / 60}h ago"
    val zone = ZoneId.systemDefault()
    val d = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
    val today = LocalDate.now(zone)
    return when {
        d == today.minusDays(1) -> "yesterday"
        d.isAfter(today.minusDays(7)) -> d.dayOfWeek.getDisplayName(DayStyle.SHORT, Locale.UK)
        d.year == today.year -> d.format(DateTimeFormatter.ofPattern("d MMM", Locale.UK))
        else -> d.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK))
    }
}

/**
 * Vault tab: every note, by folder or by search, rendered to read, with quick edits (tap a block to change it,
 * tick checkboxes, change tags). Read later, Projects and Jobs sit inside it as chips ([onOpenRead], [onOpenProjects], [onOpenJobs]).
 */
@Composable
fun VaultScreen(
    vault: Uri?, prefs: AppPrefs, snack: SnackbarHostState, unread: Int, jobsOpen: Int,
    onOpenRead: () -> Unit, onOpenProjects: () -> Unit, onOpenJobs: () -> Unit, onOpenTasks: (String?) -> Unit, onEditing: (Boolean) -> Unit,
) {
    val ctx = LocalContext.current
    var notes by remember { mutableStateOf(if (VaultCache.tree == vault) VaultCache.notes else emptyList()) }
    var full by remember { mutableStateOf(VaultCache.tree == vault && VaultCache.full) }
    var folder by remember { mutableStateOf<String?>(null) }
    var searching by remember { mutableStateOf(false) }
    var searchIn by remember { mutableStateOf<String?>(null) }
    var startTag by remember { mutableStateOf<String?>(null) }
    val open = remember { mutableStateListOf<String>() }
    var showHidden by remember { mutableStateOf(prefs.vaultShowHidden) }
    var reindex by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var kept by remember { mutableStateOf(prefs.paraKept) }
    val scope = rememberCoroutineScope()

    /** Moves a quiet project's folder into the Archive folder, with Undo. */
    fun archive(project: String, archiveDir: String) {
        val uri = vault ?: return
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { appVault(ctx, uri).move(project, archiveDir) } }
            r.onFailure { snack.showSnackbar("Couldn't move it: ${it.message}") }
            r.onSuccess { moved ->
                reindex++
                if (snack.showSnackbar("${nameOf(project)} archived", "Undo", duration = androidx.compose.material3.SnackbarDuration.Short)
                    == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                    withContext(Dispatchers.IO) { runCatching { appVault(ctx, uri).move(moved, parentOf(project)) } }
                    reindex++
                }
            }
        }
    }

    LaunchedEffect(vault, reindex) {
        val uri = vault ?: return@LaunchedEffect
        if (notes.isEmpty()) withContext(Dispatchers.IO) { runCatching { NoteIndex.paths(appVault(ctx, uri)) } }
            .onSuccess { notes = it }
        withContext(Dispatchers.IO) { runCatching { NoteIndex.build(appVault(ctx, uri), File(ctx.cacheDir, "vault-index.json")) } }
            .onSuccess { notes = it; full = true; VaultCache.tree = uri; VaultCache.notes = it; VaultCache.full = true }
            .onFailure { snack.showSnackbar("Couldn't read the vault: ${it.message}") }
    }

    fun updated(info: NoteInfo) {
        notes = notes.filter { it.path != info.path } + info
        VaultCache.notes = notes
    }

    BackHandler(enabled = open.isNotEmpty() || searching || folder != null) {
        when {
            open.isNotEmpty() -> open.removeAt(open.size - 1)
            searching -> { searching = false; startTag = null }
            else -> folder = folder?.let { parentOf(it) }?.takeIf { it.isNotEmpty() }
        }
    }

    val tags = remember(notes) { VaultBrowse.tagCounts(notes) }
    when {
        vault == null -> Column(Modifier.fillMaxSize().padding(20.dp)) { ScreenTitle("Vault", "Pick your vault in Settings") }
        open.isNotEmpty() -> NoteScreen(
            vault, open.last(), notes, full, tags, snack,
            onBack = { open.removeAt(open.size - 1) }, onOpen = { open.add(it) }, onSaved = ::updated,
            onOpenTasks = onOpenTasks, onEditing = onEditing
        )
        searching -> SearchNotes(notes, full, prefs, searchIn, startTag, tags.map { it.first }, onClose = { searching = false; startTag = null }) { open.add(it) }
        folder != null -> FolderScreen(notes, folder!!, prefs, showHidden, onBack = { folder = parentOf(folder!!).takeIf { it.isNotEmpty() } },
            onFolder = { folder = it }, onSearch = { searchIn = folder; startTag = null; searching = true }) { open.add(it) }
        else -> VaultHome(
            notes, tags, unread, jobsOpen, showHidden,
            onSearch = { searchIn = null; startTag = null; searching = true },
            onTag = { searchIn = null; startTag = it; searching = true },
            onRead = onOpenRead, onProjects = onOpenProjects, onJobs = onOpenJobs, onFolder = { folder = it }, onNote = { open.add(it) },
            onToggleHidden = { showHidden = !showHidden; prefs.vaultShowHidden = showHidden },
            kept = kept, onKeep = { p -> kept = kept + p; prefs.paraKept = kept }, onArchive = ::archive,
        )
    }
}

// ---------------------------------------------------------------- home and folders

@Composable
private fun VaultHome(
    notes: List<NoteInfo>, tags: List<Pair<String, Int>>, unread: Int, jobsOpen: Int, showHidden: Boolean,
    onSearch: () -> Unit, onTag: (String) -> Unit, onRead: () -> Unit, onProjects: () -> Unit, onJobs: () -> Unit, onFolder: (String) -> Unit, onNote: (String) -> Unit,
    onToggleHidden: () -> Unit,
    kept: Set<String> = emptySet(), onKeep: (String) -> Unit = {}, onArchive: (String, String) -> Unit = { _, _ -> },
) {
    val view = remember(notes, showHidden) { VaultBrowse.folder(notes, "", showHidden = showHidden) }
    val top = remember(notes) { VaultBrowse.folder(notes, "", showHidden = true).folders }
    val para = remember(top) { Para.folders(top.map { it.first }) }
    val quiet = remember(notes, para, kept) {
        para[ParaKind.PROJECTS]?.let { Para.quietProjects(notes, it, System.currentTimeMillis()) }.orEmpty().filter { it.first !in kept }.take(2)
    }
    var asking by remember { mutableStateOf<String?>(null) }
    var quietOpen by remember { mutableStateOf(false) }
    var foldersOpen by remember { mutableStateOf(false) }
    val recent = remember(notes, showHidden) { VaultBrowse.recent(notes, 3, showHidden) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Vault", fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp, modifier = Modifier.weight(1f))
                Pill("${"%,d".format(notes.size)} notes")
            }
            SearchBox("Search notes, text or #tag", onSearch)
        }
        // Shortcuts (tidy-v1): Read later, Projects and Jobs as tiles. Tags now sit in search.
        item {
            SectionLabel("Shortcuts")
            TileRow {
                Tile("📖", "Read later", if (unread > 0) "$unread unread" else "all read", onClick = onRead)
                Tile("💡", "Projects", if (quiet.isNotEmpty()) "${quiet.size} quiet" else "open", dot = quiet.isNotEmpty(), onClick = onProjects)
                Tile("💼", "Jobs", if (jobsOpen > 0) "$jobsOpen open" else "none open", onClick = onJobs)
            }
        }
        // PARA (paper-hybrid-v1): four doors over the vault's own folders, as one 2×2 card.
        item {
            SectionLabel("Browse")
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surface1).border(1.dp, Outline, RoundedCornerShape(16.dp))) {
                ParaKind.values().toList().chunked(2).forEachIndexed { r, pair ->
                    if (r > 0) HorizontalDivider(color = Outline)
                    Row(Modifier.height(IntrinsicSize.Min)) {
                        pair.forEachIndexed { c, k ->
                            if (c > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(Outline))
                            val dir = para[k]
                            val count = dir?.let { d -> top.firstOrNull { it.first == d }?.second }
                            ParaDoor(k, dir, count, Modifier.weight(1f)) {
                                if (k == ParaKind.PROJECTS) onProjects() else dir?.let(onFolder)
                            }
                        }
                    }
                }
            }
            if (quiet.isNotEmpty()) Text(
                (if (quietOpen) "▴ " else "▾ ") + "${quiet.size} quiet project" + (if (quiet.size > 1) "s" else ""),
                fontSize = 12.5.sp, color = Amber, modifier = Modifier.padding(top = 8.dp).clip(RoundedCornerShape(8.dp))
                    .clickable { quietOpen = !quietOpen }.padding(vertical = 4.dp, horizontal = 2.dp)
            )
        }
        if (quiet.isNotEmpty() && quietOpen) item {
            Column(Modifier.padding(top = 6.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surface1)
                .border(1.dp, Outline, RoundedCornerShape(16.dp))) {
                quiet.forEachIndexed { i, (p, last) ->
                    if (i > 0) HorizontalDivider(color = Outline)
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(nameOf(p), fontSize = 14.sp)
                            Text("${(System.currentTimeMillis() - last) / 86_400_000L} days quiet", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Muted)
                        }
                        Text("Keep", fontSize = 12.sp, color = Muted, modifier = Modifier.clickable { onKeep(p) }.padding(8.dp))
                        Text("Archive?", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Amber,
                            modifier = Modifier.clip(CircleShape).background(AmberTint).clickable { asking = p }.padding(horizontal = 10.dp, vertical = 4.dp))
                    }
                }
            }
        }
        if (recent.isNotEmpty()) {
            item { SectionLabel("Recent") }
            items(recent, key = { "r-" + it.path }) { n -> NoteRow(n, showFolder = true, preview = false) { onNote(n.path) } }
        }
        item { SectionLabel("All folders", if (foldersOpen) "Hide ›" else "Show ›") { foldersOpen = !foldersOpen } }
        if (foldersOpen) {
        items(view.folders, key = { "f-" + it.first }) { (f, n) -> FolderRow(f, "$n", onClick = { onFolder(f) }) }
        items(view.notes, key = { "n-" + it.path }) { n -> NoteRow(n, showFolder = false) { onNote(n.path) } }
        item {
            Text(if (showHidden) "Hide 99 folders" else "Show hidden folders", fontSize = 13.sp, color = Muted,
                modifier = Modifier.padding(top = 14.dp).clickable(onClick = onToggleHidden).padding(vertical = 6.dp))
        }
        }
    }
    asking?.let { p ->
        val to = para[ParaKind.ARCHIVE] ?: "05 - Archive"
        AlertDialog(
            onDismissRequest = { asking = null }, containerColor = Surface1,
            title = { Text("Archive ${nameOf(p)}?") },
            text = { Text("Moves the folder to $to. Nothing is deleted.", color = Muted) },
            confirmButton = { TextButton(onClick = { asking = null; onArchive(p, to) }) { Text("Archive", color = Primary) } },
            dismissButton = { TextButton(onClick = { asking = null }) { Text("Cancel", color = Muted) } }
        )
    }
}

/** One PARA door: icon, name, and how many notes; dimmed when the vault has no folder for it. */
@Composable
private fun ParaDoor(k: ParaKind, dir: String?, count: Int?, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clickable(enabled = dir != null || k == ParaKind.PROJECTS, onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(k.label, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (dir != null) TextMain else Muted, modifier = Modifier.weight(1f))
            Text(k.icon, fontSize = 15.sp)
        }
        Text(
            if (dir == null) "no folder yet" else "${count ?: 0} notes",
            fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Muted, modifier = Modifier.padding(top = 2.dp)
        )
    }
}

@Composable
private fun FolderScreen(
    notes: List<NoteInfo>, folder: String, prefs: AppPrefs, showHidden: Boolean,
    onBack: () -> Unit, onFolder: (String) -> Unit, onSearch: () -> Unit, onNote: (String) -> Unit,
) {
    var byName by remember { mutableStateOf(prefs.vaultByName) }
    val view = remember(notes, folder, byName, showHidden) { VaultBrowse.folder(notes, folder, byName, showHidden) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("‹ " + nameOf(folder), fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).clickable(onClick = onBack).padding(vertical = 6.dp))
                TextButton(onClick = { byName = !byName; prefs.vaultByName = byName }) { Text(if (byName) "A–Z" else "⇅ New", color = Muted) }
                TextButton(onClick = onSearch) { Text("🔍", fontSize = 17.sp) }
            }
            Text("Vault / $folder", fontSize = 12.sp, color = Muted, fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(bottom = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        items(view.folders, key = { "f-" + it.first }) { (f, n) -> FolderRow(f, if (n == 1) "1 note" else "$n notes", sub = true) { onFolder(f) } }
        if (view.notes.isNotEmpty()) item { Box(Modifier.padding(top = 14.dp, bottom = 2.dp)) { GroupLabel("Notes") } }
        items(view.notes, key = { "n-" + it.path }) { n -> NoteRow(n, showFolder = false) { onNote(n.path) } }
        if (view.folders.isEmpty() && view.notes.isEmpty()) item { Text("Nothing in here", color = Muted, modifier = Modifier.padding(top = 20.dp)) }
    }
}

private fun folderIcon(name: String): String {
    val n = name.lowercase()
    return when {
        "moc" in n -> "🗺"
        "inbox" in n -> "📥"
        "area" in n -> "🧭"
        "project" in n -> "💡"
        "journal" in n || "daily" in n -> "📓"
        "resource" in n -> "📚"
        "meta" in n || "template" in n -> "⚙"
        else -> "📁"
    }
}

@Composable
private fun FolderRow(path: String, count: String, sub: Boolean = false, onClick: () -> Unit) {
    Column {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(Surface2), contentAlignment = Alignment.Center) {
                Text(if (sub) "📁" else folderIcon(nameOf(path)), fontSize = 15.sp)
            }
            Column(Modifier.weight(1f)) {
                Text(nameOf(path), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (sub) Text(count, fontSize = 12.sp, color = Muted)
            }
            if (!sub) Text(count, fontSize = 11.sp, color = Muted, fontFamily = FontFamily.Monospace)
            Text("›", color = Muted, fontSize = 18.sp)
        }
        HorizontalDivider(color = Outline)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NoteRow(n: NoteInfo, showFolder: Boolean, preview: Boolean = true, snippet: AnnotatedString? = null, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(vertical = 11.dp, horizontal = 4.dp)) {
            Text(n.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            when {
                snippet != null && snippet.isNotEmpty() -> Text(snippet, fontSize = 13.sp, color = Muted, lineHeight = 18.sp, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
                preview && n.preview.isNotEmpty() -> Text(n.preview, fontSize = 13.sp, color = Muted, lineHeight = 18.sp, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
            }
            FlowRow(Modifier.padding(top = 5.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val where = if (showFolder) listOf(n.folder.ifEmpty { "Vault" }, agoLabel(n.modified)) else listOf(agoLabel(n.modified))
                Text(where.joinToString(" · "), fontSize = 11.sp, color = Muted, fontFamily = FontFamily.Monospace)
                n.tags.take(4).forEach { MiniTag(it) }
            }
        }
        HorizontalDivider(color = Outline)
    }
}

@Composable
internal fun MiniTag(tag: String) {
    val (fg, bg) = tagColour(tag)
    Text(Tags.label(tag).let { if (Tags.kind(tag) == TagKind.OTHER) it else it.lowercase() }, fontSize = 11.sp,
        fontFamily = FontFamily.Monospace, color = fg,
        modifier = Modifier.clip(CircleShape).background(bg).padding(horizontal = 8.dp, vertical = 1.dp))
}

@Composable
private fun Pill(text: String) {
    Text(text, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Primary,
        modifier = Modifier.clip(CircleShape).background(PrimaryTint).padding(horizontal = 10.dp, vertical = 4.dp))
}

@Composable
private fun SearchBox(hint: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(CircleShape).background(Surface1).border(1.dp, Outline, CircleShape)
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("🔍", fontSize = 14.sp)
        Text(hint, fontSize = 15.sp, color = Muted)
    }
}

// ---------------------------------------------------------------- search

private val MarkStyle = SpanStyle(color = Amber, background = Color(0xFF3A3220))

private fun marked(s: String, marks: List<IntRange>): AnnotatedString = buildAnnotatedString {
    var at = 0
    for (r in marks) {
        if (r.first < at || r.last >= s.length) continue
        append(s.substring(at, r.first))
        withStyle(MarkStyle) { append(s.substring(r.first, r.last + 1)) }
        at = r.last + 1
    }
    append(s.substring(at))
}

@Composable
private fun SearchNotes(
    notes: List<NoteInfo>, full: Boolean, prefs: AppPrefs, inFolder: String?, startTag: String?, topTags: List<String>,
    onClose: () -> Unit, onOpen: (String) -> Unit,
) {
    var field by remember { mutableStateOf(TextFieldValue("")) }
    val tags = remember { mutableStateListOf<String>().apply { startTag?.let { add(it) } } }
    var mode by remember { mutableStateOf(VaultSearch.Mode.ALL) }
    var here by remember { mutableStateOf(inFolder != null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (startTag == null) runCatching { focus.requestFocus() } }

    fun keep(q: String) {
        val s = q.trim()
        if (s.isEmpty()) return
        prefs.vaultSearches = (listOf(s) + prefs.vaultSearches.filter { it != s }).take(5)
    }

    val results = remember(notes, field.text, tags.toList(), mode, here) {
        VaultSearch.search(notes, field.text, tags.toList(), mode, if (here) inFolder else null)
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = field,
                onValueChange = { v ->
                    val (left, taken) = VaultSearch.takeTags(v.text)
                    if (taken.isEmpty()) field = v
                    else { taken.filter { it !in tags }.forEach { tags.add(it) }; field = TextFieldValue(left, TextRange(left.length)) }
                },
                modifier = Modifier.weight(1f).focusRequester(focus), singleLine = true, shape = CircleShape, colors = fieldColors(),
                placeholder = { Text(if (tags.isEmpty()) "Search notes, text or #tag" else "Search within tags") },
                leadingIcon = { Text("🔍", fontSize = 14.sp) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None)
            )
            TextButton(onClick = { keep(field.text); onClose() }) { Text("Close", color = Muted) }
        }
        if (tags.isNotEmpty()) Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) { tags.toList().forEach { t -> TagChip(t, true, onClick = { tags.remove(t) }) } }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AppChip(mode == VaultSearch.Mode.ALL, { mode = VaultSearch.Mode.ALL }, { Text("All") })
            AppChip(mode == VaultSearch.Mode.TITLES, { mode = VaultSearch.Mode.TITLES }, { Text("Titles") })
            AppChip(mode == VaultSearch.Mode.TASKS, { mode = VaultSearch.Mode.TASKS }, { Text("Tasks") })
            if (inFolder != null) AppChip(here, { here = !here }, { Text("This folder") })
        }
        val searched = field.text.isNotBlank() || tags.isNotEmpty()
        if (searched) Text(
            "${results.size}${if (results.size >= 100) "+" else ""} notes" + (if (tags.isNotEmpty()) " · " + tags.joinToString(" ") else "") +
                (if (!full) " · still reading note text…" else ""),
            fontSize = 12.sp, color = Muted, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 6.dp)
        )
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 20.dp)) {
            if (!searched) {
                // Tags (moved here from Vault home in tidy-v1): Work and Personal first, then the most used.
                val shownTags = Tags.BUILT_IN.filter { it in topTags } + topTags.filter { it !in Tags.BUILT_IN }.take(8)
                if (shownTags.isNotEmpty()) {
                    item { Box(Modifier.padding(top = 16.dp, bottom = 8.dp)) { GroupLabel("Tags") } }
                    item {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            shownTags.forEach { t -> TagChip(t, false, onClick = { if (t !in tags) tags.add(t) }) }
                        }
                    }
                }
                val recent = prefs.vaultSearches
                if (recent.isNotEmpty()) {
                    item { Box(Modifier.padding(top = 16.dp, bottom = 8.dp)) { GroupLabel("Recent searches") } }
                    item {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            recent.forEach { s ->
                                AppChip(false, {
                                    val (left, taken) = VaultSearch.takeTags("$s ")
                                    taken.filter { it !in tags }.forEach { tags.add(it) }
                                    field = TextFieldValue(left.trim(), TextRange(left.trim().length))
                                }, { Text(s) })
                            }
                        }
                    }
                }
            }
            items(results, key = { it.note.path }) { h ->
                NoteRow(h.note, showFolder = true, snippet = marked(h.snippet, h.marks)) {
                    keep((tags.joinToString(" ") + " " + field.text).trim()); onOpen(h.note.path)
                }
            }
            if (searched && results.isEmpty()) item { Text("No notes match", color = Muted, modifier = Modifier.padding(top = 20.dp)) }
        }
    }
}

// ---------------------------------------------------------------- reader and quick edit

/** What's being edited: lines [range] of [base] (the note as loaded), shown as markdown in the field. */
private data class Editing(val range: IntRange, val base: String, val original: String)

private data class Clash(val theirs: String, val mine: String, val myLines: String)

@Composable
private fun NoteScreen(
    vault: Uri, path: String, notes: List<NoteInfo>, full: Boolean, tags: List<Pair<String, Int>>, snack: SnackbarHostState,
    onBack: () -> Unit, onOpen: (String) -> Unit, onSaved: (NoteInfo) -> Unit, onOpenTasks: (String?) -> Unit,
    onEditing: (Boolean) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember(path) { mutableStateOf<String?>(null) }
    var editing by remember(path) { mutableStateOf<Editing?>(null) }
    var tagging by remember(path) { mutableStateOf(false) }
    var adding by remember(path) { mutableStateOf(false) }
    var clash by remember(path) { mutableStateOf<Clash?>(null) }
    var menu by remember { mutableStateOf(false) }
    var summarising by remember(path) { mutableStateOf(false) }
    var listing by remember(path) { mutableStateOf(false) }
    var printMenu by remember(path) { mutableStateOf(false) }
    fun v() = appVault(ctx, vault)

    suspend fun load() {
        withContext(Dispatchers.IO) { runCatching { v().read(path) } }
            .onSuccess { t -> if (t == null) { snack.showSnackbar("That note isn't there any more"); onBack() } else text = t }
            .onFailure { snack.showSnackbar("Couldn't open it: ${it.message}") }
    }
    LaunchedEffect(path) { load() }
    LaunchedEffect(editing) { onEditing(editing != null) }

    fun landed(t: String, msg: String = "Saved · syncs via Obsidian") {
        text = t
        val modified = runCatching { v().entry(path)?.modified }.getOrNull() ?: System.currentTimeMillis()
        onSaved(NoteIndex.info(path, modified, t))
        scope.launch { snack.showSnackbar(msg) }
    }

    fun result(r: NoteEdit.Result, myLines: String) {
        when (r) {
            is NoteEdit.Result.Saved -> { editing = null; landed(r.text) }
            is NoteEdit.Result.Conflict -> clash = Clash(r.theirs, r.mine, myLines)
        }
    }

    fun save(e: Editing, newText: String) {
        if (newText == e.original) { editing = null; return }
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { NoteEdit.saveLines(v(), path, e.base, e.range, newText) } }
                .onSuccess { result(it, newText) }
                .onFailure { snack.showSnackbar("Couldn't save: ${it.message}") }
        }
    }

    fun change(msg: String, block: (MutableList<String>) -> Unit) {
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { NoteEdit.edit(v(), path, block) } }
                .onSuccess { landed(it, msg) }
                .onFailure { snack.showSnackbar("Couldn't save: ${it.message}"); load() }
        }
    }

    /** A tick on a Tasks or Dataview card: written to the task's own note, which may not be this one. */
    fun tickQuery(qt: QueryTask) {
        val msg = if (qt.done) "Unticked" else "Ticked · ${qt.note}"
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    val txt = DataViews.toggle(v(), qt)
                    txt to (runCatching { v().entry(qt.path)?.modified }.getOrNull() ?: System.currentTimeMillis())
                }
            }.onSuccess { (txt, mod) ->
                if (qt.path == path) landed(txt, msg) else { onSaved(NoteIndex.info(qt.path, mod, txt)); snack.showSnackbar(msg) }
            }.onFailure { snack.showSnackbar("Couldn't save: ${it.message}") }
        }
    }

    val t = text
    if (t == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Opening…", color = Muted) }
        return
    }
    val lines = remember(t) { NoteText.lines(t) }
    val noteTags = remember(t) { NoteTags.of(lines) }

    val e = editing
    if (e != null) {
        EditBlock(VaultTasks.noteName(path), e.original, tags, onCancel = { editing = null }) { save(e, it) }
    } else Box(Modifier.fillMaxSize()) {
        val chunks = remember(t) { NoteChunks.split(lines) }
        val props = remember(t) { NoteChunks.properties(lines) }
        val openTasks = remember(t) { lines.count { NoteChunks.checkbox(it) == false && Regex("(^|\\s)#task(\\s|$)").containsMatchIn(it) } }
        val body = TextStyle(fontSize = 15.5.sp, lineHeight = 24.sp, color = TextMain)
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp), contentPadding = PaddingValues(bottom = 110.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("‹", fontSize = 26.sp, modifier = Modifier.clickable(onClick = onBack).padding(end = 16.dp, top = 4.dp, bottom = 4.dp))
                    Spacer(Modifier.weight(1f))
                    Box {
                        TextButton(onClick = { printMenu = true }) { Text("🧾", fontSize = 18.sp) }
                        DropdownMenu(printMenu, { printMenu = false }) {
                            DropdownMenuItem({ Text("Print summary") }, { printMenu = false; summarising = true })
                            DropdownMenuItem({ Text("Print as list") }, { printMenu = false; listing = true })
                        }
                    }
                    Box {
                        TextButton(onClick = { menu = true }) { Text("⋯", fontSize = 20.sp, color = Muted) }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem({ Text("Open in Obsidian") }, { menu = false; openInObsidian(ctx, vault, path) })
                            DropdownMenuItem({ Text("Reload") }, { menu = false; scope.launch { load() } })
                        }
                    }
                }
                Text(VaultTasks.noteName(path), fontSize = 23.sp, fontWeight = FontWeight.Bold, lineHeight = 29.sp)
                Text(path.removeSuffix(".md"), fontSize = 12.sp, color = Muted, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                NoteTagRow(noteTags) { tagging = true }
                if (props.isNotEmpty()) Column(
                    Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(14.dp)).background(Surface1)
                        .border(1.dp, Outline, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    props.forEach { (k, value) ->
                        Row(Modifier.padding(vertical = 2.dp)) {
                            Text(k, fontSize = 12.5.sp, color = Muted, fontFamily = FontFamily.Monospace, modifier = Modifier.width(84.dp), maxLines = 1)
                            Text(value, fontSize = 12.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
            items(chunks.filter { it.kind != NoteChunk.Kind.FRONTMATTER }, key = { "c-${it.lines.first}" }) { c ->
                if (c.kind == NoteChunk.Kind.CODE && DataViews.of(c.text) != null) QueryCard(
                    c.text, notes, full, path,
                    onEdit = { editing = Editing(c.lines, t, c.text) }, onOpen = onOpen, onTick = ::tickQuery,
                    onAllTasks = { onOpenTasks(null) }, onObsidian = { openInObsidian(ctx, vault, path) }
                ) else ChunkView(
                    c, lines, body,
                    onEdit = { editing = Editing(c.lines, t, c.text) },
                    onLink = { link ->
                        val hit = VaultBrowse.resolve(notes, link, path)
                        if (hit != null) onOpen(hit.path) else scope.launch { snack.showSnackbar("No note called $link yet") }
                    },
                    onUrl = { url -> runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } },
                    onTick = { i ->
                        val was = lines[i]
                        change(if (NoteChunks.checkbox(was) == true) "Unticked" else "Ticked") { ls ->
                            val at = if (i < ls.size && ls[i] == was) i else ls.indexOf(was)
                            if (at < 0) error("that line changed on another device")
                            ls[at] = NoteChunks.toggleCheckbox(ls[at], LocalDate.now())
                        }
                    }
                )
            }
            if (openTasks > 0) item {
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(14.dp)).background(PrimaryTint)
                        .clickable { onOpenTasks(path) }.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("✓ $openTasks open task${if (openTasks == 1) "" else "s"}", color = Primary, fontSize = 13.5.sp)
                        Text("Open in Tasks, filtered to this note", color = Muted, fontSize = 11.5.sp)
                    }
                    Text("›", color = Primary, fontWeight = FontWeight.Bold)
                }
            }
            if (chunks.none { it.kind != NoteChunk.Kind.FRONTMATTER }) item {
                Text("Empty note. Tap Edit to write something.", color = Muted, modifier = Modifier.padding(top = 16.dp))
            }
        }
        Row(
            Modifier.align(Alignment.BottomCenter).padding(horizontal = 12.dp, vertical = 12.dp).fillMaxWidth()
                .clip(RoundedCornerShape(20.dp)).background(Color(0xFF1B2132)).border(1.dp, Color(0xFF33405C), RoundedCornerShape(20.dp))
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            BarAction("✎", "Edit", Primary) {
                val start = chunks.firstOrNull { it.kind == NoteChunk.Kind.FRONTMATTER }?.lines?.last?.plus(1) ?: 0
                val end = lines.indexOfLast { it.isNotBlank() }.coerceAtLeast(start - 1)
                editing = Editing(start..end, t, if (start <= end) lines.subList(start, end + 1).joinToString("\n") else "")
            }
            BarAction("＃", "Tag") { tagging = true }
            BarAction("＋", "Task here") { adding = true }
            BarAction("↗", "Obsidian") { openInObsidian(ctx, vault, path) }
        }
    }

    if (tagging) NoteTagSheet(noteTags, tags, onDismiss = { tagging = false }) { wanted, toProps ->
        tagging = false
        change(if (wanted.size == 1) "Saved 1 tag" else "Saved ${wanted.size} tags") { NoteTags.set(it, wanted, toProps) }
    }
    if (listing) text?.let { body -> PrintListSheet(VaultTasks.noteName(path), body, snack) { listing = false } }
    if (summarising) text?.let { body ->
        PrintSummarySheet(SummarySource.Note(VaultTasks.noteName(path), body), snack) { summarising = false }
    }
    if (adding) AddToNoteDialog(VaultNote(path, 0), onDismiss = { adding = false }) { line, asTask, due ->
        adding = false
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { VaultWriter(ctx, vault).addToNote(VaultNote(path, 0), line, asTask, due); v().read(path).orEmpty() } }
                .onSuccess { landed(it, if (asTask) "Task added" else "Added") }
                .onFailure { snack.showSnackbar("Failed: ${it.message}") }
        }
    }
    clash?.let { c ->
        AlertDialog(
            onDismissRequest = {}, containerColor = Surface1,
            title = { Text("This note changed on another device", fontSize = 17.sp) },
            text = { Text("Obsidian synced a newer copy while you were editing. Which one should stay?", color = Muted) },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = {
                        clash = null
                        scope.launch {
                            withContext(Dispatchers.IO) { runCatching { NoteEdit.force(v(), path, c.mine) } }
                                .onSuccess { editing = null; landed(it) }.onFailure { snack.showSnackbar("Couldn't save: ${it.message}") }
                        }
                    }) { Text("Keep mine", color = Primary, fontWeight = FontWeight.Bold) }
                    TextButton(onClick = {
                        clash = null
                        val both = NoteEdit.both(c.theirs, c.myLines, LocalDate.now().toString())
                        scope.launch {
                            withContext(Dispatchers.IO) { runCatching { NoteEdit.force(v(), path, both) } }
                                .onSuccess { editing = null; landed(it) }.onFailure { snack.showSnackbar("Couldn't save: ${it.message}") }
                        }
                    }) { Text("Both (mine added at the end)", color = Primary) }
                    TextButton(onClick = { clash = null; editing = null; text = c.theirs }) { Text("Keep theirs", color = Muted) }
                }
            }
        )
    }
}

private fun openInObsidian(ctx: android.content.Context, vault: Uri, path: String) {
    val name = vault.lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/') ?: return
    val url = "obsidian://open?vault=${Uri.encode(name)}&file=${Uri.encode(path.removeSuffix(".md"))}"
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

@Composable
private fun BarAction(icon: String, label: String, tint: Color = TextMain, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(icon, fontSize = 17.sp, color = tint)
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = if (tint == TextMain) Muted else tint)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NoteTagRow(tags: List<String>, onEdit: () -> Unit) {
    FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        tags.forEach { TagChip(it, true, onClick = onEdit) }
        Text(
            "＋ Tag", fontSize = 14.sp, color = Muted,
            modifier = Modifier.clip(RoundedCornerShape(12.dp)).border(1.dp, Outline, RoundedCornerShape(12.dp))
                .clickable(onClick = onEdit).padding(horizontal = 12.dp, vertical = 7.dp)
        )
    }
}

private val TAG_IN_TEXT = Regex("(?<![\\w#/&])#[\\p{L}\\p{N}_/-]*[\\p{L}_/-][\\p{L}\\p{N}_/-]*")
private val CHECK = Regex("^\\[([ xX])]\\s+")

/** Inline markdown for notes: links, bold, code, italic, [[wikilinks]] (tappable) and #tags in their colours. */
private fun noteInline(s: String): AnnotatedString = buildAnnotatedString {
    fun plain(p: String) {
        var at = 0
        for (m in TAG_IN_TEXT.findAll(p)) {
            append(p.substring(at, m.range.first))
            val tag = Tags.norm(m.value)
            if (tag == null || tag == "#task") append(m.value)
            else withStyle(SpanStyle(color = tagColour(tag).first)) { append(m.value) }
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

/** Text that opens links and wikilinks when tapped on one, and does [onTap] anywhere else. */
@Composable
private fun NoteRich(text: String, style: TextStyle, modifier: Modifier, onTap: () -> Unit, onLink: (String) -> Unit, onUrl: (String) -> Unit) {
    val s = remember(text) { noteInline(text) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    BasicText(
        s, style = style, onTextLayout = { layout = it },
        modifier = modifier.pointerInput(s) {
            detectTapGestures { pos ->
                val off = layout?.getOffsetForPosition(pos) ?: return@detectTapGestures
                s.getStringAnnotations("wiki", off, off).firstOrNull()?.let { onLink(it.item); return@detectTapGestures }
                s.getStringAnnotations("url", off, off).firstOrNull()?.let { onUrl(it.item); return@detectTapGestures }
                onTap()
            }
        }
    )
}

/** One block of the note, rendered. Tap it to edit; tap a checkbox to tick it. */
@Composable
private fun ChunkView(
    c: NoteChunk, lines: List<String>, body: TextStyle,
    onEdit: () -> Unit, onLink: (String) -> Unit, onUrl: (String) -> Unit, onTick: (Int) -> Unit,
) {
    val blocks = remember(c) { MdBlocks.parse(c.text.split("\n")) }
    val itemLines = remember(c) { NoteChunks.itemLines(lines, c) }
    var item = 0
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        if (c.kind == NoteChunk.Kind.CODE) {
            Text(
                c.text.lines().drop(1).dropLastWhile { it.trim().startsWith("```") }.joinToString("\n"),
                fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Surface1).clickable(onClick = onEdit).padding(12.dp)
            )
            return@Column
        }
        blocks.forEach { b ->
            when (b) {
                is MdBlock.Heading -> NoteRich(b.text, body.copy(
                    fontSize = when (b.level) { 1 -> 21.sp; 2 -> 18.sp; else -> 16.5.sp }, fontWeight = FontWeight.Bold, lineHeight = 26.sp
                ), Modifier.padding(top = 10.dp), onEdit, onLink, onUrl)
                is MdBlock.Para -> NoteRich(b.text, body.copy(color = Color(0xFFD5DBE8)), Modifier, onEdit, onLink, onUrl)
                is MdBlock.Item -> {
                    val line = itemLines.getOrNull(item++)
                    val check = CHECK.find(b.text)
                    Row(Modifier.padding(start = (b.indent * 18).dp, top = 3.dp, bottom = 3.dp), verticalAlignment = Alignment.Top) {
                        if (check != null && line != null) {
                            val ticked = check.groupValues[1] != " "
                            Box(
                                Modifier.padding(top = 3.dp, end = 10.dp).size(20.dp).clip(RoundedCornerShape(6.dp))
                                    .then(if (ticked) Modifier.background(Green) else Modifier.border(1.5.dp, Muted, RoundedCornerShape(6.dp)))
                                    .clickable { onTick(line) },
                                contentAlignment = Alignment.Center
                            ) { if (ticked) Text("✓", fontSize = 12.sp, color = Color(0xFF0D2A1D), fontWeight = FontWeight.ExtraBold) }
                            NoteRich(b.text.substring(check.range.last + 1),
                                if (ticked) body.copy(color = Muted, textDecoration = TextDecoration.LineThrough) else body,
                                Modifier.weight(1f), onEdit, onLink, onUrl)
                        } else {
                            Text(b.marker, style = body.copy(color = Muted), modifier = Modifier.width(24.dp))
                            NoteRich(b.text, body, Modifier.weight(1f), onEdit, onLink, onUrl)
                        }
                    }
                }
                is MdBlock.Quote -> Row(Modifier.padding(vertical = 2.dp)) {
                    Box(Modifier.width(3.dp).height(22.dp).clip(RoundedCornerShape(2.dp)).background(Primary))
                    NoteRich(b.text, body.copy(color = Muted, fontStyle = FontStyle.Italic), Modifier.padding(start = 12.dp), onEdit, onLink, onUrl)
                }
                is MdBlock.Code -> Text(b.text, fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Surface1).clickable(onClick = onEdit).padding(12.dp))
                is MdBlock.Image -> Text("🖼 " + b.src.substringAfterLast('/'), fontSize = 13.sp, color = Muted,
                    modifier = Modifier.clickable(onClick = onEdit).padding(vertical = 4.dp))
                MdBlock.Rule -> HorizontalDivider(color = Outline, modifier = Modifier.padding(vertical = 8.dp).clickable(onClick = onEdit))
            }
        }
    }
}

/** Quick edit: one block as markdown, with a toolbar for tags, checkboxes, links and bold. */
@Composable
private fun EditBlock(title: String, original: String, known: List<Pair<String, Int>>, onCancel: () -> Unit, onSave: (String) -> Unit) {
    var field by remember { mutableStateOf(TextFieldValue(original, TextRange(original.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    BackHandler { onCancel() }

    fun insert(s: String, back: Int = 0) {
        val sel = field.selection
        val t = field.text.replaceRange(sel.min, sel.max, s)
        field = TextFieldValue(t, TextRange(sel.min + s.length - back))
    }
    fun lineStart(prefix: String) {
        val at = field.text.lastIndexOf('\n', (field.selection.min - 1).coerceAtLeast(0)).let { if (field.selection.min == 0) 0 else it + 1 }
        val t = field.text.substring(0, at) + prefix + field.text.substring(at)
        field = TextFieldValue(t, TextRange(field.selection.min + prefix.length))
    }
    fun wrap(mark: String) {
        val sel = field.selection
        val mid = field.text.substring(sel.min, sel.max)
        val t = field.text.replaceRange(sel.min, sel.max, "$mark$mid$mark")
        field = TextFieldValue(t, if (mid.isEmpty()) TextRange(sel.min + mark.length) else TextRange(sel.min, sel.max + 2 * mark.length))
    }

    val typing = Tags.typing(field.text, field.selection.min)
    val suggestions = typing?.let { (_, q) -> Tags.suggest(q, known, 5) }.orEmpty()
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onCancel) { Text("Cancel", color = Muted, fontSize = 16.sp) }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { onSave(field.text) }) { Text("Save", color = Primary, fontWeight = FontWeight.Bold, fontSize = 16.sp) }
        }
        Column(Modifier.weight(1f).padding(horizontal = 18.dp).verticalScroll(rememberScrollState())) {
            Text(title, fontSize = 23.sp, fontWeight = FontWeight.Bold)
            Text("EDITING · MARKDOWN", fontSize = 10.5.sp, letterSpacing = 1.sp, color = Primary, fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
            OutlinedTextField(
                value = field, onValueChange = { field = it }, modifier = Modifier.fillMaxWidth().focusRequester(focus),
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, lineHeight = 22.sp, color = TextMain),
                shape = RoundedCornerShape(14.dp), colors = fieldColors(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
            )
            Text("Only this block changes. Save writes the note; Cancel leaves it as it was.", fontSize = 12.sp, color = Muted,
                modifier = Modifier.padding(top = 8.dp, bottom = 12.dp))
        }
        if (suggestions.isNotEmpty() && typing != null) Row(
            Modifier.fillMaxWidth().background(Surface1).horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            suggestions.forEach { (tag, _) ->
                TagChip(tag, false, onClick = {
                    val (t, cur) = Tags.complete(field.text, typing.first, tag)
                    field = TextFieldValue(t, TextRange(cur))
                })
            }
        }
        Row(
            Modifier.fillMaxWidth().background(Color(0xFF1A1F2B)).padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ToolKey("#", Modifier.weight(1f)) { insert("#") }
            ToolKey("[ ]", Modifier.weight(1f)) { lineStart("- [ ] ") }
            ToolKey("[[ ]]", Modifier.weight(1f)) { insert("[[]]", back = 2) }
            ToolKey("**B**", Modifier.weight(1f)) { wrap("**") }
            ToolKey("↶", Modifier.weight(1f)) { field = TextFieldValue(original, TextRange(original.length)) }
            ToolKey("Done", Modifier.weight(1.2f), filled = true) { onSave(field.text) }
        }
    }
}

@Composable
private fun ToolKey(label: String, modifier: Modifier, filled: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(10.dp)).background(if (filled) Primary else Surface2).clickable(onClick = onClick).padding(vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontSize = 13.sp, fontFamily = if (filled) FontFamily.Default else FontFamily.Monospace,
            fontWeight = if (filled) FontWeight.Bold else FontWeight.Normal, color = if (filled) OnPrimary else TextMain)
    }
}

/** Tags on this note: current ones (tap ✕ to drop), Work/Personal, vault suggestions, and where new ones go. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun NoteTagSheet(current: List<String>, known: List<Pair<String, Int>>, onDismiss: () -> Unit, onSave: (List<String>, Boolean) -> Unit) {
    val chosen = remember { mutableStateListOf<String>().apply { addAll(current) } }
    var query by remember { mutableStateOf("") }
    var toProps by remember { mutableStateOf(true) }
    val typed = Tags.norm(query)
    val matches = Tags.suggest(query, known.filter { it.first !in chosen }, 5)
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Surface1) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            Text("Tags on this note", fontSize = 19.sp, fontWeight = FontWeight.Bold)
            FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                chosen.toList().forEach { t -> TagChip(t, true, onClick = { chosen.remove(t) }) }
                Tags.BUILT_IN.filter { it !in chosen }.forEach { t -> TagChip(t, false, onClick = { chosen.add(t) }) }
            }
            if (chosen.isNotEmpty()) Text("Tap a tag to take it off", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 6.dp))
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp), shape = RoundedCornerShape(14.dp), singleLine = true,
                prefix = { Text("# ", color = Muted, fontFamily = FontFamily.Monospace) }, placeholder = { Text("add a tag") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None), colors = fieldColors()
            )
            matches.forEach { (tag, n) ->
                Row(
                    Modifier.fillMaxWidth().clickable { chosen.add(tag); query = "" }.padding(vertical = 11.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(Modifier.size(20.dp).clip(RoundedCornerShape(6.dp)).border(1.5.dp, Muted, RoundedCornerShape(6.dp)))
                    Text(tag, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Text(if (n == 1) "1 note" else "$n notes", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Muted)
                }
                HorizontalDivider(color = Outline)
            }
            if (typed != null && typed !in chosen && matches.none { it.first == typed }) {
                Text("＋ Create “$typed”", color = Primary, fontSize = 15.sp,
                    modifier = Modifier.fillMaxWidth().clickable { chosen.add(typed); query = "" }.padding(vertical = 11.dp, horizontal = 4.dp))
            }
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Note tags (properties)", fontSize = 14.sp)
                    Text(if (toProps) "New tags go in tags: at the top. Inline #tags in the text stay where they are."
                        else "New tags go on a line at the end of the note.", fontSize = 12.sp, color = Muted)
                }
                Switch(toProps, { toProps = it }, colors = SwitchDefaults.colors(checkedTrackColor = Primary, checkedThumbColor = OnPrimary))
            }
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SheetButton("Cancel", filled = false, onClick = onDismiss)
                val n = chosen.size
                SheetButton(if (n == 1) "Save 1 tag" else "Save $n tags", filled = true) {
                    val extra = typed?.takeIf { it !in chosen && query.isNotBlank() }
                    onSave(chosen.toList() + listOfNotNull(extra), toProps)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- query blocks

private val Faint = Color(0xFF5C6478)

/** "today", "yesterday", "tomorrow" or "5 Nov". */
private fun dayWord(d: LocalDate, today: LocalDate): String = when (d) {
    today -> "today"
    today.minusDays(1) -> "yesterday"
    today.plusDays(1) -> "tomorrow"
    else -> DataViews.shortDate(d, today)
}

/**
 * A ```tasks or ```dataview block, run against the vault index ([DataViews]) and shown as a card. Ticks write to
 * the task's own note; long-press edits the query. Blocks the app can't run get a card with Open in Obsidian.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QueryCard(
    chunk: String, notes: List<NoteInfo>, full: Boolean, path: String,
    onEdit: () -> Unit, onOpen: (String) -> Unit, onTick: (QueryTask) -> Unit, onAllTasks: () -> Unit, onObsidian: () -> Unit,
) {
    val q = remember(chunk) { DataViews.of(chunk) } ?: return
    var view by remember(chunk) { mutableStateOf<QueryView?>(null) }
    var showCode by remember(chunk) { mutableStateOf(false) }
    LaunchedEffect(chunk, notes, full) {
        if (full || q.first == "dataviewjs") view = withContext(Dispatchers.Default) { DataViews.run(q.first, q.second, notes, path) }
    }
    val today = remember { LocalDate.now() }
    val v = view
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(shape).background(Surface1).border(1.dp, Outline, shape)
            .combinedClickable(onClick = {}, onLongClick = onEdit)
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                v?.kind ?: q.first.uppercase(), fontFamily = FontFamily.Monospace, fontSize = 10.5.sp, color = Primary,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(PrimaryTint).padding(horizontal = 6.dp, vertical = 1.dp)
            )
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
                modifier = if (v is QueryView.Unsupported) Modifier.clickable { showCode = !showCode } else Modifier)
        }
        HorizontalDivider(color = Outline)
        when (v) {
            null -> Text(if (full) "Running…" else "Reading the vault…", fontSize = 13.sp, color = Muted, modifier = Modifier.padding(12.dp))
            is QueryView.Tasks -> {
                if (v.tasks.isEmpty()) Text("No matching tasks", fontSize = 13.sp, color = Muted, modifier = Modifier.padding(12.dp))
                v.tasks.forEachIndexed { i, qt ->
                    if (i > 0) HorizontalDivider(color = Outline)
                    QueryTaskRow(qt, today, onTick = { onTick(qt) }, onOpen = { onOpen(qt.path) })
                }
                Row(Modifier.fillMaxWidth().background(Color(0xFF131823)).padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(v.footer.ifEmpty { "${v.total} task${if (v.total == 1) "" else "s"}" }, fontSize = 12.sp, color = Muted, modifier = Modifier.weight(1f))
                    Text("All in Tasks ›", fontSize = 12.sp, color = Primary, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable(onClick = onAllTasks))
                }
            }
            is QueryView.Table -> BoxWithConstraints(Modifier.fillMaxWidth()) {
                val firstMin = if (v.headers.size > 1) maxWidth * 0.55f else maxWidth
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    v.headers.forEachIndexed { ci, h ->
                        Column(Modifier.width(IntrinsicSize.Max).then(if (ci == 0) Modifier.widthIn(min = firstMin) else Modifier)) {
                            Text(h.uppercase(), fontFamily = FontFamily.Monospace, fontSize = 10.5.sp, color = Muted, maxLines = 1,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp))
                            v.rows.forEach { row ->
                                HorizontalDivider(color = Outline)
                                QueryCellText(row.getOrNull(ci) ?: QueryCell(""), today, onOpen, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp))
                            }
                        }
                    }
                }
                if (v.rows.isEmpty()) Text("No results", fontSize = 13.sp, color = Muted, modifier = Modifier.padding(start = 12.dp, top = 36.dp, bottom = 10.dp))
            }
            is QueryView.Items -> Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                if (v.items.isEmpty()) Text("No results", fontSize = 13.sp, color = Muted, modifier = Modifier.padding(vertical = 6.dp))
                v.items.forEach { (main, extra) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
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
                    QueryButton("↗ Open in Obsidian", onObsidian)
                    if (v.script) QueryButton(if (showCode) "Hide code" else "Show code") { showCode = !showCode }
                }
            }
        }
        if (showCode) Text(
            q.second, fontFamily = FontFamily.Monospace, fontSize = 11.5.sp, lineHeight = 17.sp, color = Primary,
            modifier = Modifier.fillMaxWidth().background(PrimaryTint).padding(horizontal = 12.dp, vertical = 10.dp)
        )
    }
}

@Composable
private fun QueryButton(label: String, onClick: () -> Unit) {
    Text(label, fontSize = 12.5.sp, color = TextMain, modifier = Modifier.clip(RoundedCornerShape(999.dp))
        .border(1.dp, Outline, RoundedCornerShape(999.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 5.dp))
}

@Composable
private fun QueryCellText(c: QueryCell, today: LocalDate, onOpen: (String) -> Unit, modifier: Modifier) {
    val link = c.link
    when {
        link != null -> Text(c.text, fontSize = 14.sp, color = Primary, textDecoration = TextDecoration.Underline, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = modifier.clickable { onOpen(link) })
        c.date != null -> Text(c.text, fontSize = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 1,
            color = if (c.date!! < today) Warn else Muted, modifier = modifier)
        else -> Text(c.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (c.text == "—") Muted else TextMain, modifier = modifier)
    }
}

@Composable
private fun QueryTaskRow(qt: QueryTask, today: LocalDate, onTick: () -> Unit, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier.padding(top = 1.dp, end = 10.dp).size(20.dp).clip(RoundedCornerShape(6.dp))
                .then(if (qt.done) Modifier.background(Green) else Modifier.border(1.5.dp, Muted, RoundedCornerShape(6.dp)))
                .clickable(onClick = onTick),
            contentAlignment = Alignment.Center
        ) { if (qt.done) Text("✓", fontSize = 12.sp, color = Color(0xFF0D2A1D), fontWeight = FontWeight.ExtraBold) }
        Column(Modifier.weight(1f).clickable(onClick = onOpen)) {
            val s = remember(qt.text) { noteInline(qt.text) }
            Text(s, fontSize = 14.5.sp, lineHeight = 20.sp, color = if (qt.done) Muted else TextMain,
                textDecoration = if (qt.done) TextDecoration.LineThrough else null)
            val (label, late) = when {
                qt.due != null -> "due ${dayWord(qt.due!!, today)}" to (!qt.done && qt.due!! <= today)
                qt.scheduled != null -> "scheduled ${dayWord(qt.scheduled!!, today)}" to false
                qt.start != null -> "starts ${dayWord(qt.start!!, today)}" to false
                else -> "" to false
            }
            Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                val mark = qt.priorityMark
                if (mark.isNotEmpty() || label.isNotEmpty()) Text(listOf(mark, label).filter { it.isNotEmpty() }.joinToString(" "),
                    fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = if (late) Warn else Muted)
                Text((if (mark.isNotEmpty() || label.isNotEmpty()) " · " else "") + qt.note, fontSize = 11.5.sp, color = Faint,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
