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
fun JarvisScreen(prefs: AppPrefs, snack: SnackbarHostState, onOpenSettings: () -> Unit) {
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
    var startedAt by remember { mutableLongStateOf(0L) }
    var elapsed by remember { mutableLongStateOf(0L) }
    val levels = remember { mutableStateListOf<Float>() }
    var last by remember { mutableStateOf(prefs.jarvisLast) }
    var fresh by remember { mutableStateOf(false) }
    /** The recording that failed to send, kept for Retry. */
    var pending by remember { mutableStateOf<File?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf<Jarvis.Done?>(null) }
    var openDone by remember { mutableStateOf<Jarvis.Done?>(null) }

    // Reply audio.
    val player = remember { arrayOfNulls<MediaPlayer>(1) }
    var playing by remember { mutableStateOf(false) }
    var hasAudio by remember { mutableStateOf(false) }
    var pos by remember { mutableFloatStateOf(0f) }
    var durMs by remember { mutableLongStateOf(0L) }
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
            if (start) p.start()
            player[0] = p; hasAudio = true; playing = start; pos = 0f
        }.onFailure { p.release(); hasAudio = false }
    }

    fun markDone(list: List<Jarvis.Done>) {
        val seen = prefs.jarvisSeenDone
        list.firstOrNull { it.id !in seen }?.let { d -> done = d; prefs.jarvisSeenDone = seen + d.id }
    }

    fun send(f: File) {
        phase = Phase.SENDING; error = null
        val w = worker
        val seconds = elapsed / 1000.0
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (demo) {
                        Jarvis.demoReply(w.id, seconds) to f
                    } else {
                        val r = Jarvis.send(address, token, f, w.id)
                        val audio = r.audioUrl?.let { u ->
                            File(ctx.cacheDir, "jarvis-reply.ogg").also { out -> Jarvis.fetchAudio(address, token, u, out) }
                        }
                        r to audio
                    }
                }
            }
            phase = Phase.IDLE
            result.onSuccess { (r, audio) ->
                if (!demo) f.delete()
                pending = null
                val now = System.currentTimeMillis()
                last = Triple(r.transcript, r.reply, now); prefs.jarvisLast = last
                fresh = true
                markDone(r.done)
                if (demo && w.id == "research") markDone(listOf(Jarvis.Done("demo-$now", "Research finished (demo)", "This is what a finished background job looks like.")))
                if (audio != null) { play(audio, prefs.jarvisSpeak) } else { player[0]?.release(); player[0] = null; hasAudio = false }
            }.onFailure { e ->
                pending = f
                error = when {
                    e is Jarvis.HttpError && (e.code == 401 || e.code == 403) -> "Jarvis refused the token. Check it in Settings."
                    e is Jarvis.HttpError -> e.message ?: "Jarvis returned an error."
                    else -> "Can't reach Jarvis. Is Tailscale on?"
                }
            }
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
            if (ok) { phase = Phase.RECORDING; cancelling = false; startedAt = System.currentTimeMillis(); elapsed = 0; levels.clear() }
        }
    }

    fun finish(keep: Boolean) {
        if (phase != Phase.RECORDING) return
        elapsed = System.currentTimeMillis() - startedAt
        phase = Phase.IDLE
        // Short taps are ignored, so a pocket press doesn't send anything.
        if (!keep || elapsed < 500) { recorder.cancel(); return }
        val f = recorder.stop()
        if (f == null) scope.launch { snack.showSnackbar("Nothing was recorded") } else send(f)
    }

    LaunchedEffect(phase) {
        while (phase == Phase.RECORDING) {
            elapsed = System.currentTimeMillis() - startedAt
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

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp).verticalScroll(rememberScrollState()).padding(bottom = 90.dp)) {
            ScreenTitle("Jarvis") {
                Row(
                    Modifier.clip(CircleShape).background(PrimaryTint).border(1.dp, NextBorder, CircleShape)
                        .clickable { picking = true }.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(worker.icon, fontSize = 13.sp)
                    Text("${worker.label} ▾", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Primary)
                }
            }

            val small = fresh && phase == Phase.IDLE
            val micSize by animateDpAsState(if (small) 72.dp else 170.dp, label = "mic")
            val rec = phase == Phase.RECORDING
            Box(Modifier.fillMaxWidth().padding(top = if (small) 12.dp else 34.dp), contentAlignment = Alignment.Center) {
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
                                        rec -> RecRed
                                        phase == Phase.SENDING -> Surface2
                                        else -> Primary
                                    }
                                )
                                .pointerInput(Unit) {
                                    awaitEachGesture {
                                        val down = awaitFirstDown()
                                        down.consume()
                                        if (!onBegin()) return@awaitEachGesture
                                        val slop = 60f
                                        var inside = true
                                        while (true) {
                                            val ev = awaitPointerEvent()
                                            val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                                            val p = c.position
                                            inside = p.x > -slop && p.y > -slop && p.x < size.width + slop && p.y < size.height + slop
                                            cancelling = !inside
                                            if (!c.pressed) { c.consume(); break }
                                        }
                                        onFinish(inside)
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
                    Text(
                        if (cancelling) "Let go to cancel" else "Let go to send to ${worker.label}\nSlide away to cancel",
                        fontSize = 13.sp, color = Muted, lineHeight = 19.sp,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
                !fresh -> Text(
                    if (ready) "Hold to record, let go to send.\nSlide off the button to cancel."
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
                    Text("Your recording is kept.", fontSize = 13.sp, color = Muted)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { pending?.let { send(it) } }, enabled = pending != null && phase == Phase.IDLE) { Text("Retry", color = Primary) }
                        TextButton(onClick = { pending?.delete(); pending = null; error = null }) { Text("Discard", color = Muted) }
                    }
                }
            }

            if (phase != Phase.RECORDING) last?.let { (heard, reply, at) ->
                Column(Modifier.padding(top = if (fresh) 20.dp else 36.dp, start = 6.dp, end = 6.dp)) {
                    Text(
                        (if (fresh) "Heard" else "Last time · " + hhmm(at)).uppercase(Locale.UK),
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
                                else { if (pos >= 1f) p.seekTo(0); p.start(); playing = true }
                            },
                            contentAlignment = Alignment.Center
                        ) { Text(if (playing) "❚❚" else "▶", fontSize = 12.sp, color = Primary) }
                        Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Surface2)) {
                            Box(Modifier.fillMaxWidth(pos.coerceIn(0f, 1f)).height(4.dp).background(Primary))
                        }
                        Text("${mmss((durMs * pos).toLong())} / ${mmss(durMs)}", fontSize = 12.5.sp, color = Muted)
                    }
                }
            }
            if (demo) Text("Demo mode: no server, replies are canned.", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 14.dp))
        }

        done?.let { d ->
            Row(
                Modifier.align(Alignment.BottomCenter).padding(horizontal = 18.dp, vertical = 12.dp).fillMaxWidth()
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
    }

    openDone?.let { d ->
        AlertDialog(
            onDismissRequest = { openDone = null }, containerColor = Surface1,
            title = { Text(d.title) },
            text = { Text(d.detail.ifBlank { "Finished." }, color = TextMain) },
            confirmButton = { TextButton(onClick = { openDone = null; done = null }) { Text("Done", color = Primary) } }
        )
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

private fun hhmm(at: Long): String =
    Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm", Locale.UK))

private fun mmss(ms: Long): String = "%d:%02d".format(ms / 60_000, (ms / 1000) % 60)
