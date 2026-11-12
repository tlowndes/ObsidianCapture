package uk.tomlowndes.capture

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime

/** What a Print summary is made from. */
sealed class SummarySource {
    /** A vault note, from the Vault reader's 🧾. */
    data class Note(val title: String, val body: String) : SummarySource()
    /** The last Jarvis exchange, from 🧾 beside the worker picker. */
    data class Chat(val said: String, val reply: String) : SummarySource()
}

/**
 * Print summary (print-summary-v1): Jarvis sums up [source], the paper shows first, Short / Bullets / Longer,
 * Redo and Edit tweak it, and Print sends it to the pocket printer saved in Daily Slip.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun PrintSummarySheet(source: SummarySource, snack: SnackbarHostState, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { AppPrefs(ctx) }
    remember { SlipText.load(ctx) }
    val address = remember { prefs.jarvisAddress }
    val token = remember { SecretStore(ctx).jarvisToken.orEmpty() }
    val demo = Jarvis.isDemo(address)
    val ready = demo || (address.isNotBlank() && token.isNotBlank())
    val chat = source is SummarySource.Chat
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var length by remember { mutableStateOf(Summary.Length.BULLETS) }
    var ticks by remember { mutableStateOf(chat) }
    var asks by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var doc by remember { mutableStateOf<Summary.Doc?>(null) }
    var paper by remember { mutableStateOf<Bitmap?>(null) }
    var editing by remember { mutableStateOf<String?>(null) }
    val now = remember(asks, length) { LocalDateTime.now() }

    val title = when (source) { is SummarySource.Note -> source.title; is SummarySource.Chat -> "Jarvis chat" }
    val subtitle = (if (chat) "Jarvis chat · " else "Summary · ") + Summary.stamp(now)

    LaunchedEffect(length, asks) {
        if (!ready) return@LaunchedEffect
        loading = true; error = null
        val r = withContext(Dispatchers.IO) {
            runCatching {
                val reply = if (demo) Summary.demo(length, chat) else when (source) {
                    is SummarySource.Note -> Jarvis.sendText(address, token, Summary.notePrompt(source.title, source.body, length), "obsidian").reply
                    is SummarySource.Chat -> Jarvis.sendText(address, token, Summary.chatPrompt(source.said, source.reply, length), "jarvis").reply
                }
                Summary.parse(reply, title, subtitle, length, ticks)
            }
        }
        loading = false
        r.onSuccess { if (it.text.isBlank() && it.bullets.isEmpty()) error = "Jarvis sent back nothing. Try Redo." else doc = it }
        r.onFailure { error = if (it is Jarvis.HttpError) it.message else "Can't reach Jarvis. Is Tailscale on?" }
    }
    LaunchedEffect(doc, ticks) {
        val d = doc ?: return@LaunchedEffect
        paper = withContext(Dispatchers.Default) { runCatching { SlipRender.drawSummary(d.copy(ticks = ticks), now) }.getOrNull() }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Surface1) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            Text("Print summary", fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Text(if (chat) "Your last Jarvis chat" else title, fontSize = 13.sp, color = Muted, modifier = Modifier.padding(top = 2.dp, bottom = 12.dp))

            Box(
                Modifier.fillMaxWidth().heightIn(min = 120.dp).clip(RoundedCornerShape(6.dp)).background(Color.White).padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                val b = paper
                when {
                    !ready -> Text("Set up Jarvis in Settings first (address and token), or type demo as the address to try it.",
                        fontSize = 13.sp, color = Color.DarkGray, modifier = Modifier.padding(20.dp))
                    loading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(color = Color.DarkGray, strokeWidth = 2.dp, modifier = Modifier.width(18.dp).height(18.dp))
                        Text("Jarvis is summing it up…", fontSize = 13.sp, color = Color.DarkGray)
                    }
                    error != null -> Text(error!!, fontSize = 13.sp, color = Color(0xFFB3261E), modifier = Modifier.padding(20.dp))
                    b != null -> Image(b.asImageBitmap(), "Preview of the summary slip", Modifier.fillMaxWidth(0.86f),
                        contentScale = ContentScale.FillWidth, filterQuality = FilterQuality.Medium)
                }
            }

            FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Summary.Length.entries.forEach { l -> AppChip(length == l, { length = l }, { Text(l.label) }, RoundedCornerShape(99.dp)) }
                if (chat) AppChip(ticks, { ticks = !ticks }, { Text("Tick boxes") }, RoundedCornerShape(99.dp))
                AppChip(false, { if (ready && !loading) asks++ }, { Text("↻ Redo") }, RoundedCornerShape(99.dp))
                AppChip(false, { doc?.let { editing = it.editable() } }, { Text("✎ Edit") }, RoundedCornerShape(99.dp))
            }

            SlipPrintButton(paper?.takeIf { !loading && error == null }, snack, Modifier.padding(top = 10.dp))
        }
    }

    editing?.let { e ->
        var t by remember(e) { mutableStateOf(e) }
        AlertDialog(
            onDismissRequest = { editing = null }, containerColor = Surface1,
            title = { Text("Edit before printing", fontSize = 17.sp) },
            text = {
                OutlinedTextField(t, { t = it }, Modifier.fillMaxWidth().heightIn(min = 160.dp), colors = fieldColors(),
                    supportingText = { Text("Lines starting with - print as points.", color = Muted) })
            },
            confirmButton = { TextButton(onClick = { doc = doc?.edited(t); editing = null }) { Text("Done", color = Primary) } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel", color = Muted) } }
        )
    }
}

/** Print as list (printables-v1): a vault note's list lines as tick boxes, grouped by its headings. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PrintListSheet(title: String, body: String, snack: SnackbarHostState, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    remember { SlipText.load(ctx) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var byHeading by remember { mutableStateOf(true) }
    var skipTicked by remember { mutableStateOf(true) }
    var paper by remember { mutableStateOf<Bitmap?>(null) }
    var count by remember { mutableIntStateOf(0) }
    LaunchedEffect(byHeading, skipTicked) {
        val d = PrintDocs.listFrom(title, body, byHeading, skipTicked)
        count = d.count
        paper = if (d.count == 0) null else withContext(Dispatchers.Default) { runCatching { SlipRender.drawList(d) }.getOrNull() }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Surface1) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            Text("Print as list", fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Text("$title · $count open", fontSize = 13.sp, color = Muted, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
            SettingRow("Group by heading", "Uses the note's ## headings") { AppSwitch(byHeading) { byHeading = it } }
            SettingRow("Leave out ticked items") { AppSwitch(skipTicked) { skipTicked = it } }
            Box(
                Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 120.dp).clip(RoundedCornerShape(6.dp)).background(Color.White).padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                val b = paper
                if (b == null) Text("No list lines in this note (- or - [ ]).", fontSize = 13.sp, color = Color.DarkGray, modifier = Modifier.padding(20.dp))
                else Image(b.asImageBitmap(), "Preview of the list slip", Modifier.fillMaxWidth(0.86f), contentScale = ContentScale.FillWidth, filterQuality = FilterQuality.Medium)
            }
            SlipPrintButton(paper, snack, Modifier.padding(top = 10.dp))
        }
    }
}

/**
 * The Print button for any slip [bitmap]: asks for Bluetooth, lets you choose the printer the first time (shared with
 * Daily Slip), then sends it. Shows progress under the button.
 */
@Composable
internal fun SlipPrintButton(bitmap: Bitmap?, snack: SnackbarHostState, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var printer by remember { mutableStateOf(SlipPrinter.saved(ctx)) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var allowed by remember { mutableStateOf(SlipPrinter.allowed(ctx)) }
    var afterAllow by remember { mutableStateOf<(() -> Unit)?>(null) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        allowed = r.values.all { it }
        if (allowed) afterAllow?.invoke() else status = "Flight Deck needs Bluetooth permission to reach the printer."
        afterAllow = null
    }
    fun withBluetooth(then: () -> Unit) {
        if (allowed) then() else { afterAllow = then; ask.launch(SlipPrinter.permissions) }
    }
    fun send() {
        val pr = printer ?: run { picking = true; return }
        val bmp = bitmap ?: return
        if (!SlipPrinter.bluetoothOn(ctx)) { status = "Bluetooth is off. Switch it on and try again."; return }
        busy = true; status = "Connecting to ${pr.name}…"
        scope.launch {
            val r = runCatching {
                val job = withContext(Dispatchers.Default) { SlipRender.job(bmp) }
                withContext(Dispatchers.IO) { SlipPrinter.print(ctx, pr, job) { pc -> status = "Printing on ${pr.name} · $pc%" } }
            }
            busy = false
            status = r.fold({ "Printed on ${pr.name}." }, { it.message ?: "Printing failed" })
            if (r.isFailure) snack.showSnackbar(status!!)
        }
    }

    Column(modifier) {
        Button(
            onClick = { withBluetooth { send() } }, enabled = !busy && bitmap != null,
            colors = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = OnPrimary),
            shape = RoundedCornerShape(99.dp), modifier = Modifier.fillMaxWidth().height(50.dp)
        ) {
            if (busy) CircularProgressIndicator(Modifier.width(20.dp).height(20.dp), color = OnPrimary, strokeWidth = 2.dp)
            else Text(if (printer == null) "Choose a printer" else "Print", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        }
        status?.let { Text(it, fontSize = 13.sp, color = Muted, modifier = Modifier.padding(top = 8.dp)) }
    }

    if (picking) PrinterPicker(
        onPick = { SlipPrinter.save(ctx, it); printer = it; picking = false; status = "${it.name} saved. Tap Print." },
        onDismiss = { picking = false }
    )
}
