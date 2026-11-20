package uk.tomlowndes.capture.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.tomlowndes.capture.Jarvis
import uk.tomlowndes.capture.KeyFindings
import uk.tomlowndes.capture.PhoneBrain
import uk.tomlowndes.capture.VaultIo
import uk.tomlowndes.capture.VaultTasks
import java.time.LocalDate

/** A note found from chat, waiting for Long or Short before anything is generated. */
data class FindingsNote(val path: String, val note: String, val related: List<String>)

/**
 * Key findings on Windows. The server (or demo) writes the reply. Nothing is saved until Save.
 * [onSaved] receives the note text that was written.
 */
@Composable
fun KeyFindingsDialog(
    state: AppState, path: String, note: String, related: List<String>,
    onDismiss: () -> Unit, onSaved: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val who = state.prefs.assistantName
    var length by remember { mutableStateOf(state.prefs.findingsLength) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var findings by remember { mutableStateOf<KeyFindings.Findings?>(null) }
    var proposed by remember { mutableStateOf<String?>(null) }

    fun shown(e: Throwable) = if (e is KeyFindings.Trouble) e.message ?: "Couldn't do that"
    else PhoneBrain.failure(who, e, viaServer = !Jarvis.isDemo(state.prefs.jarvisAddress))

    fun generate(next: KeyFindings.Length) {
        if (busy) return
        if (state.vault == null) { problem = "Pick a vault folder in Settings first"; return }
        if (!DesktopJarvis.ready(state.prefs)) { problem = "Add the server address and token in Settings."; return }
        length = next
        state.prefs.findingsLength = next
        busy = true
        problem = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val prepared = KeyFindings.prepare(note)
                    if (prepared.article.length < KeyFindings.MIN_CHARS) throw KeyFindings.Thin()
                    val prompt = KeyFindings.prompt(prepared, next, related, who)
                    val reply = if (Jarvis.isDemo(state.prefs.jarvisAddress)) KeyFindings.demo(next, prepared.links, related)
                    else Jarvis.sendText(state.prefs.jarvisAddress, state.prefs.jarvisToken ?: throw KeyFindings.Trouble("Add the server token in Settings"), prompt, "jarvis", speak = false).reply
                    KeyFindings.fromReply(note, reply, next, related, LocalDate.now())
                }
            }
            busy = false
            result.onSuccess { (found, text) -> findings = found; proposed = text }
                .onFailure { e -> problem = shown(e) }
        }
    }

    DialogCard(if (findings == null) "Key findings" else "Check and save", { if (!busy) onDismiss() }) {
        Text(VaultTasks.noteName(path), fontSize = 13.sp, color = Muted, modifier = Modifier.padding(bottom = 10.dp))
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            if (findings == null) {
                Text("Long is a few short paragraphs. Short is a handful of one-line bullets.", fontSize = 13.sp, color = Muted)
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KeyFindings.Length.entries.forEach { option ->
                        Chip(option.label, selected = length == option, small = true) { if (!busy) length = option }
                    }
                }
            } else {
                Text("Nothing else in the note changes.", fontSize = 13.sp, color = Muted, modifier = Modifier.padding(bottom = 8.dp))
                findings!!.parts.forEach { part ->
                    Text(
                        if (findings!!.length == KeyFindings.Length.SHORT) "• $part" else part,
                        fontSize = 14.5.sp, lineHeight = 21.sp, color = TextMain, modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
                findings!!.worthTrying?.let {
                    Text("Worth trying: $it", fontSize = 14.5.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold, color = TextMain)
                }
            }
            problem?.let { Text(it, fontSize = 13.sp, color = Warn, modifier = Modifier.padding(top = 8.dp)) }
            if (busy) Text("$who is reading the article…", fontSize = 13.sp, color = Muted, modifier = Modifier.padding(top = 8.dp))
        }
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val ready = findings != null && proposed != null
            if (!ready) PrimaryButton("Write them", enabled = !busy) { generate(length) }
            else {
                PrimaryButton("Save", enabled = !busy) {
                    val text = proposed ?: return@PrimaryButton
                    val v = state.vault ?: return@PrimaryButton
                    scope.launch {
                        val written = state.io("Couldn't save") { VaultIo.write(v, path, text) } ?: return@launch
                        if (written == text) onSaved(written)
                        else state.say("The note changed on another device. Nothing was overwritten.")
                    }
                }
                Chip(if (length == KeyFindings.Length.LONG) "Switch to Short" else "Switch to Long", small = true) {
                    generate(if (length == KeyFindings.Length.LONG) KeyFindings.Length.SHORT else KeyFindings.Length.LONG)
                }
            }
            TextAction(if (ready) "Not now" else "Cancel", Muted) { if (!busy) onDismiss() }
        }
    }
}
