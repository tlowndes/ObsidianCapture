package uk.tomlowndes.capture

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
        val idx = withContext(Dispatchers.IO) { runCatching { IdeaStore(ctx, uri, prefs).imageIndex() }.getOrNull() }
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
    var ideas by remember { mutableStateOf<List<IdeaSummary>?>(null) }
    var query by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }

    fun reload() {
        val uri = vault ?: return
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { IdeaStore(ctx, uri, prefs).list() } }
            r.onSuccess { ideas = it }.onFailure { snack.showSnackbar("Couldn't read ideas: ${it.message}") }
        }
    }
    LaunchedEffect(vault) { reload() }

    val shown = ideas.orEmpty().filter {
        query.isBlank() || it.title.contains(query, true) || it.snippet.contains(query, true)
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            Column(Modifier.padding(top = 8.dp)) {
                ScreenTitle("Ideas", "${prefs.ideasDir} · ${ideas?.size ?: 0} ideas")
            }
            OutlinedTextField(
                query, { query = it }, placeholder = { Text("🔍  Search ideas") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), shape = CircleShape, colors = fieldColors()
            )
            if (ideas != null && shown.isEmpty()) {
                Text(if (ideas!!.isEmpty()) "No ideas yet. Tap “New idea” to start one." else "Nothing matches.", color = Muted)
            }
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 90.dp)) {
                items(shown) { idea -> IdeaCard(idea, images) { onOpen(idea.title) } }
            }
        }
        Text(
            "＋ New idea", color = OnPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp,
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp).clip(RoundedCornerShape(18.dp)).background(Primary)
                .clickable { if (vault == null) scope.launch { snack.showSnackbar("Pick your vault in Settings first") } else creating = true }
                .padding(horizontal = 20.dp, vertical = 14.dp)
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

@Composable
private fun IdeaCard(idea: IdeaSummary, images: Map<String, Uri>, onClick: () -> Unit) {
    val thumb = idea.firstImage?.let { images[it] }
    val modified = Instant.ofEpochMilli(idea.modified).atZone(ZoneId.systemDefault()).toLocalDate()
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Surface1).clickable(onClick = onClick).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(Modifier.size(58.dp).clip(RoundedCornerShape(12.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF5B3FD6), Color(0xFF2B1D6B)))),
            contentAlignment = Alignment.Center) {
            if (thumb != null) AsyncImage(thumb, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Text(idea.title.take(1).uppercase(), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
        Column(Modifier.weight(1f)) {
            Text(idea.title, fontSize = 15.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (idea.snippet.isNotBlank()) Text(idea.snippet, fontSize = 12.5.sp, color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                buildString {
                    append("${idea.entries} entries")
                    if (idea.images > 0) append(" · 🖼 ${idea.images}")
                    append(" · ").append(dayChip(modified))
                    if (idea.status != "idea") append(" · ${idea.status}")
                },
                fontSize = 11.sp, color = Muted, modifier = Modifier.padding(top = 5.dp)
            )
        }
    }
}

@Composable
private fun NewIdeaDialog(onCreate: (String, String) -> Unit, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf("") }
    var summary by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Surface1,
        title = { Text("New idea") },
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
    var text by remember { mutableStateOf(prefs.draft("idea_$title")) }
    val pending = remember { mutableStateListOf<Uri>() }
    var busy by remember { mutableStateOf(false) }
    var editSummary by remember { mutableStateOf(false) }
    var editIndex by remember { mutableStateOf<Int?>(null) }
    var viewImage by remember { mutableStateOf<Uri?>(null) }
    var statusMenu by remember { mutableStateOf(false) }
    var cameraFile by remember { mutableStateOf<File?>(null) }

    fun reload() {
        val uri = vault ?: return
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { IdeaStore(ctx, uri, prefs).load(title) } }
            r.onSuccess { doc = it }.onFailure { snack.showSnackbar("Couldn't open idea: ${it.message}") }
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
                        store.saveImage(src, "${IdeaStore.safeName(title)}-$stamp-${i + 1}")
                    }
                    store.addEntry(title, body, saved.map { it.first })
                    saved
                }
            }
            busy = false
            r.onSuccess { saved ->
                saved.forEach { (name, u) -> images[name] = u }
                text = ""; pending.clear(); prefs.setDraft("idea_$title", "")
                reload()
            }.onFailure { snack.showSnackbar("Failed: ${it.message}") }
        }
    }

    val d = doc
    Column(Modifier.fillMaxSize().imePadding()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onBack) { Text("←", fontSize = 24.sp, color = TextMain) }
                Column {
                    Text(
                        d?.status ?: "", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF6EE7B7),
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(TaskTint).clickable { statusMenu = true }
                            .padding(horizontal = 10.dp, vertical = 3.dp)
                    )
                    DropdownMenu(statusMenu, { statusMenu = false }, modifier = Modifier.background(Surface2)) {
                        listOf("idea", "active", "done").forEach { s ->
                            DropdownMenuItem(text = { Text(s) }, onClick = { statusMenu = false; run { setStatus(title, s) } })
                        }
                    }
                }
                Text("", Modifier.width(48.dp))
            }
            Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text("${prefs.ideasDir} / $title", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 3.dp))

            Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                GroupLabel("Summary")
                Text("✎ Edit", color = Primary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { editSummary = true })
            }
            Text(
                d?.summary?.ifBlank { "No summary yet. Tap Edit to add one." } ?: "",
                fontSize = 13.5.sp, lineHeight = 19.sp, color = if (d?.summary.isNullOrBlank()) Muted else TextMain,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surface1).padding(14.dp)
            )

            Box(Modifier.padding(top = 18.dp, bottom = 10.dp)) { GroupLabel("Log") }
            if (d != null && d.entries.isEmpty()) Text("Nothing logged yet. Add the first thought below.", color = Muted, fontSize = 13.sp)
            d?.entries?.forEachIndexed { i, e ->
                EntryRow(e, images, onEdit = { editIndex = i }, onImage = { viewImage = it })
            }
        }

        Column(Modifier.fillMaxWidth().background(Color(0xFF191623)).padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp)) {
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
                placeholder = { Text("Add more to this idea…") }, maxLines = 5, shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth(), colors = fieldColors(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
            )
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RoundIconButton("📷") {
                        val dir = File(ctx.cacheDir, "camera").apply { mkdirs() }
                        val f = File.createTempFile("cam", ".jpg", dir)
                        cameraFile = f
                        camera.launch(FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f))
                    }
                    RoundIconButton("🖼") { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                }
                Button(
                    onClick = ::addToLog, enabled = !busy && (text.isNotBlank() || pending.isNotEmpty()),
                    colors = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = OnPrimary),
                    shape = CircleShape, modifier = Modifier.height(46.dp)
                ) { Text(if (busy) "Saving…" else "Add to log", fontWeight = FontWeight.Bold) }
            }
        }
    }

    if (editSummary && d != null) TextEditDialog("Summary", d.summary, onSave = { editSummary = false; run { setSummary(title, it) } },
        onDismiss = { editSummary = false })

    editIndex?.let { i ->
        val e = d?.entries?.getOrNull(i)
        if (e != null) TextEditDialog(
            entryLabel(e.heading), e.text,
            onSave = { editIndex = null; run { editEntry(title, i, it) } },
            onDelete = { editIndex = null; run { deleteEntry(title, i) } },
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

@Composable
private fun RoundIconButton(icon: String, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).background(Surface2).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(icon, fontSize = 18.sp)
    }
}

@Composable
private fun EntryRow(e: IdeaEntry, images: Map<String, Uri>, onEdit: () -> Unit, onImage: (Uri) -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(start = 6.dp).drawBehind {
            drawLine(Outline.copy(alpha = 1f), Offset(0f, 8.dp.toPx()), Offset(0f, size.height), 2.dp.toPx())
            drawCircle(Primary, 5.dp.toPx(), Offset(0f, 9.dp.toPx()))
        }.padding(start = 16.dp, bottom = 16.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(entryLabel(e.heading), fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = Muted)
            Text("✎", color = Primary, modifier = Modifier.clickable(onClick = onEdit).padding(horizontal = 6.dp))
        }
        if (e.text.isNotBlank()) Text(e.text, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 4.dp))
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
