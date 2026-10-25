package uk.tomlowndes.capture

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

private data class Entry(val isLink: Boolean, val text: String, val sub: String)

@Composable
private fun HistoryItem(e: Entry) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surface1).padding(13.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(if (e.isLink) LinkTint else TaskTint),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(if (e.isLink) "🔗" else "✓")
        }
        Column {
            Text(e.text, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp)
            Text(e.sub, fontSize = 12.sp, color = Muted)
        }
    }
}

@Composable
fun CaptureScreen(vault: Uri?, snack: SnackbarHostState, onOpenSearch: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val history = remember { mutableStateListOf<Entry>() }

    var linkMode by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var due by remember { mutableStateOf<LocalDate?>(null) }
    var showPicker by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    fun submit() {
        val value = text.trim()
        val uri = vault
        if (value.isEmpty() || busy) return
        if (uri == null) { scope.launch { snack.showSnackbar("Pick your vault folder in Settings first") }; return }
        busy = true
        val wasLink = linkMode
        val wasDue = due
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (wasLink) {
                        val url = if (Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(value)) value
                        else "https://$value"
                        ReadLater.save(ctx, uri, url, null)
                    } else { VaultWriter(ctx, uri).addTask(value, wasDue?.toString()); true }
                }
            }
            busy = false
            result.onSuccess { fullPage ->
                history.add(0, Entry(wasLink, value,
                    if (wasLink) (if (fullPage) "Read later · full page" else "Read later · link only")
                    else "Task" + (wasDue?.let { " · due ${dueLabel(it)}" } ?: "")))
                text = ""; due = null
                snack.showSnackbar(
                    if (!wasLink) "Task added"
                    else if (fullPage) "Saved page to Read It Later" else "Saved link only (couldn't fetch the page)"
                )
            }.onFailure { snack.showSnackbar("Failed: ${it.message}") }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Logo()
                        Text("Capture", fontSize = 34.sp, fontWeight = FontWeight.Bold)
                    }
                }
                TextButton(onClick = onOpenSearch) { Text("🔍", color = Muted, fontSize = 20.sp) }
            }
            Spacer(Modifier.height(2.dp))

            PillSwitch(listOf("Task", "Read later"), if (linkMode) 1 else 0) { linkMode = it == 1 }

            OutlinedTextField(
                value = text, onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
                label = { Text(if (linkMode) "Paste a link" else "What needs doing?") },
                minLines = if (linkMode) 1 else 3,
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp),
                keyboardOptions = if (linkMode) KeyboardOptions(keyboardType = KeyboardType.Uri)
                else KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                colors = fieldColors()
            )

            if (!linkMode) {
                val today = LocalDate.now()
                val custom = due != null && due != today && due != today.plusDays(1)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppChip(due == today, { due = if (due == today) null else today },
                        { Text("Today") }, shape = RoundedCornerShape(12.dp))
                    AppChip(due == today.plusDays(1), { due = if (due == today.plusDays(1)) null else today.plusDays(1) },
                        { Text("Tomorrow") }, shape = RoundedCornerShape(12.dp))
                    AppChip(custom, { showPicker = true },
                        { Text(if (custom) dueLabel(due!!) else "Pick date") }, shape = RoundedCornerShape(12.dp))
                }
            }

            Button(
                onClick = ::submit, enabled = text.isNotBlank() && !busy, shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = OnPrimary),
                modifier = Modifier.fillMaxWidth().height(54.dp)
            ) { Text(if (linkMode) "Save link" else "Add task", fontWeight = FontWeight.Bold, fontSize = 16.sp) }

            if (history.isNotEmpty()) {
                GroupLabel("Just added")
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { history.take(5).forEach { HistoryItem(it) } }
            }
        }
        Text(
            vault?.let { "Vault: " + Uri.decode(it.lastPathSegment ?: it.toString()) } ?: "No vault selected",
            fontSize = 12.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(vertical = 10.dp)
        )
    }

    if (showPicker) {
        PickDateDialog(due, onPick = { due = it; showPicker = false }, onDismiss = { showPicker = false })
    }
}
