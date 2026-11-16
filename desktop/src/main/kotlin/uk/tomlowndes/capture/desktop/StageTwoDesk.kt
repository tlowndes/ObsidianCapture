package uk.tomlowndes.capture.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.tomlowndes.capture.Briefing
import uk.tomlowndes.capture.CaseStudy
import uk.tomlowndes.capture.IdeaDoc
import uk.tomlowndes.capture.JobAd
import uk.tomlowndes.capture.JobCopilot
import uk.tomlowndes.capture.PhoneBrain
import uk.tomlowndes.capture.Recipes
import uk.tomlowndes.capture.Step
import uk.tomlowndes.capture.TalkThrough
import uk.tomlowndes.capture.TaskItem
import uk.tomlowndes.capture.TaskViews
import uk.tomlowndes.capture.VaultIo
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.time.LocalDate
import java.util.Locale

/** True when the PC has a Claude key for the Claude helpers. */
internal fun hasClaude(state: AppState) = !state.prefs.claudeKey.isNullOrBlank()

/** One Claude call from the PC, counted in the month's spend. Blocking. */
internal fun deskAsk(state: AppState, user: String, system: String, clever: Boolean = false, maxTokens: Long = 2000L): String {
    val key = state.prefs.claudeKey?.takeIf { it.isNotBlank() } ?: error("Add the Claude key in Settings")
    val model = PhoneBrain.model(clever)
    val d = PhoneBrain.complete(key, user, system, model = model, maxTokens = maxTokens, seconds = if (clever) 120 else PhoneBrain.CHAT_TIMEOUT_S)
    state.prefs.spend("Helpers", PhoneBrain.costUsd(model, d.inTok, d.outTok))
    return d.reply
}

internal fun copyToClipboard(text: String) = runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }

@Composable
internal fun Working(text: String) = Text(text, fontSize = 13.sp, color = Muted, modifier = Modifier.padding(vertical = 10.dp))

/** A wide dialog for the Claude helpers. */
@Composable
internal fun WideDialog(heading: String, sub: String?, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        CaptureTheme {
            val shape = RoundedCornerShape(18.dp)
            Column(Modifier.width(760.dp).heightIn(max = 720.dp).clip(shape).background(Popup).border(1.dp, Outline, shape).padding(22.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(heading, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextMain, modifier = Modifier.weight(1f))
                    TextAction("Close", Muted, onClick = onDismiss)
                }
                sub?.let { Text(it, fontSize = 12.sp, color = Muted, modifier = Modifier.padding(bottom = 10.dp)) }
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) { content() }
            }
        }
    }
}

// ---- Talk me through it (wellbeing-v1) ----

@Composable
internal fun StuckNudgeDesk(t: TaskItem, days: Long, onTalk: () -> Unit, onNotToday: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier.fillMaxWidth().padding(bottom = 14.dp).clip(shape).background(Surface1).border(1.dp, Purple.copy(alpha = .5f), shape)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text("🧩 \"${TaskViews.plainText(t)}\" has waited $days days", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextMain)
            Text("Want to talk it through? 2 minutes.", fontSize = 12.sp, color = Muted)
        }
        PrimaryButton("Talk it through", onClick = onTalk)
        TextAction("Not today", Muted, onClick = onNotToday)
    }
}

@Composable
internal fun TalkDialog(state: AppState, t: TaskItem, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val who = state.prefs.assistantName
    val name = TaskViews.plainText(t)
    val days = TalkThrough.waited(t, LocalDate.now()) ?: TalkThrough.STUCK_DAYS
    val turns = remember { mutableStateListOf(PhoneBrain.Turn(false, TalkThrough.opener(name))) }
    var draft by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var steps by remember { mutableStateOf<List<Step>?>(null) }
    var saved by remember { mutableStateOf(false) }
    fun send() {
        val text = draft.trim()
        if (text.isEmpty() || busy) return
        draft = ""; turns.add(PhoneBrain.Turn(true, text)); busy = true
        scope.launch {
            val sent = listOf(PhoneBrain.Turn(true, "(You asked: ${TalkThrough.opener(name)})")) + turns.drop(1)
            val d = state.io("Couldn't reach $who") {
                PhoneBrain.converse(state.prefs.claudeKey!!, TalkThrough.system(name, days, who), sent).also {
                    state.prefs.spend("Helpers", PhoneBrain.costUsd(PhoneBrain.FAST, it.inTok, it.outTok))
                }
            }
            busy = false
            d?.let { turns.add(PhoneBrain.Turn(false, TalkThrough.visible(it.reply))); TalkThrough.steps(it.reply)?.let { s -> steps = s } }
        }
    }
    WideDialog("Talk me through it", name, onDone) {
        turns.forEach { turn ->
            Box(Modifier.fillMaxWidth().padding(vertical = 3.dp), contentAlignment = if (turn.mine) Alignment.CenterEnd else Alignment.CenterStart) {
                Text(turn.text, fontSize = 14.sp, color = if (turn.mine) OnPrimary else TextMain,
                    modifier = Modifier.widthIn(max = 520.dp).clip(RoundedCornerShape(14.dp)).background(if (turn.mine) Primary else Surface2).padding(horizontal = 12.dp, vertical = 8.dp))
            }
        }
        if (busy) Working("$who is thinking…")
        val s = steps
        if (s != null) {
            Column(Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Surface1).padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(if (saved) "Steps added to $name" else "Steps for $name", fontSize = 12.sp, color = Muted)
                s.forEachIndexed { i, st -> Text((if (i == 0) "● " else "○ ") + st.text + (st.minutes?.let { " (${it}m)" } ?: "") + (if (i == 0) " · Next step" else ""), fontSize = 13.5.sp, color = if (i == 0) Primary else TextMain) }
            }
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!saved) PrimaryButton("Add steps") { scope.launch { state.io("Couldn't add the steps") { state.writer()!!.addSubtasks(t, s) }?.let { saved = true; state.changed() } } }
                else PrimaryButton("Done", onClick = onDone)
            }
        } else Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(draft, { draft = it }, Modifier.weight(1f).onKeys(enter = ::send), "Type a reply, Enter sends")
            PrimaryButton("Send", enabled = draft.isNotBlank() && !busy, onClick = ::send)
        }
    }
}

// ---- Job hunt co-pilot (job-copilot-v1) ----

/** Paste a job link: Claude reads the ad, scores the fit, and it's saved with or without a letter. */
@Composable
internal fun JobSaveDialog(state: AppState, onDone: (String?) -> Unit) {
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(clipboardUrl()) }
    var ad by remember { mutableStateOf<JobAd?>(null) }
    var page by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf<String?>(null) }
    fun read() {
        val v = state.vault ?: return
        val u = url.trim()
        if (!u.startsWith("http")) return
        busy = true; failed = null; ad = null
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val text = Recipes.fetch(u).second
                    page = text
                    JobCopilot.parse(deskAsk(state, "Job ad at $u:\n\n$text", JobCopilot.readSystem(LocalDate.now(), JobCopilot.cvText(v))), u) ?: error("Couldn't read a job from that page")
                }
            }
            busy = false
            r.onSuccess { ad = it }.onFailure { failed = it.message }
        }
    }
    fun save(letter: Boolean) {
        val a = ad ?: return
        val v = state.vault ?: return
        busy = true
        scope.launch {
            val p = state.io("Couldn't save the job") {
                val path = JobCopilot.save(v, a, page)
                if (letter) JobCopilot.saveLetter(v, path, deskAsk(state, JobCopilot.letterPrompt(VaultIo.read(v, path), JobCopilot.cvText(v), null, JobCopilot.Tone.FIRST), JobCopilot.letterSystem(), clever = true), 1)
                path
            }
            busy = false
            if (p != null) { state.changed(); onDone(p) }
        }
    }
    LaunchedEffect(Unit) { if (url.isNotBlank()) read() }
    WideDialog("💼 Save job", "Claude reads the ad and checks it against your Portfolio & CV note", { onDone(null) }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(url, { url = it }, Modifier.weight(1f).onKeys(enter = ::read), "Paste a job link")
            PrimaryButton("Read it", enabled = !busy && url.trim().startsWith("http"), onClick = ::read)
        }
        if (busy) Working("Working…")
        failed?.let { Text(it, color = Warn, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp)) }
        ad?.let { a ->
            Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("Role" to a.role, "Company" to a.company, "Where" to a.where, "Salary" to a.salary, "Closes" to a.closes?.let { dueLabel(it) }).forEach { (k, v) ->
                    if (!v.isNullOrBlank()) Row { Text(k, fontSize = 12.sp, color = Muted, modifier = Modifier.width(90.dp)); Text(v, fontSize = 13.5.sp, color = TextMain) }
                }
                a.fit?.let { f -> Text("$f · " + (if (f >= 80) "Strong fit" else if (f >= 60) "Good fit" else "A stretch"), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = fitColour(f), modifier = Modifier.padding(top = 8.dp)) }
                a.good.forEach { Text("✓ $it", fontSize = 13.sp, color = TaskGreen) }
                a.gaps.forEach { Text("△ $it", fontSize = 13.sp, color = Amber) }
            }
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Chip("Save only") { if (!busy) save(false) }
                PrimaryButton("Save + draft letter", enabled = !busy) { save(true) }
            }
        }
    }
}

internal fun fitColour(f: Int): Color = if (f >= 80) TaskGreen else if (f >= 60) Primary else Amber

private fun clipboardUrl(): String = runCatching {
    (Toolkit.getDefaultToolkit().systemClipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String)?.trim()?.takeIf { isBareUrl(it) }
}.getOrNull().orEmpty()

/** The cover letter beside the Jobs table: Shorter, Warmer, More about research, Copy. */
@Composable
internal fun LetterPanel(state: AppState, job: uk.tomlowndes.capture.Job, modifier: Modifier = Modifier, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var letter by remember(job.path) { mutableStateOf<String?>(null) }
    var draft by remember(job.path) { mutableIntStateOf(0) }
    var busy by remember(job.path) { mutableStateOf(false) }
    LaunchedEffect(job.path) {
        val v = state.vault ?: return@LaunchedEffect
        state.io { JobCopilot.letter(v, job.path) to JobCopilot.draftNumber(v, job.path) }?.let { (l, n) -> letter = l; draft = n }
    }
    fun redo(tone: JobCopilot.Tone) {
        val v = state.vault ?: return
        busy = true
        scope.launch {
            val t = state.io("Couldn't write the letter") {
                deskAsk(state, JobCopilot.letterPrompt(VaultIo.read(v, job.path), JobCopilot.cvText(v), letter, tone), JobCopilot.letterSystem(), clever = true)
                    .also { JobCopilot.saveLetter(v, job.path, it, draft + 1) }
            }
            busy = false
            if (t != null) { letter = t; draft++ }
        }
    }
    val shape = RoundedCornerShape(16.dp)
    Column(modifier.clip(shape).background(Surface1).border(1.dp, Outline, shape).padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Cover letter", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextMain, modifier = Modifier.weight(1f))
            TextAction("✕", Muted, onClick = onClose)
        }
        Text(listOf(job.role, job.company).filter { it.isNotBlank() }.joinToString(" · ") + (if (draft > 0) " · draft $draft" else ""), fontSize = 12.sp, color = Muted)
        if (busy) Working("Writing…")
        val l = letter
        if (l != null) Text(l, fontSize = 13.5.sp, lineHeight = 21.sp, color = TextMain, modifier = Modifier.padding(top = 10.dp))
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (l == null) PrimaryButton("Draft letter", enabled = !busy) { redo(JobCopilot.Tone.FIRST) }
            else {
                Chip("Shorter", small = true) { if (!busy) redo(JobCopilot.Tone.SHORTER) }
                Chip("Warmer", small = true) { if (!busy) redo(JobCopilot.Tone.WARMER) }
                Chip("More about research", small = true) { if (!busy) redo(JobCopilot.Tone.RESEARCH) }
                Chip("Copy", small = true) { copyToClipboard(l); state.say("Copied the letter") }
            }
        }
    }
}

// ---- Portfolio case study (growth-v1) ----

@Composable
internal fun CaseStudyDialog(state: AppState, title: String, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf<String?>(null) }
    var from by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    fun write(tone: CaseStudy.Tone) {
        val store = state.ideaStore() ?: return
        busy = true
        scope.launch {
            val t = state.io("Couldn't write the case study") {
                val d: IdeaDoc = store.load(title)
                from = "${d.entries.size} Log entries · ${d.entries.sumOf { it.images.size }} images · your Summary"
                val prompt = CaseStudy.prompt(d, store.noteText(title)) + (draft?.takeIf { tone != CaseStudy.Tone.FIRST }?.let { "\n\n" + CaseStudy.redo(it, tone) } ?: "")
                deskAsk(state, prompt, CaseStudy.SYSTEM, clever = true, maxTokens = 4000L)
            }
            busy = false
            if (t != null) { draft = t; saved = false }
        }
    }
    LaunchedEffect(title) { write(CaseStudy.Tone.FIRST) }
    WideDialog("✨ Case study · $title", from.ifEmpty { null }?.let { "Built from $it" }, onDone) {
        if (busy) Working("Writing the draft… (about 30 seconds)")
        draft?.let { d ->
            Text("Draft · ${"%,d".format(Locale.UK, CaseStudy.words(d))} words", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Muted)
            Text(d, fontSize = 14.sp, lineHeight = 22.sp, color = TextMain, modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Shorter", small = true) { if (!busy) write(CaseStudy.Tone.SHORTER) }
                Chip("For a recruiter", small = true) { if (!busy) write(CaseStudy.Tone.RECRUITER) }
                Chip("Copy", small = true) { copyToClipboard(d); state.say("Copied the case study") }
                Spacer(Modifier.weight(1f))
                PrimaryButton(if (saved) "Saved ✓" else "Save to Portfolio", enabled = !saved && !busy) {
                    val v = state.vault ?: return@PrimaryButton
                    scope.launch { state.io("Couldn't save it") { CaseStudy.save(v, title, d) }?.let { saved = true; state.say("Saved to ${it.substringBeforeLast('/')}") } }
                }
            }
        }
    }
}

// ---- Morning audio briefing (growth-v1) ----

/** Reads lines aloud one after another with the Windows voice; [line] is the one being read (-1 when stopped). */
internal object BriefingVoice {
    var line by mutableStateOf(-1)
        private set
    private var job: Job? = null
    private var proc: Process? = null

    fun play(state: AppState, lines: List<String>, from: Int = 0) {
        stop()
        job = state.scope.launch {
            for (i in from until lines.size) {
                line = i
                withContext(Dispatchers.IO) {
                    val script = "Add-Type -AssemblyName System.Speech; \$s = New-Object System.Speech.Synthesis.SpeechSynthesizer; \$s.Speak([Console]::In.ReadToEnd())"
                    runCatching {
                        val p = ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", script).redirectErrorStream(true).start()
                        proc = p
                        p.outputStream.use { it.write(lines[i].toByteArray(Charsets.UTF_8)) }
                        p.waitFor()
                    }
                }
                if (line != i) return@launch
            }
            line = -1
        }
    }

    fun stop() { line = -1; job?.cancel(); job = null; proc?.destroy(); proc = null }
}

/** ▶ Briefing on the Deck header: today's script (shared with the phone), read by the Windows voice. */
@Composable
internal fun BriefingButton(state: AppState, facts: () -> String) {
    val scope = rememberCoroutineScope()
    var lines by remember { mutableStateOf<List<String>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf(false) }
    val today = LocalDate.now()
    LaunchedEffect(state.vault, state.changes) { state.vault?.let { v -> lines = state.io { Briefing.cached(v, today) } } }
    fun start() {
        lines?.let { BriefingVoice.play(state, it); open = true; return }
        val v = state.vault ?: return
        busy = true
        scope.launch {
            val ls = state.io("Couldn't write the briefing") { Briefing.lines(deskAsk(state, facts(), Briefing.system(state.prefs.assistantName))).also { Briefing.save(v, today, it) } }
            busy = false
            if (ls != null) { lines = ls; BriefingVoice.play(state, ls); open = true }
        }
    }
    val playing = BriefingVoice.line >= 0
    Box {
        Text(if (busy) "…" else if (playing) "❚❚ Briefing" else "▶ Briefing", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Primary,
            modifier = Modifier.padding(top = 4.dp).clip(CircleShape).border(1.dp, Primary.copy(alpha = .5f), CircleShape).hand()
                .clickable(enabled = !busy) { if (playing) BriefingVoice.stop() else start() }.padding(horizontal = 12.dp, vertical = 6.dp))
        androidx.compose.material3.DropdownMenu(open && lines != null, { open = false }) {
            lines?.forEachIndexed { i, l ->
                androidx.compose.material3.DropdownMenuItem(
                    { Text((if (i == BriefingVoice.line) "▸ " else "○ ") + l, fontSize = 13.sp, color = if (i == BriefingVoice.line) Primary else TextMain, modifier = Modifier.widthIn(max = 460.dp)) },
                    { BriefingVoice.play(state, lines!!, i) }
                )
            }
            Box(Modifier.height(4.dp))
            Text("Length ${lines?.let { Briefing.length(it) } ?: ""} · click a line to jump", fontSize = 11.sp, color = Muted, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
        }
    }
}

