package uk.tomlowndes.capture.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.tomlowndes.capture.Article
import uk.tomlowndes.capture.DateWords
import uk.tomlowndes.capture.ReadLater
import uk.tomlowndes.capture.ReadStore
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.time.LocalDate

private val URL = Regex("^https?://\\S+$")

/** The small always-on-top popup the global shortcut (or Ctrl+N) opens: add a task or save a link, then it goes away. */
@Composable
fun QuickCaptureWindow(state: AppState, icon: Painter) {
    val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
    val width = 660
    val winState = rememberWindowState(
        size = DpSize(width.dp, 300.dp),
        position = WindowPosition(((screen.width - width) / 2).dp, (screen.height / 5).dp)
    )
    fun close() { state.quickVisible = false }
    Window(
        onCloseRequest = ::close, state = winState, title = "Quick capture", icon = icon,
        undecorated = true, transparent = true, alwaysOnTop = true, resizable = false, focusable = true,
        onPreviewKeyEvent = { e -> if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) { close(); true } else false }
    ) {
        // Clicking anywhere else puts it away, like the Windows search box.
        DisposableEffect(window) {
            val l = object : java.awt.event.WindowAdapter() {
                override fun windowLostFocus(e: java.awt.event.WindowEvent?) = close()
            }
            window.addWindowFocusListener(l)
            window.toFront(); window.requestFocus()
            onDispose { window.removeWindowFocusListener(l) }
        }
        CaptureTheme { QuickCapture(state, ::close) }
    }
}

@Composable
private fun QuickCapture(state: AppState, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    var link by remember { mutableStateOf(false) }
    var value by remember { mutableStateOf(TextFieldValue("")) }
    var preview by remember { mutableStateOf<Article?>(null) }
    var previewFor by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val text = value.text.trim()
    val (body, due) = DateWords.split(text, LocalDate.now())

    LaunchedEffect(Unit) {
        // Opened with a link on the clipboard: start in Link mode with it filled in, selected so typing replaces it.
        val clip = runCatching { Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as String }.getOrNull()?.trim()
        if (clip != null && URL.matches(clip)) { link = true; value = TextFieldValue(clip, TextRange(0, clip.length)) }
        delay(50); focus.requestFocus()
    }
    // A pasted link switches to Link mode by itself.
    LaunchedEffect(text) { if (URL.matches(text)) link = true }
    // Link preview, once typing stops.
    LaunchedEffect(text, link) {
        if (!link || !URL.matches(text) || text == previewFor) return@LaunchedEffect
        preview = null
        delay(400)
        previewFor = text
        preview = withContext(Dispatchers.IO) { ReadLater.peek(text) }
    }

    fun save() {
        if (busy || text.isEmpty() || state.vault == null) return
        busy = true
        scope.launch {
            if (link) {
                if (!URL.matches(text)) { busy = false; state.notify("Not a link", "Paste a link starting with http"); return@launch }
                val vault = state.vault!!
                val got = preview?.takeIf { previewFor == text }
                close()
                val full = state.io("Couldn't save the link") { ReadLater.save(vault, text, null, got) }
                if (full != null) state.notify("Saved to Read later", if (full) (got?.title ?: text) else "Saved the link only (couldn't fetch the page)")
            } else {
                val ok = state.io("Couldn't add the task") { state.writer()!!.addTask(body, due?.toString()) } != null
                close()
                if (ok) state.notify("Added to Quick Task", body + (due?.let { " · due ${dueLabel(it)}" } ?: ""))
            }
            state.changed()
        }
    }

    val shape = RoundedCornerShape(18.dp)
    Box(Modifier.fillMaxSize().padding(16.dp)) {
        Column(
            Modifier.fillMaxWidth().clip(shape).background(Popup).border(1.dp, Outline, shape).padding(18.dp)
        ) {
            if (state.vault == null) {
                Text("Pick your vault in Vault Capture first.", color = Muted)
                return@Column
            }
            Pill(listOf("✓ Task", "🔗 Link"), if (link) 1 else 0, Modifier.width(220.dp).padding(bottom = 12.dp)) { link = it == 1; focus.requestFocus() }
            val fieldShape = RoundedCornerShape(14.dp)
            Row(
                Modifier.fillMaxWidth().clip(fieldShape).background(Surface1).border(1.5.dp, Primary, fieldShape)
                    .padding(horizontal = 16.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(if (link) "🔗" else "✓", color = if (link) LinkBlue else TaskGreen, fontSize = 16.sp)
                BasicTextField(
                    value, { value = it }, singleLine = true, cursorBrush = SolidColor(Primary),
                    textStyle = TextStyle(color = if (link) LinkBlue else TextMain, fontSize = 17.sp),
                    modifier = Modifier.weight(1f).focusRequester(focus).onKeys(
                        enter = ::save,
                        tab = { link = !link }
                    ),
                    decorationBox = { inner ->
                        Box { if (value.text.isEmpty()) Text(if (link) "Paste a link…" else "Add a task… (end with a date like “fri”)", color = Muted, fontSize = 17.sp); inner() }
                    }
                )
                if (!link && due != null) Text("📅 " + dayChip(due), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextMain,
                    modifier = Modifier.clip(RoundedCornerShape(9.dp)).background(Surface2).padding(horizontal = 9.dp, vertical = 4.dp))
            }
            if (link && URL.matches(text)) Row(
                Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(14.dp)).background(Surface1).padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(preview?.title ?: if (previewFor == text) "Couldn't read the page, the link will be saved" else "Fetching the page…",
                        fontWeight = FontWeight.Bold, fontSize = 14.sp, color = TextMain, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(ReadStore.host(text), preview?.let { "${(it.markdown.length / 1100).coerceAtLeast(1)} min read" }, "goes to ReadItLater Inbox")
                        .joinToString(" · "), fontSize = 12.sp, color = Muted)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (link) "Saves the page as a full article note" else "Saves to ⚡ Quick Task · Tab switches to Link · a pasted URL switches by itself",
                    fontSize = 12.sp, color = Muted, modifier = Modifier.weight(1f)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Kbd("↵"); Text(if (busy) "saving…" else "save", fontSize = 12.sp, color = Muted)
                    Box(Modifier.size(6.dp)); Kbd("Esc"); Text("close", fontSize = 12.sp, color = Muted)
                }
            }
        }
    }
}

/** Enter and Tab handling for a single-line field. */
fun Modifier.onKeys(enter: () -> Unit, tab: (() -> Unit)? = null): Modifier = onPreviewKeyEvent { e ->
    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
    when (e.key) {
        Key.Enter, Key.NumPadEnter -> { enter(); true }
        Key.Tab -> if (tab != null) { tab(); true } else false
        else -> false
    }
}
