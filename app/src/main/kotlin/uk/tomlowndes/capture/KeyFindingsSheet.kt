package uk.tomlowndes.capture

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** A note the chat found, waiting for Long or Short before anything is generated. */
internal data class FindingsAsk(val path: String, val note: String, val related: List<String>)

/**
 * Key findings: pick Long or Short, see them, then save. Switching length asks again.
 * Nothing is written until Save.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun KeyFindingsSheet(
    vault: Uri, path: String, note: String, related: List<String>, snack: SnackbarHostState,
    onDismiss: () -> Unit, onSaved: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { AppPrefs(ctx) }
    val secrets = remember { SecretStore(ctx) }
    val who = prefs.assistantName
    val address = prefs.jarvisAddress
    val token = secrets.jarvisToken.orEmpty()
    val demo = Jarvis.isDemo(address)
    val api = prefs.phoneBrain && !secrets.claudeKey.isNullOrBlank() && !demo
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var length by remember { mutableStateOf(prefs.findingsLength) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var findings by remember { mutableStateOf<KeyFindings.Findings?>(null) }
    var proposed by remember { mutableStateOf<String?>(null) }

    fun shown(e: Throwable) = if (e is KeyFindings.Trouble) e.message ?: "Couldn't do that" else PhoneBrain.failure(who, e, viaServer = !api && !demo)

    fun generate(next: KeyFindings.Length) {
        if (busy) return
        if (!demo && !api && (address.isBlank() || token.isBlank())) {
            problem = "Turn on Claude from the phone, or add the server in Settings."
            return
        }
        length = next
        prefs.findingsLength = next
        busy = true
        problem = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val prepared = KeyFindings.prepare(note)
                    if (prepared.article.length < KeyFindings.MIN_CHARS) throw KeyFindings.Thin()
                    val prompt = KeyFindings.prompt(prepared, next, related, who)
                    val reply = when {
                        demo -> KeyFindings.demo(next, prepared.links, related)
                        api -> {
                            val d = PhoneBrain.complete(secrets.claudeKey!!, prompt, "You are $who. British English. Follow the request exactly.")
                            prefs.brainSpend = prefs.brainSpend.add(PhoneBrain.costUsd(PhoneBrain.FAST, d.inTok, d.outTok))
                            d.reply
                        }
                        else -> Jarvis.sendText(address, token, prompt, "jarvis", speak = false).reply
                    }
                    KeyFindings.fromReply(note, reply, next, related, LocalDate.now())
                }
            }
            busy = false
            result.onSuccess { (found, text) -> findings = found; proposed = text }
                .onFailure { e -> problem = shown(e); if (findings == null) proposed = null }
        }
    }

    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }, sheetState = sheet, containerColor = Surface1) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            Text(if (findings == null) "Key findings" else "Check and save", fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Text(VaultTasks.noteName(path), fontSize = 13.sp, color = Muted, modifier = Modifier.padding(top = 2.dp, bottom = 12.dp))
            if (findings == null) {
                Text("Long is a few short paragraphs. Short is a handful of one-line bullets.", fontSize = 13.sp, color = Muted)
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KeyFindings.Length.entries.forEach { option ->
                        AppChip(length == option, { if (!busy) length = option }, { Text(option.label) }, RoundedCornerShape(99.dp))
                    }
                }
            } else {
                Text("Nothing else in the note changes.", fontSize = 13.sp, color = Muted, modifier = Modifier.padding(bottom = 8.dp))
                FindingsBody(findings!!)
            }
            problem?.let { Text(it, fontSize = 13.sp, color = Warn, modifier = Modifier.padding(top = 10.dp)) }
            if (busy) Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(color = Primary, strokeWidth = 2.dp, modifier = Modifier.width(18.dp).height(18.dp))
                Text("$who is reading the article…", fontSize = 13.sp, color = Muted)
            }
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val ready = findings != null && proposed != null
                if (!ready) {
                    Button(
                        onClick = { generate(length) }, enabled = !busy,
                        colors = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = OnPrimary)
                    ) { Text("Write them") }
                } else {
                    Button(
                        onClick = {
                            val text = proposed ?: return@Button
                            scope.launch {
                                val saved = withContext(Dispatchers.IO) { runCatching { VaultIo.write(appVault(ctx, vault), path, text) } }
                                saved.onSuccess { written ->
                                    if (written == text) onSaved(written)
                                    else snack.showSnackbar("The note changed on another device. Nothing was overwritten.")
                                }.onFailure { snack.showSnackbar("Couldn't save: ${it.message}") }
                            }
                        },
                        enabled = !busy, colors = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = OnPrimary)
                    ) { Text("Save") }
                    TextButton(onClick = { generate(if (length == KeyFindings.Length.LONG) KeyFindings.Length.SHORT else KeyFindings.Length.LONG) }, enabled = !busy) {
                        Text(if (length == KeyFindings.Length.LONG) "Switch to Short" else "Switch to Long", color = Primary)
                    }
                }
                TextButton(onClick = { if (!busy) onDismiss() }) { Text(if (ready) "Not now" else "Cancel", color = Muted) }
            }
        }
    }
}

@Composable
private fun FindingsBody(findings: KeyFindings.Findings) {
    Column(verticalArrangement = Arrangement.spacedBy(if (findings.length == KeyFindings.Length.LONG) 10.dp else 6.dp)) {
        findings.parts.forEach { part ->
            Text(if (findings.length == KeyFindings.Length.SHORT) "• $part" else part, fontSize = 15.sp, lineHeight = 22.sp, color = TextMain)
        }
        findings.worthTrying?.let { Text("Worth trying: $it", fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, color = TextMain) }
    }
}
