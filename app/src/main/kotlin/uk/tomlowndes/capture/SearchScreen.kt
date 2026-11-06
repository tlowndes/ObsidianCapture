package uk.tomlowndes.capture

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** Find any note by name or folder, then add a task or a line to it. */
@Composable
fun SearchScreen(vault: Uri?, snack: SnackbarHostState, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var notes by remember { mutableStateOf<List<VaultNote>?>(null) }
    var query by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<VaultNote?>(null) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(vault) {
        val uri = vault ?: return@LaunchedEffect
        withContext(Dispatchers.IO) { runCatching { VaultWriter(ctx, uri).allNotes() } }
            .onSuccess { notes = it }
            .onFailure { notes = emptyList(); snack.showSnackbar("Couldn't read the vault: ${it.message}") }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        ScreenTitle("Find a note", notes?.let { "${it.size} notes" } ?: if (vault == null) "Pick your vault in Settings" else "Reading the vault…") {
            TextButton(onClick = onClose) { Text("Close", color = Muted) }
        }
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().focusRequester(focus), shape = RoundedCornerShape(20.dp),
            label = { Text("Note name or folder") }, singleLine = true, colors = fieldColors()
        )
        val results = remember(notes, query) { NoteSearch.match(notes.orEmpty(), query) }
        if (notes != null && results.isEmpty()) {
            Text("No notes match", color = Muted, modifier = Modifier.padding(top = 20.dp))
        }
        if (query.isBlank() && results.isNotEmpty()) {
            GroupLabel("Recently changed", Muted)
        }
        LazyColumn(Modifier.fillMaxSize().padding(top = 10.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
            items(results, key = { it.path }) { n ->
                Column(
                    Modifier.fillMaxWidth().padding(bottom = 7.dp).clip(RoundedCornerShape(16.dp)).background(Surface1)
                        .clickable { picked = n }.padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Text(VaultTasks.noteName(n.path), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    NoteSearch.folder(n.path).takeIf { it.isNotEmpty() }?.let {
                        Text(it, fontSize = 12.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }

    picked?.let { n ->
        AddToNoteDialog(n, onDismiss = { picked = null }) { text, asTask, due ->
            val uri = vault ?: return@AddToNoteDialog
            picked = null
            scope.launch {
                withContext(Dispatchers.IO) { runCatching { VaultWriter(ctx, uri).addToNote(n, text, asTask, due) } }
                    .onSuccess { snack.showSnackbar((if (asTask) "Task added to " else "Added to ") + VaultTasks.noteName(n.path)) }
                    .onFailure { snack.showSnackbar("Failed: ${it.message}") }
            }
        }
    }
}

@Composable
internal fun AddToNoteDialog(note: VaultNote, onDismiss: () -> Unit, onAdd: (String, Boolean, LocalDate?) -> Unit) {
    var asTask by remember { mutableStateOf(true) }
    var text by remember { mutableStateOf("") }
    var due by remember { mutableStateOf<LocalDate?>(null) }
    var picking by remember { mutableStateOf(false) }
    if (picking) {
        PickDateDialog(due, onPick = { due = it; picking = false }, onDismiss = { picking = false })
        return
    }
    val today = LocalDate.now()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface1,
        title = { Text(VaultTasks.noteName(note.path), fontSize = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PillSwitch(listOf("Task", "Line"), if (asTask) 0 else 1) { asTask = it == 0 }
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp), minLines = 2, colors = fieldColors(),
                    label = { Text(if (asTask) "What needs doing?" else "Add to the end of the note") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
                )
                if (asTask) {
                    val custom = due != null && due != today && due != today.plusDays(1)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AppChip(due == today, { due = if (due == today) null else today }, { Text("Today") })
                        AppChip(due == today.plusDays(1), { due = if (due == today.plusDays(1)) null else today.plusDays(1) }, { Text("Tomorrow") })
                        AppChip(custom, { picking = true }, { Text(if (custom) dueLabel(due!!) else "Date") })
                    }
                    Text("Goes under ## Tasks if the note has one, otherwise at the end.", fontSize = 12.sp, color = Muted)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(text.trim(), asTask, if (asTask) due else null) }, enabled = text.isNotBlank()) {
                Text("Add", color = Primary, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Muted) } }
    )
}
