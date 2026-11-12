package uk.tomlowndes.capture

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The evening check-in (journal-prompt-v2): scales and ticks from `99 - Meta/Journal prompts.md`, two optional
 * one-liners and a free note, saved to this month's journal note. [events] is today's calendar count.
 */
@Composable
internal fun JournalSheet(vault: Uri?, events: Int, snack: SnackbarHostState, onSaved: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val today = remember { LocalDate.now() }
    var prompts by remember { mutableStateOf<List<JournalPrompt>?>(null) }
    var answers by remember { mutableStateOf(emptyMap<String, String>()) }
    var note by remember { mutableStateOf("") }
    var noteOpen by remember { mutableStateOf(false) }
    var facts by remember { mutableStateOf(JournalFacts(events = events)) }
    var saving by remember { mutableStateOf(false) }

    // 🎤: Android's own speech-to-text (as Jarvis uses); tap to start, tap again to add what was heard to the note.
    val speech = remember { PhoneSpeech(ctx) }
    var listening by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { speech.cancel() } }
    var heardNow by remember { mutableStateOf("") }
    LaunchedEffect(listening) { while (listening) { heardNow = speech.soFar; kotlinx.coroutines.delay(250) } }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (!ok) scope.launch { snack.showSnackbar("The journal needs the microphone to hear you") }
    }
    fun toggleMic() {
        if (listening) {
            listening = false
            speech.stop { heard ->
                val t = Journal.tidySpoken(heard)
                if (t.isNotEmpty()) { note = listOf(note.trim(), t).filter { it.isNotEmpty() }.joinToString("\n"); noteOpen = true }
            }
            return
        }
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            askMic.launch(Manifest.permission.RECORD_AUDIO); return
        }
        if (!PhoneSpeech.available(ctx)) { scope.launch { snack.showSnackbar("Speech-to-text isn't available on this phone") }; return }
        speech.start(); listening = true
    }

    LaunchedEffect(vault) {
        val uri = vault ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            runCatching {
                val v = appVault(ctx, uri)
                val p = Journal.loadPrompts(v)
                val start = Journal.start(v, today, p)
                Triple(p, start, Journal.facts(v, today, events))
            }.getOrNull()
        }?.let { (p, start, f) ->
            prompts = p; answers = start.answers; note = start.note; noteOpen = start.note.isNotBlank(); facts = f
        }
    }

    val list = prompts
    Column(Modifier.fillMaxWidth().heightIn(max = 640.dp).verticalScroll(rememberScrollState())) {
        val title = "📓 " + today.format(DateTimeFormatter.ofPattern("EEEE", Locale.UK)) + " check-in"
        val asked = list?.count { it.kind != JournalKind.LINE } ?: 0
        val done = list?.filter { it.kind != JournalKind.LINE }?.let { JournalEntry(answers).answered(it) } ?: 0
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (asked > 0) Text("$done of $asked", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Muted)
        }
        Box(Modifier.padding(top = 8.dp, bottom = 6.dp).fillMaxWidth().height(4.dp).clip(CircleShape).background(Surface2)) {
            if (asked > 0) Box(Modifier.fillMaxWidth(done / asked.toFloat()).height(4.dp).clip(CircleShape).background(Primary))
        }
        if (list == null) {
            Text(if (vault == null) "Pick your vault in Settings first." else "Loading…", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 16.dp))
            return@Column
        }
        list.forEachIndexed { i, p ->
            if (i > 0) HorizontalDivider(color = Outline)
            val a = answers[p.key]
            Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${p.emoji} ${p.label}", fontSize = 13.5.sp, modifier = Modifier.weight(1f))
                when (p.kind) {
                    JournalKind.SCALE, JournalKind.MOOD -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        p.options.forEachIndexed { n, o ->
                            val value = if (p.kind == JournalKind.MOOD) (n + 1).toString() else o
                            Choice(o, a == value) { answers = if (a == value) answers - p.key else answers + (p.key to value) }
                        }
                    }
                    JournalKind.TICK -> TickSquare(a == "y") { answers = if (a == "y") answers - p.key else answers + (p.key to "y") }
                    JournalKind.LINE -> Unit
                }
            }
            if (p.kind == JournalKind.LINE) OutlinedTextField(
                a.orEmpty(), { answers = answers + (p.key to it.replace("\n", " ")) }, singleLine = true, colors = fieldColors(),
                placeholder = { Text("optional", color = Muted) }, shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
            )
        }
        val chips = facts.parts()
        if (chips.isNotEmpty()) Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            chips.forEach { Text(it, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Muted,
                modifier = Modifier.clip(CircleShape).background(Surface2).padding(horizontal = 9.dp, vertical = 3.dp)) }
        }
        if (noteOpen) OutlinedTextField(
            note, { note = it }, colors = fieldColors(), placeholder = { Text("Anything else about today", color = Muted) },
            shape = RoundedCornerShape(12.dp), minLines = 3, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
        )
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (listening) "■ Stop" else "🎤", fontSize = 13.sp, color = if (listening) Warn else TextMain,
                modifier = Modifier.clip(CircleShape).background(if (listening) OverdueTint else Color.Transparent)
                    .border(1.dp, if (listening) Warn else Outline, CircleShape).clickable { toggleMic() }
                    .padding(horizontal = 12.dp, vertical = 7.dp))
            if (!noteOpen) Text("+ Note", fontSize = 12.5.sp, modifier = Modifier.clip(CircleShape).border(1.dp, Outline, CircleShape)
                .clickable { noteOpen = true }.padding(horizontal = 12.dp, vertical = 7.dp))
            Box(Modifier.weight(1f))
            DeckButton(if (saving) "Saving…" else "Save", primary = true) {
                val uri = vault ?: return@DeckButton
                if (saving) return@DeckButton
                saving = true
                scope.launch {
                    val r = withContext(Dispatchers.IO) {
                        runCatching { Journal.saveWithMood(appVault(ctx, uri), today, list, JournalEntry(answers, note), facts) }
                    }
                    saving = false
                    r.onSuccess { onSaved(); snack.showSnackbar("Saved to ${Journal.path(today).substringAfterLast('/')}") }
                        .onFailure { snack.showSnackbar("Failed: ${it.message}") }
                }
            }
        }
        if (listening) Text(heardNow.ifBlank { "Listening…" }, fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 8.dp))
        Text("Saves to ${Journal.path(today)}", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Muted, modifier = Modifier.padding(top = 10.dp))
    }
}

@Composable
private fun Choice(text: String, on: Boolean, onClick: () -> Unit) {
    Text(
        text, fontSize = 12.sp, color = if (on) Primary else Muted,
        modifier = Modifier.clip(CircleShape).background(if (on) PrimaryTint else Color.Transparent)
            .border(1.dp, if (on) Primary else Outline, CircleShape).clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 3.dp)
    )
}

@Composable
private fun TickSquare(on: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier.size(24.dp).clip(shape).background(if (on) Green else Color.Transparent)
            .border(1.8.dp, if (on) Green else Muted, shape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { if (on) Text("✓", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0B1F17)) }
}
