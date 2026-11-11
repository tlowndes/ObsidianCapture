package uk.tomlowndes.capture

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A summary slip (a Jarvis chat or a note) for the pocket printer, drawn like the Daily Slip. */
object SummarySlip {
    private const val W = SlipRender.WIDTH
    private const val M = 6f
    private val head = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    private val mono = Typeface.MONOSPACE
    private val monoBold = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt() }

    private fun paint(size: Float, face: Typeface = mono) =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; typeface = face; color = 0xFF000000.toInt() }

    private fun stroke(w: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt(); style = Paint.Style.STROKE; strokeWidth = w }

    /** [kind] is the big word at the top, e.g. "JARVIS" or "NOTE". */
    fun draw(s: PhoneBrain.Summary, kind: String, now: LocalDateTime = LocalDateTime.now()): Bitmap {
        val tall = Bitmap.createBitmap(W, 6000, Bitmap.Config.ARGB_8888)
        val c = Canvas(tall)
        c.drawColor(0xFFFFFFFF.toInt())
        var y = 24f
        val inner = W - 2 * M
        fun text(t: String, p: TextPaint, x: Float = M, w: Float = inner, align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL) {
            val l = StaticLayout.Builder.obtain(t, 0, t.length, p, w.toInt()).setAlignment(align).setLineSpacing(0f, 1.05f).setIncludePad(false).build()
            c.save(); c.translate(x, y); l.draw(c); c.restore(); y += l.height
        }
        fun solid() { y += 10f; c.drawRect(M, y, W - M, y + 5f, ink); y += 23f }
        fun dashed() {
            y += 20f
            c.drawLine(M, y, W - M, y, stroke(3f).apply { pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f) })
            y += 24f
        }
        fun heading(t: String) {
            val hp = paint(36f, head)
            c.drawText(t, M, y + 30f, hp)
            val x1 = M + hp.measureText(t) + 14f
            if (x1 < W - M - 20) c.drawRect(x1, y + 16f, W - M, y + 21f, ink)
            y += 52f
        }

        text(kind.uppercase(Locale.UK), paint(96f, head), align = Layout.Alignment.ALIGN_CENTER)
        y += 4f
        text(now.format(DateTimeFormatter.ofPattern("EEE d MMM yyyy · HH:mm", Locale.UK)).uppercase(Locale.UK),
            paint(24f), align = Layout.Alignment.ALIGN_CENTER)
        solid()
        text(s.title, paint(44f, head))
        if (s.points.isNotEmpty()) {
            dashed(); heading("KEY POINTS")
            s.points.forEach { p ->
                val top = y
                c.drawCircle(M + 12f, top + 17f, 6f, ink)
                text(p, paint(27f), M + 34f, inner - 34f)
                y = maxOf(y, top + 32f) + 10f
            }
        }
        if (s.todos.isNotEmpty()) {
            dashed(); heading("TO DO")
            s.todos.forEach { t ->
                val top = y
                c.drawRect(M + 3f, top + 4f, M + 29f, top + 30f, stroke(3.5f))
                text(t, paint(27f), M + 44f, inner - 44f)
                y = maxOf(y, top + 34f) + 12f
            }
        }
        if (s.points.isEmpty() && s.todos.isEmpty()) { y += 12f; text("Nothing to sum up yet.", paint(27f)) }
        y += 10f; solid()
        text("Printed " + now.format(DateTimeFormatter.ofPattern("HH:mm")) + " · Flight Deck", paint(22f, monoBold), align = Layout.Alignment.ALIGN_CENTER)
        val h = (y + 40).toInt().coerceAtMost(tall.height)
        return Bitmap.createBitmap(tall, 0, 0, W, h).also { if (it !== tall) tall.recycle() }
    }
}

/**
 * Preview and print a summary: [load] makes it (it may ask Claude), the slip is drawn as it'll print, and Print sends it
 * to the saved pocket printer (choosing one first if needed). Used by Jarvis's Print sheet and the Vault reader.
 */
@Composable
internal fun SummaryPrintPanel(kind: String, key: Any?, load: suspend () -> PhoneBrain.Summary) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var slip by remember(key) { mutableStateOf<Bitmap?>(null) }
    var error by remember(key) { mutableStateOf<String?>(null) }
    var printer by remember { mutableStateOf(SlipPrinter.saved(ctx)) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var afterAllow by remember { mutableStateOf<(() -> Unit)?>(null) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.all { it }) afterAllow?.invoke() else status = "Flight Deck needs Bluetooth permission to reach the printer."
        afterAllow = null
    }
    fun withBluetooth(then: () -> Unit) { if (SlipPrinter.allowed(ctx)) then() else { afterAllow = then; ask.launch(SlipPrinter.permissions) } }

    LaunchedEffect(key) {
        val r = withContext(Dispatchers.IO) { runCatching { load() } }
        r.onSuccess { s -> slip = withContext(Dispatchers.Default) { SummarySlip.draw(s, kind) } }
            .onFailure { error = it.message ?: "Couldn't make the summary" }
    }

    fun print() {
        val pr = printer ?: run { picking = true; return }
        val bmp = slip ?: return
        if (!SlipPrinter.bluetoothOn(ctx)) { status = "Bluetooth is off. Switch it on and try again."; return }
        busy = true; status = "Connecting to ${pr.name}…"
        scope.launch {
            val r = runCatching {
                val job = withContext(Dispatchers.Default) { SlipRender.job(bmp) }
                withContext(Dispatchers.IO) { SlipPrinter.print(ctx, pr, job) { pc -> status = "Printing on ${pr.name} · $pc%" } }
            }
            busy = false
            status = r.fold({ "Printed on ${pr.name}." }, { it.message ?: "Printing failed" })
        }
    }

    Column {
        Box(
            Modifier.fillMaxWidth().heightIn(max = 340.dp).clip(RoundedCornerShape(6.dp)).background(Color.White)
                .verticalScroll(rememberScrollState()).padding(vertical = 10.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            val b = slip
            when {
                b != null -> Image(b.asImageBitmap(), "Preview of the slip", Modifier.fillMaxWidth(0.7f),
                    contentScale = ContentScale.FillWidth, filterQuality = FilterQuality.Medium)
                error != null -> Text(error!!, color = Color(0xFF8A2A22), fontSize = 13.sp, modifier = Modifier.padding(24.dp))
                else -> Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.DarkGray)
                    Text("Summing it up…", color = Color.DarkGray, fontSize = 13.sp)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(9.dp).clip(CircleShape).background(if (printer != null) Green else Muted))
            Text(printer?.name ?: "No printer yet", fontSize = 14.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { withBluetooth { picking = true } }) { Text(if (printer == null) "Choose" else "Change", color = Primary) }
        }
        Button(
            onClick = { withBluetooth { print() } }, enabled = !busy && slip != null,
            colors = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = OnPrimary),
            shape = RoundedCornerShape(99.dp), modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = OnPrimary, strokeWidth = 2.dp)
            else Text(if (printer == null) "Choose a printer" else "Print", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        }
        status?.let { Text(it, fontSize = 12.5.sp, color = Muted, modifier = Modifier.padding(top = 8.dp)) }
    }

    if (picking) PrinterPicker(
        onPick = { SlipPrinter.save(ctx, it); printer = it; picking = false; status = "${it.name} saved. Tap Print." },
        onDismiss = { picking = false }
    )
}

/**
 * Jarvis's 🖨 sheet: Today opens the Daily Slip, This chat sums up the chat on screen, A note points to the Vault reader.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PrintSummarySheet(
    chat: List<PhoneBrain.Turn>, claudeKey: String, onToday: () -> Unit, onDismiss: () -> Unit,
) {
    var pick by remember { mutableStateOf(1) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Surface1) {
        Column(Modifier.padding(horizontal = 18.dp).padding(bottom = 24.dp)) {
            Text("🖨 Print a summary", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                listOf("☀️" to "Today", "💬" to "This chat", "📝" to "A note").forEachIndexed { i, (icon, label) ->
                    val on = i == pick
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(if (on) PrimaryTint else Color.Transparent)
                            .border(1.dp, if (on) Primary else Outline, RoundedCornerShape(14.dp))
                            .clickable { if (i == 0) { onDismiss(); onToday() } else pick = i }.padding(vertical = 9.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(icon, fontSize = 17.sp)
                        Text(label, fontSize = 12.5.sp, color = if (on) TextMain else Muted)
                    }
                }
            }
            when (pick) {
                1 -> if (chat.none { !it.mine }) Text("Nothing in this chat to sum up yet.", fontSize = 13.sp, color = Muted)
                else SummaryPrintPanel("Jarvis", chat.size) { chatSummary(chat, claudeKey) }
                else -> Text(
                    "Open any note in Vault and tap 🖨 at the top to print it. Long notes are shortened to fit.",
                    fontSize = 13.sp, color = Muted, lineHeight = 19.sp, textAlign = TextAlign.Start
                )
            }
        }
    }
}

/** Claude's summary of a chat, or (no key) the last few answers as points. */
internal fun chatSummary(chat: List<PhoneBrain.Turn>, claudeKey: String): PhoneBrain.Summary =
    if (claudeKey.isNotBlank()) PhoneBrain.summarise(claudeKey, PhoneBrain.FAST, "chat", PhoneBrain.transcript(chat.takeLast(60)))
    else PhoneBrain.Summary("Jarvis chat", chat.filter { !it.mine }.takeLast(5).map { PhoneBrain.plainMd(it.text).lines().first().take(120) }, emptyList())

/** A note's summary: Claude for long notes when there's a key, otherwise its own bullets and to-dos. */
internal fun noteSummary(name: String, markdown: String, claudeKey: String): PhoneBrain.Summary {
    val own = PhoneBrain.fromNote(name, markdown)
    val long = markdown.length > 1500 || own.points.size + own.todos.size > 12
    return if (claudeKey.isNotBlank() && long) PhoneBrain.summarise(claudeKey, PhoneBrain.FAST, "note called \"$name\"", markdown.take(60_000)) else own
}
