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
private val DoneBg = Color(0xFF14271F)
private val DoneBorder = Color(0xFF24493A)
private val ErrBg = Color(0xFF2A1716)
private val ErrBorder = Color(0xFF4A2523)

private enum class Phase { IDLE, RECORDING, SENDING }

/** A recording (locked or held) stops and sends itself after ten minutes. */
private const val MAX_RECORDING_MS = 10 * 60_000L

/** Workers the Claude key on the phone can answer. Coding and odd jobs stay on the box. */
private val API_WORKERS = setOf("jarvis", "obsidian", "research")

private data class ApiOutcome(
    val text: String, val sorted: SortResult?, val handoffs: List<PhoneBrain.Handoff>,
    val inTok: Long, val outTok: Long, val model: String,
)

/** OGG/Opus from the mic into a cache file. */
private class Recorder(private val ctx: Context) {
    private var rec: MediaRecorder? = null
    var file: File? = null; private set

    fun start(): File {
        val f = File(ctx.cacheDir, "jarvis-${System.currentTimeMillis()}.ogg")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.OGG)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
        r.setAudioSamplingRate(48_000)
        r.setAudioEncodingBitRate(32_000)
        r.setOutputFile(f.absolutePath)
        r.prepare()
        r.start()
        rec = r; file = f
        return f
    }

    /** 0..1, for the level bars. */
    fun level(): Float = runCatching { (rec?.maxAmplitude ?: 0) / 32767f }.getOrDefault(0f).coerceIn(0f, 1f)

    /** Stops and returns the file, or null if nothing usable was recorded. */
    fun stop(): File? {
        val r = rec ?: return null
        rec = null
        val ok = runCatching { r.stop() }.isSuccess
        r.release()
        return file?.takeIf { ok && it.length() > 0 }
    }

    fun cancel() { stop()?.delete(); file = null }
}

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
    title: String = "Jarvis",
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
    /** Phone brain: Claude from the app. Jarvis, Obsidian and Research. Coding and odd jobs still use the box. */
    val claudeKey = remember { secrets.claudeKey.orEmpty() }
    val brainOn = !demo && prefs.phoneBrain && claudeKey.isNotBlank()
    fun usesBrain(w: Jarvis.Worker) = brainOn && w.id in API_WORKERS
    /** Gemini Nano on the phone answers quick asks to Jarvis; the box gets the rest (mockup nano-brain-v1). */
    val nanoOn = !demo && prefs.nanoBrain
    var nanoState by remember { mutableStateOf(PhoneNano.state) }
    LaunchedEffect(nanoOn) { if (nanoOn) nanoState = withContext(Dispatchers.IO) { PhoneNano.ensure() } }
    fun usesNano(w: Jarvis.Worker) = nanoOn && w.id == "jarvis" && !usesBrain(w) && nanoState == PhoneNano.State.READY
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

    var worker by remember { mutableStateOf(Jarvis.worker(prefs.jarvisWorker)) }
    val ready = demo || usesBrain(worker) || usesNano(worker) || boxReady
    /** The conversation with [worker]; each worker keeps its own, and the server remembers it by its id. */
    var chat by remember { mutableStateOf(prefs.jarvisChat(worker.id)) }
    var picking by remember { mutableStateOf(false) }
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

    fun pickWorker(w: Jarvis.Worker) {
        worker = w; prefs.jarvisWorker = w.id; picking = false
        pauseAudio(); chat = prefs.jarvisChat(w.id)
    }

    fun markDone(list: List<Jarvis.Done>) {
        val seen = prefs.jarvisSeenDone
        list.firstOrNull { it.id !in seen }?.let { d -> done = d; prefs.jarvisSeenDone = seen + d.id }
    }

    fun failed(e: Throwable): String = when {
        e is Jarvis.HttpError && (e.code == 401 || e.code == 403) -> "Jarvis refused the token. Check it in Settings."
        e is Jarvis.HttpError && e.code == 404 && wasTyped -> "This server can't take typed messages yet (no /text)."
        e is Jarvis.HttpError -> e.message ?: "Jarvis returned an error."
        else -> "Can't reach Jarvis. Is Tailscale on?"
    }

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

    fun claudeError(e: Throwable, seconds: Long): String = PhoneBrain.timedOut(e, seconds) ?: when (e) {
        is PhoneBrain.Refused -> e.message ?: "Claude couldn't help with that one."
        is com.anthropic.errors.UnauthorizedException, is com.anthropic.errors.PermissionDeniedException -> "Claude refused the key. Check it in Settings › Jarvis."
        is com.anthropic.errors.RateLimitException -> "Claude is busy right now. Try again in a moment."
        is com.anthropic.errors.AnthropicIoException -> "Can't reach Claude. Are you online?"
        else -> e.message ?: "Claude couldn't answer."
    }

    /**
     * A job the phone brain handed on. Research and vault notes stay on the phone when the Claude key is on.
     * The "sent" line is not written until the other side has actually answered.
     */
    fun handOff(h: PhoneBrain.Handoff, from: Jarvis.Worker) {
        val label = PhoneBrain.workerLabel(h.worker)
        if (brainOn && h.worker in API_WORKERS && h.worker != "jarvis") {
            val flight = PhoneBrain.flightKey(h.worker, h.request)
            if (!PhoneBrain.beginFlight(flight)) return
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val today = java.time.LocalDate.now()
                        if (h.worker == "research") PhoneBrain.research(claudeKey, h.request, today)
                        else PhoneBrain.sortOrAnswer(claudeKey, h.request, today)
                    }
                }
                PhoneBrain.endFlight(flight)
                result.onSuccess { d ->
                    prefs.brainSpend = prefs.brainSpend.add(PhoneBrain.costUsd(PhoneBrain.FAST, d.inTok, d.outTok))
                    storeSucceed(h.worker, h.request, prefs.jarvisChat(h.worker).id, false)
                    answered(Jarvis.Reply("", d.reply, null, sorted = d.sorted), null, from, "", false, via = label)
                }.onFailure { e ->
                    PhoneOutbox.keepJarvis(ctx, h.worker, h.request, prefs.jarvisChat(h.worker).id, false)
                    onQueued()
                    val why = claudeError(e, if (h.worker == "research") PhoneBrain.RESEARCH_TIMEOUT_S else PhoneBrain.CHAT_TIMEOUT_S)
                    answered(Jarvis.Reply("", why, null), null, from, "", false, via = label)
                }
            }
            return
        }
        if (!boxReady) {
            PhoneOutbox.keepJarvis(ctx, h.worker, h.request, prefs.jarvisChat(h.worker).id, false)
            onQueued()
            answered(Jarvis.Reply("", "$label needs the Oracle box. It's kept on the phone.", null), null, from, "", false, via = "box")
            return
        }
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { Jarvis.sendText(address, token, h.request, h.worker, prefs.jarvisVoice, chat = prefs.jarvisChat(h.worker).id, speak = !phoneVoice) }
            }
            r.onSuccess { reply ->
                storeSucceed(h.worker, h.request, prefs.jarvisChat(h.worker).id, false)
                answered(reply.copy(sorted = reply.sorted), null, from, "", false, via = label)
            }.onFailure { e ->
                PhoneOutbox.keepJarvis(ctx, h.worker, h.request, prefs.jarvisChat(h.worker).id, false)
                onQueued()
                answered(Jarvis.Reply("", "$label didn't answer. It's kept on the phone. " + failed(e), null), null, from, "", false, via = "box")
            }
        }
    }

    /** Phone brain: Claude straight from the app. A long message is sorted or answered in one call. */
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
        val seconds = if (w.id == "research") PhoneBrain.RESEARCH_TIMEOUT_S else PhoneBrain.CHAT_TIMEOUT_S
        streaming = ""; streamingVia = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val today = java.time.LocalDate.now()
                    when {
                        w.id == "research" -> PhoneBrain.research(claudeKey, t, today).let { ApiOutcome(it.reply, it.sorted, emptyList(), it.inTok, it.outTok, PhoneBrain.FAST) }
                        w.id == "obsidian" || PhoneBrain.worthSorting(t) -> PhoneBrain.sortOrAnswer(claudeKey, t, today).let { ApiOutcome(it.reply, it.sorted, emptyList(), it.inTok, it.outTok, PhoneBrain.FAST) }
                        else -> PhoneBrain.chat(claudeKey, model, turns, today, listOf("#work", "#personal")) { so -> main.post { streaming = so } }
                            .let { a -> ApiOutcome(a.text.ifBlank { "Done." }, a.tasks.takeIf { it.isNotEmpty() }?.let { SortResult(it, emptyList(), null) }, a.handoffs, a.inputTokens, a.outputTokens, a.model) }
                    }
                }
            }
            PhoneBrain.endFlight(flight)
            phase = Phase.IDLE
            sendingText = null
            streaming = null
            result.onSuccess { a ->
                pendingText = null
                storeSucceed(w.id, t, chatId, spoken)
                prefs.brainSpend = prefs.brainSpend.add(PhoneBrain.costUsd(a.model, a.inTok, a.outTok))
                answered(Jarvis.Reply(t, a.text, null, sorted = a.sorted), null, w, t, spoken)
                a.handoffs.forEach { handOff(it, w) }
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
                    val prompt = NanoRoute.prompt(turns, t, java.time.LocalDate.now(), listOf("#work", "#personal"), day, notes)
                    PhoneNano.ask(prompt) { so -> main.post { streaming = NanoRoute.visible(so) } }
                }
            }
            streaming = null; streamingVia = null
            val r = result.getOrNull()?.let(NanoRoute::parse)
            if (r == null || r.toBox) {
                val why = if (r == null) "Nano couldn't answer" else "Needs more than Nano"
                if (boxReady && prefs.nanoToBox) { askBox(t, spoken, w, route = "🛰 $why · sent to Claude on the box"); return@launch }
                phase = Phase.IDLE; sendingText = null
                answered(Jarvis.Reply(t, "$why, and the box isn't set up. Add its address and token in Settings › Jarvis.", null), null, w, t, spoken, via = NanoRoute.VIA)
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
        if (!boxReady) { scope.launch { snack.showSnackbar("Add the box's address and token in Settings › Jarvis first") }; return }
        phase = Phase.SENDING; error = null; wasTyped = true
        pauseAudio()
        askBox(asked, false, worker, route = "🛰 Asked Claude on the box", record = false)
    }

    /** Typed text, or (with [spoken]) what the phone's speech-to-text heard. */
    fun sendText(text: String, spoken: Boolean = false) {
        val t = text.trim()
        if (t.isEmpty() || phase != Phase.IDLE) return
        if (!ready) { onOpenSettings(); return }
        phase = Phase.SENDING; error = null; wasTyped = !spoken; sendingText = t
        if (!spoken) { typed = ""; prefs.setDraft("capture", "") }
        pauseAudio()
        val w = worker
        if (usesBrain(w)) { askBrain(t, spoken, w); return }
        if (usesNano(w)) {
            val why = if (prefs.nanoToBox && boxReady) NanoRoute.needsBox(t) else null
            if (why == null) { askNano(t, spoken, w); return }
            askBox(t, spoken, w, route = "🛰 $why · sent to Claude on the box")
            return
        }
        askBox(t, spoken, w)
    }

    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (!ok) scope.launch { snack.showSnackbar("Jarvis needs the microphone to hear you") }
    }

    fun begin(): Boolean {
        if (phase != Phase.IDLE) return false
        if (!ready) { onOpenSettings(); return false }
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            askMic.launch(Manifest.permission.RECORD_AUDIO); return false
        }
        pauseAudio()
        // Android's speech-to-text when it's on; the box's Whisper (a recording) otherwise, or for demo mode.
        listening = phoneSpeech && !demo
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
                Text("↑ Send to ${worker.label}", color = OnPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
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
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp).verticalScroll(scroll).padding(bottom = if (rec && inChat) 300.dp else 150.dp)) {
            ScreenTitle(title) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (usesBrain(worker) || usesNano(worker)) Text(
                        if (usesBrain(worker)) "⚡ phone" else "📱 phone", fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, color = Primary,
                        modifier = Modifier.clip(CircleShape).background(PrimaryTint).border(1.dp, NextBorder, CircleShape)
                            .padding(horizontal = 9.dp, vertical = 5.dp)
                    )
                    if (last != null) Text("🧾", fontSize = 18.sp,
                        modifier = Modifier.clip(CircleShape).clickable { summarising = true }.padding(horizontal = 10.dp, vertical = 6.dp))
                    Row(
                        Modifier.clip(CircleShape).background(PrimaryTint).border(1.dp, NextBorder, CircleShape)
                            .clickable { picking = true }.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(worker.icon, fontSize = 13.sp)
                        Text("${worker.label} ▾", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Primary)
                    }
                }
            }
            Box(Modifier.padding(top = 12.dp)) { header() }

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
                    if (ready) "Ask anything, or ramble and Jarvis sorts it.\nHold to talk, or type below."
                    else "Add the Jarvis address and token in Settings first.",
                    fontSize = 13.sp, color = Muted, lineHeight = 19.sp,
                    modifier = Modifier.fillMaxWidth().padding(top = 26.dp).let { if (ready) it else it.clickable(onClick = onOpenSettings) },
                    textAlign = TextAlign.Center
                )
            } else {
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Chat · ${worker.label} · ${chat.messages.size} messages".uppercase(Locale.UK),
                        fontSize = 10.5.sp, letterSpacing = 1.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF5C6478),
                        modifier = Modifier.weight(1f)
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
                    else if (m.via == "box") Text(
                        m.text, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, color = Muted, lineHeight = 17.sp,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Surface2)
                            .border(1.dp, Outline, RoundedCornerShape(12.dp)).padding(horizontal = 11.dp, vertical = 8.dp)
                    )
                    else Column(
                        Modifier.padding(end = 36.dp).clip(RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)).background(Surface1)
                            .border(1.dp, Outline, RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)).padding(horizontal = 13.dp, vertical = 10.dp)
                    ) {
                        val nano = m.via == NanoRoute.VIA
                        Text(
                            (m.via?.let { if (nano) "📱 Nano" else "🛰 $it" } ?: worker.label).uppercase(Locale.UK), fontSize = 10.sp, letterSpacing = 1.sp,
                            fontFamily = FontFamily.Monospace, color = if (m.via != null && !nano) Amber else Primary
                        )
                        Text(remember(m.text) { chatMarkdown(m.text) }, fontSize = 14.5.sp, lineHeight = 21.sp, color = TextMain, modifier = Modifier.padding(top = 3.dp))
                        if (nano) {
                            Text("on phone · free", fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, color = Muted, modifier = Modifier.padding(top = 6.dp))
                            Text(
                                "🛰 Ask the box", fontSize = 12.sp, color = if (phase == Phase.IDLE) Primary else Muted,
                                modifier = Modifier.padding(top = 8.dp).clip(CircleShape).background(PrimaryTint).border(1.dp, NextBorder, CircleShape)
                                    .clickable(enabled = phase == Phase.IDLE) { askTheBox(m) }.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                        if (m.audio == null && phoneVoice) {
                            val here = speakingId == m.at.toString()
                            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Box(
                                    Modifier.size(28.dp).clip(CircleShape).background(PrimaryTint).clickable { sayAloud(m) },
                                    contentAlignment = Alignment.Center
                                ) { Text(if (here) "■" else "▶", fontSize = 11.sp, color = Primary) }
                                Text(if (here) "Reading out…" else "Read out · ${Jarvis.speedLabel(speed)}", fontSize = 11.5.sp, color = Muted)
                            }
                        }
                        m.audio?.let { a ->
                            val here = loaded == a
                            Row(
                                Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(
                                    Modifier.size(28.dp).clip(CircleShape).background(PrimaryTint).clickable { toggle(a) },
                                    contentAlignment = Alignment.Center
                                ) { Text(if (here && playing) "❚❚" else "▶", fontSize = 11.sp, color = Primary) }
                                if (here) {
                                    Box(Modifier.width(90.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Surface2)) {
                                        Box(Modifier.fillMaxWidth(pos.coerceIn(0f, 1f)).height(4.dp).background(Primary))
                                    }
                                    Text("${mmss((durMs * pos).toLong())} / ${mmss(durMs)}", fontSize = 11.5.sp, color = Muted)
                                    Text(
                                        Jarvis.speedLabel(speed), fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Primary,
                                        modifier = Modifier.clip(CircleShape).background(PrimaryTint).clickable {
                                            speed = Jarvis.nextSpeed(speed); prefs.jarvisSpeed = speed
                                            player[0]?.let { if (playing) setSpeed(it, speed) }
                                        }.padding(horizontal = 8.dp, vertical = 2.dp)
                                    )
                                } else Text("Play · ${Jarvis.speedLabel(speed)}", fontSize = 11.5.sp, color = Muted)
                            }
                        }
                    }
                }
                sendingText?.let { MineBubble(it, (if (wasTyped) "⌨ typed" else "🎙 spoken") + " · sending") }
                val so = streaming
                if (so != null && so.isNotEmpty()) Column(
                    Modifier.padding(end = 36.dp).clip(RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)).background(Surface1)
                        .border(1.dp, Outline, RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)).padding(horizontal = 13.dp, vertical = 10.dp)
                ) {
                    Text((if (streamingVia == NanoRoute.VIA) "📱 Nano" else worker.label).uppercase(Locale.UK), fontSize = 10.sp, letterSpacing = 1.sp, fontFamily = FontFamily.Monospace, color = Primary)
                    Text(remember(so) { chatMarkdown("$so ▍") }, fontSize = 14.5.sp, lineHeight = 21.sp, color = TextMain, modifier = Modifier.padding(top = 3.dp))
                    Text(if (streamingVia == NanoRoute.VIA) "📱 on phone…" else "⚡ streaming…", fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, color = Muted, modifier = Modifier.padding(top = 6.dp))
                }
                else if (phase == Phase.SENDING) Text(
                    "• • •", fontSize = 16.sp, color = Muted, letterSpacing = 3.sp,
                    modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(Surface1).border(1.dp, Outline, RoundedCornerShape(18.dp))
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }

            error?.let { msg ->
                Column(
                    Modifier.fillMaxWidth().padding(top = 18.dp).clip(RoundedCornerShape(14.dp)).background(ErrBg)
                        .border(1.dp, ErrBorder, RoundedCornerShape(14.dp)).padding(14.dp)
                ) {
                    Text(msg, fontSize = 13.sp, color = Warn, fontWeight = FontWeight.SemiBold)
                    Text(if (pendingText != null) "Your message is kept." else "Your recording is kept.", fontSize = 13.sp, color = Muted)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(
                            onClick = { pendingText?.let { sendText(it) } ?: pending?.let { send(it) } },
                            enabled = (pending != null || pendingText != null) && phase == Phase.IDLE
                        ) { Text("Retry", color = Primary) }
                        TextButton(onClick = { pending?.delete(); pending = null; pendingText = null; error = null }) { Text("Discard", color = Muted) }
                    }
                }
            }

            found?.let { r ->
                Row(
                    Modifier.fillMaxWidth().padding(top = 14.dp).clip(RoundedCornerShape(16.dp)).background(PrimaryTint)
                        .border(1.dp, NextBorder, RoundedCornerShape(16.dp)).clickable(onClick = onCheck).padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("✨", fontSize = 16.sp)
                    Text(foundLabel(r), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text("Check and save", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = OnPrimary,
                        modifier = Modifier.clip(CircleShape).background(Primary).padding(horizontal = 12.dp, vertical = 7.dp))
                }
            }
            if (demo) Text("Demo mode: no server, replies are canned.", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 14.dp))
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Bg).padding(horizontal = 18.dp, vertical = 10.dp)) {
            // In a chat the mic is the small button by the box, so its timer and Send / Cancel sit here.
            if (rec && inChat) Column(Modifier.padding(bottom = if (locked) 8.dp else 64.dp)) { RecordingPanel() }
            done?.let { d ->
                Row(
                    Modifier.padding(bottom = 8.dp).fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp)).background(DoneBg).border(1.dp, DoneBorder, RoundedCornerShape(14.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(Modifier.size(24.dp).clip(CircleShape).background(Green), contentAlignment = Alignment.Center) {
                        Text("✓", fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF0B2117))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(d.title, fontSize = 13.sp)
                        if (d.detail.isNotBlank()) Text(d.detail, fontSize = 11.5.sp, color = Muted, maxLines = 1)
                    }
                    Text("Open", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = Green, modifier = Modifier.clickable { openDone = d })
                    Text("✕", fontSize = 13.sp, color = Muted, modifier = Modifier.clickable { done = null }.padding(start = 4.dp))
                }
            }
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
                                if (typed.isEmpty()) Text(if (inChat) "Message ${worker.label}…" else "Type to ${worker.label}…", color = Muted, fontSize = 15.sp)
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
    if (picking) {
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { picking = false }, sheetState = sheet, containerColor = Surface1) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
                Text("Talk to", fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                Jarvis.WORKERS.forEach { w ->
                    Row(
                        Modifier.fillMaxWidth().clickable { pickWorker(w) }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(Surface2), contentAlignment = Alignment.Center) {
                            Text(w.icon, fontSize = 17.sp)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(w.label, fontSize = 15.sp)
                            Text(w.blurb, fontSize = 12.sp, color = Muted)
                        }
                        RadioButton(
                            selected = w.id == worker.id, onClick = { pickWorker(w) },
                            colors = RadioButtonDefaults.colors(selectedColor = Primary, unselectedColor = Muted)
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text("Each one keeps its own chat. Remembers your last pick.", fontSize = 12.5.sp, color = Muted)
            }
        }
    }
}

/** Speed changes only while playing: on a paused player Android would start it. */
internal fun setSpeed(p: MediaPlayer, speed: Float) {
    runCatching { p.playbackParams = p.playbackParams.setSpeed(speed) }
}

private fun hhmm(at: Long): String =
    Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm", Locale.UK))

/** An answer's Markdown as styled text: bold, italic, code, link text, headings in bold, and "- " bullets as "•". */
internal fun chatMarkdown(md: String): AnnotatedString = buildAnnotatedString {
    fun inline(p: String) {
        var at = 0
        for (m in MdBlocks.INLINE.findAll(p)) {
            append(p.substring(at, m.range.first))
            val g = m.groups
            when {
                m.value.startsWith("!") -> Unit
                g[1] != null -> withStyle(SpanStyle(color = Primary)) { append(g[1]!!.value) }
                g[3] != null || g[4] != null -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { inline((g[3] ?: g[4])!!.value) }
                g[5] != null -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Surface2)) { append(g[5]!!.value) }
                g[6] != null -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { inline(g[6]!!.value) }
                g[7] != null -> append((g[8] ?: g[7])!!.value)
                else -> append(m.value)
            }
            at = m.range.last + 1
        }
        append(p.substring(at))
    }
    md.trim().lines().forEachIndexed { i, raw ->
        if (i > 0) append("\n")
        val line = raw.trimEnd()
        val head = Regex("^#{1,6}\\s+(.*)").find(line)
        val bullet = Regex("^(\\s*)[-*+]\\s+(.*)").find(line)
        when {
            head != null -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { inline(head.groupValues[1]) }
            bullet != null -> { append(bullet.groupValues[1] + "• "); inline(bullet.groupValues[2]) }
            else -> inline(line)
        }
    }
}

/** One of Tom's messages: right-aligned, with how it was sent and when. */
@Composable
private fun MineBubble(text: String, caption: String) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Column(
            Modifier.padding(start = 48.dp).clip(RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)).background(Primary)
                .padding(horizontal = 13.dp, vertical = 9.dp),
            horizontalAlignment = Alignment.End
        ) {
            Text(text, fontSize = 14.5.sp, lineHeight = 21.sp, color = OnPrimary)
            Text(caption, fontSize = 10.5.sp, color = OnPrimary.copy(alpha = 0.7f), modifier = Modifier.padding(top = 2.dp))
        }
    }
}

private fun foundLabel(r: SortResult): String = listOfNotNull(
    if (r.note != null) "1 note" else null,
    r.tasks.size.takeIf { it > 0 }?.let { if (it == 1) "1 task" else "$it tasks" },
    r.ideas.size.takeIf { it > 0 }?.let { if (it == 1) "1 idea" else "$it ideas" },
).joinToString(" · ")

private fun mmss(ms: Long): String = "%d:%02d".format(ms / 60_000, (ms / 1000) % 60)

/**
 * Settings › Jarvis › Voice. Lists the server's voices (GET /voices); ▶ plays a voice's sample when the server has one.
 * [onPick] gets the id ("" for the server's default) and a label for the row.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun JarvisVoiceSheet(prefs: AppPrefs, onPick: (String, String) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val address = remember { prefs.jarvisAddress }
    val token = remember { SecretStore(ctx).jarvisToken.orEmpty() }
    var voices by remember { mutableStateOf<List<Jarvis.Voice>?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    var sampling by remember { mutableStateOf<String?>(null) }
    val player = remember { arrayOfNulls<MediaPlayer>(1) }
    DisposableEffect(Unit) { onDispose { player[0]?.release(); player[0] = null } }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { runCatching { Jarvis.voices(address, token) } }
            .onSuccess { voices = it }
            .onFailure { voices = emptyList(); failed = "Couldn't load the voices: ${it.message ?: "no answer"}" }
    }
    fun sample(v: Jarvis.Voice) {
        val url = v.sampleUrl ?: return
        sampling = v.id
        scope.launch {
            val f = File(ctx.cacheDir, "jarvis-sample.ogg")
            val ok = withContext(Dispatchers.IO) { runCatching { Jarvis.fetchAudio(address, token, url, f) }.isSuccess }
            if (ok) {
                player[0]?.release()
                player[0] = MediaPlayer().apply {
                    runCatching {
                        setDataSource(f.absolutePath); prepare(); start(); setSpeed(this, prefs.jarvisSpeed)
                        setOnCompletionListener { sampling = null }
                    }.onFailure { sampling = null }
                }
            } else {
                sampling = null
            }
        }
    }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Surface1) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text("Jarvis voice", fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
            val list = voices
            if (list == null) Text("Loading…", fontSize = 13.sp, color = Muted, modifier = Modifier.padding(vertical = 12.dp))
            val rows = list.orEmpty() + Jarvis.Voice("", "Server default", "Whatever the server is set to", null)
            if (list != null) rows.forEach { v ->
                val on = v.id == prefs.jarvisVoice
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(v.id, if (v.id.isEmpty()) "" else v.label) }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    RadioButton(
                        selected = on, onClick = { onPick(v.id, if (v.id.isEmpty()) "" else v.label) },
                        colors = RadioButtonDefaults.colors(selectedColor = Primary, unselectedColor = Muted)
                    )
                    Column(Modifier.weight(1f)) {
                        Text(v.label, fontSize = 15.sp)
                        if (v.detail.isNotBlank()) Text(v.detail, fontSize = 12.sp, color = Muted)
                    }
                    if (v.sampleUrl != null) Box(
                        Modifier.size(30.dp).clip(CircleShape).background(PrimaryTint).clickable { sample(v) },
                        contentAlignment = Alignment.Center
                    ) { Text(if (sampling == v.id) "…" else "▶", fontSize = 11.sp, color = Primary) }
                }
            }
            failed?.let { Text(it, fontSize = 12.5.sp, color = Warn, modifier = Modifier.padding(top = 6.dp)) }
            if (list != null && list.isEmpty() && failed == null)
                Text("Your server doesn't list its voices yet, so it uses its own default.", fontSize = 12.5.sp, color = Muted, modifier = Modifier.padding(top = 6.dp))
            else Text("The list comes from your server. ▶ plays a short sample.", fontSize = 12.5.sp, color = Muted, modifier = Modifier.padding(top = 6.dp))
        }
    }
}
