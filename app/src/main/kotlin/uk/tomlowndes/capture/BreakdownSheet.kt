package uk.tomlowndes.capture

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Break it down (build-queue T3): Jarvis splits [t] into small steps. The chillies set how small. Untick any you
 * don't want, then Add saves them as indented subtasks under the task, so Obsidian shows them nested.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BreakdownSheet(t: TaskItem, vault: Uri?, prefs: AppPrefs, snack: SnackbarHostState, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val secrets = remember { SecretStore(ctx) }
    val who = prefs.assistantName
    val address = prefs.jarvisAddress
    val token = secrets.jarvisToken.orEmpty()
    val demo = Jarvis.isDemo(address)
    val api = prefs.phoneBrain && !secrets.claudeKey.isNullOrBlank() && !demo
    val ready = demo || api || (address.isNotBlank() && token.isNotBlank())
    var level by remember { mutableIntStateOf(2) }
    var asks by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val steps = remember { mutableStateListOf<Step>() }
    val off = remember { mutableStateListOf<Int>() }
    val name = TaskViews.plainText(t)

    LaunchedEffect(level, asks) {
        if (!ready) return@LaunchedEffect
        loading = true; error = null
        val r = withContext(Dispatchers.IO) {
            runCatching {
                if (demo) Breakdown.demo(name, level)
                else {
                    val prompt = Breakdown.prompt(name, level)
                    val reply = if (api) {
                        val d = PhoneBrain.complete(secrets.claudeKey!!, prompt, "You are $who. UK English. Follow the request exactly.")
                        prefs.brainSpend = prefs.brainSpend.add(PhoneBrain.costUsd(PhoneBrain.FAST, d.inTok, d.outTok))
                        d.reply
                    } else Jarvis.sendText(address, token, prompt, "jarvis").reply
                    Breakdown.parse(reply)
                }
            }
        }
        loading = false
        r.onSuccess { steps.clear(); steps.addAll(it); off.clear(); if (it.isEmpty()) error = "$who didn't send back a list. Try again." }
        r.onFailure { error = PhoneBrain.failure(who, it, viaServer = !api) }
    }

    ModalBottomSheet(onDismissRequest = onDone, sheetState = sheet, containerColor = Surface1) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            Text("Break it down", fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Text(name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 2.dp))
            Row(Modifier.padding(top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("How small?", fontSize = 13.sp, color = Muted, modifier = Modifier.padding(end = 8.dp))
                (1..Breakdown.LEVELS).forEach { i ->
                    Text("🌶", fontSize = 18.sp, modifier = Modifier.alpha(if (i <= level) 1f else 0.25f).clickable { level = i }.padding(horizontal = 3.dp))
                }
            }
            when {
                !ready -> Text("Turn on Claude from the phone in Settings, or add the server there. Type demo as the address to try it.",
                    fontSize = 13.sp, color = Muted, modifier = Modifier.padding(vertical = 10.dp))
                loading -> Row(Modifier.padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(color = Primary, strokeWidth = 2.dp, modifier = Modifier.width(18.dp))
                    Text("$who is thinking…", fontSize = 13.sp, color = Muted)
                }
                error != null -> Text(error!!, fontSize = 13.sp, color = Warn, modifier = Modifier.padding(vertical = 10.dp))
                else -> ListCard {
                    steps.forEachIndexed { i, s ->
                        if (i > 0) HorizontalDivider(color = Outline)
                        Row(Modifier.fillMaxWidth().clickable { if (i in off) off.remove(i) else off.add(i) }.padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("${i + 1}", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Muted)
                            TickBox(done = i !in off, onClick = { if (i in off) off.remove(i) else off.add(i) }, outline = Amber)
                            Text(s.text, fontSize = 13.5.sp, color = if (i in off) Muted else TextMain, modifier = Modifier.weight(1f))
                            s.minutes?.let { Text("${it}m", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Muted) }
                        }
                    }
                }
            }
            val keep = steps.filterIndexed { i, _ -> i !in off }
            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Try again", fontSize = 13.sp, color = Muted, textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).clip(CircleShape).border(1.dp, Outline, CircleShape)
                        .clickable(enabled = ready && !loading) { asks++ }.padding(vertical = 11.dp))
                Text("Add ${keep.size} steps", fontSize = 13.sp, color = OnPrimary, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).clip(CircleShape).background(if (keep.isEmpty() || loading) Primary.copy(alpha = 0.4f) else Primary)
                        .clickable(enabled = keep.isNotEmpty() && !loading) {
                            val uri = vault ?: return@clickable
                            scope.launch {
                                val r = withContext(Dispatchers.IO) { runCatching { VaultWriter(ctx, uri).addSubtasks(t, keep) } }
                                if (r.isFailure) error = "Failed: ${r.exceptionOrNull()?.message}" else onDone()
                            }
                        }.padding(vertical = 11.dp))
            }
        }
    }
}
