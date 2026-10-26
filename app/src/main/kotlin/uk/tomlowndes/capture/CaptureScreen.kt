package uk.tomlowndes.capture

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Capture: one box, typed or dictated. "Sort it" keeps the dump in the queue, asks Claude Haiku to split it
 * into tasks, project ideas and a note, then shows Check and save. Nothing reaches the vault until Save.
 */
@Composable
fun CaptureScreen(vault: Uri?, prefs: AppPrefs, snack: SnackbarHostState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val secrets = remember { SecretStore(ctx) }
    var text by remember { mutableStateOf(prefs.draft("capture")) }
    var queue by remember { mutableStateOf(prefs.captureQueue) }
    var sorting by remember { mutableStateOf(false) }
    var offline by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf<QueuedDump?>(null) }
    var recording by remember { mutableStateOf(false) }
    var partial by remember { mutableStateOf("") }
    var mode by remember { mutableIntStateOf(prefs.captureMode) }

    fun setQueue(q: List<QueuedDump>) { queue = q; prefs.captureQueue = q }
    fun setText(t: String) { text = t; prefs.setDraft("capture", t) }

    /** Sorts every queued dump that hasn't been sorted yet; stops at the first sign of no signal. */
    suspend fun sortQueued(openFirst: Boolean) {
        val key = secrets.claudeKey ?: return
        val tags = vault?.let { uri ->
            withContext(Dispatchers.IO) { runCatching { TaskViews.topTags(VaultWriter(ctx, uri).listTasks(), 10) }.getOrNull() }
        }.orEmpty()
        sorting = true
        for (d in queue.filter { it.sorted == null }) {
            val r = withContext(Dispatchers.IO) { runCatching { SortClient.sort(key, d.text, tags) } }
            r.onSuccess { res ->
                offline = false
                val done = d.copy(sorted = res)
                setQueue(queue.map { if (it.id == d.id) done else it })
                if (openFirst && checking == null) checking = done
            }.onFailure { e ->
                if (e is OfflineException) offline = true else snack.showSnackbar("Couldn't sort: ${e.message}")
            }
            if (r.isFailure) break
        }
        sorting = false
    }

    LaunchedEffect(Unit) { if (queue.any { it.sorted == null }) sortQueued(openFirst = false) }

    fun sortIt() {
        val dump = text.trim()
        if (dump.isEmpty() || sorting) return
        setQueue(queue + QueuedDump(System.currentTimeMillis(), dump))
        setText("")
        if (secrets.claudeKey == null) {
            scope.launch { snack.showSnackbar("No Claude API key · Settings") }
            return
        }
        scope.launch { sortQueued(openFirst = true) }
    }

    // ---- dictation ----
    val recognizer = remember { if (SpeechRecognizer.isRecognitionAvailable(ctx)) SpeechRecognizer.createSpeechRecognizer(ctx) else null }
    DisposableEffect(recognizer) { onDispose { recognizer?.destroy() } }
    fun startListening() {
        val r = recognizer ?: run { scope.launch { snack.showSnackbar("Dictation isn't available on this phone") }; return }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onPartialResults(b: Bundle?) {
                partial = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            }
            override fun onResults(b: Bundle?) {
                val said = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (said.isNotBlank()) setText(listOf(text.trim(), said.trim()).filter { it.isNotEmpty() }.joinToString(" "))
                partial = ""; recording = false
            }
            override fun onError(error: Int) { partial = ""; recording = false }
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(t: Int, b: Bundle?) {}
        })
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.UK.toLanguageTag())
        })
        recording = true
    }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) startListening() }
    fun mic() {
        if (recording) { recognizer?.stopListening(); return }
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startListening()
        else askMic.launch(Manifest.permission.RECORD_AUDIO)
    }

    checking?.let { d ->
        BackHandler { checking = null }
        CheckAndSave(
            d.sorted ?: SortResult(emptyList(), emptyList(), null),
            onBack = { checking = null },
            onDiscard = { setQueue(queue.filter { it.id != d.id }); checking = null },
            onSave = { r ->
                val uri = vault ?: run { scope.launch { snack.showSnackbar("Pick your vault folder in Settings first") }; return@CheckAndSave }
                scope.launch {
                    withContext(Dispatchers.IO) { runCatching { Sorting.save(r, VaultWriter(ctx, uri), IdeaStore(ctx, uri, prefs)) } }
                        .onSuccess { setQueue(queue.filter { it.id != d.id }); checking = null; snack.showSnackbar("Saved") }
                        .onFailure { snack.showSnackbar("Failed: ${it.message}") }
                }
            }
        )
        return
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Capture", fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp)
            Text(
                "Queue ${queue.size}", fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                color = if (offline && queue.isNotEmpty()) Amber else Primary,
                modifier = Modifier.clip(CircleShape).background(if (offline && queue.isNotEmpty()) AmberTint else PrimaryTint)
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
        if (offline && queue.isNotEmpty()) {
            Text("⚠ Offline · Queue ${queue.size}", color = Amber, fontSize = 13.sp, modifier = Modifier.padding(bottom = 10.dp))
        }

        PillSwitch(listOf("Task", "Read later", "Brain dump"), mode) { mode = it; prefs.captureMode = it }
        Box(Modifier.height(14.dp))
        if (mode < 2) QuickAdd(vault, link = mode == 1, snack = snack) else {
            Box {
                OutlinedTextField(
                    value = text, onValueChange = { setText(it) },
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
                    placeholder = { Text("Type or tap the mic") }, minLines = 5,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    colors = fieldColors()
                )
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(10.dp).size(44.dp).clip(CircleShape)
                        .background(if (recording) Warn else Primary).clickable { mic() },
                    contentAlignment = Alignment.Center
                ) { Text(if (recording) "■" else "●", color = OnPrimary, fontSize = 16.sp) }
            }
            if (recording) {
                Text("RECORDING", fontSize = 12.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.2.sp, color = Warn,
                    modifier = Modifier.padding(top = 10.dp))
                if (partial.isNotBlank()) Text(partial, fontSize = 14.sp, color = Muted, modifier = Modifier.padding(top = 4.dp))
            }

            Text(
                if (sorting) "Sorting…" else "Sort it", textAlign = TextAlign.Center, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                color = OnPrimary,
                modifier = Modifier.padding(top = 14.dp).fillMaxWidth().clip(CircleShape)
                    .background(if (text.isBlank() || sorting) Primary.copy(alpha = 0.4f) else Primary)
                    .clickable(enabled = text.isNotBlank() && !sorting) { sortIt() }.padding(vertical = 14.dp)
            )
            if (offline && queue.any { it.sorted == null }) {
                Text("Sorting on the next signal · Saved in the queue", fontSize = 12.sp, color = Muted, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }

            if (queue.isNotEmpty()) {
                Box(Modifier.height(16.dp))
                GroupLabel("Queue")
                ListCard(Modifier.padding(top = 6.dp)) {
                    queue.forEachIndexed { i, d ->
                        if (i > 0) HorizontalDivider(color = Outline)
                        Row(
                            Modifier.fillMaxWidth().clickable(enabled = d.sorted != null) { checking = d }.padding(horizontal = 16.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(d.text, fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text(if (d.sorted != null) "check ›" else "queued", fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                                color = if (d.sorted != null) Primary else Muted)
                        }
                    }
                }
                if (queue.any { it.sorted == null } && !sorting) {
                    Text("Sort now", color = Primary, fontSize = 13.sp, modifier = Modifier.clickable { scope.launch { sortQueued(openFirst = false) } }.padding(vertical = 6.dp))
                }
            }
        }
        Box(Modifier.height(16.dp))
    }
}

@Composable
private fun CheckAndSave(result: SortResult, onBack: () -> Unit, onDiscard: () -> Unit, onSave: (SortResult) -> Unit) {
    var r by remember(result) { mutableStateOf(result) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = Muted) }
            Text("Check and save", fontSize = 22.sp, fontWeight = FontWeight.Bold)
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
        r.note?.let { note ->
            GroupLabel("Note")
            ListCard(Modifier.padding(top = 6.dp)) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(note, fontSize = 13.5.sp, modifier = Modifier.weight(1f))
                    Text("✕", color = Muted, fontSize = 14.sp, modifier = Modifier.clickable { r = r.copy(note = null) }.padding(start = 10.dp))
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

/** Task and Read later, as Capture had them before Flight Deck: one field, due chips for tasks, Just added. */
@Composable
private fun QuickAdd(vault: Uri?, link: Boolean, snack: SnackbarHostState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val history = remember { mutableStateListOf<Added>() }
    var text by remember(link) { mutableStateOf("") }
    var due by remember { mutableStateOf<LocalDate?>(null) }
    var minutes by remember { mutableStateOf<Int?>(null) }
    var repeat by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    fun submit() {
        val value = text.trim()
        val uri = vault
        if (value.isEmpty() || busy) return
        if (uri == null) { scope.launch { snack.showSnackbar("Pick your vault folder in Settings first") }; return }
        busy = true
        val wasRule = repeat
        // A repeating task needs a first date for the series to count from.
        val wasDue = due ?: if (wasRule != null) LocalDate.now() else null
        val wasMin = minutes
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    if (link) {
                        val url = if (Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(value)) value else "https://$value"
                        ReadLater.save(ctx, uri, url, null)
                    } else { VaultWriter(ctx, uri).addTask(value + (wasMin?.let { " ⏱ ${it}m" } ?: "") + (wasRule?.let { " 🔁 $it" } ?: ""), wasDue?.toString()); true }
                }
            }
            busy = false
            r.onSuccess { full ->
                history.add(0, Added(link, value,
                    if (link) (if (full) "Read later · full page" else "Read later · link only")
                    else "Task" + (wasDue?.let { " · due ${dueLabel(it)}" } ?: "") + (wasMin?.let { " · ${TaskRules.minutesLabel(it)}" } ?: "") +
                        (wasRule?.let { " · ↻ $it" } ?: "")))
                text = ""; due = null; minutes = null; repeat = null
                snack.showSnackbar(if (!link) "Task added" else if (full) "Saved page to Read It Later" else "Saved link only (couldn't fetch the page)")
            }.onFailure { snack.showSnackbar("Failed: ${it.message}") }
        }
    }

    OutlinedTextField(
        value = text, onValueChange = { text = it },
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        placeholder = { Text(if (link) "Paste a link" else "What needs doing?") },
        minLines = if (link) 1 else 2,
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp),
        keyboardOptions = if (link) KeyboardOptions(keyboardType = KeyboardType.Uri) else KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        colors = fieldColors()
    )
    if (!link) {
        val today = LocalDate.now()
        val custom = due != null && due != today && due != today.plusDays(1)
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppChip(due == today, { due = if (due == today) null else today }, { Text("Today") })
            AppChip(due == today.plusDays(1), { due = if (due == today.plusDays(1)) null else today.plusDays(1) }, { Text("Tomorrow") })
            AppChip(custom, { picking = true }, { Text(if (custom) dueLabel(due!!) else "Pick date") })
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ESTIMATES.forEach { m -> AppChip(minutes == m, { minutes = if (minutes == m) null else m }, { Text(TaskRules.minutesLabel(m)) }) }
        }
        RepeatPicker(repeat, due) { repeat = it }
    }
    Text(
        if (link) "Save link" else "Add task", textAlign = TextAlign.Center, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = OnPrimary,
        modifier = Modifier.padding(top = 14.dp).fillMaxWidth().clip(CircleShape)
            .background(if (text.isBlank() || busy) Primary.copy(alpha = 0.4f) else Primary)
            .clickable(enabled = text.isNotBlank() && !busy) { submit() }.padding(vertical = 14.dp)
    )
    if (history.isNotEmpty()) {
        Box(Modifier.height(16.dp))
        GroupLabel("Just added")
        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            history.take(5).forEach { e ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surface1).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(if (e.isLink) LinkTint else TaskTint), contentAlignment = Alignment.Center) {
                        Text(if (e.isLink) "🔗" else "✓")
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
