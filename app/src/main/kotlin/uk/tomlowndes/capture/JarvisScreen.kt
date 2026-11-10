package uk.tomlowndes.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.ui.text.TextStyle
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
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val secrets = remember { SecretStore(ctx) }
    val recorder = remember { Recorder(ctx) }
    val address = remember { prefs.jarvisAddress }
    val token = remember { secrets.jarvisToken.orEmpty() }
    val demo = Jarvis.isDemo(address)
    val ready = demo || (address.isNotBlank() && token.isNotBlank())

    var worker by remember { mutableStateOf(Jarvis.worker(prefs.jarvisWorker)) }
    var picking by remember { mutableStateOf(false) }
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
    var last by remember { mutableStateOf(prefs.jarvisLast) }
    var summarising by remember { mutableStateOf(false) }
    var fresh by remember { mutableStateOf(false) }
    /** The recording that failed to send, kept for Retry. */
    var pending by remember { mutableStateOf<File?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf<Jarvis.Done?>(null) }
    var openDone by remember { mutableStateOf<Jarvis.Done?>(null) }
    var typed by remember { mutableStateOf(prefs.draft("capture")) }
    /** Whether the last message was typed rather than spoken, for the "You typed" label. */
    var wasTyped by remember { mutableStateOf(false) }
    /** Typed text that failed to send, kept for Retry. */
    var pendingText by remember { mutableStateOf<String?>(null) }

    // Reply audio.
    val player = remember { arrayOfNulls<MediaPlayer>(1) }
    var playing by remember { mutableStateOf(false) }
    var hasAudio by remember { mutableStateOf(false) }
    var pos by remember { mutableFloatStateOf(0f) }
    var durMs by remember { mutableLongStateOf(0L) }
    var speed by remember { mutableFloatStateOf(prefs.jarvisSpeed) }
    DisposableEffect(Unit) {
        onDispose {
            player[0]?.release(); player[0] = null
            recorder.cancel()
        }
    }
    LaunchedEffect(playing) {
        while (playing) {
            player[0]?.let { p -> runCatching { if (p.duration > 0) pos = p.currentPosition.toFloat() / p.duration } }
            delay(150)
        }
    }

    /** Loads the reply audio; starts it straight away when [start] (Settings › Play replies aloud). */
    fun play(f: File, start: Boolean) {
        player[0]?.release()
        val p = MediaPlayer()
        runCatching {
            p.setDataSource(f.absolutePath)
            p.prepare()
            durMs = p.duration.toLong()
            p.setOnCompletionListener { playing = false; pos = 1f }
            if (start) { p.start(); setSpeed(p, speed) }
            player[0] = p; hasAudio = true; playing = start; pos = 0f
        }.onFailure { p.release(); hasAudio = false }
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

    fun answered(r: Jarvis.Reply, audio: File?, w: Jarvis.Worker) {
        val now = System.currentTimeMillis()
        last = Triple(r.transcript, r.reply, now); prefs.jarvisLast = last
        fresh = true
        markDone(r.done)
        r.sorted?.let(onFound)
        if (demo && w.id == "research") markDone(listOf(Jarvis.Done("demo-$now", "Research finished (demo)", "This is what a finished background job looks like.")))
        if (audio != null) { play(audio, prefs.jarvisSpeak) } else { player[0]?.release(); player[0] = null; hasAudio = false }
    }

    fun fetch(r: Jarvis.Reply): File? = r.audioUrl?.let { u ->
        File(ctx.cacheDir, "jarvis-reply.ogg").also { out -> Jarvis.fetchAudio(address, token, u, out) }
    }

    fun send(f: File) {
        phase = Phase.SENDING; error = null; wasTyped = false
        val w = worker
        val seconds = elapsed / 1000.0
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (demo) Jarvis.demoReply(w.id, seconds) to f
                    else Jarvis.send(address, token, f, w.id, prefs.jarvisVoice).let { r -> r to fetch(r) }
                }
            }
            phase = Phase.IDLE
            result.onSuccess { (r, audio) ->
                if (!demo) f.delete()
                pending = null
                answered(r, audio, w)
            }.onFailure { e -> pending = f; error = failed(e) }
        }
    }

    fun sendText(text: String) {
        val t = text.trim()
        if (t.isEmpty() || phase != Phase.IDLE) return
        if (!ready) { onOpenSettings(); return }
        phase = Phase.SENDING; error = null; wasTyped = true
        runCatching { if (playing) player[0]?.pause() }; playing = false
        val w = worker
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (demo) Jarvis.demoText(w.id, t) to null
                    else Jarvis.sendText(address, token, t, w.id, prefs.jarvisVoice).let { r -> r to fetch(r) }
                }
            }
            phase = Phase.IDLE
            result.onSuccess { (r, audio) ->
                pendingText = null
                typed = ""; prefs.setDraft("capture", "")
                answered(r.copy(transcript = r.transcript.ifBlank { t }), audio, w)
            }.onFailure { e -> pendingText = t; error = failed(e) }
        }
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
        runCatching { if (playing) player[0]?.pause() }; playing = false
        return runCatching { recorder.start() }.onFailure {
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
            levels.add(recorder.level())
            while (levels.size > 24) levels.removeAt(0)
            delay(90)
        }
    }

    // Background jobs that finished. Servers without /done just answer 404.
    LaunchedEffect(address, token) {
        if (demo || !ready) return@LaunchedEffect
        while (true) {
            withContext(Dispatchers.IO) { runCatching { Jarvis.done(address, token) } }.onSuccess { markDone(it) }
            delay(30_000)
        }
    }

    val onBegin by rememberUpdatedState(::begin)
    val onFinish by rememberUpdatedState(::finish)
    val onLock by rememberUpdatedState(::lock)
    val isLocked by rememberUpdatedState(locked)

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp).verticalScroll(rememberScrollState()).padding(bottom = 150.dp)) {
            ScreenTitle(title) {
                Row(verticalAlignment = Alignment.CenterVertically) {
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

            val small = fresh && phase == Phase.IDLE
            val micSize by animateDpAsState(if (small) 72.dp else 170.dp, label = "mic")
            val rec = phase == Phase.RECORDING
            Box(Modifier.fillMaxWidth().padding(top = if (small) 12.dp else 34.dp), contentAlignment = Alignment.Center) {
                if (rec && !locked) Column(
                    Modifier.align(Alignment.TopCenter).offset(y = (-36).dp).zIndex(1f).width(52.dp).clip(CircleShape)
                        .background(if (locking) PrimaryTint else Surface1).border(1.dp, if (locking) Primary else Outline, CircleShape)
                        .padding(vertical = 7.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("🔒", fontSize = 17.sp)
                    Text(if (locking) "Lock" else "▲", fontSize = 11.sp, color = if (locking) Primary else Muted)
                }
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
                                .pointerInput(Unit) {
                                    awaitEachGesture {
                                        val down = awaitFirstDown()
                                        down.consume()
                                        // Locked: a tap on the button sends, like the Send button.
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
                                        val lockAt = -28.dp.toPx()
                                        var inside = true
                                        var toLock = false
                                        while (true) {
                                            val ev = awaitPointerEvent()
                                            val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                                            val p = c.position
                                            toLock = p.y < lockAt && p.x > -slop && p.x < size.width + slop
                                            // Up towards the lock isn't cancelling; sideways or down off the button is.
                                            inside = toLock || (p.x > -slop && p.y > -slop && p.x < size.width + slop && p.y < size.height + slop) ||
                                                (p.y < 0 && p.x > -slop && p.x < size.width + slop)
                                            cancelling = !inside
                                            locking = toLock
                                            if (!c.pressed) { c.consume(); break }
                                        }
                                        if (toLock) onLock() else onFinish(inside)
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    when { rec -> "●"; phase == Phase.SENDING -> "…"; else -> "🎙" },
                                    fontSize = if (small) 24.sp else 46.sp,
                                    color = if (rec) Color(0xFF2A0F0E) else OnPrimary
                                )
                                if (!small) Text(
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

            when {
                phase == Phase.RECORDING -> {
                    Text(
                        "%d:%02d".format(elapsed / 60_000, (elapsed / 1000) % 60),
                        fontSize = 22.sp, fontFamily = FontFamily.Monospace, color = RecRed,
                        modifier = Modifier.fillMaxWidth().padding(top = 20.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Row(
                        Modifier.fillMaxWidth().height(36.dp).padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically
                    ) {
                        levels.forEach { l -> Box(Modifier.width(5.dp).height((6 + 28 * l).dp).clip(RoundedCornerShape(3.dp)).background(RecRed)) }
                    }
                    if (locked) {
                        Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("✕ Cancel", color = Muted, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier.weight(1f).clip(CircleShape).border(1.dp, Outline, CircleShape)
                                    .clickable { finish(false) }.padding(vertical = 14.dp))
                            Text("↑ Send to ${worker.label}", color = OnPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier.weight(1f).clip(CircleShape).background(Primary)
                                    .clickable { finish(true) }.padding(vertical = 14.dp))
                        }
                        Text(
                            "🔒 Locked · keep talking. Sends on its own at 10:00.",
                            fontSize = 12.5.sp, color = Muted,
                            modifier = Modifier.fillMaxWidth().padding(top = 10.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    } else Text(
                        when {
                            cancelling -> "Let go to cancel"
                            locking -> "Let go to lock"
                            else -> "Let go to send · Slide up to lock\nSlide sideways to cancel"
                        },
                        fontSize = 13.sp, color = if (locking) Primary else Muted, lineHeight = 19.sp,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
                !fresh -> Text(
                    if (ready) "Ask anything, or ramble and Jarvis sorts it.\nHold to talk, or type below."
                    else "Add the Jarvis address and token in Settings first.",
                    fontSize = 13.sp, color = Muted, lineHeight = 19.sp,
                    modifier = Modifier.fillMaxWidth().padding(top = 26.dp).let { if (ready) it else it.clickable(onClick = onOpenSettings) },
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
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

            if (phase != Phase.RECORDING) last?.let { (heard, reply, at) ->
                Column(Modifier.padding(top = if (fresh) 20.dp else 36.dp, start = 6.dp, end = 6.dp)) {
                    Text(
                        (if (fresh) (if (wasTyped) "You typed" else "Heard") else "Last time · " + hhmm(at)).uppercase(Locale.UK),
                        fontSize = 10.5.sp, letterSpacing = 1.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF5C6478)
                    )
                    if (heard.isNotBlank()) Text("“$heard”", fontSize = 12.5.sp, color = Muted, lineHeight = 18.sp, modifier = Modifier.padding(top = 3.dp))
                }
                Column(
                    Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(18.dp)).background(Surface1)
                        .border(1.dp, Outline, RoundedCornerShape(18.dp)).padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    if (fresh) Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(worker.label.uppercase(Locale.UK), fontSize = 10.5.sp, letterSpacing = 1.sp, fontFamily = FontFamily.Monospace, color = Primary)
                        Text(hhmm(at), fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, color = Muted)
                    }
                    Text(reply, fontSize = if (fresh) 15.5.sp else 14.sp, lineHeight = if (fresh) 23.sp else 20.sp, color = if (fresh) TextMain else Muted)
                    if (fresh && hasAudio) Row(
                        Modifier.fillMaxWidth().padding(top = 12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            Modifier.size(32.dp).clip(CircleShape).background(PrimaryTint).clickable {
                                val p = player[0] ?: return@clickable
                                if (playing) { p.pause(); playing = false }
                                else { if (pos >= 1f) p.seekTo(0); p.start(); setSpeed(p, speed); playing = true }
                            },
                            contentAlignment = Alignment.Center
                        ) { Text(if (playing) "❚❚" else "▶", fontSize = 12.sp, color = Primary) }
                        Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Surface2)) {
                            Box(Modifier.fillMaxWidth(pos.coerceIn(0f, 1f)).height(4.dp).background(Primary))
                        }
                        Text("${mmss((durMs * pos).toLong())} / ${mmss(durMs)}", fontSize = 12.5.sp, color = Muted)
                        Text(
                            Jarvis.speedLabel(speed), fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, color = Primary,
                            modifier = Modifier.clip(CircleShape).background(PrimaryTint).clickable {
                                speed = Jarvis.nextSpeed(speed); prefs.jarvisSpeed = speed
                                player[0]?.let { if (playing) setSpeed(it, speed) }
                            }.padding(horizontal = 9.dp, vertical = 3.dp)
                        )
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
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Surface1).border(1.dp, Outline, RoundedCornerShape(24.dp))
                    .padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("⌨️", fontSize = 15.sp)
                BasicTextField(
                    typed, { typed = it; prefs.setDraft("capture", it) },
                    modifier = Modifier.weight(1f).padding(vertical = 10.dp),
                    textStyle = TextStyle(color = TextMain, fontSize = 15.sp),
                    cursorBrush = SolidColor(Primary), maxLines = 5,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { sendText(typed) }),
                    decorationBox = { field ->
                        if (typed.isEmpty()) Text("Type to ${worker.label}…", color = Muted, fontSize = 15.sp)
                        field()
                    }
                )
                val canSend = typed.isNotBlank() && phase == Phase.IDLE
                Box(
                    Modifier.size(36.dp).clip(CircleShape).background(if (canSend) Primary else Surface2)
                        .clickable(enabled = canSend) { sendText(typed) },
                    contentAlignment = Alignment.Center
                ) { Text("↑", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = if (canSend) OnPrimary else Muted) }
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
                        Modifier.fillMaxWidth().clickable { worker = w; prefs.jarvisWorker = w.id; picking = false }.padding(vertical = 10.dp),
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
                            selected = w.id == worker.id, onClick = { worker = w; prefs.jarvisWorker = w.id; picking = false },
                            colors = RadioButtonDefaults.colors(selectedColor = Primary, unselectedColor = Muted)
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text("Remembers your last pick.", fontSize = 12.5.sp, color = Muted)
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
