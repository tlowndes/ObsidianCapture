package uk.tomlowndes.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.zIndex
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val RecRed = Color(0xFFFF8A80)
private val RecRing1 = Color(0xFF3A1D1D)
private val RecRing2 = Color(0xFF241414)
private val MicRing = Color(0xFF1B2440)

private enum class Phase { IDLE, RECORDING, SENDING }

/** A recording (locked or held) stops and sends itself after ten minutes. */
private const val MAX_RECORDING_MS = 10 * 60_000L

private data class ApiOutcome(
    val text: String, val sorted: SortResult?, val handoffs: List<PhoneBrain.Handoff>,
    val inTok: Long, val outTok: Long, val model: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
/**
 * Jarvis, as Capture's third mode: hold to talk or type, the reply read out, and [found] (what a ramble was sorted
 * into) waiting behind a Check and save line. [header] sits under the title (Capture's mode switch).
 */
fun JarvisScreen(
    prefs: AppPrefs,
    snack: SnackbarHostState,
    onOpenSettings: () -> Unit,
    title: String = "",
    header: @Composable () -> Unit = {},
    found: SortResult? = null,
    onFound: (SortResult) -> Unit = {},
    onCheck: () -> Unit = {},
    onPrintToday: () -> Unit = {},
    onQueued: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val secrets = remember { SecretStore(ctx) }
    val recorder = remember { Recorder(ctx) }
    val address = remember { prefs.jarvisAddress }
    val token = remember { secrets.jarvisToken.orEmpty() }
    val demo = Jarvis.isDemo(address)
    val boxReady = address.isNotBlank() && token.isNotBlank()
    val who = prefs.assistantName
    /** One assistant. Claude from the phone answers everything when that switch is on. */
    val claudeKey = remember { secrets.claudeKey.orEmpty() }
    val brainOn = !demo && prefs.phoneBrain && claudeKey.isNotBlank()
    fun usesBrain(w: Jarvis.Worker) = brainOn
    /** Gemini Nano answers quick asks only while Claude from the phone is off. */
    val nanoOn = !demo && prefs.nanoBrain && !brainOn
    var nanoState by remember { mutableStateOf(PhoneNano.state) }
    LaunchedEffect(nanoOn) { if (nanoOn) nanoState = withContext(Dispatchers.IO) { PhoneNano.ensure() } }
    fun usesNano(w: Jarvis.Worker) = nanoOn && nanoState == PhoneNano.State.READY
    val speechIn = remember { PhoneSpeech(ctx) }
    val phoneSpeech = remember { prefs.phoneSpeech && PhoneSpeech.available(ctx) }
    val phoneVoice = remember { prefs.phoneVoice }
    /** Which message the phone voice is reading out (its time stamp), if any. */
    var speakingId by remember { mutableStateOf<String?>(null) }
    val voiceOut = remember { PhoneVoiceOut(ctx) { id -> android.os.Handler(android.os.Looper.getMainLooper()).post { speakingId = id } } }
    /** The phone brain's answer so far, while it streams in. */
    var streaming by remember { mutableStateOf<String?>(null) }
    /** Who is writing [streaming]: the Claude key brain (null) or Nano. */
    var streamingVia by remember { mutableStateOf<String?>(null) }
    /** This hold-to-talk uses Android's speech-to-text rather than a recording. */
    var listening by remember { mutableStateOf(false) }

    val worker = Jarvis.worker("jarvis")
    val ready = demo || usesBrain(worker) || usesNano(worker) || boxReady
    /** One conversation. Older chats for the retired workers stay stored and unused. */
    var chat by remember { mutableStateOf(prefs.jarvisChat(worker.id)) }
    /** The latest question and answer in this chat, for 🧾 Print summary (print-summary-v1). */
    val last = chat.messages.lastOrNull { !it.mine && it.via != "box" }?.let { a ->
        Triple(chat.messages.takeWhile { it !== a }.lastOrNull { it.mine }?.text.orEmpty(), a.text, a.at)
    }
    var phase by remember { mutableStateOf(Phase.IDLE) }
    var cancelling by remember { mutableStateOf(false) }
    /** Thumb is over the 🔒 while holding; [locked] once let go there (records hands-free until Send). */
    var locking by remember { mutableStateOf(false) }
    var locked by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val view = LocalView.current
    var startedAt by remember { mutableLongStateOf(0L) }
    var elapsed by remember { mutableLongStateOf(0L) }
    val levels = remember { mutableStateListOf<Float>() }
    var summarising by remember { mutableStateOf(false) }
    /** A note found for key findings, waiting on the Long / Short picker. */
    var findingsAsk by remember { mutableStateOf<FindingsAsk?>(null) }
    /** The recording that failed to send, kept for Retry. */
    var pending by remember { mutableStateOf<File?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf<Jarvis.Done?>(null) }
    var openDone by remember { mutableStateOf<Jarvis.Done?>(null) }
    var typed by remember { mutableStateOf(prefs.draft("capture")) }
    /** Whether the message on its way (or that failed) was typed, for the error wording. */
    var wasTyped by remember { mutableStateOf(false) }
    /** Typed text that failed to send, kept for Retry. */
    var pendingText by remember { mutableStateOf<String?>(null) }
    /** Typed text on its way, shown as a bubble until the answer comes. */
    var sendingText by remember { mutableStateOf<String?>(null) }

    // Reply audio: one player, for whichever answer's ▶ was tapped last.
    val player = remember { arrayOfNulls<MediaPlayer>(1) }
    var loaded by remember { mutableStateOf<String?>(null) }
    var playing by remember { mutableStateOf(false) }
    var pos by remember { mutableFloatStateOf(0f) }
    var durMs by remember { mutableLongStateOf(0L) }
    var speed by remember { mutableFloatStateOf(prefs.jarvisSpeed) }
    DisposableEffect(Unit) {
        onDispose {
            player[0]?.release(); player[0] = null
            recorder.cancel()
            speechIn.cancel()
            voiceOut.shutdown()
        }
    }
    LaunchedEffect(playing) {
        while (playing) {
            player[0]?.let { p -> runCatching { if (p.duration > 0) pos = p.currentPosition.toFloat() / p.duration } }
            delay(150)
        }
    }

    fun setChat(c: Jarvis.Chat) { chat = c; prefs.setJarvisChat(worker.id, c) }
    fun audioFile(name: String) = File(ctx.cacheDir, name)

    /** Loads and starts one answer's audio. */
    fun play(name: String) {
        player[0]?.release(); player[0] = null; playing = false; loaded = null
        val f = audioFile(name)
        if (!f.exists()) { scope.launch { snack.showSnackbar("That reply's audio has been cleared") }; return }
        val p = MediaPlayer()
        runCatching {
            p.setDataSource(f.absolutePath)
            p.prepare()
            durMs = p.duration.toLong()
            p.setOnCompletionListener { playing = false; pos = 1f }
            p.start(); setSpeed(p, speed)
            player[0] = p; loaded = name; playing = true; pos = 0f
        }.onFailure { p.release() }
    }

    fun toggle(name: String) {
        val p = player[0]
        if (p == null || loaded != name) { play(name); return }
        if (playing) { p.pause(); playing = false }
        else { if (pos >= 1f) p.seekTo(0); p.start(); setSpeed(p, speed); playing = true }
    }

    fun pauseAudio() { runCatching { if (playing) player[0]?.pause() }; playing = false; voiceOut.stop() }
    /** The chat's mute button: the same switch as Settings › Play replies aloud. Muting also stops a reply mid-sentence. */
    var speakOn by remember { mutableStateOf(prefs.jarvisSpeak) }
    fun toggleMute() { speakOn = !speakOn; prefs.jarvisSpeak = speakOn; if (!speakOn) { pauseAudio(); speakingId = null } }

    /** Reads an answer out in the phone's voice; tapping the one being read stops it. */
    fun sayAloud(m: Jarvis.ChatMsg) {
        val id = m.at.toString()
        if (speakingId == id) { voiceOut.stop(); speakingId = null; return }
        runCatching { if (playing) player[0]?.pause() }; playing = false
        voiceOut.speak(id, m.text, speed)
    }

    fun newChat() {
        pauseAudio(); player[0]?.release(); player[0] = null; loaded = null
        chat.messages.mapNotNull { it.audio }.forEach { audioFile(it).delete() }
        setChat(Jarvis.Chat.new()); error = null
    }

    fun markDone(list: List<Jarvis.Done>) {
        val seen = prefs.jarvisSeenDone
        list.firstOrNull { it.id !in seen }?.let { d -> done = d; prefs.jarvisSeenDone = seen + d.id }
    }

    fun failed(e: Throwable): String = PhoneBrain.failure(who, e, viaServer = true)

    /** Adds what was said and the answer to [w]'s chat (it may not be the one on screen any more). */
    fun answered(r: Jarvis.Reply, audio: File?, w: Jarvis.Worker, said: String, spoken: Boolean, via: String? = null, extra: List<Jarvis.ChatMsg> = emptyList(), route: String? = null) {
        val now = System.currentTimeMillis()
        // A route line ("sent to Claude on the box") sits between what was said and the box's answer.
        val replyAt = if (route != null) now + 2 else now + 1
        val add = listOfNotNull(
            Jarvis.ChatMsg(true, said, now, spoken = spoken).takeIf { said.isNotBlank() },
            route?.let { Jarvis.ChatMsg(false, it, now + 1, via = "box") },
            Jarvis.ChatMsg(false, r.reply, replyAt, audio = audio?.name, via = via).takeIf { r.reply.isNotBlank() },
        ) + extra
        val onScreen = w.id == worker.id
        val base = if (onScreen) chat else prefs.jarvisChat(w.id)
        val all = base.messages + add
        all.dropLast(Jarvis.CHAT_KEEP).mapNotNull { it.audio }.forEach { audioFile(it).delete() }
        val c = base.copy(messages = all.takeLast(Jarvis.CHAT_KEEP))
        if (onScreen) setChat(c) else prefs.setJarvisChat(w.id, c)
        markDone(r.done)
        r.sorted?.let(onFound)
        if (demo && w.id == "research") markDone(listOf(Jarvis.Done("demo-$now", "Research finished (demo)", "This is what a finished background job looks like.")))
        // Only spoken messages are answered out loud; a typed one gets its ▶ to play by hand.
        if (audio != null && onScreen && spoken && prefs.jarvisSpeak) play(audio.name)
        else if (audio == null && phoneVoice && onScreen && spoken && prefs.jarvisSpeak && r.reply.isNotBlank()) voiceOut.speak(replyAt.toString(), r.reply, speed)
    }

    /** Server audio, unless the phone reads replies out itself (then there's nothing to wait for). */
    fun fetch(r: Jarvis.Reply): File? = if (phoneVoice) null else r.audioUrl?.let { u ->
        File(ctx.cacheDir, "jarvis-reply-${System.currentTimeMillis()}.ogg").also { out -> Jarvis.fetchAudio(address, token, u, out) }
    }

    fun send(f: File) {
        if (brainOn && !demo) {
            phase = Phase.IDLE
            error = "Type to $who. A recording needs the server, and Claude from the phone is on."
            f.delete()
            return
        }
        phase = Phase.SENDING; error = null; wasTyped = false
        val w = worker
        val id = chat.id
        val seconds = elapsed / 1000.0
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    // Demo plays your own recording back, so it's kept as the "reply" audio.
                    if (demo) Jarvis.demoReply(w.id, seconds) to f
                    else Jarvis.send(address, token, f, w.id, prefs.jarvisVoice, chat = id).let { r -> r to fetch(r) }
                }
            }
            phase = Phase.IDLE
            result.onSuccess { (r, audio) ->
                if (!demo) f.delete()
                pending = null
                answered(r, audio, w, r.transcript, spoken = true)
            }.onFailure { e ->
                pending = f
                if (!demo) PhoneOutbox.keepVoice(ctx, w.id, id, f)
                if (!demo) onQueued()
                error = failed(e)
            }
        }
    }

    fun storeSucceed(worker: String, text: String, chatId: String, spoken: Boolean) {
        Outbox(java.io.File(ctx.filesDir, "outbox.json")).succeed(Outbox.id(Outbox.JARVIS, Outbox.jarvisPayload(worker, text, chatId, spoken)))
    }

    fun claudeError(e: Throwable, seconds: Long): String = PhoneBrain.failure(who, e, viaServer = false, seconds = seconds)

    /** One assistant on Claude. A long ramble is sorted; everything else is the same chat, which can look things up. */
    fun askBrain(t: String, spoken: Boolean, w: Jarvis.Worker) {
        val flight = PhoneBrain.flightKey(w.id, t)
        if (!PhoneBrain.beginFlight(flight)) {
            phase = Phase.IDLE; sendingText = null
            error = "That's already being sent, so it won't be sent again."
            return
        }
        val chatId = chat.id
        val turns = chat.messages.filter { it.via != "box" }.map { PhoneBrain.Turn(it.mine, it.text) } + PhoneBrain.Turn(true, t)
        val model = PhoneBrain.model(prefs.brainClever)
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        val seconds = if (PhoneBrain.worthSorting(t)) PhoneBrain.CHAT_TIMEOUT_S else PhoneBrain.CHAT_TIMEOUT_S
        streaming = ""; streamingVia = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val today = java.time.LocalDate.now()
                    if (PhoneBrain.worthSorting(t)) PhoneBrain.sortOrAnswer(claudeKey, t, today, who).let { ApiOutcome(it.reply, it.sorted, emptyList(), it.inTok, it.outTok, PhoneBrain.FAST) }
                    else PhoneBrain.chat(claudeKey, model, turns, today, listOf("#work", "#personal"), who) { so -> main.post { streaming = so } }
                        .let { a -> ApiOutcome(a.text.ifBlank { "Done." }, PhoneBrain.sortedOf(a.tasks, a.notes), emptyList(), a.inputTokens, a.outputTokens, a.model) }
                }
            }
            PhoneBrain.endFlight(flight)
            phase = Phase.IDLE
            sendingText = null
            streaming = null
            result.onSuccess { a ->
                pendingText = null
                storeSucceed(w.id, t, chatId, spoken)
                prefs.spend("Jarvis", PhoneBrain.costUsd(a.model, a.inTok, a.outTok))
                answered(Jarvis.Reply(t, a.text, null, sorted = a.sorted), null, w, t, spoken)
            }.onFailure { e ->
                pendingText = t
                PhoneOutbox.keepJarvis(ctx, w.id, t, chatId, spoken)
                onQueued()
                error = claudeError(e, seconds)
            }
        }
    }

    /**
     * Sends [t] to [w] on the box. [route] adds a "sent to the box" line first; [record] false leaves Tom's message out
     * of the chat (Ask the box resends one that's already there).
     */
    fun askBox(t: String, spoken: Boolean, w: Jarvis.Worker, route: String? = null, record: Boolean = true) {
        val id = chat.id
        val via = if (usesNano(w) || route != null) "Claude · box" else null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (demo) Jarvis.demoText(w.id, t) to null
                    else Jarvis.sendText(address, token, t, w.id, prefs.jarvisVoice, chat = id, speak = !phoneVoice).let { r -> r to fetch(r) }
                }
            }
            phase = Phase.IDLE
            sendingText = null
            result.onSuccess { (r, audio) ->
                pendingText = null
                if (record) storeSucceed(w.id, t, id, spoken)
                answered(r, audio, w, if (record) t else "", spoken = spoken, via = via, route = route)
            }.onFailure { e ->
                pendingText = if (record) t else null
                if (record) PhoneOutbox.keepJarvis(ctx, w.id, t, id, spoken)
                if (record) onQueued()
                error = failed(e)
            }
        }
    }

    /** Gemini Nano on the phone; if it says the ask needs more (BOX) or fails, it goes to the box instead. */
    fun askNano(t: String, spoken: Boolean, w: Jarvis.Worker) {
        val turns = chat.messages.filter { it.via != "box" }.map { PhoneBrain.Turn(it.mine, it.text) }
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        streaming = ""; streamingVia = NanoRoute.VIA
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    // Calendar, tasks, weather and the best-matching notes go in with every question.
                    val (day, notes) = runCatching { NanoContext.gather(ctx, prefs.vault, t) }.getOrDefault("" to "")
                    val prompt = NanoRoute.prompt(turns, t, java.time.LocalDate.now(), listOf("#work", "#personal"), day, notes, who)
                    PhoneNano.ask(prompt) { so -> main.post { streaming = NanoRoute.visible(so, who) } }
                }
            }
            streaming = null; streamingVia = null
            val r = result.getOrNull()?.let { NanoRoute.parse(it, who) }
            if (r == null || r.toBox) {
                val why = if (r == null) "Nano couldn't answer" else "Needs more than Nano"
                if (boxReady && prefs.nanoToBox) { askBox(t, spoken, w, route = "Sent via the server"); return@launch }
                phase = Phase.IDLE; sendingText = null
                answered(Jarvis.Reply(t, "$why, and the server isn't set up. Add its address and token in Settings.", null), null, w, t, spoken, via = NanoRoute.VIA)
                return@launch
            }
            phase = Phase.IDLE; sendingText = null; pendingText = null
            val sorted = if (r.tasks.isEmpty()) null else SortResult(r.tasks, emptyList(), null)
            answered(Jarvis.Reply(t, r.text, null, sorted = sorted), null, w, t, spoken, via = NanoRoute.VIA)
        }
    }

    /** Ask the box: resends the message a Nano answer was for to Claude on the box. */
    fun askTheBox(m: Jarvis.ChatMsg) {
        if (phase != Phase.IDLE) return
        val asked = chat.messages.takeWhile { it !== m }.lastOrNull { it.mine }?.text ?: return
        if (!boxReady) { scope.launch { snack.showSnackbar("Add the server address and token in Settings first") }; return }
        phase = Phase.SENDING; error = null; wasTyped = true
        pauseAudio()
        askBox(asked, false, worker, route = "Sent via the server", record = false)
    }

    /** Finds the article, then opens the same Long / Short check as the Vault button. Nothing is written yet. */
    fun openFindings(said: String, query: String, spoken: Boolean) {
        val uri = prefs.vault
        if (uri == null) { scope.launch { snack.showSnackbar("Pick a vault in Settings first") }; return }
        phase = Phase.SENDING; error = null; wasTyped = !spoken; sendingText = said
        if (!spoken) { typed = ""; prefs.setDraft("capture", "") }
        pauseAudio()
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val v = appVault(ctx, uri)
                    val notes = v.notes { NoteIndex.skip(it) }
                    val found = KeyFindings.find(notes, query) { v.read(it) } ?: error("Couldn't find that article in the vault")
                    val body = v.read(found) ?: error("Couldn't open that note")
                    FindingsAsk(found, body, KeyFindings.related(notes, found))
                }
            }
            phase = Phase.IDLE
            sendingText = null
            result.onSuccess { ask ->
                findingsAsk = ask
                answered(Jarvis.Reply(said, "Key findings for ${VaultTasks.noteName(ask.path)}. Pick Long or Short, then save if you want them in the note.", null), null, worker, said, spoken, via = who)
            }.onFailure { e ->
                val msg = e.message?.takeIf { it.startsWith("Couldn't") } ?: PhoneBrain.failure(who, e, viaServer = !brainOn && !demo)
                answered(Jarvis.Reply(said, msg, null), null, worker, said, spoken, via = who)
            }
        }
    }

    /** Typed text, or (with [spoken]) what the phone's speech-to-text heard. */
    fun sendText(text: String, spoken: Boolean = false) {
        val t = text.trim()
        if (t.isEmpty() || phase != Phase.IDLE) return
        KeyFindings.query(t)?.let { openFindings(t, it, spoken); return }
        if (!ready) { onOpenSettings(); return }
        phase = Phase.SENDING; error = null; wasTyped = !spoken; sendingText = t
        if (!spoken) { typed = ""; prefs.setDraft("capture", "") }
        pauseAudio()
        val w = worker
        if (usesBrain(w)) { askBrain(t, spoken, w); return }
        if (usesNano(w)) {
            val why = if (prefs.nanoToBox && boxReady) NanoRoute.needsBox(t) else null
            if (why == null) { askNano(t, spoken, w); return }
            askBox(t, spoken, w, route = "Sent via the server")
            return
        }
        askBox(t, spoken, w)
    }

    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (!ok) scope.launch { snack.showSnackbar("$who needs the microphone to hear you") }
    }

    fun begin(): Boolean {
        if (phase != Phase.IDLE) return false
        if (!ready) { onOpenSettings(); return false }
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            askMic.launch(Manifest.permission.RECORD_AUDIO); return false
        }
        pauseAudio()
        // With Claude on, speech stays on the phone so Tailscale is not required. Otherwise the phone
        // speech switch chooses Android's hearing, and a recording goes to the server.
        listening = !demo && ((brainOn && PhoneSpeech.available(ctx)) || (!brainOn && phoneSpeech))
        if (brainOn && !demo && !listening) {
            scope.launch { snack.showSnackbar("Type to $who. This phone can't hear you without the server.") }
            return false
        }
        return runCatching { if (listening) speechIn.start() else recorder.start() }.onFailure {
            scope.launch { snack.showSnackbar("Couldn't start the microphone") }
        }.isSuccess.also { ok ->
            if (ok) { phase = Phase.RECORDING; cancelling = false; locking = false; locked = false; startedAt = System.currentTimeMillis(); elapsed = 0; levels.clear() }
        }
    }

    fun finish(keep: Boolean) {
        if (phase != Phase.RECORDING) return
        elapsed = System.currentTimeMillis() - startedAt
        phase = Phase.IDLE
        locked = false; locking = false; cancelling = false
        // Short taps are ignored, so a pocket press doesn't send anything.
        if (listening) {
            listening = false
            if (!keep || elapsed < 500) { speechIn.cancel(); return }
            phase = Phase.SENDING
            speechIn.stop { heard ->
                phase = Phase.IDLE
                if (heard.isBlank()) scope.launch { snack.showSnackbar("Didn't catch that. Try again, or type it.") }
                else sendText(heard, spoken = true)
            }
            return
        }
        if (!keep || elapsed < 500) { recorder.cancel(); return }
        val f = recorder.stop()
        if (f == null) scope.launch { snack.showSnackbar("Nothing was recorded") } else send(f)
    }

    fun lock() {
        if (phase != Phase.RECORDING) return
        locked = true; locking = false; cancelling = false
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    // The screen stays on while recording, so a locked ramble isn't cut off by the screen timeout.
    DisposableEffect(phase) {
        view.keepScreenOn = phase == Phase.RECORDING
        onDispose { view.keepScreenOn = false }
    }

    LaunchedEffect(phase) {
        while (phase == Phase.RECORDING) {
            elapsed = System.currentTimeMillis() - startedAt
            if (elapsed >= MAX_RECORDING_MS) { finish(true); break }
            levels.add(if (listening) speechIn.level else recorder.level())
            while (levels.size > 24) levels.removeAt(0)
            delay(90)
        }
    }

    // Background jobs that finished. Servers without /done just answer 404.
    LaunchedEffect(address, token) {
        if (demo || !boxReady) return@LaunchedEffect
        while (true) {
            withContext(Dispatchers.IO) { runCatching { Jarvis.done(address, token) } }.onSuccess { markDone(it) }
            delay(30_000)
        }
    }

    val onBegin by rememberUpdatedState(::begin)
    val onFinish by rememberUpdatedState(::finish)
    val onLock by rememberUpdatedState(::lock)
    val isLocked by rememberUpdatedState(locked)

    /**
     * Hold to talk: let go to send, slide sideways or down off the button to cancel, slide up past [lockAt] (above the
     * button's top) onto the 🔒 to keep recording hands-free. While locked, a tap sends.
     */
    fun Modifier.micGesture(lockAt: Dp): Modifier = pointerInput(lockAt) {
        awaitEachGesture {
            val down = awaitFirstDown()
            down.consume()
            if (isLocked) {
                while (true) {
                    val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    if (!c.pressed) { c.consume(); break }
                }
                onFinish(true)
                return@awaitEachGesture
            }
            if (!onBegin()) return@awaitEachGesture
            val slop = 60f
            val lockY = -lockAt.toPx()
            var inside = true
            var toLock = false
            while (true) {
                val ev = awaitPointerEvent()
                val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                val p = c.position
                val inX = p.x > -slop && p.x < size.width + slop
                toLock = p.y < lockY && inX
                // Up towards the lock isn't cancelling; sideways or down off the button is.
                inside = inX && p.y < size.height + slop
                cancelling = !inside
                locking = toLock
                if (!c.pressed) { c.consume(); break }
            }
            if (toLock) onLock() else onFinish(inside)
        }
    }

    val rec = phase == Phase.RECORDING
    val inChat = chat.messages.isNotEmpty()

    @Composable
    fun LockPill(modifier: Modifier) {
        Column(
            modifier.zIndex(1f).width(52.dp).clip(CircleShape)
                .background(if (locking) PrimaryTint else Surface1).border(1.dp, if (locking) Primary else Outline, CircleShape)
                .padding(vertical = 7.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("🔒", fontSize = 17.sp)
            Text(if (locking) "Lock" else "▲", fontSize = 11.sp, color = if (locking) Primary else Muted)
        }
    }

    /** Timer, level bars, and the hint or (locked) Cancel / Send. */
    @Composable
    fun RecordingPanel() {
        Text(
            "%d:%02d".format(elapsed / 60_000, (elapsed / 1000) % 60),
            fontSize = 22.sp, fontFamily = FontFamily.Monospace, color = RecRed,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp), textAlign = TextAlign.Center
        )
        Row(
            Modifier.fillMaxWidth().height(36.dp).padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically
        ) {
            levels.forEach { l -> Box(Modifier.width(5.dp).height((6 + 28 * l).dp).clip(RoundedCornerShape(3.dp)).background(RecRed)) }
        }
        if (listening) speechIn.soFar.takeIf { it.isNotBlank() }?.let { heard ->
            Text("“${heard.takeLast(160)}”", fontSize = 13.sp, color = TextMain, lineHeight = 19.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        }
        if (locked) {
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("✕ Cancel", color = Muted, fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).clip(CircleShape).border(1.dp, Outline, CircleShape)
                        .clickable { finish(false) }.padding(vertical = 14.dp))
                Text("↑ Send to $who", color = OnPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).clip(CircleShape).background(Primary)
                        .clickable { finish(true) }.padding(vertical = 14.dp))
            }
            Text("🔒 Locked · keep talking. Sends on its own at 10:00.", fontSize = 12.5.sp, color = Muted,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp), textAlign = TextAlign.Center)
        } else Text(
            when {
                cancelling -> "Let go to cancel"
                locking -> "Let go to lock"
                else -> "Let go to send · Slide up to lock\nSlide sideways to cancel"
            },
            fontSize = 13.sp, color = if (locking) Primary else Muted, lineHeight = 19.sp,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp), textAlign = TextAlign.Center
        )
    }

    val scroll = rememberScrollState()
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    // New messages, the typing dots, and the keyboard opening all keep the latest message in view.
    LaunchedEffect(chat.messages.size, sendingText, phase, found, error, imeBottom, streaming?.length?.div(40)) {
        if (inChat || sendingText != null) scroll.animateScrollTo(scroll.maxValue)
    }

    // imePadding keeps the type box above the keyboard (the app draws edge to edge, so nothing else moves it).
    Box(Modifier.fillMaxSize().imePadding()) {
        // tower-bar-events-v1: the title and the mode switch stay pinned; only the chat scrolls under them.
        Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().background(Bg).padding(horizontal = 20.dp).padding(bottom = 10.dp)) {
            ScreenTitle(title.ifBlank { who }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (usesBrain(worker) || usesNano(worker)) Text(
                        if (usesBrain(worker)) "⚡ phone" else "📱 phone", fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, color = Primary,
                        modifier = Modifier.clip(CircleShape).background(PrimaryTint).border(1.dp, NextBorder, CircleShape)
                            .padding(horizontal = 9.dp, vertical = 5.dp)
                    )
                    if (last != null) Text("🧾", fontSize = 18.sp,
                        modifier = Modifier.clip(CircleShape).clickable { summarising = true }.padding(horizontal = 10.dp, vertical = 6.dp))
                }
            }
            Box(Modifier.padding(top = 12.dp)) { header() }
        }
        if (scroll.value > 0) HorizontalDivider(color = Outline)
        Column(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 20.dp).verticalScroll(scroll).padding(bottom = if (rec && inChat) 300.dp else 150.dp)) {

            if (!inChat) {
                // A new chat: the big button.
                val micSize = 170.dp
                Box(Modifier.fillMaxWidth().padding(top = 34.dp), contentAlignment = Alignment.Center) {
                    if (rec && !locked) LockPill(Modifier.align(Alignment.TopCenter).offset(y = (-36).dp))
                    Box(
                        Modifier.size(micSize + if (rec) 60.dp else 28.dp).clip(CircleShape).background(if (rec) RecRing2 else Color.Transparent),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            Modifier.size(micSize + 28.dp).clip(CircleShape).background(
                                when { rec -> RecRing1; phase == Phase.SENDING -> Surface1; else -> MicRing }
                            ),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                Modifier.size(micSize).clip(CircleShape)
                                    .background(
                                        when {
                                            rec && cancelling -> Surface2
                                            rec && locking -> RecRed.copy(alpha = 0.8f)
                                            rec -> RecRed
                                            phase == Phase.SENDING -> Surface2
                                            else -> Primary
                                        }
                                    )
                                    .micGesture(28.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        when { rec -> "●"; phase == Phase.SENDING -> "…"; else -> "🎙" },
                                        fontSize = 46.sp, color = if (rec) Color(0xFF2A0F0E) else OnPrimary
                                    )
                                    Text(
                                        when {
                                            rec && cancelling -> "Let go to cancel"
                                            rec && locking -> "Let go to lock"
                                            rec && locked -> "Tap to send"
                                            rec -> "Recording"
                                            phase == Phase.SENDING -> "Sending"
                                            else -> "Hold to talk"
                                        },
                                        fontSize = 15.sp, fontWeight = FontWeight.Bold,
                                        color = when { rec && cancelling -> Muted; rec -> Color(0xFF2A0F0E); phase == Phase.SENDING -> Muted; else -> OnPrimary }
                                    )
                                }
                            }
                        }
                    }
                }
                if (rec) RecordingPanel()
                else if (sendingText == null) Text(
                    if (ready) "Ask anything, or ramble and $who sorts it.\nHold to talk, or type below."
                    else "Turn on Claude from the phone in Settings, or add the server there.",
                    fontSize = 13.sp, color = Muted, lineHeight = 19.sp,
                    modifier = Modifier.fillMaxWidth().padding(top = 26.dp).let { if (ready) it else it.clickable(onClick = onOpenSettings) },
                    textAlign = TextAlign.Center
                )
            } else {
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Chat · $who · ${chat.messages.size} messages".uppercase(Locale.UK),
                        fontSize = 10.5.sp, letterSpacing = 1.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF5C6478),
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        if (speakOn) "🔊 Sound on" else "🔇 Muted", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
                        color = if (speakOn) Muted else Amber,
                        modifier = Modifier.padding(end = 8.dp).clip(CircleShape).border(1.dp, if (speakOn) Outline else Amber, CircleShape)
                            .clickable { toggleMute() }.padding(horizontal = 11.dp, vertical = 5.dp)
                    )
                    Text(
                        "＋ New chat", fontSize = 12.5.sp, color = if (phase == Phase.IDLE) Primary else Muted,
                        modifier = Modifier.clip(CircleShape).border(1.dp, NextBorder, CircleShape)
                            .clickable(enabled = phase == Phase.IDLE) { newChat() }.padding(horizontal = 11.dp, vertical = 5.dp)
                    )
                }
            }

            if (inChat || sendingText != null) Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                chat.messages.forEach { m ->
                    if (m.mine) MineBubble(m.text, (if (m.spoken) "🎙 spoken" else "⌨ typed") + " · " + hhmm(m.at))
                    else if (m.via == "box") JarvisRouteLine(m.text)
                    else JarvisAnswer(
                        m, who, idle = phase == Phase.IDLE, phoneVoice = phoneVoice, reading = speakingId == m.at.toString(),
                        audio = m.audio?.let { a -> JarvisAudio(here = loaded == a, playing = playing, pos = pos, durMs = durMs) },
                        speed = speed,
                        onSaveNote = { onFound(SortResult(emptyList(), emptyList(), m.text, noteTitle = Sorting.titleFrom(m.text))) },
                        onTryServer = { askTheBox(m) },
                        onReadOut = { sayAloud(m) },
                        onPlay = { m.audio?.let { toggle(it) } },
                        onSpeed = {
                            speed = Jarvis.nextSpeed(speed); prefs.jarvisSpeed = speed
                            player[0]?.let { if (playing) setSpeed(it, speed) }
                        },
                    )
                }
                sendingText?.let { MineBubble(it, (if (wasTyped) "⌨ typed" else "🎙 spoken") + " · sending") }
                val so = streaming
                if (so != null && so.isNotEmpty()) JarvisStreaming(so, nano = streamingVia == NanoRoute.VIA, who = who)
                else if (phase == Phase.SENDING) JarvisTyping()
            }

            error?.let { msg ->
                JarvisError(
                    msg, textKept = pendingText != null,
                    canRetry = (pending != null || pendingText != null) && phase == Phase.IDLE,
                    onRetry = { pendingText?.let { sendText(it) } ?: pending?.let { send(it) } },
                    onDiscard = { pending?.delete(); pending = null; pendingText = null; error = null },
                )
            }

            found?.let { r -> JarvisFound(r, onCheck) }
            if (demo) Text("Demo mode: no server, replies are canned.", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 14.dp))
        }
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Bg).padding(horizontal = 18.dp, vertical = 10.dp)) {
            // In a chat the mic is the small button by the box, so its timer and Send / Cancel sit here.
            if (rec && inChat) Column(Modifier.padding(bottom = if (locked) 8.dp else 64.dp)) { RecordingPanel() }
            done?.let { d -> JarvisDone(d, onOpen = { openDone = d }, onClose = { done = null }) }
            Box {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        Modifier.weight(1f).clip(RoundedCornerShape(24.dp)).background(Surface1).border(1.dp, Outline, RoundedCornerShape(24.dp))
                            .padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (!inChat) Text("⌨️", fontSize = 15.sp)
                        BasicTextField(
                            typed, { typed = it; prefs.setDraft("capture", it) },
                            modifier = Modifier.weight(1f).padding(vertical = 10.dp),
                            textStyle = TextStyle(color = TextMain, fontSize = 15.sp),
                            cursorBrush = SolidColor(Primary), maxLines = 5,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { sendText(typed) }),
                            decorationBox = { field ->
                                if (typed.isEmpty()) Text(if (inChat) "Message $who…" else "Type to $who…", color = Muted, fontSize = 15.sp)
                                field()
                            }
                        )
                        if (!inChat) {
                            val canSend = typed.isNotBlank() && phase == Phase.IDLE
                            Box(
                                Modifier.size(36.dp).clip(CircleShape).background(if (canSend) Primary else Surface2)
                                    .clickable(enabled = canSend) { sendText(typed) },
                                contentAlignment = Alignment.Center
                            ) { Text("↑", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = if (canSend) OnPrimary else Muted) }
                        }
                    }
                    if (inChat) {
                        if (typed.isNotBlank() && !rec) {
                            val canSend = phase == Phase.IDLE
                            Box(
                                Modifier.size(46.dp).clip(CircleShape).background(if (canSend) Primary else Surface2)
                                    .clickable(enabled = canSend) { sendText(typed) },
                                contentAlignment = Alignment.Center
                            ) { Text("↑", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = if (canSend) OnPrimary else Muted) }
                        } else Box(
                            Modifier.size(46.dp).clip(CircleShape)
                                .background(
                                    when {
                                        rec && cancelling -> Surface2
                                        rec -> RecRed
                                        phase == Phase.SENDING -> Surface2
                                        else -> Primary
                                    }
                                )
                                .micGesture(20.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                when { rec -> "●"; phase == Phase.SENDING -> "…"; else -> "🎙" },
                                fontSize = 18.sp, color = if (rec) Color(0xFF2A0F0E) else OnPrimary
                            )
                        }
                    }
                }
                if (inChat && rec && !locked) LockPill(Modifier.align(Alignment.TopEnd).offset(x = 3.dp, y = (-64).dp))
            }
        }
    }


    openDone?.let { d ->
        AlertDialog(
            onDismissRequest = { openDone = null }, containerColor = Surface1,
            title = { Text(d.title) },
            text = { Text(d.detail.ifBlank { "Finished." }, color = TextMain) },
            confirmButton = { TextButton(onClick = { openDone = null; done = null }) { Text("Done", color = Primary) } }
        )
    }

    if (summarising) last?.let { (said, reply, _) ->
        PrintSummarySheet(SummarySource.Chat(said, reply), snack) { summarising = false }
    }
    findingsAsk?.let { ask ->
        val uri = prefs.vault
        if (uri != null) KeyFindingsSheet(uri, ask.path, ask.note, ask.related, snack, onDismiss = { findingsAsk = null }) {
            findingsAsk = null
            scope.launch { snack.showSnackbar("Key findings saved") }
        }
    }
}

/** Speed changes only while playing: on a paused player Android would start it. */
internal fun setSpeed(p: MediaPlayer, speed: Float) {
    runCatching { p.playbackParams = p.playbackParams.setSpeed(speed) }
}

private fun hhmm(at: Long): String =
    Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm", Locale.UK))
