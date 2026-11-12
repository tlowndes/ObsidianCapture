package uk.tomlowndes.capture.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.tomlowndes.capture.Jarvis
import uk.tomlowndes.capture.SortResult
import uk.tomlowndes.capture.Sorting
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val HM = DateTimeFormatter.ofPattern("HH:mm")
private fun clock(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(HM)

/** What one Jarvis exchange needs: the chat so far and the sorted results by message time (kept until the app closes). */
class JarvisSession(val state: AppState) {
    var worker by mutableStateOf("jarvis")
    var chat by mutableStateOf(DesktopJarvis.chats(state.prefs, worker).firstOrNull() ?: Jarvis.Chat.new())
    var busy by mutableStateOf(false)
    var done by mutableStateOf<List<Jarvis.Done>>(emptyList())
    val sorted = mutableStateMapOf<Long, SortResult>()
    val mic = MicRecorder()

    fun pick(w: String) {
        worker = w; state.prefs.jarvisWorker = w
        chat = DesktopJarvis.chats(state.prefs, w).firstOrNull() ?: Jarvis.Chat.new()
    }

    private fun add(m: Jarvis.ChatMsg) {
        chat = chat.copy(messages = chat.messages + m)
        DesktopJarvis.save(state.prefs, worker, chat)
    }

    private suspend fun answer(work: () -> Jarvis.Reply, mine: (Jarvis.Reply) -> Jarvis.ChatMsg?) {
        busy = true
        val r = withContext(Dispatchers.IO) { runCatching(work) }
        busy = false
        r.onFailure { e ->
            val who = state.prefs.assistantName
            val msg = when ((e as? Jarvis.HttpError)?.code) { 401, 403 -> "$who refused the token. Check Settings."; else -> "Can't reach $who. Is Tailscale on?" }
            add(Jarvis.ChatMsg(false, msg, System.currentTimeMillis(), via = "error"))
        }.onSuccess { reply ->
            mine(reply)?.let(::add)
            val at = System.currentTimeMillis()
            add(Jarvis.ChatMsg(false, reply.reply, at, via = state.prefs.assistantName))
            reply.sorted?.takeIf { !it.isEmpty }?.let { sorted[at] = it }
            if (reply.done.isNotEmpty()) done = done + reply.done
            if (state.prefs.jarvisSpeak && reply.reply.isNotBlank()) DesktopJarvis.speak(reply.reply, state.prefs.jarvisSpeed)
        }
    }

    suspend fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty() || busy) return
        add(Jarvis.ChatMsg(true, t, System.currentTimeMillis()))
        val w = worker; val id = chat.id
        answer({ DesktopJarvis.ask(state.prefs, w, t, id) }) { null }
    }

    fun startTalking(): Boolean {
        if (busy) return false
        DesktopJarvis.stopSpeaking()
        return mic.start().also { if (!it) state.say("Couldn't open the microphone") }
    }

    suspend fun stopTalking() {
        val got = withContext(Dispatchers.IO) { mic.stop(File(state.prefs.cacheDir, "jarvis/last.wav")) } ?: return
        val (wav, secs) = got
        val w = worker; val id = chat.id
        answer({ DesktopJarvis.askVoice(state.prefs, w, wav, secs, id) }) { r ->
            Jarvis.ChatMsg(true, r.transcript.ifBlank { "(voice note)" }, System.currentTimeMillis(), spoken = true)
        }
    }

    suspend fun saveSorted(at: Long, r: SortResult) {
        val w = state.writer() ?: return
        val ideas = state.ideaStore() ?: return
        val ok = state.io("Couldn't save it") { Sorting.save(r, w, ideas) } != null
        if (ok) { sorted.remove(at); state.say("Saved to the vault"); state.changed() }
    }
}

/** Jarvis pane: workers and their chats on the left, the chat on the right. Enter sends; hold Space in an empty box to talk. */
@Composable
fun JarvisPane(state: AppState) {
    val s = state.jarvis
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val list = rememberLazyListState()
    var history by remember { mutableStateOf<List<Jarvis.Chat>>(emptyList()) }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(s.worker, s.chat.id, s.chat.messages.size) { history = withContext(Dispatchers.IO) { DesktopJarvis.chats(state.prefs, s.worker) } }
    LaunchedEffect(s.chat.messages.size, s.busy) { val n = s.chat.messages.size + (if (s.busy) 1 else 0) + s.done.size; if (n > 0) list.animateScrollToItem(n - 1) }
    LaunchedEffect(Unit) { delay(80); runCatching { focus.requestFocus() } }
    LaunchedEffect(Unit) { while (true) { withContext(Dispatchers.IO) { DesktopJarvis.newDone(state.prefs) }.let { if (it.isNotEmpty()) s.done = s.done + it }; delay(120_000) } }
    LaunchedEffect(s.mic.recording) { while (s.mic.recording) { tick++; delay(250) } }

    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(230.dp).fillMaxHeight().padding(horizontal = 12.dp, vertical = 22.dp)) {
            Text("CHATS", fontSize = 11.sp, letterSpacing = 1.4.sp, fontFamily = FontFamily.Monospace,
                color = Primary, modifier = Modifier.padding(start = 10.dp, bottom = 6.dp))
            history.take(10).forEach { c ->
                val on = c.id == s.chat.id
                Text(DesktopJarvis.title(c), fontSize = 12.5.sp, color = if (on) TextMain else Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (on) Surface2 else Color.Transparent)
                        .hand().clickable { s.chat = c }.padding(horizontal = 10.dp, vertical = 6.dp))
            }
            if (history.isEmpty()) Text("No chats yet", fontSize = 12.sp, color = Faint, modifier = Modifier.padding(start = 10.dp))
        }
        Box(Modifier.fillMaxHeight().width(1.dp).background(Outline))
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                H1(state.prefs.assistantName, 22)
                Spacer(Modifier.weight(1f))
                val ready = DesktopJarvis.ready(state.prefs)
                Text(if (!ready) "not set up" else if (Jarvis.isDemo(state.prefs.jarvisAddress)) "demo" else "box",
                    fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, color = if (ready) TaskGreen else Warn, modifier = Modifier.padding(end = 12.dp))
                if (s.chat.messages.isNotEmpty()) Chip("🧾 Print", small = true, modifier = Modifier.padding(end = 8.dp)) {
                    val recent = s.chat.messages.takeLast(12)
                    state.printSummaryOf = PrintSource.Chat(recent.filter { it.mine }.joinToString("\n") { it.text }, recent.filter { !it.mine }.joinToString("\n") { it.text })
                }
                Chip("＋ New chat", small = true) { s.chat = Jarvis.Chat.new() }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Outline))
            if (!DesktopJarvis.ready(state.prefs)) {
                Column(Modifier.padding(28.dp)) {
                    Sub("Add the server address and token in Settings, or type demo as the address to try it.")
                    PrimaryButton("Open Settings", Modifier.padding(top = 14.dp)) { state.settingsPage = SettingsPage.JARVIS; state.tab = Tab.SETTINGS }
                }
                return@Column
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 28.dp), state = list, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Spacer(Modifier.height(6.dp)) }
                items(s.chat.messages, key = { "${it.at}-${it.mine}-${it.text.hashCode()}" }) { m -> Bubble(state, s, m) }
                if (s.busy) item { Text("${state.prefs.assistantName} is thinking…", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Muted) }
                items(s.done, key = { "done" + it.id }) { d ->
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text("✓ ${d.title}" + (d.detail.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""), fontSize = 11.5.sp, fontFamily = FontFamily.Monospace,
                            color = TaskGreen, modifier = Modifier.clip(CircleShape).background(TaskTint).padding(horizontal = 12.dp, vertical = 4.dp))
                    }
                }
                item { Spacer(Modifier.height(6.dp)) }
            }
            val shape = RoundedCornerShape(16.dp)
            Row(
                Modifier.fillMaxWidth().padding(start = 28.dp, end = 28.dp, bottom = 20.dp).clip(shape).background(Surface1)
                    .border(1.dp, if (s.mic.recording) Warn else Outline, shape).padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (s.mic.recording) {
                    tick.let { }
                    Text("● ${s.mic.seconds / 60}:${"%02d".format(s.mic.seconds % 60)}", color = Warn, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    Box(Modifier.weight(1f).height(6.dp).clip(CircleShape).background(Surface2)) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(s.mic.level.coerceIn(0.03f, 1f)).clip(CircleShape).background(Warn))
                    }
                    Text("let go to send · Esc cancel", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Muted)
                } else {
                    BasicTextField(
                        text, { text = it }, cursorBrush = SolidColor(Primary), maxLines = 6,
                        textStyle = TextStyle(color = TextMain, fontSize = 14.sp, lineHeight = 20.sp),
                        modifier = Modifier.weight(1f).focusRequester(focus).onPreviewKeyEvent { e ->
                            when {
                                e.key == Key.Enter && e.type == KeyEventType.KeyDown && !e.isShiftPressed -> {
                                    val t = text; text = ""; scope.launch { s.send(t) }; true
                                }
                                e.key == Key.Spacebar && text.isEmpty() && state.prefs.jarvisHoldSpace && e.type == KeyEventType.KeyDown -> {
                                    if (!s.mic.recording) s.startTalking(); true
                                }
                                else -> false
                            }
                        },
                        decorationBox = { inner -> Box { if (text.isEmpty()) Text("Ask ${state.prefs.assistantName}…", color = Faint, fontSize = 14.sp); inner() } }
                    )
                    Kbd("Enter")
                    if (state.prefs.jarvisHoldSpace) Kbd("hold Space to talk")
                }
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(if (s.mic.recording) Warn else PrimaryTint)
                        .pointerInput(Unit) {
                            detectTapGestures(onPress = {
                                if (s.startTalking()) { tryAwaitRelease(); scope.launch { s.stopTalking() } }
                            })
                        },
                    contentAlignment = Alignment.Center
                ) { Text("🎙", fontSize = 16.sp) }
            }
        }
    }
}

/** Space let go (or Esc) while recording from the keyboard. Handled at window level so focus doesn't matter. */
fun jarvisKeyUp(state: AppState, key: Key): Boolean {
    val s = state.jarvisOrNull ?: return false
    if (!s.mic.recording) return false
    when (key) {
        Key.Spacebar -> { state.scope.launch { s.stopTalking() }; return true }
        Key.Escape -> { s.mic.cancel(); return true }
    }
    return false
}

@Composable
private fun Bubble(state: AppState, s: JarvisSession, m: Jarvis.ChatMsg) {
    val scope = rememberCoroutineScope()
    if (m.mine) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Column(Modifier.widthIn(max = 560.dp).clip(RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)).background(Primary).padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(m.text, fontSize = 14.sp, color = OnPrimary, lineHeight = 20.sp)
                Text(clock(m.at) + if (m.spoken) " · 🎙" else " · typed", fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, color = OnPrimary.copy(alpha = .7f), modifier = Modifier.padding(top = 4.dp))
            }
        }
        return
    }
    val sorted = s.sorted[m.at]
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        val shape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp)
        Column(Modifier.widthIn(max = 640.dp).clip(shape).background(Surface1).border(1.dp, if (m.via == "error") OverdueLine else Outline, shape).padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${m.via ?: state.prefs.assistantName} · ${clock(m.at)}" + if (sorted != null) " · sorted" else "", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Muted)
                if (m.via != "error") Text("▶", fontSize = 11.sp, color = Primary, modifier = Modifier.padding(start = 10.dp).clip(CircleShape).background(PrimaryTint)
                    .hand().clickable { DesktopJarvis.speak(m.text, state.prefs.jarvisSpeed) }.padding(horizontal = 9.dp, vertical = 1.dp))
            }
            Text(m.text, fontSize = 14.sp, color = TextMain, lineHeight = 21.sp, modifier = Modifier.padding(top = 5.dp))
            if (sorted != null) SortedCard(sorted,
                onSave = { scope.launch { s.saveSorted(m.at, sorted) } },
                onNoteOnly = { scope.launch { s.saveSorted(m.at, sorted.copy(tasks = emptyList(), ideas = emptyList())) } })
        }
    }
}

@Composable
private fun SortedCard(r: SortResult, onSave: () -> Unit, onNoteOnly: () -> Unit) {
    Column(Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Surface2)) {
        @Composable
        fun Row1(kind: String, content: @Composable () -> Unit) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(kind, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, color = Muted, modifier = Modifier.width(48.dp))
                content()
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Outline))
        }
        r.tasks.forEach { t ->
            Row1("TASK") {
                Box(Modifier.size(15.dp).clip(RoundedCornerShape(5.dp)).border(1.5.dp, if (t.tag == "#work") WorkEdge else PersonalEdge, RoundedCornerShape(5.dp)))
                Text(t.text, fontSize = 13.sp, color = TextMain, modifier = Modifier.weight(1f).padding(start = 10.dp))
                t.tag?.let { Text(it, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = if (it == "#work") WorkEdge else TaskGreen, modifier = Modifier.padding(start = 8.dp)) }
                t.due?.let { Text(dueLabel(it), fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Muted, modifier = Modifier.padding(start = 8.dp)) }
            }
        }
        r.ideas.forEach { i -> Row1("IDEA") { Text("💡 $i", fontSize = 13.sp, color = TextMain) } }
        r.note?.let { n -> Row1("NOTE") { Text(r.noteTitle ?: n.lines().first().take(80), fontSize = 13.sp, color = TextMain, maxLines = 1, overflow = TextOverflow.Ellipsis) } }
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton("Save all", onClick = onSave)
            if (r.note != null && (r.tasks.isNotEmpty() || r.ideas.isNotEmpty())) Chip("Just the note", small = true, onClick = onNoteOnly)
        }
    }
}

/** The quick capture popup's Jarvis tab: type or hold Space, Enter sends to the last worker; the reply shows here. */
@Composable
fun QuickJarvis(state: AppState) {
    val s = state.jarvis
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var sentAt by remember { mutableStateOf(Long.MAX_VALUE) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(60); runCatching { focus.requestFocus() } }
    if (!DesktopJarvis.ready(state.prefs)) { Sub("Set up the assistant in Settings first."); return }
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(Surface1).border(1.5.dp, if (s.mic.recording) Warn else Primary, shape).padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (s.mic.recording) {
            Text("● recording · let go of Space to send", color = Warn, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Box(Modifier.width(120.dp).height(6.dp).clip(CircleShape).background(Surface2)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(s.mic.level.coerceIn(0.03f, 1f)).clip(CircleShape).background(Warn))
            }
        } else BasicTextField(
            text, { text = it }, singleLine = true, cursorBrush = SolidColor(Primary),
            textStyle = TextStyle(color = TextMain, fontSize = 17.sp),
            modifier = Modifier.weight(1f).focusRequester(focus).onPreviewKeyEvent { e ->
                when {
                    e.key == Key.Enter && e.type == KeyEventType.KeyDown -> {
                        val t = text; text = ""; sentAt = System.currentTimeMillis(); scope.launch { s.send(t) }; true
                    }
                    e.key == Key.Spacebar && text.isEmpty() && state.prefs.jarvisHoldSpace && e.type == KeyEventType.KeyDown -> {
                        if (!s.mic.recording && s.startTalking()) sentAt = System.currentTimeMillis(); true
                    }
                    else -> false
                }
            },
            decorationBox = { inner -> Box { if (text.isEmpty()) Text("Ask ${state.prefs.assistantName}…", color = Muted, fontSize = 17.sp); inner() } }
        )
    }
    val reply = s.chat.messages.lastOrNull { !it.mine && it.at >= sentAt }
    if (s.busy) Text("${state.prefs.assistantName} is thinking…", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Muted, modifier = Modifier.padding(top = 12.dp))
    reply?.let { m ->
        Column(Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Surface1).border(1.dp, Outline, RoundedCornerShape(14.dp)).padding(14.dp)) {
            Text("${m.via ?: state.prefs.assistantName} · ${clock(m.at)}", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Muted)
            Text(m.text, fontSize = 14.sp, color = TextMain, lineHeight = 20.sp, maxLines = 6, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            if (s.sorted[m.at] != null) TextAction("Open the assistant to save what it sorted ›") { state.tab = Tab.JARVIS; state.windowVisible = true; state.quickVisible = false }
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Enter send · hold Space talk · Esc close", fontSize = 12.sp, color = Muted)
    }
}
