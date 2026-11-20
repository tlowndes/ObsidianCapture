package uk.tomlowndes.capture

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.Locale

/** The Claude key when Claude from the phone is on, else null. */
internal fun claudeKey(ctx: Context, prefs: AppPrefs): String? =
    SecretStore(ctx).claudeKey?.takeIf { prefs.phoneBrain && it.isNotBlank() }

/** One Claude call that counts toward the month's spend. Blocking. */
internal fun ask(ctx: Context, prefs: AppPrefs, user: String, system: String, clever: Boolean = false, maxTokens: Long = 2000L): String {
    val key = claudeKey(ctx, prefs) ?: error("Turn on Claude from the phone in Settings")
    val model = PhoneBrain.model(clever)
    val d = PhoneBrain.complete(key, user, system, model = model, maxTokens = maxTokens, seconds = if (clever) 120 else PhoneBrain.CHAT_TIMEOUT_S)
    prefs.spend("Helpers", PhoneBrain.costUsd(model, d.inTok, d.outTok))
    return d.reply
}

internal fun copyText(ctx: Context, label: String, text: String) {
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, text))
}

@Composable
internal fun SheetButton(text: String, modifier: Modifier = Modifier, primary: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        text, fontSize = 13.sp, fontWeight = if (primary) FontWeight.Bold else FontWeight.Normal, textAlign = TextAlign.Center,
        color = if (primary) OnPrimary else if (enabled) TextMain else Muted,
        modifier = modifier.clip(CircleShape)
            .then(if (primary) Modifier.background(if (enabled) Primary else Primary.copy(alpha = 0.4f)) else Modifier.border(1.dp, Outline, CircleShape))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp)
    )
}

@Composable
private fun Thinking(text: String) {
    Row(Modifier.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CircularProgressIndicator(color = Primary, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
        Text(text, fontSize = 13.sp, color = Muted)
    }
}

// ---- Talk me through it (wellbeing-v1) ----

/** The nudge on the Deck when a task has waited 3+ days. */
@Composable
internal fun StuckNudge(t: TaskItem, days: Long, onTalk: () -> Unit, onNotToday: () -> Unit) {
    DeckCard(border = Purple.copy(alpha = 0.5f), modifier = Modifier.padding(top = 12.dp)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text("🧩 \"${TaskViews.plainText(t)}\" has waited $days days", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text("Want to talk it through? 2 minutes.", fontSize = 12.5.sp, color = Muted, modifier = Modifier.padding(top = 2.dp))
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SheetButton("Talk it through", primary = true, onClick = onTalk)
                SheetButton("Not today", onClick = onNotToday)
            }
        }
    }
}

/** The short chat. It ends by adding the steps under the task; the first one becomes the Next step. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TalkSheet(t: TaskItem, vault: Uri?, prefs: AppPrefs, snack: SnackbarHostState, onFocus: (TaskItem, Int) -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val who = prefs.assistantName
    val name = TaskViews.plainText(t)
    val days = TalkThrough.waited(t, LocalDate.now()) ?: TalkThrough.STUCK_DAYS
    val turns = remember { mutableStateListOf(PhoneBrain.Turn(false, TalkThrough.opener(name))) }
    var draft by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var steps by remember { mutableStateOf<List<Step>?>(null) }
    var saved by remember { mutableStateOf(false) }
    val key = claudeKey(ctx, prefs)

    fun send() {
        val text = draft.trim()
        if (text.isEmpty() || busy || key == null) return
        draft = ""; turns.add(PhoneBrain.Turn(true, text)); busy = true; error = null
        scope.launch {
            // The opener is ours, so Claude sees it as context in the first user turn.
            val sent = listOf(PhoneBrain.Turn(true, "(You asked: ${TalkThrough.opener(name)})")) + turns.drop(1)
            val r = withContext(Dispatchers.IO) {
                runCatching { PhoneBrain.converse(key, TalkThrough.system(name, days, who), sent).also { prefs.spend("Helpers", PhoneBrain.costUsd(PhoneBrain.FAST, it.inTok, it.outTok)) } }
            }
            busy = false
            r.onSuccess { d -> turns.add(PhoneBrain.Turn(false, TalkThrough.visible(d.reply))); TalkThrough.steps(d.reply)?.let { steps = it } }
            r.onFailure { error = PhoneBrain.failure(who, it, viaServer = false) }
        }
    }

    ModalBottomSheet(onDismissRequest = onDone, sheetState = sheet, containerColor = Surface1) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            Text("Talk it through", fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Text(name, fontSize = 13.sp, color = Muted, modifier = Modifier.padding(bottom = 10.dp))
            turns.forEach { turn ->
                Box(Modifier.fillMaxWidth().padding(vertical = 3.dp), contentAlignment = if (turn.mine) Alignment.CenterEnd else Alignment.CenterStart) {
                    Text(turn.text, fontSize = 14.sp, color = if (turn.mine) OnPrimary else TextMain,
                        modifier = Modifier.widthIn(max = 290.dp).clip(RoundedCornerShape(14.dp)).background(if (turn.mine) Primary else Surface2)
                            .padding(horizontal = 12.dp, vertical = 8.dp))
                }
            }
            if (busy) Thinking("$who is thinking…")
            error?.let { Text(it, fontSize = 13.sp, color = Warn, modifier = Modifier.padding(vertical = 8.dp)) }
            if (key == null) Text("Turn on Claude from the phone in Settings to use this.", fontSize = 13.sp, color = Muted, modifier = Modifier.padding(vertical = 8.dp))
            val s = steps
            if (s != null) {
                ListCard(Modifier.padding(top = 10.dp)) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(if (saved) "Steps added to $name" else "Steps for $name", fontSize = 12.sp, color = Muted)
                        s.forEachIndexed { i, st ->
                            Text((if (i == 0) "● " else "○ ") + st.text + (st.minutes?.let { " (${it}m)" } ?: "") + (if (i == 0) " · Next step" else ""),
                                fontSize = 13.5.sp, color = if (i == 0) Primary else TextMain)
                        }
                    }
                }
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!saved) SheetButton("Add steps", Modifier.weight(1f), primary = true) {
                        val uri = vault ?: return@SheetButton
                        scope.launch {
                            val r = withContext(Dispatchers.IO) { runCatching { VaultWriter(ctx, uri).addSubtasks(t, s) } }
                            if (r.isFailure) error = "Failed: ${r.exceptionOrNull()?.message}" else saved = true
                        }
                    } else {
                        SheetButton("Start ${s.first().minutes ?: 5}m focus", Modifier.weight(1f), primary = true) {
                            onFocus(TaskItem("", s.first().text, null, null, t.path), s.first().minutes ?: 5); onDone()
                        }
                        SheetButton("Done", Modifier.weight(1f), onClick = onDone)
                    }
                }
            } else if (key != null) Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(draft, { draft = it }, Modifier.weight(1f), colors = fieldColors(), placeholder = { Text("Type a reply") })
                SheetButton("Send", primary = true, enabled = draft.isNotBlank() && !busy, onClick = ::send)
            }
        }
    }
}

// ---- Job hunt co-pilot (job-copilot-v1) ----

/** Save job: Claude reads the ad and scores the fit; Save only, or Save + draft letter. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun JobSaveSheet(url: String, vault: Uri?, prefs: AppPrefs, snack: SnackbarHostState, onDone: (saved: String?) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var ad by remember { mutableStateOf<JobAd?>(null) }
    var page by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(url) {
        val uri = vault ?: return@LaunchedEffect
        val r = withContext(Dispatchers.IO) {
            runCatching {
                val text = Recipes.fetch(url).second
                page = text
                val cv = JobCopilot.cvText(appVault(ctx, uri))
                JobCopilot.parse(ask(ctx, prefs, "Job ad at $url:\n\n$text", JobCopilot.readSystem(LocalDate.now(), cv)), url)
                    ?: error("Couldn't read a job from that page")
            }
        }
        r.onSuccess { ad = it }.onFailure { error = it.message }
    }

    fun save(letter: Boolean) {
        val a = ad ?: return
        val uri = vault ?: return
        saving = true
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val v = appVault(ctx, uri)
                    val path = JobCopilot.save(v, a, page)
                    if (letter) {
                        val text = ask(ctx, prefs, JobCopilot.letterPrompt(VaultIo.read(v, path), JobCopilot.cvText(v), null, JobCopilot.Tone.FIRST), JobCopilot.letterSystem(), clever = true)
                        JobCopilot.saveLetter(v, path, text, 1)
                    }
                    path
                }
            }
            saving = false
            r.onSuccess { onDone(it) }.onFailure { error = "Failed: ${it.message}" }
        }
    }

    ModalBottomSheet(onDismissRequest = { onDone(null) }, sheetState = sheet, containerColor = Surface1) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            Text("💼 Save job", fontSize = 19.sp, fontWeight = FontWeight.Bold)
            val a = ad
            when {
                error != null -> Text(error!!, fontSize = 13.sp, color = Warn, modifier = Modifier.padding(vertical = 10.dp))
                a == null -> Thinking("Reading the ad…")
                else -> {
                    ListCard(Modifier.padding(top = 10.dp)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf("Role" to a.role, "Company" to a.company, "Where" to a.where, "Salary" to a.salary,
                                "Closes" to a.closes?.let { shortDate(it) }).forEach { (k, v) ->
                                if (!v.isNullOrBlank()) Row { Text(k, fontSize = 12.sp, color = Muted, modifier = Modifier.width(76.dp)); Text(v, fontSize = 13.5.sp) }
                            }
                        }
                    }
                    a.fit?.let { f -> FitBlock(f, a.good, a.gaps) }
                    Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SheetButton("Save only", Modifier.weight(1f), enabled = !saving) { save(false) }
                        SheetButton(if (saving) "Saving…" else "Save + draft letter", Modifier.weight(1f), primary = true, enabled = !saving) { save(true) }
                    }
                }
            }
        }
    }
}

@Composable
private fun FitBlock(fit: Int, good: List<String>, gaps: List<String>) {
    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("$fit", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = fitColour(fit))
        Text(if (fit >= 80) "Strong fit" else if (fit >= 60) "Good fit" else "A stretch", fontSize = 13.sp, color = Muted)
    }
    good.forEach { Text("✓ $it", fontSize = 13.sp, color = Green, modifier = Modifier.padding(top = 2.dp)) }
    gaps.forEach { Text("△ $it", fontSize = 13.sp, color = Amber, modifier = Modifier.padding(top = 2.dp)) }
}

internal fun fitColour(fit: Int): Color = if (fit >= 80) Green else if (fit >= 60) Primary else Amber

/** The cover letter for a job: draft it, Shorter / Warmer redraft, Copy. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LetterSheet(job: Job, vault: Uri?, prefs: AppPrefs, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var letter by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(job.path) {
        val uri = vault ?: return@LaunchedEffect
        withContext(Dispatchers.IO) { runCatching { appVault(ctx, uri).let { JobCopilot.letter(it, job.path) to JobCopilot.draftNumber(it, job.path) } }.getOrNull() }
            ?.let { (l, n) -> letter = l; draft = n }
    }
    fun redo(tone: JobCopilot.Tone) {
        val uri = vault ?: return
        busy = true; error = null
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val v = appVault(ctx, uri)
                    val text = ask(ctx, prefs, JobCopilot.letterPrompt(VaultIo.read(v, job.path), JobCopilot.cvText(v), letter, tone), JobCopilot.letterSystem(), clever = true)
                    JobCopilot.saveLetter(v, job.path, text, draft + 1); text
                }
            }
            busy = false
            r.onSuccess { letter = it; draft++ }.onFailure { error = "Failed: ${it.message}" }
        }
    }
    ModalBottomSheet(onDismissRequest = onDone, sheetState = sheet, containerColor = Surface1) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            Text("Cover letter", fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Text(listOf(job.role, job.company).filter { it.isNotBlank() }.joinToString(" · ") + (if (draft > 0) " · draft $draft" else ""), fontSize = 13.sp, color = Muted)
            val l = letter
            if (busy) Thinking("Writing…")
            error?.let { Text(it, fontSize = 13.sp, color = Warn, modifier = Modifier.padding(vertical = 8.dp)) }
            if (l != null) Text(l, fontSize = 14.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 10.dp))
            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (l == null) SheetButton("Draft letter", Modifier.weight(1f), primary = true, enabled = !busy) { redo(JobCopilot.Tone.FIRST) }
                else {
                    SheetButton("Copy", primary = true) { copyText(ctx, "Cover letter", l) }
                    SheetButton("Shorter", enabled = !busy) { redo(JobCopilot.Tone.SHORTER) }
                    SheetButton("Warmer", enabled = !busy) { redo(JobCopilot.Tone.WARMER) }
                }
            }
        }
    }
}

// ---- Portfolio case study (growth-v1) ----

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CaseStudySheet(title: String, vault: Uri?, prefs: AppPrefs, snack: SnackbarHostState, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var draft by remember { mutableStateOf<String?>(null) }
    var from by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var savedAt by remember { mutableStateOf<String?>(null) }
    fun write(tone: CaseStudy.Tone) {
        val uri = vault ?: return
        busy = true; error = null
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val store = IdeaStore(appVault(ctx, uri), prefs)
                    val d = store.load(title)
                    from = "${d.entries.size} Log entries · ${d.entries.sumOf { it.images.size }} images · your Summary"
                    val prompt = CaseStudy.prompt(d, store.noteText(title)) + (draft?.takeIf { tone != CaseStudy.Tone.FIRST }?.let { "\n\n" + CaseStudy.redo(it, tone) } ?: "")
                    ask(ctx, prefs, prompt, CaseStudy.SYSTEM, clever = true, maxTokens = 4000L)
                }
            }
            busy = false
            r.onSuccess { draft = it; savedAt = null }.onFailure { error = "Failed: ${it.message}" }
        }
    }
    LaunchedEffect(title) { write(CaseStudy.Tone.FIRST) }
    ModalBottomSheet(onDismissRequest = onDone, sheetState = sheet, containerColor = Surface1) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            Text("✨ Case study", fontSize = 19.sp, fontWeight = FontWeight.Bold)
            if (from.isNotEmpty()) Text("Built from $from", fontSize = 12.sp, color = Muted)
            if (busy) Thinking("Writing the draft… (about 30 seconds)")
            error?.let { Text(it, fontSize = 13.sp, color = Warn, modifier = Modifier.padding(vertical = 8.dp)) }
            draft?.let { d ->
                Text("Draft · ${"%,d".format(Locale.UK, CaseStudy.words(d))} words", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Muted, modifier = Modifier.padding(top = 8.dp))
                Text(d, fontSize = 14.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 6.dp))
                Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SheetButton("Shorter", enabled = !busy) { write(CaseStudy.Tone.SHORTER) }
                    SheetButton("For a recruiter", enabled = !busy) { write(CaseStudy.Tone.RECRUITER) }
                    SheetButton("Copy") { copyText(ctx, "Case study", d) }
                }
                SheetButton(savedAt?.let { "Saved ✓" } ?: "Save to Portfolio", Modifier.fillMaxWidth().padding(top = 8.dp), primary = true, enabled = savedAt == null && !busy) {
                    val uri = vault ?: return@SheetButton
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { runCatching { CaseStudy.save(appVault(ctx, uri), title, d) } }
                        r.onSuccess { savedAt = it; snack.showSnackbar("Saved to ${it.substringBeforeLast('/')}") }.onFailure { error = "Failed: ${it.message}" }
                    }
                }
            }
        }
    }
}

// ---- Morning audio briefing (growth-v1) ----

/**
 * ▶ Your briefing: Claude writes today's script once (shared with the PC through the vault), the phone voice reads
 * it. Tap a line to jump to it. Once it has been played to the end it hides till tomorrow (on the PC too).
 * [hasWeather]: the forecast has loaded, so a script written without it is written again.
 */
@Composable
internal fun BriefingCard(vault: Uri?, prefs: AppPrefs, hasWeather: Boolean, facts: () -> String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val today = LocalDate.now()
    var lines by remember { mutableStateOf<List<String>?>(null) }
    var playing by remember { mutableIntStateOf(-1) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    var heard by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        var t: TextToSpeech? = null
        t = TextToSpeech(ctx.applicationContext) { ok ->
            if (ok == TextToSpeech.SUCCESS) { t?.language = Locale.UK; tts = t }
        }
        onDispose { t?.stop(); t?.shutdown() }
    }
    LaunchedEffect(vault, hasWeather) {
        val uri = vault ?: return@LaunchedEffect
        val r = withContext(Dispatchers.IO) {
            runCatching { appVault(ctx, uri).let { v -> Briefing.heard(v, today) to Briefing.cached(v, today, needWeather = hasWeather) } }.getOrNull()
        } ?: return@LaunchedEffect
        heard = r.first; lines = r.second
    }
    fun finished() {
        val uri = vault ?: return
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { Briefing.markHeard(appVault(ctx, uri), today) } }
            heard = true
        }
    }
    fun speakFrom(i: Int) {
        val t = tts ?: return
        val ls = lines ?: return
        t.stop()
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) { playing = id?.toIntOrNull() ?: -1 }
            override fun onDone(id: String?) { if (id?.toIntOrNull() == ls.lastIndex) { playing = -1; finished() } }
            @Deprecated("old API") override fun onError(id: String?) { playing = -1 }
        })
        ls.drop(i).forEachIndexed { n, l -> t.speak(l, TextToSpeech.QUEUE_ADD, null, "${i + n}") }
        playing = i
    }
    fun play() {
        if (lines != null) { speakFrom(0); return }
        val uri = vault ?: return
        busy = true; error = null
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { Briefing.lines(ask(ctx, prefs, facts(), Briefing.system(prefs.assistantName))).also { Briefing.save(appVault(ctx, uri), today, it, weather = hasWeather) } }
            }
            busy = false
            r.onSuccess { lines = it; speakFrom(0) }.onFailure { error = PhoneBrain.failure(prefs.assistantName, it, viaServer = false) }
        }
    }
    if (heard) return
    DeckCard(modifier = Modifier.padding(top = 12.dp)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(36.dp).clip(CircleShape).background(Primary).clickable(enabled = !busy) {
                    if (playing >= 0) { tts?.stop(); playing = -1 } else play()
                }, contentAlignment = Alignment.Center) { Text(if (playing >= 0) "❚❚" else "▶", color = OnPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
                Text("Your briefing", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                lines?.let { Text(Briefing.length(it), fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Muted) }
            }
            if (busy) Thinking("Writing today's briefing…")
            error?.let { Text(it, fontSize = 12.5.sp, color = Warn, modifier = Modifier.padding(top = 6.dp)) }
            lines?.forEachIndexed { i, l ->
                Text((if (i < playing) "✓ " else if (i == playing) "▸ " else "○ ") + l, fontSize = 13.sp, maxLines = if (i == playing) 4 else 1,
                    color = if (i == playing) Primary else if (i < playing) Muted else TextMain,
                    modifier = Modifier.fillMaxWidth().clickable { speakFrom(i) }.padding(top = 6.dp))
            }
            if (lines == null && !busy && error == null) Text("A 2-minute spoken rundown of your day.", fontSize = 12.5.sp, color = Muted, modifier = Modifier.padding(top = 6.dp))
        }
    }
    Box(Modifier.height(0.dp))
}
