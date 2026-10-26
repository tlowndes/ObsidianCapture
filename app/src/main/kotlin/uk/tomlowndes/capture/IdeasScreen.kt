package uk.tomlowndes.capture

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun IdeasScreen(vault: Uri?, prefs: AppPrefs, snack: SnackbarHostState, open: String?, onOpen: (String?) -> Unit) {
    val ctx = LocalContext.current
    val images = remember(vault) { mutableStateMapOf<String, Uri>() }
    LaunchedEffect(vault) {
        val uri = vault ?: return@LaunchedEffect
        val idx = withContext(Dispatchers.IO) { runCatching { IdeaStore(ctx, uri, prefs).imageUris() }.getOrNull() }
        idx?.let { images.putAll(it) }
    }
    if (open != null) IdeaDetail(vault, prefs, snack, open, images) { onOpen(null) }
    else IdeaList(vault, prefs, snack, images, onOpen)
}

// ---------------------------------------------------------------- list

@Composable
private fun IdeaList(
    vault: Uri?, prefs: AppPrefs, snack: SnackbarHostState, images: Map<String, Uri>, onOpen: (String?) -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // Show the last list straight away, then refresh from the vault.
    var ideas by remember { mutableStateOf(ProjectsCache.load(ctx)) }
    var query by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    var byUpdated by remember { mutableStateOf(prefs.ideasByUpdated) }
    var menu by remember { mutableStateOf(false) }

    fun reload() {
        val uri = vault ?: return
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { IdeaStore(ctx, uri, prefs).list() } }
            r.onSuccess { ideas = it; ProjectsCache.save(ctx, it) }.onFailure { snack.showSnackbar("Couldn't read projects: ${it.message}") }
        }
    }
    LaunchedEffect(vault) { reload() }

    /** Runs one change on the vault, then refreshes the list. */
    fun saveTo(change: (IdeaStore) -> Unit) {
        val uri = vault ?: return
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { change(IdeaStore(ctx, uri, prefs)) } }
            r.onSuccess { reload() }.onFailure { snack.showSnackbar("Couldn't save: ${it.message}") }
        }
    }

    val shown = IdeaStore.sorted(ideas.orEmpty(), byUpdated).filter {
        query.isBlank() || it.title.contains(query, true) || it.snippet.contains(query, true)
    }

    var searching by remember { mutableStateOf(false) }
    var showDone by remember { mutableStateOf(false) }
    var sheetFor by remember { mutableStateOf<IdeaSummary?>(null) }
    val counts = ideas.orEmpty().groupingBy { ProjectGroup.of(it) }.eachCount()

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            Column(Modifier.padding(top = 8.dp)) {
                ScreenTitle("Projects") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        HeaderIcon("🔍") { searching = !searching; if (!searching) query = "" }
                        Box {
                            HeaderIcon("⇅") { menu = true }
                            DropdownMenu(menu, { menu = false }, modifier = Modifier.background(Surface2)) {
                                DropdownMenuItem(text = { Text((if (byUpdated) "● " else "") + "Last updated") },
                                    onClick = { byUpdated = true; prefs.ideasByUpdated = true; menu = false })
                                DropdownMenuItem(text = { Text((if (!byUpdated) "● " else "") + "A–Z") },
                                    onClick = { byUpdated = false; prefs.ideasByUpdated = false; menu = false })
                            }
                        }
                    }
                }
                Text(
                    listOfNotNull(
                        counts[ProjectGroup.ACTIVE]?.let { "$it active" }, counts[ProjectGroup.PAUSED]?.let { "$it paused" },
                        counts[ProjectGroup.IDEAS]?.let { "$it ideas" }
                    ).joinToString(" · ").ifEmpty { "${prefs.ideasDir} · ${ideas?.size ?: 0}" },
                    color = Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 3.dp, bottom = 8.dp)
                )
            }
            if (searching) OutlinedTextField(
                query, { query = it }, placeholder = { Text("Search projects") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp), shape = CircleShape, colors = fieldColors()
            )
            if (ideas != null && shown.isEmpty()) {
                Text(if (ideas!!.isEmpty()) "No projects yet" else "Nothing matches.", color = Muted, modifier = Modifier.padding(top = 12.dp))
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 90.dp)) {
                // Active first, then Paused and Ideas. Done folds into one row. Empty groups are left out.
                listOf(ProjectGroup.ACTIVE, ProjectGroup.PAUSED, ProjectGroup.IDEAS).forEach { group ->
                    val list = shown.filter { ProjectGroup.of(it) == group }
                    if (list.isNotEmpty()) {
                        item(key = group.name) { Box(Modifier.padding(top = 16.dp, bottom = 2.dp)) { MonoLabel(group.label) } }
                        items(list, key = { it.title }) { idea -> ProjectRow(idea, { sheetFor = idea }) { onOpen(idea.title) } }
                    }
                }
                val done = shown.filter { ProjectGroup.of(it) == ProjectGroup.DONE }
                if (done.isNotEmpty()) {
                    // A search shows matching done projects without having to open the fold.
                    val open = showDone || query.isNotBlank()
                    item(key = "done") {
                        Column(Modifier.fillMaxWidth().clickable { showDone = !showDone }) {
                            Row(Modifier.fillMaxWidth().padding(vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                                StatusGlyph("done")
                                Text("Done · ${done.size}", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(start = 12.dp).weight(1f))
                                Text(if (open) "⌄" else "›", color = Muted, fontSize = 16.sp)
                            }
                            HorizontalDivider(color = Outline)
                        }
                    }
                    if (open) items(done, key = { it.title }) { idea -> ProjectRow(idea, { sheetFor = idea }) { onOpen(idea.title) } }
                }
            }
        }
        Box(
            Modifier.align(Alignment.BottomEnd).padding(20.dp).size(56.dp).clip(RoundedCornerShape(18.dp)).background(Primary)
                .clickable { if (vault == null) scope.launch { snack.showSnackbar("Pick your vault in Settings first") } else creating = true },
            contentAlignment = Alignment.Center
        ) { Text("＋", color = OnPrimary, fontWeight = FontWeight.Bold, fontSize = 24.sp) }
    }

    sheetFor?.let { p ->
        ProjectSheet(
            p.title, p.status, p.area, p.type,
            onStatus = { s -> saveTo { it.setStatus(p.title, s) } },
            onArea = { a -> saveTo { it.setArea(p.title, a) } },
            onType = { t -> saveTo { it.setType(p.title, t) } },
            onDismiss = { sheetFor = null }
        )
    }

    if (creating) NewIdeaDialog(
        onCreate = { title, summary ->
            val uri = vault ?: return@NewIdeaDialog
            scope.launch {
                val r = withContext(Dispatchers.IO) { runCatching { IdeaStore(ctx, uri, prefs).create(title, summary) } }
                r.onSuccess { creating = false; onOpen(it) }.onFailure { snack.showSnackbar(it.message ?: "Failed") }
            }
        },
        onDismiss = { creating = false }
    )
}

/** Two lines per project: name and area dot, then the next step. The status icon opens [ProjectSheet]. */
@Composable
private fun ProjectRow(idea: IdeaSummary, onStatus: () -> Unit, onClick: () -> Unit) {
    val modified = Instant.ofEpochMilli(idea.modified).atZone(ZoneId.systemDefault()).toLocalDate()
    val today = modified == LocalDate.now()
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 11.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.padding(top = 2.dp).clip(CircleShape).clickable(onClick = onStatus).padding(2.dp)) { StatusGlyph(idea.status) }
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(idea.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false))
                    idea.area?.let { AreaDot(it, Modifier.padding(start = 7.dp)) }
                }
                val line = idea.next ?: idea.snippet.takeIf { it.isNotBlank() }
                Text(
                    line ?: "No next step", fontSize = 12.5.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontStyle = if (line == null) FontStyle.Italic else FontStyle.Normal, modifier = Modifier.padding(top = 2.dp)
                )
            }
            Text(
                if (today) "Today" else modified.format(DateTimeFormatter.ofPattern("d MMM", Locale.UK)),
                fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = if (today) TextMain else Muted,
                modifier = Modifier.padding(start = 8.dp, top = 3.dp)
            )
        }
        HorizontalDivider(color = Outline)
    }
}

@Composable
private fun AreaDot(area: String, modifier: Modifier = Modifier) {
    Box(modifier.size(7.dp).clip(CircleShape).background(if (area == "work") WorkEdge else PersonalEdge))
}

@Composable
private fun MonoLabel(text: String, color: Color = Muted) {
    Text(text.uppercase(Locale.UK), fontSize = 11.sp, letterSpacing = 1.2.sp, color = color, fontFamily = FontFamily.Monospace)
}

@Composable
private fun HeaderIcon(icon: String, onClick: () -> Unit) {
    Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(icon, fontSize = 17.sp, color = Muted)
    }
}
@Composable
private fun NewIdeaDialog(onCreate: (String, String) -> Unit, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf("") }
    var summary by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Surface1,
        title = { Text("New project") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true, colors = fieldColors(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
                OutlinedTextField(summary, { summary = it }, label = { Text("Summary (optional)") }, minLines = 3, colors = fieldColors(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
            }
        },
        confirmButton = { TextButton(onClick = { onCreate(title, summary) }, enabled = title.isNotBlank()) { Text("Create", color = Primary) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Muted) } }
    )
}

// ---------------------------------------------------------------- detail

private val ENTRY_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

private fun entryLabel(heading: String): String = runCatching {
    val dt = LocalDateTime.parse(heading, ENTRY_FMT)
    val d = dt.toLocalDate()
    val today = LocalDate.now()
    val day = when (d) { today -> "Today"; today.minusDays(1) -> "Yesterday"; else -> d.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)) }
    "$day · ${dt.format(DateTimeFormatter.ofPattern("HH:mm"))}"
}.getOrDefault(heading)

@Composable
private fun IdeaDetail(
    vault: Uri?, prefs: AppPrefs, snack: SnackbarHostState, title: String, images: MutableMap<String, Uri>, onBack: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var doc by remember { mutableStateOf<IdeaDoc?>(null) }
    var notes by remember { mutableStateOf<Pair<String?, String?>>(null to null) }
    var github by remember { mutableStateOf<GitHubRepo.Activity?>(null) }
    var githubError by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf(prefs.draft("idea_$title")) }
    val pending = remember { mutableStateListOf<Uri>() }
    var busy by remember { mutableStateOf(false) }
    var editSummary by remember { mutableStateOf(false) }
    var editIndex by remember { mutableStateOf<Int?>(null) }
    var viewImage by remember { mutableStateOf<Uri?>(null) }
    var sheet by remember { mutableStateOf(false) }
    var tab by remember(title) { mutableStateOf(0) }
    var composing by remember { mutableStateOf(false) }
    var links by remember { mutableStateOf<List<IdeaNote.Link>>(emptyList()) }
    var repo by remember { mutableStateOf<Pair<String, String>?>(null) }
    val focus = remember { FocusRequester() }
    var cameraFile by remember { mutableStateOf<File?>(null) }

    fun reload() {
        val uri = vault ?: return
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val store = IdeaStore(ctx, uri, prefs)
                    store.load(title) to (store.notePart(title, "Changelog") to store.notePart(title, "Decisions"))
                }
            }
            r.onSuccess { doc = it.first; notes = it.second }.onFailure { snack.showSnackbar("Couldn't open project: ${it.message}") }
            // The repo line needs a token on the phone and a github.com link in the note.
            val token = SecretStore(ctx).githubToken
            val noteText = withContext(Dispatchers.IO) { runCatching { IdeaStore(ctx, uri, prefs).noteText(title) }.getOrDefault("") }
            links = IdeaNote.links(noteText)
            val found = GitHubRepo.repoOf(noteText)
            repo = found
            github = null
            githubError = false
            if (token != null && found != null) {
                withContext(Dispatchers.IO) { runCatching { GitHubRepo.fetch(found.first, found.second, token) } }
                    .onSuccess { github = it }.onFailure { githubError = true }
            }
        }
    }
    LaunchedEffect(title, vault) { reload() }

    fun run(block: IdeaStore.() -> Unit) {
        val uri = vault ?: return
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { IdeaStore(ctx, uri, prefs).block() } }
            r.onFailure { snack.showSnackbar(it.message ?: "Failed") }
            reload()
        }
    }

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(6)) { uris -> pending.addAll(uris) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = cameraFile
        if (ok && f != null) pending.add(Uri.fromFile(f))
    }

    fun takePhoto() {
        val dir = File(ctx.cacheDir, "camera").apply { mkdirs() }
        val f = File.createTempFile("cam", ".jpg", dir)
        cameraFile = f
        camera.launch(FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f))
    }

    fun addToLog() {
        val uri = vault ?: return
        if (busy || (text.isBlank() && pending.isEmpty())) return
        busy = true
        val body = text
        val picked = pending.toList()
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val store = IdeaStore(ctx, uri, prefs)
                    val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                    val saved = picked.mapIndexed { i, src ->
                        store.saveImage(ctx, src, "${IdeaStore.safeName(title)}-$stamp-${i + 1}")
                    }
                    store.addEntry(title, body, saved.map { it.first })
                    saved
                }
            }
            busy = false
            r.onSuccess { saved ->
                saved.forEach { (name, u) -> images[name] = u }
                text = ""; pending.clear(); prefs.setDraft("idea_$title", ""); composing = false
                reload()
            }.onFailure { snack.showSnackbar("Failed: ${it.message}") }
        }
    }

    val d = doc
    Column(Modifier.fillMaxSize().imePadding()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Text("‹ Projects", color = Primary, fontSize = 14.sp,
                modifier = Modifier.padding(top = 10.dp, bottom = 12.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onBack).padding(vertical = 4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.clip(CircleShape).clickable(enabled = d != null) { sheet = true }.padding(3.dp)) { StatusGlyph(d?.status ?: "idea", 18.dp) }
                Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp))
            }
            Text(
                listOfNotNull(d?.area, d?.type, d?.entries?.firstOrNull()?.let { "last log " + entryLabel(it.heading).substringBefore(" · ").let { day -> if (day in setOf("Today", "Yesterday")) day.lowercase() else day } })
                    .joinToString(" · "),
                fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Muted,
                modifier = Modifier.padding(start = 32.dp, top = 4.dp, bottom = 16.dp).clickable(enabled = d != null) { sheet = true }
            )

            d?.next?.let { next ->
                Column(
                    Modifier.fillMaxWidth().padding(bottom = 14.dp).clip(RoundedCornerShape(18.dp)).background(NextBg)
                        .border(1.dp, NextBorder, RoundedCornerShape(18.dp)).padding(horizontal = 15.dp, vertical = 13.dp)
                ) {
                    MonoLabel("Next step", Primary)
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(20.dp).clip(RoundedCornerShape(6.dp)).border(1.5.dp, Muted, RoundedCornerShape(6.dp))
                            .clickable { run { tickNext(title) } })
                        Text(next, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 10.dp))
                    }
                }
            }

            Text(
                d?.summary?.ifBlank { "Add a summary" } ?: "",
                fontSize = 13.5.sp, lineHeight = 20.sp, color = if (d?.summary.isNullOrBlank()) Muted else TextMain,
                modifier = Modifier.fillMaxWidth().clickable(enabled = d != null) { editSummary = true }.padding(bottom = 12.dp)
            )
            github?.let { GitHubLine(it) { repo?.let { (o, r) -> openLink(ctx, "https://github.com/$o/$r") } } }
            if (githubError) Text("Couldn't reach GitHub. Check the token in Settings.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))

            val tabs = buildList {
                add("Log" + (d?.entries?.size?.takeIf { it > 0 }?.let { " $it" } ?: ""))
                if (!notes.first.isNullOrBlank()) add("Changelog")
                if (!notes.second.isNullOrBlank()) add("Decisions")
                if (links.isNotEmpty()) add("Links")
            }
            val current = tabs.getOrElse(tab) { tabs.first() }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                tabs.forEachIndexed { i, t ->
                    val on = t == current
                    Text(
                        t, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = if (on) TextMain else Muted,
                        modifier = Modifier.clickable { tab = i }.drawBehind {
                            if (on) drawLine(Primary, Offset(0f, size.height), Offset(size.width, size.height), 2.dp.toPx())
                        }.padding(vertical = 9.dp)
                    )
                }
            }
            HorizontalDivider(color = Outline)

            when {
                current.startsWith("Log") -> {
                    if (d != null && d.entries.isEmpty()) Text("Nothing logged yet.", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
                    d?.entries?.forEachIndexed { i, e -> EntryRow(e, images, onEdit = { editIndex = i }, onImage = { viewImage = it }) }
                }
                current == "Changelog" -> NotesText(notes.first.orEmpty())
                current == "Decisions" -> NotesText(notes.second.orEmpty())
                else -> links.forEach { l ->
                    Column(Modifier.fillMaxWidth().clickable {
                        if (l.web) openLink(ctx, l.target) else vaultName(vault)?.let { v ->
                            openLink(ctx, "obsidian://open?vault=${Uri.encode(v)}&file=${Uri.encode(l.target)}")
                        }
                    }) {
                        Text(l.label + if (l.web) "  ↗" else "", color = Primary, fontSize = 14.sp, modifier = Modifier.padding(vertical = 12.dp))
                        HorizontalDivider(color = Outline)
                    }
                }
            }
            Box(Modifier.height(16.dp))
        }

        Column(Modifier.fillMaxWidth().background(NavBg).padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp)) {
            if (!composing && text.isBlank() && pending.isEmpty()) {
                // One line until tapped, so the log gets the screen.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    RoundIconButton("📷") { takePhoto() }
                    Text(
                        "Add to the log…", color = Muted, fontSize = 14.sp,
                        modifier = Modifier.weight(1f).clip(CircleShape).border(1.dp, Outline, CircleShape).clickable { composing = true }
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                }
            } else {
                if (pending.isNotEmpty()) {
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pending.forEach { u ->
                            Box {
                                AsyncImage(u, null, Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop)
                                Text("✕", color = Color.White, fontSize = 12.sp,
                                    modifier = Modifier.align(Alignment.TopEnd).padding(2.dp).clip(CircleShape).background(Color(0xAA000000))
                                        .clickable { pending.remove(u) }.padding(horizontal = 6.dp, vertical = 2.dp))
                            }
                        }
                    }
                }
                OutlinedTextField(
                    text, { text = it; prefs.setDraft("idea_$title", it) },
                    placeholder = { Text("Add to the log…") }, maxLines = 5, shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus), colors = fieldColors(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
                )
                LaunchedEffect(composing) { if (composing) runCatching { focus.requestFocus() } }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RoundIconButton("📷") { takePhoto() }
                        RoundIconButton("🖼") { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (text.isBlank() && pending.isEmpty()) TextButton(onClick = { composing = false }) { Text("Cancel", color = Muted) }
                        Button(
                            onClick = ::addToLog, enabled = !busy && (text.isNotBlank() || pending.isNotEmpty()),
                            colors = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = OnPrimary),
                            shape = CircleShape, modifier = Modifier.height(46.dp)
                        ) { Text(if (busy) "Saving…" else "Add to log", fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
    }

    if (sheet && d != null) ProjectSheet(
        title, d.status, d.area, d.type,
        onStatus = { s -> run { setStatus(title, s) } },
        onArea = { a -> run { setArea(title, a) } },
        onType = { t -> run { setType(title, t) } },
        couldContinue = if (d.status == "shelved") d.couldContinue else null,
        onCouldContinue = { on -> run { setCouldContinue(title, on) } },
        onDismiss = { sheet = false }
    )
    if (editSummary && d != null) TextEditDialog("Summary", d.summary, onSave = { editSummary = false; run { setSummary(title, d.summary, it) } },
        onDismiss = { editSummary = false })

    editIndex?.let { i ->
        val e = d?.entries?.getOrNull(i)
        if (e != null) TextEditDialog(
            entryLabel(e.heading), e.text,
            onSave = { editIndex = null; run { editEntry(title, e, it) } },
            onDelete = { editIndex = null; run { deleteEntry(title, e) } },
            onDismiss = { editIndex = null }
        )
    }

    viewImage?.let { u ->
        Dialog(onDismissRequest = { viewImage = null }) {
            AsyncImage(u, null, Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { viewImage = null },
                contentScale = ContentScale.Fit)
        }
    }
}

/** The repo's last push, open pull requests and latest release, on one line. */
@Composable
private fun GitHubLine(a: GitHubRepo.Activity, onRepo: () -> Unit) {
    val push = a.lastPush?.let { runCatching { LocalDate.parse(it).format(DateTimeFormatter.ofPattern("d MMM", Locale.UK)) }.getOrDefault(it) } ?: "–"
    HorizontalDivider(color = Outline)
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        listOf("push" to push, "PRs" to "${a.openPrs}", "release" to (a.release ?: "none")).forEach { (k, v) ->
            Text(
                androidx.compose.ui.text.buildAnnotatedString {
                    append("$k ")
                    pushStyle(androidx.compose.ui.text.SpanStyle(color = TextMain)); append(v); pop()
                },
                fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Muted
            )
        }
        Box(Modifier.weight(1f))
        Text("Repo ↗", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Primary, modifier = Modifier.clickable(onClick = onRepo))
    }
    HorizontalDivider(color = Outline, modifier = Modifier.padding(bottom = 8.dp))
}

/** The project's own Changelog or Decisions note, as it's written. */
@Composable
private fun NotesText(body: String) {
    Text(body, fontSize = 13.sp, lineHeight = 19.sp, color = TextMain, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
}

private fun openLink(ctx: android.content.Context, url: String) {
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** The vault's folder name, which Obsidian uses as the vault name in `obsidian://` links. */
private fun vaultName(vault: Uri?): String? = vault?.lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/')

@Composable
private fun RoundIconButton(icon: String, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).background(Surface2).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(icon, fontSize = 18.sp)
    }
}

@Composable
private fun EntryRow(e: IdeaEntry, images: Map<String, Uri>, onEdit: () -> Unit, onImage: (Uri) -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onEdit)) {
        Column(Modifier.padding(vertical = 10.dp)) {
            Text(entryLabel(e.heading), fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Muted)
            if (e.text.isNotBlank()) Text(e.text, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 2.dp))
            if (e.images.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    e.images.forEach { name ->
                        val u = images[name]
                        Box(Modifier.size(width = 110.dp, height = 78.dp).clip(RoundedCornerShape(12.dp)).background(Surface2)
                            .clickable(enabled = u != null) { u?.let(onImage) }, contentAlignment = Alignment.Center) {
                            if (u != null) AsyncImage(u, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            else Text("🖼", color = Muted)
                        }
                    }
                }
            }
        }
        HorizontalDivider(color = Outline)
    }
}
@Composable
private fun TextEditDialog(
    title: String, initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit, onDelete: (() -> Unit)? = null
) {
    var v by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Surface1,
        title = { Text(title, fontSize = 16.sp) },
        text = {
            OutlinedTextField(v, { v = it }, minLines = 4, maxLines = 12, colors = fieldColors(), modifier = Modifier.fillMaxWidth(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
        },
        confirmButton = { TextButton(onClick = { onSave(v) }) { Text("Save", color = Primary) } },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete", color = Warn) }
                TextButton(onClick = onDismiss) { Text("Cancel", color = Muted) }
            }
        }
    )
}

/** The sections on the Projects tab, from the note's status. */
private enum class ProjectGroup(val label: String) {
    ACTIVE("Active"), PAUSED("Paused"), IDEAS("Ideas"), DONE("Done");

    companion object {
        /** The statuses the app can set, as written in the note. */
        val STATUSES = listOf("active", "paused", "idea", "done")

        fun of(idea: IdeaSummary): ProjectGroup = of(idea.status)

        fun of(status: String): ProjectGroup = when (status.lowercase()) {
            "done", "shelved" -> DONE
            "paused" -> PAUSED
            "idea" -> IDEAS
            else -> ACTIVE
        }
    }
}


/** Linear-style status icon: ringed dot = active, bars = paused, dashed ring = idea, filled tick = done. */
@Composable
private fun StatusGlyph(status: String, size: androidx.compose.ui.unit.Dp = 16.dp) {
    val s = status.lowercase()
    Canvas(Modifier.size(size)) {
        val stroke = 1.6.dp.toPx()
        val r = this.size.minDimension / 2
        when (s) {
            "paused" -> {
                drawCircle(Amber, r - stroke / 2, style = Stroke(stroke))
                val h = r * 0.75f
                listOf(-0.28f, 0.28f).forEach { dx ->
                    val x = center.x + r * dx
                    drawLine(Amber, Offset(x, center.y - h / 2), Offset(x, center.y + h / 2), 2.dp.toPx())
                }
            }
            "idea" -> drawCircle(Muted, r - stroke / 2, style = Stroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.5.dp.toPx(), 2.dp.toPx()))))
            "done", "shelved" -> {
                drawCircle(Purple, r)
                val p = Path().apply {
                    moveTo(center.x - r * 0.45f, center.y)
                    lineTo(center.x - r * 0.1f, center.y + r * 0.35f)
                    lineTo(center.x + r * 0.5f, center.y - r * 0.35f)
                }
                drawPath(p, Color(0xFF1A1433), style = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            else -> {
                drawCircle(Green, r - stroke / 2, style = Stroke(stroke))
                drawCircle(Green, r - 3.dp.toPx())
            }
        }
    }
}

/** One sheet for status, Work/Personal and type. Each tap saves to the note's frontmatter. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectSheet(
    title: String, status: String, area: String?, type: String?,
    onStatus: (String) -> Unit, onArea: (String) -> Unit, onType: (String) -> Unit, onDismiss: () -> Unit,
    couldContinue: Boolean? = null, onCouldContinue: (Boolean) -> Unit = {}
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Surface1) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Box(Modifier.padding(top = 2.dp, bottom = 10.dp)) { MonoLabel("Status") }
            ProjectGroup.STATUSES.forEach { s ->
                val on = ProjectGroup.of(s) == ProjectGroup.of(status)
                HorizontalDivider(color = Outline)
                Row(Modifier.fillMaxWidth().clickable { onStatus(s); onDismiss() }.padding(vertical = 13.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    StatusGlyph(s)
                    Text(s.replaceFirstChar { it.uppercase() }, fontSize = 15.sp, color = if (on) Green else TextMain,
                        modifier = Modifier.padding(start = 12.dp).weight(1f))
                    if (on) Text("✓", color = Green, fontSize = 13.sp)
                }
            }
            Box(Modifier.padding(top = 18.dp, bottom = 8.dp)) { MonoLabel("Area") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("work" to "Work", "personal" to "Personal").forEach { (a, label) ->
                    SheetOption(area == a, Modifier.weight(1f), { onArea(a); onDismiss() }) {
                        AreaDot(a); Text(label, fontSize = 13.sp, color = if (area == a) Primary else TextMain, modifier = Modifier.padding(start = 7.dp))
                    }
                }
            }
            Box(Modifier.padding(top = 18.dp, bottom = 8.dp)) { MonoLabel("Type") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IdeaNote.TYPES.forEach { t ->
                    SheetOption(type == t, Modifier.weight(1f), { onType(t); onDismiss() }) {
                        Text(t, fontSize = 13.sp, color = if (type == t) Primary else TextMain)
                    }
                }
            }
            couldContinue?.let { cc ->
                Row(Modifier.fillMaxWidth().padding(top = 14.dp).clickable { onCouldContinue(!cc); onDismiss() }.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("Could continue", fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Text(if (cc) "On" else "Off", color = if (cc) Primary else Muted, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun SheetOption(on: Boolean, modifier: Modifier, onClick: () -> Unit, content: @Composable () -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(12.dp)).background(if (on) Color(0xFF1B2440) else Color.Transparent)
            .border(1.dp, if (on) Primary else Outline, RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
    ) { content() }
}
