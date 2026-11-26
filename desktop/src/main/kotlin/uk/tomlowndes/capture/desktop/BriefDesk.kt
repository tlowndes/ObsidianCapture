package uk.tomlowndes.capture.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.tomlowndes.capture.BriefDoc
import uk.tomlowndes.capture.BriefRow
import uk.tomlowndes.capture.BriefThing
import uk.tomlowndes.capture.DailyBrief
import uk.tomlowndes.capture.DayPlan
import uk.tomlowndes.capture.HangarTool
import uk.tomlowndes.capture.Jobs
import uk.tomlowndes.capture.TaskViews
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import uk.tomlowndes.capture.mmss

// ---- Daily Brief on Windows (daily-brief-v1) ----

/**
 * Reads the brief aloud with the Windows voice, a line at a time, Full (chapters) or Short, at a set speed.
 * Same chapters as the phone; "heard" is written to the shared note when it reaches the end.
 */
internal object DeskBriefPlayer {
    var doc by mutableStateOf<BriefDoc?>(null); private set
    var full by mutableStateOf(true); private set
    var speed by mutableFloatStateOf(1f); private set
    var playing by mutableStateOf(false); private set
    var index by mutableIntStateOf(-1); private set
    var queue by mutableStateOf<List<Pair<Int, String>>>(emptyList()); private set
    private var job: Job? = null
    private var proc: Process? = null
    private var onEnd: () -> Unit = {}

    val chapter: Int get() = queue.getOrNull(index.coerceAtLeast(0))?.first ?: -1
    val elapsed: Int get() = (DailyBrief.seconds(queue.take(index.coerceAtLeast(0)).map { it.second }) / speed).toInt()
    val total: Int get() = (DailyBrief.seconds(queue.map { it.second }) / speed).toInt()
    val SPEEDS = listOf(0.8f, 1f, 1.25f, 1.5f)
    fun speedLabel(s: Float) = (if (s == s.toInt().toFloat()) s.toInt().toString() else s.toString().trimEnd('0')) + "×"

    fun load(state: AppState, d: BriefDoc, onHeard: () -> Unit) {
        onEnd = onHeard
        speed = state.prefs.briefSpeed
        val same = doc?.let { it.date == d.date && it.chapters == d.chapters && it.short == d.short } == true
        doc = d
        if (!same || queue.isEmpty()) { stop(); full = state.prefs.briefFull; rebuild(); index = -1 }
    }

    private fun rebuild() {
        val d = doc ?: return
        queue = if (full) DailyBrief.fullQueue(d) else d.shortLines.map { -1 to it }
    }

    /** SAPI's rate runs -10..10; 0 is normal. */
    private fun rate(s: Float) = ((s - 1f) * 8).toInt().coerceIn(-10, 10)

    fun play(state: AppState, from: Int = index) {
        stop(keepIndex = true)
        val start = if (from < 0 || from > queue.lastIndex) 0 else from
        val lines = queue
        playing = true
        job = state.scope.launch {
            for (i in start until lines.size) {
                index = i
                withContext(Dispatchers.IO) {
                    val script = "Add-Type -AssemblyName System.Speech; \$s = New-Object System.Speech.Synthesis.SpeechSynthesizer; \$s.Rate = ${rate(speed)}; \$s.Speak([Console]::In.ReadToEnd())"
                    runCatching {
                        val p = ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", script).redirectErrorStream(true).start()
                        proc = p
                        p.outputStream.use { it.write(lines[i].second.toByteArray(Charsets.UTF_8)) }
                        p.waitFor()
                    }
                }
                if (!playing) return@launch
            }
            playing = false; index = -1
            onEnd()
        }
    }

    fun stop(keepIndex: Boolean = false) { playing = false; job?.cancel(); job = null; proc?.destroy(); proc = null; if (!keepIndex) index = -1 }

    fun toggle(state: AppState) = if (playing) stop(keepIndex = true) else play(state)

    fun playChapter(state: AppState, c: Int) {
        if (!full) { full = true; state.prefs.briefFull = true; rebuild() }
        queue.indexOfFirst { it.first == c }.takeIf { it >= 0 }?.let { play(state, it) }
    }

    fun next(state: AppState) {
        if (!full) { play(state, (index + 1).coerceAtMost(queue.lastIndex)); return }
        queue.indexOfFirst { it.first > chapter }.takeIf { it >= 0 }?.let { play(state, it) } ?: stop()
    }

    fun previous(state: AppState) {
        if (!full) { play(state, (index - 1).coerceAtLeast(0)); return }
        val c = chapter
        val start = queue.indexOfFirst { it.first == c }
        play(state, (if (index - start >= 2 || c <= 0) start else queue.indexOfFirst { it.first == c - 1 }).coerceAtLeast(0))
    }

    fun setFull(state: AppState, on: Boolean) {
        if (on == full) return
        val was = playing
        stop(); full = on; state.prefs.briefFull = on; rebuild()
        if (was) play(state, 0)
    }

    fun setSpeed(state: AppState, s: Float) {
        speed = s; state.prefs.briefSpeed = s
        if (playing) play(state, index)
    }
}

/** Today's brief from the vault, or the PC writes it (from 06:45, or [force]) with one Claude call. Blocking. */
private fun loadOrWriteDeskBrief(state: AppState, force: Boolean): BriefDoc? {
    val v = state.vault ?: return null
    val now = LocalDateTime.now()
    val today = now.toLocalDate()
    if (!force) DailyBrief.load(v, today)?.let { return it }
    if (!force && !DailyBrief.appMayWrite(now)) return null
    if (!hasClaude(state)) return null
    // Bills switched off in Where things live: the brief leaves them out too (printed-marks-v1).
    val tasks = uk.tomlowndes.capture.Places.forBills(state.writer()!!.listTasks(), runCatching { uk.tomlowndes.capture.Places.load(v) }.getOrNull())
    val input = DailyBrief.Input(
        now, tasks, runCatching { DesktopCalendar.load(v).second }.getOrDefault(emptyList()),
        runCatching { DesktopWeather.today(state.prefs) }.getOrNull(),
        runCatching { Jobs.list(v) }.getOrDefault(emptyList()),
        runCatching { loadDeskInbox(state)?.open(today) }.getOrNull().orEmpty(),
    )
    val base = DailyBrief.base(input)
    val reply = deskAsk(state, DailyBrief.prompt(input, base, DayPlan.candidates(tasks, today, 10)), DailyBrief.system(state.prefs.assistantName), maxTokens = 3500L)
    return DailyBrief.merge(base, reply).also { DailyBrief.save(v, it) }
}

private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)
private val Orange = Color(0xFFFFB36B)
private val Pink = Color(0xFFFF8AC0)
private val Teal = Color(0xFF5FC8D3)

/** ☀️ Brief on the Deck header: opens the Brief page in Hangar. */
@Composable
internal fun BriefDeckButton(state: AppState) {
    Text(if (DeskBriefPlayer.playing) "❚❚ Brief" else "☀️ Brief", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Primary,
        modifier = Modifier.padding(top = 4.dp).clip(CircleShape).border(1.dp, Primary.copy(alpha = .5f), CircleShape).hand()
            .clickable { state.hangarTool = HangarTool.BRIEF; state.tab = Tab.HANGAR }.padding(horizontal = 12.dp, vertical = 6.dp))
}

/** The Brief page (daily-brief-v1, Windows): the written brief in two columns, the player and chapters on the right. */
@Composable
internal fun BriefDeskPage(state: AppState) {
    var doc by remember { mutableStateOf<BriefDoc?>(null) }
    var busy by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var ticked by remember { mutableStateOf(setOf<String>()) }
    fun load(force: Boolean) {
        busy = force || busy
        state.scope.launch {
            val d = state.io("Couldn't write the brief") { loadOrWriteDeskBrief(state, force) }
            busy = false; loaded = true
            if (d != null) {
                doc = d
                val v = state.vault ?: return@launch
                DeskBriefPlayer.load(state, d) { state.scope.launch { state.io { DailyBrief.markHeard(v, d.date) } } }
                if (!d.opened) state.io { DailyBrief.markOpened(v, d.date) }
            }
        }
    }
    LaunchedEffect(state.vault) { load(false) }
    fun tick(t: BriefThing) {
        val w = state.writer() ?: return
        state.scope.launch {
            val r = state.io("Couldn't tick it") {
                val all = w.listTasks()
                val m = all.firstOrNull { TaskViews.plainText(it).equals(t.title, true) }
                    ?: all.firstOrNull { TaskViews.plainText(it).contains(t.title, true) || t.title.contains(TaskViews.plainText(it), true) }
                    ?: error("\"${t.title}\" isn't in your tasks")
                m to w.completeTask(m, state.prefs.recurring)
            } ?: return@launch
            ticked = ticked + t.title
            state.changed()
            state.offerUndo("Task completed") { state.io { w.restoreTask(r.first, r.second) }; ticked = ticked - t.title; state.changed() }
        }
    }

    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 22.dp)) {
            TextAction("‹ Hangar", Muted) { state.hangarTool = null }
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Daily Brief", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = TextMain, modifier = Modifier.weight(1f))
                val d = doc
                Text(LocalDate.now().format(DAY) + (d?.let { " · ${if (it.writer == DailyBrief.WRITER_BOX) "box" else "app"}${it.made?.let { m -> " $m" } ?: ""}" } ?: ""),
                    fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Primary,
                    modifier = Modifier.clip(CircleShape).background(PrimaryTint).padding(horizontal = 10.dp, vertical = 4.dp))
            }
            val d = doc
            when {
                busy -> Sub("Writing today's brief…", Modifier.padding(vertical = 16.dp))
                d == null && loaded -> Column(Modifier.padding(vertical = 14.dp)) {
                    Sub(if (!hasClaude(state)) "Add the Claude key in Settings, or set up the box, to get a Daily Brief."
                        else "The box writes it at 06:15, with the news. If it hasn't by ${DailyBrief.APP_FROM}, the app writes its own.")
                    if (hasClaude(state)) TextAction("Write it now", Primary, Modifier.padding(top = 10.dp)) { load(true) }
                }
                d != null -> DeskBriefBody(state, d, ticked, ::tick) { load(true) }
            }
        }
        val d = doc
        if (d != null) Column(Modifier.width(400.dp).fillMaxHeight().background(Surface2).padding(20.dp).verticalScroll(rememberScrollState())) {
            DeskPlayer(state, d)
        }
    }
}

@Composable
private fun DeskStats(stats: List<BriefRow>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        stats.take(4).forEach { s ->
            Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(Surface2).padding(horizontal = 12.dp, vertical = 9.dp)) {
                Text(s.text, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = if (s.label.startsWith("waiting") && s.text != "0") Orange else TextMain)
                Text(s.label.uppercase(), fontSize = 9.sp, fontFamily = FontFamily.Monospace, letterSpacing = 0.8.sp, color = Muted)
            }
        }
    }
}

@Composable
private fun DeskLabel(state: AppState, text: String, chapter: Int?, now: Boolean) {
    Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), fontSize = 11.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.4.sp, color = if (now) Primary else Muted, modifier = Modifier.weight(1f))
        if (chapter != null) Text(if (now) "▶ playing" else "▶", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Primary,
            modifier = Modifier.clip(CircleShape).background(PrimaryTint).hand().clickable { DeskBriefPlayer.playChapter(state, chapter) }.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

@Composable
private fun DeskBriefBody(state: AppState, d: BriefDoc, ticked: Set<String>, onTick: (BriefThing) -> Unit, onRewrite: () -> Unit) {
    val chapters = d.chapters
    val nowKey = if (DeskBriefPlayer.playing && DeskBriefPlayer.full) chapters.getOrNull(DeskBriefPlayer.chapter)?.key else null
    fun ch(key: String): Int? = chapters.indexOfFirst { it.key == key }.takeIf { it >= 0 }
    @Composable fun Label(text: String, key: String) = DeskLabel(state, text, ch(key), nowKey == key)
    @Composable fun Hl(key: String, content: @Composable () -> Unit) {
        Column(if (nowKey == key) Modifier.clip(RoundedCornerShape(14.dp)).background(Primary.copy(alpha = 0.08f)).border(1.dp, Color(0xFF2F4170), RoundedCornerShape(14.dp)).padding(horizontal = 8.dp).padding(bottom = 8.dp) else Modifier) { content() }
    }
    if (d.opening.isNotBlank()) Text(d.opening, fontSize = 15.sp, lineHeight = 22.sp, color = Color(0xFFCFD6E6), modifier = Modifier.padding(top = 10.dp, bottom = 14.dp))
    if (d.stats.isNotEmpty()) DeskStats(d.stats)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(26.dp)) {
        Column(Modifier.weight(1f)) {
            if (d.today.isNotEmpty()) Hl("opening") {
                Label("📅 Today", "opening")
                d.today.forEach { r ->
                    val free = r.text.startsWith("Free")
                    Row(Modifier.padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(r.label, fontSize = 14.sp, fontFamily = FontFamily.Monospace, color = TaskGreen)
                        Text(r.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (free) TaskGreen else TextMain)
                    }
                }
            }
            d.weather?.let { w ->
                Hl("weather") {
                    Label("🌦 Weather & travel", "weather")
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(WeatherBg).border(1.dp, WeatherBorder, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 12.dp)) {
                        Text(w.label, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextMain)
                        if (w.text.isNotBlank()) Text(w.text, fontSize = 13.sp, color = Muted, lineHeight = 19.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
            if (d.things.isNotEmpty()) Hl("things") {
                Label("✅ Three things", "things")
                d.things.forEach { t ->
                    val c = when {
                        t.area.contains("job", true) -> Orange
                        t.area.contains("portfolio", true) || t.area.contains("design", true) -> Primary
                        t.area.contains("home", true) -> TaskGreen
                        else -> Purple
                    }
                    val done = t.title in ticked
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp).height(IntrinsicSize.Min).clip(RoundedCornerShape(14.dp)).background(c.copy(alpha = 0.09f))) {
                        Box(Modifier.width(3.dp).fillMaxHeight().background(c))
                        Column(Modifier.weight(1f).padding(start = 12.dp, top = 11.dp, bottom = 11.dp)) {
                            Text("${t.area} · 🔋 ${t.energy}".uppercase(), fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp, color = c)
                            Text(t.title, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 2.dp),
                                color = if (done) Muted else TextMain, textDecoration = if (done) TextDecoration.LineThrough else null)
                            if (t.why.isNotBlank()) Text(t.why, fontSize = 12.5.sp, color = Muted, lineHeight = 17.sp)
                        }
                        Box(Modifier.padding(14.dp).size(22.dp).clip(RoundedCornerShape(6.dp)).background(if (done) TaskGreen else Color.Transparent)
                            .border(2.dp, if (done) TaskGreen else Muted, RoundedCornerShape(6.dp)).hand().clickable(enabled = !done) { onTick(t) }, contentAlignment = Alignment.Center) {
                            if (done) Text("✓", fontSize = 13.sp, color = Bg, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
        Column(Modifier.weight(1f)) {
            if (d.comingUp.isNotEmpty()) Hl("coming") {
                Label("🗓 Coming up", "coming")
                d.comingUp.forEach { r ->
                    Text(buildAnnotatedString {
                        withStyle(SpanStyle(color = TextMain, fontWeight = FontWeight.Bold)) { append(r.label) }
                        append("  ·  ${r.text}")
                    }, fontSize = 13.5.sp, color = Muted, lineHeight = 20.sp, modifier = Modifier.padding(vertical = 3.dp))
                }
            }
            if (d.roles.isNotEmpty()) Hl("roles") {
                Label("💼 Roles", "roles")
                d.roles.forEach { r ->
                    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp).clip(RoundedCornerShape(12.dp)).background(Surface1).border(1.dp, Outline, RoundedCornerShape(12.dp))
                        .hand().clickable { state.tab = Tab.JOBS }.padding(horizontal = 12.dp, vertical = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(r.title, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, color = TextMain, modifier = Modifier.weight(1f))
                            val warm = r.tag == "follow up" || r.tag == "interview"
                            Text(r.tag, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = if (warm) Amber else Primary,
                                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(if (warm) AmberTint else PrimaryTint).padding(horizontal = 7.dp, vertical = 2.dp))
                        }
                        if (r.detail.isNotBlank()) Text(r.detail, fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
            if (d.bills.isNotEmpty()) Hl("bills") {
                Label("💷 Bills & inbox", "bills")
                d.bills.forEach { Text(it, fontSize = 13.5.sp, color = TextMain, modifier = Modifier.padding(vertical = 3.dp)) }
            }
            if (d.news.isNotEmpty()) Hl("news") {
                Label("📰 The TL;DR", "news")
                d.news.groupBy { it.cat }.forEach { (cat, items) ->
                    val c = when { cat.contains("design", true) -> Pink; cat.contains("ai", true) -> Purple; cat.contains("sec", true) -> Warn; else -> Teal }
                    Text(cat.uppercase(), fontSize = 11.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp, fontWeight = FontWeight.Bold, color = c, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                    items.forEach { n ->
                        Text(buildAnnotatedString {
                            withStyle(SpanStyle(color = TextMain, fontWeight = FontWeight.SemiBold, textDecoration = TextDecoration.Underline)) { append(n.title) }
                            if (n.line.isNotBlank()) append(" · ${n.line}")
                        }, fontSize = 13.sp, color = Muted, lineHeight = 19.sp, modifier = Modifier.padding(bottom = 6.dp).hand().clickable { state.browse(n.url) })
                    }
                }
            }
            d.hardStop?.let { h ->
                Hl("stop") {
                    if (ch("stop") != null) Label("🛑 Hard stop", "stop") else Box(Modifier.height(14.dp))
                    Text(buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("Hard stop: ${h.label}. ") }
                        append(h.text)
                    }, fontSize = 14.sp, lineHeight = 20.sp, color = Color(0xFF1B1006),
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFFF3A35C)).padding(horizontal = 14.dp, vertical = 12.dp))
                }
            }
        }
    }
    TextAction("↻ Write again (one Claude call${if (d.news.isNotEmpty()) ", drops the box's news" else ""})", Muted, Modifier.padding(top = 18.dp)) { DeskBriefPlayer.stop(); onRewrite() }
}

@Composable
private fun DeskSeg(options: List<String>, selected: Int, modifier: Modifier = Modifier, onPick: (Int) -> Unit) {
    Row(modifier.clip(CircleShape).background(Bg).border(1.dp, Outline, CircleShape).padding(3.dp)) {
        options.forEachIndexed { i, o ->
            Text(o, fontSize = 12.5.sp, textAlign = TextAlign.Center, color = if (i == selected) Primary else Muted, fontWeight = if (i == selected) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.weight(1f).clip(CircleShape).background(if (i == selected) PrimaryTint else Color.Transparent).hand().clickable { onPick(i) }.padding(vertical = 6.dp))
        }
    }
}

@Composable
private fun DeskPlayer(state: AppState, d: BriefDoc) {
    val chapters = d.chapters
    val starts = DailyBrief.starts(chapters)
    val p = DeskBriefPlayer
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Listen", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = TextMain, modifier = Modifier.weight(1f))
        Text("Windows voice", fontSize = 12.sp, color = Muted)
    }
    DeskSeg(listOf("Short · ${DailyBrief.length(d.shortLines)}", "Full · ${DailyBrief.length(DailyBrief.fullQueue(d).map { it.second })}"),
        if (p.full) 1 else 0, Modifier.fillMaxWidth().padding(top = 12.dp)) { p.setFull(state, it == 1) }
    Box(Modifier.padding(top = 16.dp).fillMaxWidth().height(5.dp).clip(RoundedCornerShape(4.dp)).background(Outline)) {
        Box(Modifier.fillMaxWidth((p.elapsed.toFloat() / p.total.coerceAtLeast(1)).coerceIn(0f, 1f)).fillMaxHeight().background(Primary))
    }
    Row(Modifier.fillMaxWidth().padding(top = 5.dp)) {
        Text(mmss(p.elapsed), fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Muted, modifier = Modifier.weight(1f))
        Text("−" + mmss((p.total - p.elapsed).coerceAtLeast(0)), fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Muted)
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Text("⏮", fontSize = 22.sp, color = TextMain, modifier = Modifier.clip(CircleShape).hand().clickable { p.previous(state) }.padding(10.dp))
        Box(Modifier.width(22.dp))
        Box(Modifier.size(58.dp).clip(CircleShape).background(Primary).hand().clickable { p.toggle(state) }, contentAlignment = Alignment.Center) {
            Text(if (p.playing) "❚❚" else "▶", color = OnPrimary, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        }
        Box(Modifier.width(22.dp))
        Text("⏭", fontSize = 22.sp, color = TextMain, modifier = Modifier.clip(CircleShape).hand().clickable { p.next(state) }.padding(10.dp))
    }
    DeskSeg(p.SPEEDS.map { p.speedLabel(it) }, p.SPEEDS.indexOf(p.speed).coerceAtLeast(1), Modifier.fillMaxWidth().padding(horizontal = 30.dp)) { p.setSpeed(state, p.SPEEDS[it]) }
    Box(Modifier.height(10.dp))
    chapters.forEachIndexed { i, c ->
        val on = p.full && p.index >= 0 && i == p.chapter
        val done = p.full && p.index >= 0 && i < p.chapter
        if (i > 0) HorizontalDivider(color = Outline)
        Row(Modifier.fillMaxWidth().hand().clickable { p.playChapter(state, i) }.padding(horizontal = 4.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${i + 1}", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Muted, modifier = Modifier.width(22.dp))
            Text((if (on) "▸ " else if (done) "✓ " else "") + c.title, fontSize = 14.sp, modifier = Modifier.weight(1f),
                color = if (on) Primary else if (done) Muted else TextMain, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
            Text(mmss((starts[i] / p.speed).toInt()), fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Muted)
        }
    }
    Text("Written by the ${if (d.writer == DailyBrief.WRITER_BOX) "Oracle box" else "app"}${d.made?.let { " at $it" } ?: ""}.",
        fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 16.dp))
}
