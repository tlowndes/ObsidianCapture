package uk.tomlowndes.capture

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

// ---- Daily Brief (daily-brief-v1) ----

/** What the card and page know about today's brief. */
internal sealed class BriefState {
    object Loading : BriefState()
    /** None yet and it's before 06:45: the box gets first go. */
    object Waiting : BriefState()
    object Writing : BriefState()
    /** None and no Claude key: the page can't write one. */
    object NoClaude : BriefState()
    data class Ready(val doc: BriefDoc) : BriefState()
    data class Failed(val message: String) : BriefState()
}

/**
 * Today's brief from the vault. When there's none and it's past [DailyBrief.APP_FROM] (or [force]), the app writes its
 * own with one Claude call: the box's version, with the news, is used whenever it's there.
 */
internal fun loadOrWriteBrief(ctx: Context, prefs: AppPrefs, uri: Uri, weather: WeatherSummary?, force: Boolean = false, onWriting: () -> Unit = {}): BriefState {
    val v = appVault(ctx, uri)
    val now = LocalDateTime.now()
    val today = now.toLocalDate()
    if (!force) DailyBrief.load(v, today)?.let { return BriefState.Ready(it) }
    if (!force && !DailyBrief.appMayWrite(now)) return BriefState.Waiting
    if (claudeKey(ctx, prefs) == null) return BriefState.NoClaude
    onWriting()
    return runCatching {
        val on = runCatching { CalendarConfig.load(v) }.getOrNull().orEmpty().filter { it.enabled }.map { it.id }.toSet()
        val events = CalendarSync.cached(ctx).filter { it.id in on || it.id == AppEvents.CAL_ID }.flatMap { it.events }
        // Bills switched off in Where things live: the brief leaves them out too (printed-marks-v1).
        val tasks = Places.forBills(VaultWriter(ctx, uri).listTasks(), runCatching { PhonePlaces.load(ctx, uri, prefs) }.getOrNull())
        val input = DailyBrief.Input(
            now, tasks, events, weather ?: runCatching { WeatherFetch.today(ctx) }.getOrNull(),
            runCatching { Jobs.list(v) }.getOrDefault(emptyList()),
            runCatching { loadInbox(ctx, uri).open(today) }.getOrDefault(emptyList()),
        )
        val base = DailyBrief.base(input)
        val reply = ask(ctx, prefs, DailyBrief.prompt(input, base, DayPlan.candidates(tasks, today, 10)), DailyBrief.system(prefs.assistantName), maxTokens = 3500L)
        val doc = DailyBrief.merge(base, reply)
        DailyBrief.save(v, doc)
        BriefState.Ready(doc)
    }.getOrElse { BriefState.Failed(PhoneBrain.failure(prefs.assistantName, it, viaServer = false)) }
}

private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)

/** The four stats, as on the email: free time, roles, waiting on you, apps this week. */
@Composable
private fun StatStrip(stats: List<BriefRow>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        stats.take(4).forEach { s ->
            Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(Surface2).padding(horizontal = 9.dp, vertical = 8.dp)) {
                Text(s.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = if (s.label.startsWith("waiting") && s.text != "0") Orange else TextMain, maxLines = 1)
                Text(s.label.uppercase(), fontSize = 8.5.sp, fontFamily = FontFamily.Monospace, letterSpacing = 0.6.sp, color = Muted, lineHeight = 11.sp)
            }
        }
    }
}

@Composable
private fun Pill(text: String, primary: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        text, fontSize = 14.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
        color = if (primary) OnPrimary else TextMain,
        modifier = modifier.clip(CircleShape).background(if (primary) Primary else Color.Transparent)
            .border(1.dp, if (primary) Primary else Outline, CircleShape).clickable(onClick = onClick).padding(vertical = 11.dp)
    )
}

/**
 * The Deck card (daily-brief-v1 #1): opening line, the four stats, ▶ Listen and Read. Once heard or opened it shrinks to
 * one row instead of hiding, so it's easy to go back to.
 */
@Composable
internal fun DailyBriefCard(vault: Uri?, prefs: AppPrefs, weather: WeatherSummary?, refresh: Int, onOpen: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<BriefState>(BriefState.Loading) }
    LaunchedEffect(vault, refresh) {
        val uri = vault ?: return@LaunchedEffect
        state = withContext(Dispatchers.IO) { loadOrWriteBrief(ctx, prefs, uri, weather) { state = BriefState.Writing } }
    }
    fun heard(uri: Uri, d: BriefDoc) {
        scope.launch { withContext(Dispatchers.IO) { runCatching { DailyBrief.markHeard(appVault(ctx, uri), d.date) } } }
        state = BriefState.Ready(d.copy(heard = true))
    }
    val s = state
    if (s is BriefState.Loading || s is BriefState.NoClaude) return
    val d = (s as? BriefState.Ready)?.doc
    if (d != null && (d.heard || d.opened) && !BriefPlayer.playing) {
        DeckCard(modifier = Modifier.padding(top = 12.dp).clickable(onClick = onOpen)) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("☀️", fontSize = 15.sp)
                Text("Daily Brief ✓", fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text("Read again ›", fontSize = 12.5.sp, color = Primary)
            }
        }
        return
    }
    DeckCard(modifier = Modifier.padding(top = 12.dp)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("☀️ Daily Brief", fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                d?.let { Text("${it.writer} · ${it.made ?: ""}".trimEnd(' ', '·'), fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, color = Primary,
                    modifier = Modifier.clip(CircleShape).background(PrimaryTint).padding(horizontal = 9.dp, vertical = 3.dp)) }
            }
            when (s) {
                is BriefState.Waiting -> {
                    Text("Your brief lands by ${DailyBrief.APP_FROM}.", fontSize = 14.sp, color = Muted, modifier = Modifier.padding(top = 8.dp))
                    Pill("Write it now", false, Modifier.fillMaxWidth().padding(top = 12.dp)) {
                        val uri = vault ?: return@Pill
                        scope.launch { state = withContext(Dispatchers.IO) { loadOrWriteBrief(ctx, prefs, uri, weather, force = true) { state = BriefState.Writing } } }
                    }
                }
                is BriefState.Writing -> Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(color = Primary, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    Text("Writing today's brief…", fontSize = 13.sp, color = Muted)
                }
                is BriefState.Failed -> Text(s.message, fontSize = 12.5.sp, color = Warn, modifier = Modifier.padding(top = 8.dp))
                is BriefState.Ready -> {
                    if (s.doc.opening.isNotBlank()) Text(s.doc.opening, fontSize = 14.5.sp, lineHeight = 21.sp, color = Color(0xFFCFD6E6), modifier = Modifier.padding(top = 8.dp, bottom = 12.dp))
                    StatStrip(s.doc.stats)
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val len = DailyBrief.length((if (prefs.briefFull) DailyBrief.fullQueue(s.doc).map { it.second } else s.doc.shortLines), prefs.briefSpeed)
                        Pill(if (BriefPlayer.playing) "❚❚ Pause" else "▶ Listen  $len", true, Modifier.weight(1f)) {
                            val uri = vault ?: return@Pill
                            BriefPlayer.load(ctx, s.doc, prefs) { heard(uri, s.doc) }
                            BriefPlayer.toggle()
                        }
                        Pill("Read", false, Modifier.weight(1f), onOpen)
                    }
                }
                else -> {}
            }
        }
    }
}

/** A section heading with ▶ to play from its chapter; [now] highlights the one being read. */
@Composable
private fun BriefLabel(text: String, chapter: Int?, start: String?, now: Boolean) {
    Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), fontSize = 11.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.4.sp, color = if (now) Primary else Muted, modifier = Modifier.weight(1f))
        if (chapter != null) Text(if (now) "▶ playing" else "▶ ${start.orEmpty()}".trim(), fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Primary,
            modifier = Modifier.clip(CircleShape).background(PrimaryTint).clickable { BriefPlayer.playChapter(chapter) }.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

private fun energyColour(area: String): Pair<Color, Color> = when {
    area.contains("job", true) -> Orange to Color(0xFF221A12)
    area.contains("portfolio", true) || area.contains("design", true) -> Primary to Color(0xFF141C2E)
    area.contains("home", true) -> Green to Color(0xFF14231D)
    else -> Purple to VioletTint
}

private fun newsColour(cat: String): Color = when {
    cat.contains("design", true) -> Pink
    cat.contains("ai", true) -> Purple
    cat.contains("sec", true) -> Warn
    else -> Teal
}

/**
 * The Brief page (daily-brief-v1 #2–#4): every section of the morning email, the mini player pinned at the bottom and
 * the full player as a sheet. Ticking one of the Three things completes the matching task.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BriefScreen(vault: Uri?, prefs: AppPrefs, snack: SnackbarHostState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<BriefState>(BriefState.Loading) }
    var rev by remember { mutableIntStateOf(0) }
    var force by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf(false) }
    var askRewrite by remember { mutableStateOf(false) }
    val ticked = remember { mutableStateOf(setOf<String>()) }
    LaunchedEffect(vault, rev) {
        val uri = vault ?: return@LaunchedEffect
        val f = force; force = false
        state = withContext(Dispatchers.IO) { loadOrWriteBrief(ctx, prefs, uri, null, force = f) { state = BriefState.Writing } }
        (state as? BriefState.Ready)?.doc?.let { d ->
            BriefPlayer.load(ctx, d, prefs) { scope.launch { withContext(Dispatchers.IO) { runCatching { DailyBrief.markHeard(appVault(ctx, uri), d.date) } } } }
            if (!d.opened) withContext(Dispatchers.IO) { runCatching { DailyBrief.markOpened(appVault(ctx, uri), d.date) } }
        }
    }
    fun tick(t: BriefThing) {
        val uri = vault ?: return
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val w = VaultWriter(ctx, uri)
                    val all = w.listTasks()
                    val match = all.firstOrNull { TaskViews.plainText(it).equals(t.title, true) }
                        ?: all.firstOrNull { TaskViews.plainText(it).contains(t.title, true) || t.title.contains(TaskViews.plainText(it), true) }
                        ?: error("Couldn't find \"${t.title}\" in your tasks")
                    match to w.completeTask(match, prefs.recurring)
                }
            }
            r.onFailure { snack.showSnackbar(it.message ?: "Failed") }
            r.onSuccess { (task, c) ->
                ticked.value = ticked.value + t.title
                if (snack.showSnackbar("Task completed", "Undo", duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) {
                    withContext(Dispatchers.IO) { runCatching { VaultWriter(ctx, uri).restoreTask(task, c) } }
                    ticked.value = ticked.value - t.title
                }
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
            Text("‹ Deck", color = Muted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable(onClick = onBack).padding(top = 10.dp))
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Daily Brief", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(LocalDate.now().format(DAY), fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Primary,
                    modifier = Modifier.clip(CircleShape).background(PrimaryTint).padding(horizontal = 10.dp, vertical = 4.dp))
            }
            when (val s = state) {
                is BriefState.Loading -> {}
                is BriefState.Writing -> Row(Modifier.padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(color = Primary, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    Text("Writing today's brief…", fontSize = 13.sp, color = Muted)
                }
                is BriefState.Waiting -> Column(Modifier.padding(vertical = 14.dp)) {
                    Text("The box writes it at 06:15, with the news. If it hasn't by ${DailyBrief.APP_FROM}, the app writes its own.", fontSize = 14.sp, color = Muted)
                    Pill("Write it now", true, Modifier.fillMaxWidth().padding(top = 12.dp)) { force = true; rev++ }
                }
                is BriefState.NoClaude -> Text("Turn on Claude from the phone in Settings, or set up the box, to get a Daily Brief.", fontSize = 14.sp, color = Muted, modifier = Modifier.padding(vertical = 14.dp))
                is BriefState.Failed -> Column(Modifier.padding(vertical = 14.dp)) {
                    Text(s.message, fontSize = 13.sp, color = Warn)
                    Pill("Try again", false, Modifier.fillMaxWidth().padding(top = 12.dp)) { force = true; rev++ }
                }
                is BriefState.Ready -> BriefBody(s.doc, ticked.value, ::tick, onLink = { url ->
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }, onRewrite = { askRewrite = true })
            }
            Box(Modifier.height(if (state is BriefState.Ready) 110.dp else 24.dp))
        }
        if (state is BriefState.Ready && BriefPlayer.queue.isNotEmpty()) Box(Modifier.align(Alignment.BottomCenter).padding(horizontal = 12.dp, vertical = 10.dp)) {
            MiniPlayer { sheet = true }
        }
    }
    if (sheet) ModalBottomSheet(onDismissRequest = { sheet = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Surface2) {
        PlayerSheet()
    }
    if (askRewrite) ModalBottomSheet(onDismissRequest = { askRewrite = false }, containerColor = Surface2) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text("Write today's brief again?", fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text("It's one Claude call and replaces this one" + (if ((state as? BriefState.Ready)?.doc?.news?.isNotEmpty() == true) ", without the box's news." else "."),
                fontSize = 13.5.sp, color = Muted, modifier = Modifier.padding(top = 6.dp))
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Cancel", false, Modifier.weight(1f)) { askRewrite = false }
                Pill("Write again", true, Modifier.weight(1f)) { askRewrite = false; BriefPlayer.stop(); force = true; rev++ }
            }
        }
    }
}

@Composable
private fun BriefBody(d: BriefDoc, ticked: Set<String>, onTick: (BriefThing) -> Unit, onLink: (String) -> Unit, onRewrite: () -> Unit) {
    val chapters = d.chapters
    val starts = DailyBrief.starts(chapters)
    val nowKey = if (BriefPlayer.playing && BriefPlayer.full) chapters.getOrNull(BriefPlayer.chapter)?.key else null
    fun ch(key: String): Int? = chapters.indexOfFirst { it.key == key }.takeIf { it >= 0 }
    fun start(key: String): String? = ch(key)?.let { i -> starts[i].let { s -> "${s / 60}:${"%02d".format(s % 60)}" } }
    @Composable fun Label(text: String, key: String) = BriefLabel(text, ch(key), start(key), nowKey == key)
    @Composable fun Hl(key: String, content: @Composable () -> Unit) {
        Column(if (nowKey == key) Modifier.clip(RoundedCornerShape(14.dp)).background(Primary.copy(alpha = 0.08f)).border(1.dp, Color(0xFF2F4170), RoundedCornerShape(14.dp)).padding(horizontal = 8.dp).padding(bottom = 8.dp) else Modifier) { content() }
    }

    if (d.opening.isNotBlank()) Text(d.opening, fontSize = 14.5.sp, lineHeight = 21.sp, color = Color(0xFFCFD6E6), modifier = Modifier.padding(top = 8.dp, bottom = 12.dp))
    if (d.stats.isNotEmpty()) StatStrip(d.stats)
    Hl("opening") {
        if (d.today.isNotEmpty()) {
            Label("📅 Today", "opening")
            d.today.forEach { r ->
                val free = r.text.startsWith("Free")
                Row(Modifier.padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(r.label, fontSize = 14.sp, fontFamily = FontFamily.Monospace, color = Green)
                    Text(r.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (free) Green else TextMain)
                }
            }
        }
    }
    d.weather?.let { w ->
        Hl("weather") {
            Label("🌦 Weather & travel", "weather")
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(WeatherBg).border(1.dp, WeatherBorder, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 12.dp)) {
                Text(w.label, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                if (w.text.isNotBlank()) Text(w.text, fontSize = 13.sp, color = Muted, lineHeight = 19.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
    if (d.things.isNotEmpty()) Hl("things") {
        Label("✅ Three things", "things")
        d.things.forEach { t ->
            val (c, bg) = energyColour(t.area)
            val done = t.title in ticked
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp).height(IntrinsicSize.Min).clip(RoundedCornerShape(14.dp)).background(bg)) {
                Box(Modifier.width(3.dp).fillMaxHeight().background(c))
                Column(Modifier.weight(1f).padding(start = 12.dp, top = 11.dp, bottom = 11.dp)) {
                    Text("${t.area} · 🔋 ${t.energy}".uppercase(), fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp, color = c)
                    Text(t.title, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 2.dp),
                        color = if (done) Muted else TextMain, textDecoration = if (done) TextDecoration.LineThrough else null)
                    if (t.why.isNotBlank()) Text(t.why, fontSize = 12.5.sp, color = Muted, lineHeight = 17.sp)
                }
                Box(Modifier.padding(14.dp).size(22.dp).clip(RoundedCornerShape(6.dp)).background(if (done) Green else Color.Transparent)
                    .border(2.dp, if (done) Green else Muted, RoundedCornerShape(6.dp)).clickable(enabled = !done) { onTick(t) }, contentAlignment = Alignment.Center) {
                    if (done) Text("✓", fontSize = 13.sp, color = Bg, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
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
            Column(Modifier.fillMaxWidth().padding(bottom = 6.dp).clip(RoundedCornerShape(14.dp)).background(Surface1).border(1.dp, Outline, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(r.title, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
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
            Text(cat.uppercase(), fontSize = 11.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp, fontWeight = FontWeight.Bold, color = newsColour(cat), modifier = Modifier.padding(top = 10.dp, bottom = 5.dp))
            items.forEach { n ->
                Text(buildAnnotatedString {
                    withStyle(SpanStyle(color = TextMain, fontWeight = FontWeight.SemiBold, textDecoration = TextDecoration.Underline)) { append(n.title) }
                    if (n.line.isNotBlank()) append(" · ${n.line}")
                }, fontSize = 13.sp, color = Muted, lineHeight = 19.sp, modifier = Modifier.padding(bottom = 7.dp).clickable { onLink(n.url) })
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
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFFF3A35C)).padding(horizontal = 14.dp, vertical = 13.dp))
        }
    }
    Text("Written by the ${if (d.writer == DailyBrief.WRITER_BOX) "Oracle box" else "app"}${d.made?.let { " at $it" } ?: ""} · ↻ Write again",
        fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 18.dp).clickable(onClick = onRewrite))
}

/** The mini player pinned under the page: play/pause, the chapter being read, progress, next chapter. */
@Composable
private fun MiniPlayer(onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Surface2).border(1.dp, Outline, RoundedCornerShape(18.dp))
            .clickable(onClick = onOpen).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)
    ) {
        PlayButton(38)
        Column(Modifier.weight(1f)) {
            Text(BriefPlayer.chapterTitle, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text("${mmss(BriefPlayer.elapsed)} / ${mmss(BriefPlayer.total)} · ${if (BriefPlayer.full) "Full" else "Short"} · ${BriefPlayer.speedLabel(BriefPlayer.speed)}",
                fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Muted)
            Progress(Modifier.padding(top = 6.dp))
        }
        Text("⏭", fontSize = 16.sp, color = Muted, modifier = Modifier.clip(CircleShape).clickable { BriefPlayer.next() }.padding(6.dp))
    }
}

@Composable
private fun PlayButton(size: Int) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(Primary).clickable { BriefPlayer.toggle() }, contentAlignment = Alignment.Center) {
        Text(if (BriefPlayer.playing) "❚❚" else "▶", color = OnPrimary, fontSize = (size / 3).sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Progress(modifier: Modifier = Modifier) {
    val total = BriefPlayer.total.coerceAtLeast(1)
    Box(modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(4.dp)).background(Outline)) {
        Box(Modifier.fillMaxWidth((BriefPlayer.elapsed.toFloat() / total).coerceIn(0f, 1f)).fillMaxHeight().background(Primary))
    }
}

private fun mmss(s: Int) = "${s / 60}:${"%02d".format(s % 60)}"

@Composable
private fun Seg(options: List<String>, selected: Int, modifier: Modifier = Modifier, onPick: (Int) -> Unit) {
    Row(modifier.clip(CircleShape).background(Bg).border(1.dp, Outline, CircleShape).padding(3.dp)) {
        options.forEachIndexed { i, o ->
            Text(o, fontSize = 12.5.sp, textAlign = TextAlign.Center, color = if (i == selected) Primary else Muted, fontWeight = if (i == selected) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.weight(1f).clip(CircleShape).background(if (i == selected) PrimaryTint else Color.Transparent).clickable { onPick(i) }.padding(vertical = 6.dp))
        }
    }
}

/** The full player (daily-brief-v1 #4): Short or Full, progress, ⏮ ⏯ ⏭ by chapter, speed and the chapter list. */
@Composable
internal fun PlayerSheet() {
    val ctx = LocalContext.current
    val d = BriefPlayer.doc ?: return
    val chapters = d.chapters
    val starts = DailyBrief.starts(chapters)
    Column(Modifier.padding(horizontal = 18.dp).padding(bottom = 28.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Daily Brief", fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("Phone voice", fontSize = 12.sp, color = Muted)
        }
        Seg(listOf("Short · ${DailyBrief.length(d.shortLines)}", "Full · ${DailyBrief.length(DailyBrief.fullQueue(d).map { it.second })}"),
            if (BriefPlayer.full) 1 else 0, Modifier.fillMaxWidth().padding(top = 12.dp)) { BriefPlayer.setFull(ctx, it == 1) }
        Progress(Modifier.padding(top = 16.dp))
        Row(Modifier.fillMaxWidth().padding(top = 5.dp)) {
            Text(mmss(BriefPlayer.elapsed), fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Muted, modifier = Modifier.weight(1f))
            Text("−" + mmss((BriefPlayer.total - BriefPlayer.elapsed).coerceAtLeast(0)), fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Muted)
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Text("⏮", fontSize = 22.sp, modifier = Modifier.clip(CircleShape).clickable { BriefPlayer.previous() }.padding(10.dp))
            Box(Modifier.width(22.dp))
            PlayButton(58)
            Box(Modifier.width(22.dp))
            Text("⏭", fontSize = 22.sp, modifier = Modifier.clip(CircleShape).clickable { BriefPlayer.next() }.padding(10.dp))
        }
        Seg(BriefPlayer.SPEEDS.map { BriefPlayer.speedLabel(it) }, BriefPlayer.SPEEDS.indexOf(BriefPlayer.speed).coerceAtLeast(1), Modifier.fillMaxWidth().padding(horizontal = 30.dp, vertical = 4.dp)) {
            BriefPlayer.setSpeed(ctx, BriefPlayer.SPEEDS[it])
        }
        Box(Modifier.height(8.dp))
        chapters.forEachIndexed { i, c ->
            val on = BriefPlayer.full && i == BriefPlayer.chapter && BriefPlayer.index >= 0
            val done = BriefPlayer.full && BriefPlayer.index >= 0 && i < BriefPlayer.chapter
            if (i > 0) HorizontalDivider(color = Outline)
            Row(Modifier.fillMaxWidth().clickable { BriefPlayer.playChapter(i) }.padding(horizontal = 4.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Muted, modifier = Modifier.width(22.dp))
                Text((if (on) "▸ " else if (done) "✓ " else "") + c.title, fontSize = 14.sp, modifier = Modifier.weight(1f),
                    color = if (on) Primary else if (done) Muted else TextMain, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
                Text(mmss((starts[i] / BriefPlayer.speed).toInt()), fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Muted)
            }
        }
    }
}
