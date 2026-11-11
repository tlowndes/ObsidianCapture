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
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDatePickerState
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
import androidx.compose.ui.input.key.isAltPressed
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
import uk.tomlowndes.capture.Tags
import uk.tomlowndes.capture.TaskRules
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** The whole clipboard or field is one http(s) link, with nothing else around it. */
internal fun isBareUrl(text: String) = Regex("^https?://\\S+$").matches(text.trim())

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
        onPreviewKeyEvent = { e ->
            if (e.type == KeyEventType.KeyUp && jarvisKeyUp(state, e.key)) true
            else if (e.type == KeyEventType.KeyDown && e.key == Key.Escape && state.jarvisOrNull?.mic?.recording != true) { close(); true } else false
        }
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
        CaptureTheme {
            QuickCapture(state, ::close) { side, tall ->
                // Grows to the right for the Custom… or date panel, and down for the task chips.
                winState.size = DpSize((width + if (side) 420 else 0).dp, (if (side) 600 else if (tall) 500 else 380).dp)
            }
        }
    }
}

@Composable
private fun QuickCapture(state: AppState, close: () -> Unit, resize: (side: Boolean, tall: Boolean) -> Unit) {
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    var link by remember { mutableStateOf(false) }
    var jarvisMode by remember { mutableStateOf(false) }
    var value by remember { mutableStateOf(TextFieldValue("")) }
    var preview by remember { mutableStateOf<Article?>(null) }
    var previewFor by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<LocalDate?>(null) }
    var minutes by remember { mutableStateOf<Int?>(null) }
    var repeat by remember { mutableStateOf<String?>(null) }
    var side by remember { mutableStateOf<Side?>(null) }
    // Tags from the Tag row; in Task mode a #word typed in the box counts too.
    var chosenTags by remember { mutableStateOf<List<String>>(emptyList()) }
    var pinned by remember { mutableStateOf<List<String>>(emptyList()) }
    var known by remember { mutableStateOf<List<Pair<String, Int>>>(emptyList()) }
    val today = LocalDate.now()
    val text = value.text.trim()
    val typedTags = if (link) emptyList() else Tags.inText(value.text)
    val onTags = (chosenTags + typedTags).distinct()
    val typing = if (link) null else Tags.typing(value.text, value.selection.end)
    val suggestions = typing?.let { (_, part) ->
        val pool = (known + (Tags.BUILT_IN + pinned).filter { p -> known.none { it.first == p } }.map { it to 0 })
        Tags.suggest(part, pool, 5).map { it.first }
    }.orEmpty()
    val (body, typed) = DateWords.split(text, today)
    // A repeating task needs a first date for the series to count from.
    val due = picked ?: typed ?: if (repeat != null) today else null
    LaunchedEffect(side, link, jarvisMode) { resize(side != null && !link && !jarvisMode, !link && !jarvisMode) }

    LaunchedEffect(state.vault) {
        val v = state.vault ?: return@LaunchedEffect
        state.io { Tags.loadPinned(v) to Tags.counts(state.writer()!!.listTasks().map { it.raw }) }?.let { (p, k) -> pinned = p; known = k }
    }

    fun setText(t: String) { value = TextFieldValue(t, TextRange(t.length)) }

    fun toggleTag(tag: String) {
        when {
            tag in typedTags -> setText(Tags.remove(value.text, tag) + " ")
            tag in chosenTags -> chosenTags = chosenTags - tag
            else -> chosenTags = chosenTags + tag
        }
        focus.requestFocus()
    }

    /** Finishes the #word being typed with [tag]. */
    fun completeTag(tag: String) {
        val (range, _) = typing ?: return
        val (out, cursor) = Tags.complete(value.text, range, tag)
        value = TextFieldValue(out, TextRange(cursor))
        focus.requestFocus()
    }

    LaunchedEffect(Unit) {
        // Opened with a link on the clipboard: start in Link mode with it filled in, selected so typing replaces it.
        val clip = runCatching { Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as String }.getOrNull()?.trim()
        if (clip != null && isBareUrl(clip)) { link = true; value = TextFieldValue(clip, TextRange(0, clip.length)) }
        delay(50); focus.requestFocus()
    }
    // A pasted link switches to Link mode by itself.
    LaunchedEffect(text) { if (isBareUrl(text)) link = true }
    // Link preview, once typing stops.
    LaunchedEffect(text, link) {
        if (!link || !isBareUrl(text) || text == previewFor) return@LaunchedEffect
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
                if (!isBareUrl(text)) { busy = false; state.notify("Not a link", "Paste a link starting with http"); return@launch }
                val vault = state.vault!!
                val got = preview?.takeIf { previewFor == text }
                close()
                val tags = chosenTags
                val full = state.io("Couldn't save the link") { ReadLater.save(vault, text, null, got, tags) }
                if (full != null) state.notify("Saved to Read later", (if (full) (got?.title ?: text) else "Saved the link only (couldn't fetch the page)") +
                    tags.joinToString("") { " · ${Tags.label(it)}" })
            } else {
                val line = Tags.withTags(body, chosenTags) + (minutes?.let { " ⏱ ${it}m" } ?: "") + (repeat?.let { " 🔁 $it" } ?: "")
                val ok = state.io("Couldn't add the task") { state.writer()!!.addTask(line, due?.toString()) } != null
                close()
                if (ok) state.notify("Added to Quick Task", Tags.strip(body).ifBlank { body } + onTags.joinToString("") { " · ${Tags.label(it)}" } + (due?.let { " · due ${dueLabel(it)}" } ?: "") + (repeat?.let { " · 🔁 $it" } ?: ""))
            }
            state.changed()
        }
    }

    val shape = RoundedCornerShape(18.dp)
    Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
        Column(
            Modifier.width(628.dp).clip(shape).background(Popup).border(1.dp, Outline, shape).padding(18.dp)
        ) {
            if (state.vault == null) {
                Text("Pick your vault in Flight Deck first.", color = Muted)
                return@Column
            }
            Pill(listOf("✓ Task", "🔗 Link", "🎙 Jarvis"), if (jarvisMode) 2 else if (link) 1 else 0, Modifier.width(330.dp).padding(bottom = 12.dp)) {
                jarvisMode = it == 2; link = it == 1; if (!jarvisMode) focus.requestFocus()
            }
            if (jarvisMode) { QuickJarvis(state); return@Column }
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
                    modifier = Modifier.weight(1f).focusRequester(focus)
                        .onPreviewKeyEvent { e ->
                            // Alt+W and Alt+P toggle Work and Personal.
                            if (e.type != KeyEventType.KeyDown || !e.isAltPressed) return@onPreviewKeyEvent false
                            when (e.key) {
                                Key.W -> { toggleTag(Tags.WORK); true }
                                Key.P -> { toggleTag(Tags.PERSONAL); true }
                                else -> false
                            }
                        }
                        .onKeys(
                            enter = ::save,
                            // Tab finishes a #tag being typed; otherwise it switches Task and Link.
                            tab = { if (suggestions.isNotEmpty()) completeTag(suggestions.first()) else link = !link }
                        ),
                    decorationBox = { inner ->
                        Box { if (value.text.isEmpty()) Text(if (link) "Paste a link…" else "Add a task… (end with a date like “fri”)", color = Muted, fontSize = 17.sp); inner() }
                    }
                )
                if (!link && typed != null && picked == null) Text("📅 " + dayChip(typed), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextMain,
                    modifier = Modifier.clip(RoundedCornerShape(9.dp)).background(Surface2).padding(horizontal = 9.dp, vertical = 4.dp))
            }
            if (suggestions.isNotEmpty()) Row(
                Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                suggestions.forEachIndexed { i, tag ->
                    TagChip(tag, selected = i == 0) { completeTag(tag) }
                    if (i == 0) Kbd("Tab")
                }
            }
            if (!link) TaskOptions(
                due, picked, minutes, repeat, side,
                onDay = { d -> picked = if (picked == d) null else d; if (side == Side.DATE) side = null; focus.requestFocus() },
                onPickDate = { side = if (side == Side.DATE) null else Side.DATE },
                onMinutes = { m -> minutes = if (minutes == m) null else m; focus.requestFocus() },
                onRepeat = { r -> repeat = r; if (side == Side.REPEAT) side = null; focus.requestFocus() },
                onCustom = { side = if (side == Side.REPEAT) null else Side.REPEAT },
            )
            Column(Modifier.padding(top = 12.dp)) {
                GroupLabel("Tag")
                Box(Modifier.padding(top = 6.dp)) {
                    TagRow(pinned, onTags, ::toggleTag) {
                        // ＋ Tag starts a #word in the box; the suggestions above take it from there.
                        if (link) state.say("New tags are pinned from the phone's ＋ Tag")
                        else setText(value.text.trimEnd().let { if (it.isEmpty()) "#" else "$it #" })
                        focus.requestFocus()
                    }
                }
            }
            if (link && isBareUrl(text)) Row(
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
                    if (link) "Saves the page as a full article note" else "Saves to ⚡ Quick Task · Tab switches to Link · Alt W / Alt P tag it",
                    fontSize = 12.sp, color = Muted, modifier = Modifier.weight(1f)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Kbd("↵"); Text(if (busy) "saving…" else "save", fontSize = 12.sp, color = Muted)
                    Box(Modifier.size(6.dp)); Kbd("Esc"); Text("close", fontSize = 12.sp, color = Muted)
                }
            }
        }
        if (!link && side != null) Column(
            Modifier.width(400.dp).clip(shape).background(Popup).border(1.dp, Outline, shape).padding(18.dp)
        ) {
            if (side == Side.REPEAT) {
                Text("Repeat", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = TextMain, modifier = Modifier.padding(bottom = 12.dp))
                RepeatForm(repeat, onUse = { repeat = it; side = null; focus.requestFocus() }, onClear = { repeat = null; side = null; focus.requestFocus() })
            } else {
                InlineDatePicker(due) { d -> picked = d; side = null; focus.requestFocus() }
            }
        }
    }
}

private enum class Side { REPEAT, DATE }

/** Task mode: Today / Tomorrow / Pick date, the time estimate, and the phone's Repeat row. */
@Composable
private fun TaskOptions(
    due: LocalDate?, picked: LocalDate?, minutes: Int?, repeat: String?, side: Side?,
    onDay: (LocalDate) -> Unit, onPickDate: () -> Unit, onMinutes: (Int) -> Unit, onRepeat: (String?) -> Unit, onCustom: () -> Unit,
) {
    val today = LocalDate.now()
    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Chip("Today", picked == today, small = true) { onDay(today) }
        Chip("Tomorrow", picked == today.plusDays(1), small = true) { onDay(today.plusDays(1)) }
        val other = picked != null && picked != today && picked != today.plusDays(1)
        Chip(if (other) dayChip(picked!!) else "Pick date", other || side == Side.DATE, small = true, onClick = onPickDate)
        Box(Modifier.width(8.dp))
        TaskRules.ESTIMATE_CHOICES.forEach { m -> Chip(TaskRules.minutesLabel(m), minutes == m, small = true) { onMinutes(m) } }
    }
    val spec = repeat?.let { TaskRules.parseRepeat(it) }
    fun simple(unit: String) = spec != null && spec.unit == unit && spec.every == 1 && !spec.weekdays && !spec.whenDone && spec.weekday == null && spec.monthDay == null
    val daily = repeat == "every day"
    val weekly = simple("week")
    val monthly = simple("month")
    val custom = repeat != null && !daily && !weekly && !monthly
    Column(Modifier.padding(top = 12.dp)) {
        GroupLabel("Repeat")
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(if (daily) "↻ Daily" else "Daily", daily, small = true) { onRepeat(if (daily) null else "every day") }
            Chip(if (weekly) "↻ Weekly" else "Weekly", weekly, small = true) { onRepeat(if (weekly) null else "every week") }
            Chip(if (monthly) "↻ Monthly" else "Monthly", monthly, small = true) { onRepeat(if (monthly) null else "every month") }
            Chip(if (custom) "↻ $repeat" else "Custom…", custom || side == Side.REPEAT, small = true, onClick = onCustom)
        }
        if (repeat != null && due != null) Text(
            "Next one appears when you tick it · first due ${dayChip(due)}", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 6.dp)
        )
    }
}

/** A calendar inside the popup: a separate date dialog would take focus and close quick capture. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InlineDatePicker(initial: LocalDate?, onPick: (LocalDate?) -> Unit) {
    val picker = rememberDatePickerState(
        initialSelectedDateMillis = (initial ?: LocalDate.now()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    )
    DatePicker(picker, title = null, headline = null, showModeToggle = false,
        colors = DatePickerDefaults.colors(containerColor = Popup))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
        Chip("No date", false, small = true) { onPick(null) }
        PrimaryButton("Done") { onPick(picker.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }) }
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
