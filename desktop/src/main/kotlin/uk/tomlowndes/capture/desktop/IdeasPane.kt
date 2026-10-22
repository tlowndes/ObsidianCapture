package uk.tomlowndes.capture.desktop

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.onExternalDrag
import androidx.compose.ui.DragData
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch
import uk.tomlowndes.capture.IdeaDoc
import uk.tomlowndes.capture.IdeaEntry
import uk.tomlowndes.capture.IdeaStore
import uk.tomlowndes.capture.IdeaSummary
import java.awt.FileDialog
import java.awt.Frame
import java.awt.image.BufferedImage
import java.io.File
import java.net.URI
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val THUMBS = listOf(
    listOf(Color(0xFF5B3FD6), Color(0xFF2B1D6B)), listOf(Color(0xFF1F7A6A), Color(0xFF0F3A38)), listOf(Color(0xFF8A5A2B), Color(0xFF3B2410))
)

/** Ideas: the list beside the open idea, with its summary, newest-first log and an entry box that takes images. */
@Composable
fun IdeasPane(state: AppState) {
    val scope = rememberCoroutineScope()
    var ideas by remember { mutableStateOf<List<IdeaSummary>?>(null) }
    var open by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    var byUpdated by remember { mutableStateOf(state.prefs.ideasByUpdated) }
    val images = remember(state.vault) { mutableStateMapOf<String, Any>() }

    fun reload() = scope.launch {
        val list = state.io("Couldn't read ideas") { IdeaStore.sorted(state.ideaStore()!!.list(), byUpdated) } ?: return@launch
        ideas = list
        if (open == null || list.none { it.title == open }) open = list.firstOrNull()?.title
    }
    LaunchedEffect(state.vault, state.changes) {
        reload()
        state.io { state.ideaStore()!!.imageIndex() }?.let { images.putAll(it) }
    }

    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(380.dp).fillMaxHeight().padding(horizontal = 20.dp, vertical = 26.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { H1("Ideas", 26) }
                Chip("＋ New", small = true) { creating = true }
            }
            Field(query, { query = it }, Modifier.fillMaxWidth().padding(bottom = 10.dp), "🔍  Search ideas", shape = RoundedCornerShape(999.dp))
            Row(Modifier.padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                fun sortBy(updated: Boolean) { byUpdated = updated; state.prefs.ideasByUpdated = updated; ideas = ideas?.let { IdeaStore.sorted(it, updated) } }
                Chip("Last updated", byUpdated, small = true) { sortBy(true) }
                Chip("A–Z", !byUpdated, small = true) { sortBy(false) }
            }
            val shown = ideas.orEmpty().filter { query.isBlank() || it.title.contains(query.trim(), true) || it.snippet.contains(query.trim(), true) }
            if (ideas != null && shown.isEmpty()) Sub(if (query.isBlank()) "No ideas yet." else "No ideas match.")
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(shown, key = { it.title }) { idea -> IdeaCard(idea, idea.title == open, images, ideas!!.indexOf(idea)) { open = idea.title } }
            }
        }
        Box(Modifier.fillMaxHeight().width(1.dp).background(Divider))
        Box(Modifier.weight(1f).fillMaxHeight()) {
            val title = open
            if (title == null) Sub("Pick an idea, or make a new one.", Modifier.padding(36.dp))
            else IdeaDetail(state, title, images, onChanged = { reload() })
        }
    }

    if (creating) NewIdeaDialog(onDismiss = { creating = false }) { title, summary ->
        scope.launch {
            val made = state.io { state.ideaStore()!!.create(title, summary) } ?: return@launch
            creating = false; open = made; reload()
        }
    }
}

@Composable
private fun Thumb(name: String?, images: Map<String, Any>, index: Int, size: Int) {
    val shape = RoundedCornerShape(10.dp)
    val model = name?.let { images[it] }
    Box(Modifier.size(size.dp).clip(shape).background(Brush.linearGradient(THUMBS[index.mod(THUMBS.size)]))) {
        if (model != null) AsyncImage(model, Modifier.size(size.dp), maxSide = 200)
    }
}

@Composable
private fun IdeaCard(idea: IdeaSummary, selected: Boolean, images: Map<String, Any>, index: Int, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(if (selected) Surface2 else Color.Transparent)
            .then(if (selected) Modifier.border(1.5.dp, Primary, shape) else Modifier)
            .hand().clickable(onClick = onClick).padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically
    ) {
        Thumb(idea.firstImage, images, index, 48)
        Column(Modifier.weight(1f)) {
            Text(idea.title, fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = TextMain, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(idea.snippet.ifBlank { "${idea.entries} entries" }, fontSize = 12.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private val ENTRY_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

private fun entryLabel(heading: String): String = runCatching {
    val dt = LocalDateTime.parse(heading, ENTRY_FMT)
    val d = dt.toLocalDate()
    val today = LocalDate.now()
    val day = when (d) { today -> "Today"; today.minusDays(1) -> "Yesterday"; else -> d.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)) }
    "$day ${dt.format(DateTimeFormatter.ofPattern("HH:mm"))}"
}.getOrDefault(heading)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun IdeaDetail(state: AppState, title: String, images: MutableMap<String, Any>, onChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    var doc by remember(title) { mutableStateOf<IdeaDoc?>(null) }
    var text by remember(title) { mutableStateOf(state.prefs.draft("idea_$title")) }
    val pending = remember(title) { mutableStateListOf<BufferedImage>() }
    var busy by remember { mutableStateOf(false) }
    var editSummary by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<IdeaEntry?>(null) }
    var statusMenu by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var viewImage by remember { mutableStateOf<Any?>(null) }

    fun reload() = scope.launch { state.io("Couldn't open the idea") { state.ideaStore()!!.load(title) }?.let { doc = it } }
    LaunchedEffect(title, state.vault, state.changes) { reload() }

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
        Column(Modifier.fillMaxSize().padding(horizontal = 36.dp).padding(top = 26.dp, bottom = 150.dp).verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { H1(title, 26) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        Text((d?.status ?: "") + " ▾", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TaskGreen,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(TaskTint).hand().clickable { statusMenu = true }
                                .padding(horizontal = 10.dp, vertical = 5.dp))
                        DropdownMenu(statusMenu, { statusMenu = false }, Modifier.background(Menu)) {
                            listOf("idea", "active", "done").forEach { s -> DropdownMenuItem({ Text(s) }, { statusMenu = false; run { setStatus(title, s) } }) }
                        }
                    }
                    Chip("Open in Obsidian ↗", small = true) { state.openInObsidian("${state.prefs.ideasDir}/$title/$title.md") }
                }
            }
            val photos = d?.entries?.sumOf { it.images.size } ?: 0
            Sub("${state.prefs.ideasDir}/$title · ${d?.entries?.size ?: 0} entries · $photos photos", Modifier.padding(top = 2.dp, bottom = 16.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                SmallLabel("Summary", Modifier.weight(1f))
                if (d != null) TextAction("Edit", modifier = Modifier.padding(bottom = 6.dp)) { editSummary = true }
            }
            Text(
                d?.summary?.ifBlank { "No summary yet." } ?: "", fontSize = 14.sp, lineHeight = 21.sp,
                color = if (d?.summary.isNullOrBlank()) Muted else TextMain,
                modifier = Modifier.fillMaxWidth().padding(bottom = 18.dp).clip(RoundedCornerShape(14.dp)).background(Surface1).padding(horizontal = 16.dp, vertical = 12.dp)
            )
            d?.entries?.forEach { e -> EntryRow(e, images, onEdit = { editing = e }, onImage = { viewImage = it }) }
        }

        // The entry box: Ctrl+Enter adds; images can be dropped on it, pasted (Ctrl+V) or picked.
        val shape = RoundedCornerShape(16.dp)
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 36.dp, vertical = 22.dp).clip(shape)
                .background(if (dragging) Surface2 else Surface1).border(1.5.dp, Primary.copy(alpha = if (dragging) 1f else .6f), shape)
                .onExternalDrag(
                    onDragStart = { dragging = true }, onDragExit = { dragging = false },
                    onDrop = { v ->
                        dragging = false
                        val data = v.dragData
                        if (data is DragData.FilesList) data.readFiles().mapNotNull { runCatching { File(URI(it)) }.getOrNull() }
                            .mapNotNull(Images::read).forEach { pending.add(it) }
                    }
                )
                .padding(12.dp)
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
            Field(
                text, { text = it; state.prefs.setDraft("idea_$title", it) },
                Modifier.fillMaxWidth().heightIn(min = 44.dp).onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown || !e.isCtrlPressed) false
                    else when (e.key) {
                        Key.Enter -> { addToLog(); true }
                        Key.V -> Images.fromClipboard().takeIf { it.isNotEmpty() }?.let { pending.addAll(it); true } ?: false
                        else -> false
                    }
                },
                "Add to the log… drop or paste images here", singleLine = false, focusBorder = false, shape = RoundedCornerShape(10.dp)
            )
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Chip("🖼 Add image", small = true) { pickImages() }
                Box(Modifier.weight(1f))
                PrimaryButton(if (busy) "Adding…" else "Add entry", enabled = !busy && (text.isNotBlank() || pending.isNotEmpty()), hint = "Ctrl ↵") { addToLog() }
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

@Composable
private fun EntryRow(e: IdeaEntry, images: Map<String, Any>, onEdit: () -> Unit, onImage: (Any) -> Unit) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Column(Modifier.width(14.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.padding(top = 4.dp).size(10.dp).clip(RoundedCornerShape(5.dp)).background(Primary))
            Box(Modifier.width(2.dp).weight(1f).background(Outline))
        }
        Column(Modifier.weight(1f).padding(start = 12.dp, bottom = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(entryLabel(e.heading), fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = Muted, modifier = Modifier.weight(1f))
                TextAction("Edit", onClick = onEdit)
            }
            if (e.text.isNotBlank()) Text(e.text, fontSize = 14.sp, lineHeight = 21.sp, color = TextMain)
            if (e.images.isNotEmpty()) Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                e.images.forEach { name ->
                    val m = images[name]
                    Box(Modifier.size(160.dp, 108.dp).clip(RoundedCornerShape(12.dp)).background(Surface2).hand().clickable { m?.let(onImage) }) {
                        if (m != null) AsyncImage(m, Modifier.size(160.dp, 108.dp), maxSide = 480)
                    }
                }
            }
        }
    }
}

@Composable
private fun NewIdeaDialog(onDismiss: () -> Unit, onCreate: (String, String) -> Unit) {
    var title by remember { mutableStateOf("") }
    var summary by remember { mutableStateOf("") }
    DialogCard("New idea", onDismiss) {
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
