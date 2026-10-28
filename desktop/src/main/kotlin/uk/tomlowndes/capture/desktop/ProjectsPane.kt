package uk.tomlowndes.capture.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.dragData
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch
import uk.tomlowndes.capture.GitHubRepo
import uk.tomlowndes.capture.IdeaDoc
import uk.tomlowndes.capture.IdeaEntry
import uk.tomlowndes.capture.IdeaNote
import uk.tomlowndes.capture.IdeaStore
import uk.tomlowndes.capture.IdeaSummary
import java.awt.FileDialog
import java.awt.Frame
import java.awt.image.BufferedImage
import java.io.File
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The sections of the list, from the note's status. */
private enum class ProjectGroup(val label: String) {
    ACTIVE("Active"), PAUSED("Paused"), IDEAS("Ideas"), DONE("Done");

    companion object {
        /** The statuses the app can set, as written in the note. */
        val STATUSES = listOf("active", "paused", "idea", "done")

        fun of(status: String): ProjectGroup = when (status.lowercase()) {
            "done", "shelved" -> DONE
            "paused" -> PAUSED
            "idea" -> IDEAS
            else -> ACTIVE
        }
    }
}

/** Projects: the quiet list beside the open project, with its next step, GitHub line, tabs and a log composer that takes images. */
@Composable
fun ProjectsPane(state: AppState) {
    val scope = rememberCoroutineScope()
    var projects by remember { mutableStateOf<List<IdeaSummary>?>(null) }
    var open by remember { mutableStateOf(state.openProject) }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var byUpdated by remember { mutableStateOf(state.prefs.ideasByUpdated) }
    var sortMenu by remember { mutableStateOf(false) }
    var showDone by remember { mutableStateOf(false) }
    val images = remember(state.vault) { mutableStateMapOf<String, Any>() }

    fun reload() = scope.launch {
        val list = state.io("Couldn't read projects") { state.ideaStore()!!.list() } ?: return@launch
        projects = list
        if (open == null || list.none { it.title == open }) {
            open = IdeaStore.sorted(list, byUpdated).firstOrNull { ProjectGroup.of(it.status) == ProjectGroup.ACTIVE }?.title ?: list.firstOrNull()?.title
        }
    }
    LaunchedEffect(state.vault, state.changes) {
        reload()
        state.io { state.ideaStore()!!.imageIndex() }?.let { images.putAll(it) }
    }
    LaunchedEffect(state.openProject) { state.openProject?.let { open = it; state.openProject = null } }

    fun saveTo(change: IdeaStore.() -> Unit) = scope.launch {
        state.io("Couldn't save") { state.ideaStore()!!.change() }
        reload(); state.changed()
    }

    val all = projects.orEmpty()
    val shown = IdeaStore.sorted(all, byUpdated).filter {
        query.isBlank() || it.title.contains(query.trim(), true) || it.snippet.contains(query.trim(), true)
    }
    val counts = all.groupingBy { ProjectGroup.of(it.status) }.eachCount()

    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(440.dp).fillMaxHeight().padding(horizontal = 24.dp, vertical = 26.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    H1("Projects", 28)
                    Text(
                        listOfNotNull(
                            counts[ProjectGroup.ACTIVE]?.let { "$it active" }, counts[ProjectGroup.PAUSED]?.let { "$it paused" },
                            counts[ProjectGroup.IDEAS]?.let { "$it ideas" }
                        ).joinToString(" · ").ifEmpty { state.prefs.ideasDir },
                        color = Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                    )
                }
                HeaderIcon("🔍") { searching = !searching; if (!searching) query = "" }
                Box {
                    HeaderIcon("⇅") { sortMenu = true }
                    DropdownMenu(sortMenu, { sortMenu = false }, Modifier.background(Menu)) {
                        fun sortBy(updated: Boolean) { byUpdated = updated; state.prefs.ideasByUpdated = updated; sortMenu = false }
                        DropdownMenuItem({ Text((if (byUpdated) "● " else "") + "Last updated") }, { sortBy(true) })
                        DropdownMenuItem({ Text((if (!byUpdated) "● " else "") + "A–Z") }, { sortBy(false) })
                    }
                }
                HeaderIcon("＋", Primary) { creating = true }
            }
            if (searching) Field(query, { query = it }, Modifier.fillMaxWidth().padding(bottom = 4.dp), "Search projects", shape = CircleShape)
            if (projects != null && shown.isEmpty()) Sub(if (all.isEmpty()) "No projects yet." else "Nothing matches.", Modifier.padding(top = 12.dp))
            LazyColumn(Modifier.fillMaxSize()) {
                // Active first, then Paused and Ideas. Done folds into one row. Empty groups are left out.
                listOf(ProjectGroup.ACTIVE, ProjectGroup.PAUSED, ProjectGroup.IDEAS).forEach { group ->
                    val list = shown.filter { ProjectGroup.of(it.status) == group }
                    if (list.isNotEmpty()) {
                        item(key = group.name) { Box(Modifier.padding(top = 16.dp, bottom = 4.dp)) { MonoLabel(group.label) } }
                        items(list, key = { it.title }) { p ->
                            ProjectRow(p, p.title == open, onStatus = { s -> saveTo { setStatus(p.title, s) } },
                                onArea = { a -> saveTo { setArea(p.title, a) } }, onType = { t -> saveTo { setType(p.title, t) } }) { open = p.title }
                        }
                    }
                }
                val done = shown.filter { ProjectGroup.of(it.status) == ProjectGroup.DONE }
                if (done.isNotEmpty()) {
                    // A search shows matching done projects without opening the fold.
                    val unfolded = showDone || query.isNotBlank()
                    item(key = "done") {
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).hand().clickable { showDone = !showDone }) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                                StatusGlyph("done")
                                Text("Done · ${done.size}", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(start = 12.dp).weight(1f))
                                Text(if (unfolded) "⌄" else "›", color = Muted, fontSize = 16.sp)
                            }
                            Line()
                        }
                    }
                    if (unfolded) items(done, key = { it.title }) { p ->
                        ProjectRow(p, p.title == open, onStatus = { s -> saveTo { setStatus(p.title, s) } },
                            onArea = { a -> saveTo { setArea(p.title, a) } }, onType = { t -> saveTo { setType(p.title, t) } }) { open = p.title }
                    }
                }
            }
        }
        Box(Modifier.fillMaxHeight().width(1.dp).background(Divider))
        Box(Modifier.weight(1f).fillMaxHeight()) {
            val title = open
            if (title == null) Sub("Pick a project, or make a new one.", Modifier.padding(36.dp))
            else ProjectDetail(state, title, images, onChanged = { reload(); state.changed() })
        }
    }

    if (creating) NewProjectDialog(onDismiss = { creating = false }) { title, summary ->
        scope.launch {
            val made = state.io { state.ideaStore()!!.create(title, summary) } ?: return@launch
            creating = false; open = made; reload()
        }
    }
}

@Composable
private fun HeaderIcon(icon: String, color: Color = Muted, onClick: () -> Unit) {
    Box(Modifier.size(36.dp).clip(CircleShape).hand().clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(icon, fontSize = 17.sp, color = color)
    }
}

@Composable
private fun MonoLabel(text: String, color: Color = Muted) =
    Text(text.uppercase(Locale.UK), fontSize = 11.sp, letterSpacing = 1.4.sp, color = color, fontFamily = FontFamily.Monospace)

@Composable
private fun Line(modifier: Modifier = Modifier) = Box(modifier.fillMaxWidth().height(1.dp).background(Outline))

@Composable
private fun AreaDot(area: String, modifier: Modifier = Modifier) {
    Box(modifier.size(6.dp).clip(CircleShape).background(if (area == "work") WorkEdge else PersonalEdge))
}

private fun shortDay(millis: Long): String {
    val d = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
    return if (d == LocalDate.now()) "Today" else d.format(DateTimeFormatter.ofPattern("d MMM", Locale.UK))
}

/** Two lines per project: name and area dot, then the next step. The status icon opens [ProjectMenu]. */
@Composable
private fun ProjectRow(
    p: IdeaSummary, selected: Boolean,
    onStatus: (String) -> Unit, onArea: (String) -> Unit, onType: (String) -> Unit, onClick: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clip(shape).background(if (selected) PrimaryTint else Color.Transparent)
                .hand().clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 11.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box {
                Box(Modifier.padding(top = 1.dp).clip(CircleShape).hand().clickable { menu = true }.padding(2.dp)) { StatusGlyph(p.status) }
                ProjectMenu(menu, { menu = false }, p.title, p.status, p.area, p.type, onStatus, onArea, onType)
            }
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p.title, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, color = TextMain, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    p.area?.let { AreaDot(it, Modifier.padding(start = 7.dp)) }
                }
                val line = p.next ?: p.snippet.takeIf { it.isNotBlank() }
                Text(
                    line ?: "No next step", fontSize = 12.5.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontStyle = if (line == null) FontStyle.Italic else FontStyle.Normal, modifier = Modifier.padding(top = 2.dp)
                )
            }
            val day = shortDay(p.modified)
            Text(day, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = if (day == "Today") TextMain else Muted,
                modifier = Modifier.padding(start = 8.dp, top = 3.dp))
        }
        if (!selected) Line(Modifier.padding(horizontal = 10.dp))
    }
}

/** Linear-style status icon: ringed dot = active, bars = paused, dashed ring = idea, filled tick = done. */
@Composable
private fun StatusGlyph(status: String, size: Dp = 16.dp) {
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
                val path = Path().apply {
                    moveTo(center.x - r * 0.45f, center.y)
                    lineTo(center.x - r * 0.1f, center.y + r * 0.35f)
                    lineTo(center.x + r * 0.5f, center.y - r * 0.35f)
                }
                drawPath(path, Color(0xFF1A1433), style = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            else -> {
                drawCircle(TaskGreen, r - stroke / 2, style = Stroke(stroke))
                drawCircle(TaskGreen, r - 3.dp.toPx())
            }
        }
    }
}

/** The phone's project sheet as a small menu: status, Work or Personal, and type. Each click saves to the frontmatter. */
@Composable
private fun ProjectMenu(
    expanded: Boolean, onDismiss: () -> Unit, title: String, status: String, area: String?, type: String?,
    onStatus: (String) -> Unit, onArea: (String) -> Unit, onType: (String) -> Unit, onObsidian: (() -> Unit)? = null,
) {
    DropdownMenu(expanded, onDismiss, Modifier.width(300.dp).background(Popup)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextMain, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Box(Modifier.padding(top = 2.dp, bottom = 8.dp)) { MonoLabel("Status") }
            ProjectGroup.STATUSES.forEach { s ->
                val on = ProjectGroup.of(s) == ProjectGroup.of(status)
                Line()
                Row(
                    Modifier.fillMaxWidth().hand().clickable { onStatus(s); onDismiss() }.padding(vertical = 9.dp, horizontal = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatusGlyph(s)
                    Text(s.replaceFirstChar { it.uppercase() }, fontSize = 14.sp, color = if (on) TaskGreen else TextMain,
                        modifier = Modifier.padding(start = 12.dp).weight(1f))
                    if (on) Text("✓", color = Muted, fontSize = 13.sp)
                }
            }
            Line()
            Box(Modifier.padding(top = 10.dp, bottom = 6.dp)) { MonoLabel("Area") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("work" to "Work", "personal" to "Personal").forEach { (a, label) ->
                    MenuOption(area == a, Modifier.weight(1f), { onArea(a); onDismiss() }) {
                        AreaDot(a); Text(label, fontSize = 13.sp, color = if (area == a) Primary else TextMain, modifier = Modifier.padding(start = 7.dp))
                    }
                }
            }
            Box(Modifier.padding(top = 10.dp, bottom = 6.dp)) { MonoLabel("Type") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IdeaNote.TYPES.forEach { t ->
                    MenuOption(type == t, Modifier.weight(1f), { onType(t); onDismiss() }) {
                        Text(t, fontSize = 13.sp, color = if (type == t) Primary else TextMain)
                    }
                }
            }
            if (onObsidian != null) {
                Text("Open in Obsidian ↗", fontSize = 13.sp, color = Primary,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp).hand().clickable { onObsidian(); onDismiss() })
            }
        }
    }
}

@Composable
private fun MenuOption(on: Boolean, modifier: Modifier, onClick: () -> Unit, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier.clip(shape).background(if (on) PrimaryTint else Color.Transparent).border(1.dp, if (on) Primary else Outline, shape)
            .hand().clickable(onClick = onClick).padding(vertical = 7.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
    ) { content() }
}

private val ENTRY_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

private fun entryLabel(heading: String): String = runCatching {
    val dt = LocalDateTime.parse(heading, ENTRY_FMT)
    val d = dt.toLocalDate()
    val today = LocalDate.now()
    val day = when (d) { today -> "Today"; today.minusDays(1) -> "Yesterday"; else -> d.format(DateTimeFormatter.ofPattern("d MMM", Locale.UK)) }
    "$day ${dt.format(DateTimeFormatter.ofPattern("HH:mm"))}"
}.getOrDefault(heading)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ProjectDetail(state: AppState, title: String, images: MutableMap<String, Any>, onChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    var doc by remember(title) { mutableStateOf<IdeaDoc?>(null) }
    var notes by remember(title) { mutableStateOf<Pair<String?, String?>>(null to null) }
    var links by remember(title) { mutableStateOf<List<IdeaNote.Link>>(emptyList()) }
    var repo by remember(title) { mutableStateOf<Pair<String, String>?>(null) }
    var github by remember(title) { mutableStateOf<GitHubRepo.Activity?>(null) }
    var githubError by remember(title) { mutableStateOf(false) }
    var tab by remember(title) { mutableIntStateOf(0) }
    var text by remember(title) { mutableStateOf(state.prefs.draft("idea_$title")) }
    val pending = remember(title) { mutableStateListOf<BufferedImage>() }
    var busy by remember { mutableStateOf(false) }
    var editSummary by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<IdeaEntry?>(null) }
    var menu by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    // onExternalDrag was removed in Compose Multiplatform 1.8. This is the same drop: image files land in the log box.
    val dropTarget = remember(title) {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) { dragging = true }
            override fun onExited(event: DragAndDropEvent) { dragging = false }
            override fun onEnded(event: DragAndDropEvent) { dragging = false }
            override fun onDrop(event: DragAndDropEvent): Boolean {
                dragging = false
                val data = event.dragData()
                if (data is DragData.FilesList) {
                    data.readFiles().mapNotNull { runCatching { File(URI(it)) }.getOrNull() }
                        .mapNotNull(Images::read).forEach { pending.add(it) }
                }
                return data is DragData.FilesList
            }
        }
    }
    var viewImage by remember { mutableStateOf<Any?>(null) }

    fun reload() = scope.launch {
        state.io("Couldn't open the project") {
            val store = state.ideaStore()!!
            Triple(store.load(title), store.notePart(title, "Changelog") to store.notePart(title, "Decisions"), store.noteText(title))
        }?.let { (d, n, noteText) ->
            doc = d; notes = n
            links = IdeaNote.links(noteText)
            repo = GitHubRepo.repoOf(noteText)
        }
    }
    LaunchedEffect(title, state.vault, state.changes) { reload() }
    // The repo line needs a github.com link in the note and the token from Settings.
    LaunchedEffect(repo) {
        github = null; githubError = false
        val (owner, name) = repo ?: return@LaunchedEffect
        val token = state.prefs.githubToken ?: return@LaunchedEffect
        val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { GitHubRepo.fetch(owner, name, token) } }
        r.onSuccess { github = it }.onFailure { githubError = true }
    }

    fun run(block: IdeaStore.() -> Unit) = scope.launch {
        state.io { state.ideaStore()!!.block() }
        reload(); onChanged()
    }

    fun addToLog() {
        if (busy || (text.isBlank() && pending.isEmpty())) return
        busy = true
        val body = text
        val picked = pending.toList()
        scope.launch {
            val saved = state.io {
                val store = state.ideaStore()!!
                val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                val saved = picked.mapIndexed { i, img -> store.saveImage(Images.toJpeg(img), "${IdeaStore.safeName(title)}-$stamp-${i + 1}") }
                store.addEntry(title, body, saved.map { it.first })
                saved
            }
            busy = false
            saved ?: return@launch
            saved.forEach { (name, h) -> images[name] = h }
            text = ""; pending.clear(); state.prefs.setDraft("idea_$title", "")
            reload(); onChanged()
        }
    }

    fun pickImages() {
        val dlg = FileDialog(null as Frame?, "Add images", FileDialog.LOAD).apply {
            isMultipleMode = true
            setFilenameFilter { _, n -> n.lowercase().substringAfterLast('.') in setOf("jpg", "jpeg", "png", "gif", "bmp") }
            file = "*.jpg;*.jpeg;*.png;*.gif;*.bmp"
            isVisible = true
        }
        dlg.files.mapNotNull(Images::read).forEach { pending.add(it) }
    }

    val d = doc
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 36.dp).padding(top = 26.dp, bottom = if (pending.isEmpty()) 90.dp else 170.dp).verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.clip(CircleShape).hand().clickable(enabled = d != null) { menu = true }.padding(3.dp)) { StatusGlyph(d?.status ?: "idea", 18.dp) }
                Box(Modifier.weight(1f).padding(start = 10.dp)) { H1(title, 26) }
                Box {
                    Box(
                        Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, Outline, RoundedCornerShape(10.dp))
                            .hand().clickable(enabled = d != null) { menu = true },
                        contentAlignment = Alignment.Center
                    ) { Text("⚙", fontSize = 18.sp, color = Muted) }
                    if (d != null) ProjectMenu(
                        menu, { menu = false }, title, d.status, d.area, d.type,
                        onStatus = { s -> run { setStatus(title, s) } }, onArea = { a -> run { setArea(title, a) } },
                        onType = { t -> run { setType(title, t) } },
                        onObsidian = { state.openInObsidian("${state.prefs.ideasDir}/$title/$title.md") }
                    )
                }
            }
            Text(
                listOfNotNull(d?.area, d?.type, d?.entries?.firstOrNull()?.let { "updated " + entryLabel(it.heading).substringBefore(" ").lowercase() })
                    .joinToString(" · "),
                fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Muted, modifier = Modifier.padding(start = 34.dp, top = 4.dp, bottom = 16.dp)
            )

            d?.next?.let { next ->
                val shape = RoundedCornerShape(16.dp)
                Column(Modifier.fillMaxWidth().padding(bottom = 14.dp).clip(shape).background(PrimaryTint).border(1.dp, NextBorder, shape)
                    .padding(horizontal = 18.dp, vertical = 14.dp)) {
                    MonoLabel("Next step", Primary)
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(18.dp).clip(RoundedCornerShape(6.dp)).border(1.5.dp, Muted, RoundedCornerShape(6.dp))
                            .hand().clickable { run { tickNext(title) } })
                        Text(next, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TextMain, modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }

            Text(
                d?.summary?.ifBlank { "Add a summary" } ?: "", fontSize = 14.sp, lineHeight = 21.sp,
                color = if (d?.summary.isNullOrBlank()) Muted else TextMain,
                modifier = Modifier.fillMaxWidth().hand().clickable(enabled = d != null) { editSummary = true }.padding(bottom = 12.dp)
            )
            github?.let { a -> GitHubLine(a) { repo?.let { (o, r) -> state.browse("https://github.com/$o/$r") } } }
            if (githubError) Text("Couldn't reach GitHub. Check the token in Settings.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))

            val tabs = buildList {
                add("Log" + (d?.entries?.size?.takeIf { it > 0 }?.let { " $it" } ?: ""))
                if (!notes.first.isNullOrBlank()) add("Changelog")
                if (!notes.second.isNullOrBlank()) add("Decisions")
                if (links.isNotEmpty()) add("Links")
            }
            val current = tabs.getOrElse(tab) { tabs.first() }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                tabs.forEachIndexed { i, t ->
                    val on = t == current
                    Text(
                        t, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (on) TextMain else Muted,
                        modifier = Modifier.hand().clickable { tab = i }.drawBehind {
                            if (on) drawLine(Primary, Offset(0f, size.height), Offset(size.width, size.height), 2.dp.toPx())
                        }.padding(bottom = 9.dp)
                    )
                }
            }
            Line()
            when {
                current.startsWith("Log") -> {
                    if (d != null && d.entries.isEmpty()) Sub("Nothing logged yet.", Modifier.padding(top = 12.dp))
                    d?.entries?.forEach { e -> EntryRow(e, images, onEdit = { editing = e }, onImage = { viewImage = it }) }
                }
                current == "Changelog" -> NotesText(notes.first.orEmpty())
                current == "Decisions" -> NotesText(notes.second.orEmpty())
                else -> links.forEach { l ->
                    Column(Modifier.fillMaxWidth().hand().clickable { if (l.web) state.browse(l.target) else state.openInObsidian(l.target) }) {
                        Text(l.label + if (l.web) "  ↗" else "", color = Primary, fontSize = 14.sp, modifier = Modifier.padding(vertical = 12.dp))
                        Line()
                    }
                }
            }
        }

        // The log box: one line until there's something in it. Ctrl+Enter adds; images can be dropped, pasted (Ctrl+V) or picked.
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Bg).padding(horizontal = 36.dp, vertical = 18.dp)
                .dragAndDropTarget(shouldStartDragAndDrop = { true }, target = dropTarget)
        ) {
            if (pending.isNotEmpty()) Row(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pending.forEachIndexed { i, img ->
                    Box(Modifier.size(64.dp).clip(RoundedCornerShape(10.dp))) {
                        androidx.compose.foundation.Image(Images.shrink(img, 160).toComposeImageBitmap(), null, Modifier.size(64.dp), contentScale = ContentScale.Crop)
                        Text("✕", fontSize = 11.sp, color = Color.White, modifier = Modifier.align(Alignment.TopEnd).padding(3.dp)
                            .clip(RoundedCornerShape(6.dp)).background(Color(0x99000000)).hand().clickable { pending.removeAt(i) }.padding(horizontal = 4.dp))
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).border(1.dp, Outline, CircleShape).hand().clickable { pickImages() },
                    contentAlignment = Alignment.Center
                ) { Text("📷", fontSize = 17.sp) }
                Field(
                    text, { text = it; state.prefs.setDraft("idea_$title", it) },
                    Modifier.weight(1f).heightIn(min = 44.dp).onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown || !e.isCtrlPressed) false
                        else when (e.key) {
                            Key.Enter -> { addToLog(); true }
                            Key.V -> Images.fromClipboard().takeIf { it.isNotEmpty() }?.let { pending.addAll(it); true } ?: false
                            else -> false
                        }
                    },
                    if (dragging) "Drop to add" else "Add to the log… (drop or paste a photo)", singleLine = false,
                    shape = RoundedCornerShape(22.dp), focusBorder = true
                )
                if (text.isNotBlank() || pending.isNotEmpty()) {
                    PrimaryButton(if (busy) "Adding…" else "Add", enabled = !busy, hint = "Ctrl ↵") { addToLog() }
                }
            }
        }
    }

    if (editSummary && d != null) TextEditDialog("Summary", d.summary, onDismiss = { editSummary = false }) {
        editSummary = false; run { setSummary(title, d.summary, it) }
    }
    editing?.let { e ->
        TextEditDialog("Edit entry", e.text, onDismiss = { editing = null }, onDelete = { editing = null; run { deleteEntry(title, e) } }) {
            editing = null; run { editEntry(title, e, it) }
        }
    }
    viewImage?.let { m ->
        Dialog(onDismissRequest = { viewImage = null }) {
            Box(Modifier.clip(RoundedCornerShape(12.dp)).clickable { viewImage = null }) { AsyncImage(m, Modifier.heightIn(max = 760.dp), maxSide = 1600, scale = ContentScale.Fit) }
        }
    }
}

/** The repo's last push, open pull requests and latest release, on one line. */
@Composable
private fun GitHubLine(a: GitHubRepo.Activity, onRepo: () -> Unit) {
    val push = a.lastPush?.let { runCatching { LocalDate.parse(it).format(DateTimeFormatter.ofPattern("d MMM", Locale.UK)) }.getOrDefault(it) } ?: "–"
    Line()
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        listOf("push" to push, "PRs" to "${a.openPrs}", "release" to (a.release ?: "none")).forEach { (k, v) ->
            Text(buildAnnotatedString { append("$k "); withStyle(SpanStyle(color = TextMain)) { append(v) } },
                fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Muted)
        }
        Box(Modifier.weight(1f))
        Text("Repo ↗", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Primary, modifier = Modifier.hand().clickable(onClick = onRepo))
    }
    Line(Modifier.padding(bottom = 14.dp))
}

/** The project's own Changelog or Decisions note, as it's written. */
@Composable
private fun NotesText(body: String) {
    Text(body, fontSize = 13.5.sp, lineHeight = 20.sp, color = TextMain, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
}

@Composable
private fun EntryRow(e: IdeaEntry, images: Map<String, Any>, onEdit: () -> Unit, onImage: (Any) -> Unit) {
    Column(Modifier.fillMaxWidth().hand().clickable(onClick = onEdit)) {
        Column(Modifier.padding(vertical = 12.dp)) {
            Text(entryLabel(e.heading), fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Muted)
            if (e.text.isNotBlank()) Text(e.text, fontSize = 14.sp, lineHeight = 21.sp, color = TextMain, modifier = Modifier.padding(top = 3.dp))
            if (e.images.isNotEmpty()) Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                e.images.forEach { name ->
                    val m = images[name]
                    Box(Modifier.size(160.dp, 108.dp).clip(RoundedCornerShape(12.dp)).background(Surface2).hand().clickable { m?.let(onImage) }) {
                        if (m != null) AsyncImage(m, Modifier.size(160.dp, 108.dp), maxSide = 480)
                    }
                }
            }
        }
        Line()
    }
}

@Composable
private fun NewProjectDialog(onDismiss: () -> Unit, onCreate: (String, String) -> Unit) {
    var title by remember { mutableStateOf("") }
    var summary by remember { mutableStateOf("") }
    DialogCard("New project", onDismiss) {
        SmallLabel("Title")
        Field(title, { title = it }, Modifier.fillMaxWidth().padding(bottom = 12.dp), "Garage workshop layout")
        SmallLabel("Summary")
        Field(summary, { summary = it }, Modifier.fillMaxWidth().padding(bottom = 16.dp), "What it is, in a sentence or two", singleLine = false, minLines = 3)
        DialogButtons(onDismiss) { PrimaryButton("Create", enabled = title.isNotBlank()) { onCreate(title, summary) } }
    }
}

@Composable
fun TextEditDialog(heading: String, initial: String, onDismiss: () -> Unit, onDelete: (() -> Unit)? = null, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    DialogCard(heading, onDismiss) {
        Field(text, { text = it }, Modifier.fillMaxWidth().padding(bottom = 16.dp), singleLine = false, minLines = 5)
        DialogButtons(onDismiss, onDelete) { PrimaryButton("Save") { onSave(text) } }
    }
}

@Composable
fun DialogCard(heading: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        CaptureTheme {
            val shape = RoundedCornerShape(18.dp)
            Column(Modifier.width(520.dp).clip(shape).background(Popup).border(1.dp, Outline, shape).padding(22.dp)) {
                Text(heading, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextMain, modifier = Modifier.padding(bottom = 14.dp))
                content()
            }
        }
    }
}

@Composable
fun DialogButtons(onDismiss: () -> Unit, onDelete: (() -> Unit)? = null, confirm: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (onDelete != null) TextAction("Delete", Warn, onClick = onDelete)
        Box(Modifier.weight(1f))
        TextAction("Cancel", Muted, onClick = onDismiss)
        confirm()
    }
}
