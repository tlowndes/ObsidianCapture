package uk.tomlowndes.capture.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import uk.tomlowndes.capture.DateWords
import uk.tomlowndes.capture.NoteSearch
import uk.tomlowndes.capture.VaultNote
import java.time.LocalDate

/** Find a note by name or folder, then add a task (under its "## Tasks") or a line at its end. */
@Composable
fun FindPane(state: AppState) {
    val scope = rememberCoroutineScope()
    var notes by remember { mutableStateOf<List<VaultNote>?>(null) }
    var query by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<VaultNote?>(null) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(state.vault, state.changes) { notes = state.io("Couldn't read the vault") { state.writer()!!.allNotes() } }
    LaunchedEffect(Unit) { focus.requestFocus() }

    val results = notes?.let { NoteSearch.match(it, query) }.orEmpty()
    LaunchedEffect(results.firstOrNull()) { if (picked == null || picked !in results) picked = results.firstOrNull() }

    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(440.dp).fillMaxHeight().padding(horizontal = 24.dp, vertical = 26.dp)) {
            H1("Find a note", 26)
            Sub("Search by name or folder, then add a task or a line to it.", Modifier.padding(top = 4.dp, bottom = 14.dp))
            Field(query, { query = it }, Modifier.fillMaxWidth().focusRequester(focus).padding(bottom = 12.dp)
                .onKeys(enter = { picked = results.firstOrNull() }), "🔍  Note name", shape = RoundedCornerShape(999.dp))
            if (notes == null) Sub("Reading the vault…")
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(results, key = { it.path }) { n ->
                    val on = n == picked
                    val shape = RoundedCornerShape(12.dp)
                    Column(
                        Modifier.fillMaxWidth().clip(shape).background(if (on) Surface2 else Color.Transparent)
                            .then(if (on) Modifier.border(1.5.dp, Primary, shape) else Modifier)
                            .hand().clickable { picked = n }.padding(horizontal = 12.dp, vertical = 9.dp)
                    ) {
                        Text(n.path.substringAfterLast('/').removeSuffix(".md"), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextMain,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        NoteSearch.folder(n.path).takeIf { it.isNotEmpty() }?.let {
                            Text(it, fontSize = 11.5.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        Box(Modifier.fillMaxHeight().width(1.dp).background(Divider))
        Box(Modifier.weight(1f).fillMaxHeight().padding(36.dp)) {
            picked?.let { n -> AddToNote(state, n) } ?: Sub("Pick a note.")
        }
    }
}

@Composable
private fun AddToNote(state: AppState, note: VaultNote) {
    val scope = rememberCoroutineScope()
    var asTask by remember(note) { mutableStateOf(true) }
    var text by remember(note) { mutableStateOf("") }
    val (body, due) = DateWords.split(text, LocalDate.now())

    fun add() {
        val t = if (asTask) body else text
        if (t.isBlank()) return
        scope.launch {
            state.io { state.writer()!!.addToNote(note, t, asTask, if (asTask) due else null) } ?: return@launch
            text = ""
            state.say(if (asTask) "Task added to ${note.path.substringAfterLast('/').removeSuffix(".md")}" else "Added to the end of the note")
            state.changed()
        }
    }

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { H1(note.path.substringAfterLast('/').removeSuffix(".md"), 24) }
            Chip("Open in Obsidian ↗", small = true) { state.openInObsidian(note.path) }
        }
        Sub(note.path, Modifier.padding(top = 2.dp, bottom = 18.dp))
        Pill(listOf("✓ Task", "Line at the end"), if (asTask) 0 else 1, Modifier.width(300.dp).padding(bottom = 12.dp)) { asTask = it == 0 }
        Field(text, { text = it }, Modifier.fillMaxWidth().onKeys(enter = ::add),
            if (asTask) "Task (end with a date like “fri”)" else "Text to add", fontSize = 15)
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (asTask) "Goes under the note's ## Tasks heading if it has one, otherwise at the end" +
                    (due?.let { " · due ${dayChip(it)}" } ?: "")
                else "Goes on a new line at the end of the note",
                fontSize = 12.sp, color = Muted, modifier = Modifier.weight(1f)
            )
            PrimaryButton("Add", enabled = text.isNotBlank(), hint = "↵") { add() }
        }
    }
}
