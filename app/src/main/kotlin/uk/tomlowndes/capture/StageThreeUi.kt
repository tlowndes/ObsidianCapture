package uk.tomlowndes.capture

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Duration
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

// ---- Sleep and steps from Health Connect (body-people-errands-v1) ----

/** Reads last night's sleep and yesterday's steps and puts them in the Habits rows. Phone only. */
internal object HealthSync {
    val PERMISSIONS = setOf(
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class),
    )

    fun available(ctx: Context) = HealthConnectClient.getSdkStatus(ctx) == HealthConnectClient.SDK_AVAILABLE

    suspend fun granted(ctx: Context): Boolean = available(ctx) &&
        HealthConnectClient.getOrCreate(ctx).permissionController.getGrantedPermissions().containsAll(PERMISSIONS)

    /** Reads and saves; does nothing when Health Connect isn't there or allowed. */
    suspend fun sync(ctx: Context, vault: Uri, today: LocalDate = LocalDate.now()) {
        if (!granted(ctx)) return
        val c = HealthConnectClient.getOrCreate(ctx)
        val zone = ZoneId.systemDefault()
        val from = today.minusDays(1).atTime(18, 0).atZone(zone).toInstant()
        val to = today.atTime(14, 0).atZone(zone).toInstant()
        val nights = c.readRecords(ReadRecordsRequest(SleepSessionRecord::class, TimeRangeFilter.between(from, to))).records
        val sleep = nights.sumOf { Duration.between(it.startTime, it.endTime).toMinutes() }.toInt()
        val bed = nights.minByOrNull { it.startTime }?.startTime?.atZone(zone)?.toLocalTime()
        val y0 = today.minusDays(1).atStartOfDay(zone).toInstant()
        val y1 = today.atStartOfDay(zone).toInstant()
        val steps = c.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), TimeRangeFilter.between(y0, y1)))[StepsRecord.COUNT_TOTAL]?.toInt()
        withContext(Dispatchers.IO) {
            val v = appVault(ctx, vault)
            if (sleep > 0) Habits.update(v, today) { Body.with(it, sleep, null, bed) }
            if (steps != null && steps > 0) Habits.update(v, today.minusDays(1)) { Body.with(it, null, steps, null) }
        }
    }
}

/** Health Connect opens this to show why Flight Deck asks. */
class HealthRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CaptureTheme {
                Column(Modifier.fillMaxSize().background(Bg).padding(28.dp), verticalArrangement = Arrangement.Center) {
                    Text("Sleep and steps", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("Flight Deck reads last night's sleep and yesterday's steps to show them on your Deck, pick a shorter task after a short night, " +
                        "and find patterns in What helps you. They're saved as two numbers a day in your Habits note in your own vault. Nothing else is read, and nothing is sent anywhere.",
                        fontSize = 14.sp, color = Muted, modifier = Modifier.padding(top = 10.dp))
                    Text("Close", color = Primary, fontSize = 15.sp, modifier = Modifier.padding(top = 18.dp).clickable { finish() })
                }
            }
        }
    }
}

/** Settings › Health: the switch (asks Health Connect), step goal, and easy day after a short night. */
@Composable
internal fun HealthSettings(prefs: AppPrefs, vault: Uri?) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var on by remember { mutableStateOf(prefs.healthOn) }
    var goal by remember { mutableIntStateOf(prefs.stepGoal) }
    var easy by remember { mutableStateOf(prefs.easyAfterShort) }
    var note by remember { mutableStateOf<String?>(null) }
    val ask = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { got ->
        on = got.containsAll(HealthSync.PERMISSIONS); prefs.healthOn = on
        note = if (on) null else "Health Connect didn't allow it"
        if (on && vault != null) scope.launch { runCatching { HealthSync.sync(ctx, vault) } }
    }
    SectionLabel("Health")
    DeckCard {
        Box(Modifier.padding(horizontal = 14.dp)) {
            SettingRow("Read sleep and steps", note ?: if (HealthSync.available(ctx)) "Health Connect · Pixel Watch or phone" else "Health Connect isn't on this phone") {
                AppSwitch(on) { want ->
                    if (!want) { on = false; prefs.healthOn = false }
                    else if (HealthSync.available(ctx)) ask.launch(HealthSync.PERMISSIONS) else note = "Install Health Connect from the Play Store first"
                }
            }
        }
        if (on) {
            HorizontalDivider(color = Outline)
            Box(Modifier.padding(horizontal = 14.dp)) {
                SettingRow("Step goal", "%,d".format(goal)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(5000, 7000, 10000).forEach { g -> AppChip(goal == g, { goal = g; prefs.stepGoal = g }, { Text("${g / 1000}k") }) }
                    }
                }
            }
            HorizontalDivider(color = Outline)
            Box(Modifier.padding(horizontal = 14.dp)) {
                SettingRow("Easy day after short sleep", "Under ${Body.hm(Body.SHORT_MIN)} picks shorter tasks") { AppSwitch(easy) { easy = it; prefs.easyAfterShort = it } }
            }
        }
    }
}

// ---- What helps you (wellbeing-v1) ----

/** Last month's patterns, made once (first week of the month) and kept as a note. Blocking. */
internal fun loadOrMakePatterns(ctx: Context, prefs: AppPrefs, vault: Uri, today: LocalDate): MonthPatterns? {
    val v = appVault(ctx, vault)
    val m = Patterns.lastMonth(today)
    Patterns.load(v, m)?.let { return it }
    if (claudeKey(ctx, prefs) == null) return null
    val habits = runCatching { Habits.load(v) }.getOrNull()
    val focus = runCatching { FlightNotes.loadSince(v, m.atDay(1)) }.getOrDefault(emptyList())
    val done = runCatching { VaultWriter(ctx, vault).listTaskLists().done }.getOrDefault(emptyList())
        .filter { it.done?.let { d -> YearMonth.from(d) == m } == true }
    val p = Patterns.parse(ask(ctx, prefs, Patterns.facts(m, habits, focus, done), Patterns.SYSTEM), m) ?: return null
    if (p.patterns.isNotEmpty()) Patterns.save(v, p, today)
    return p.takeIf { it.patterns.isNotEmpty() }
}

@Composable
internal fun HelpsCard(p: MonthPatterns, onAll: () -> Unit, onClose: () -> Unit) {
    DeckCard(border = Purple.copy(alpha = 0.5f), modifier = Modifier.padding(top = 12.dp)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("What helped in ${Patterns.monthName(p.month)}", fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("✕", color = Muted, modifier = Modifier.clip(CircleShape).clickable(onClick = onClose).padding(6.dp))
            }
            p.patterns.take(3).forEach { pt -> PatternRow(pt) }
            Text("See all ${p.patterns.size} ›", color = Primary, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp).clickable(onClick = onAll))
        }
    }
}

@Composable
private fun PatternRow(pt: Pattern) {
    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(pt.emoji.ifEmpty { if (pt.helps) "↑" else "↓" }, fontSize = 17.sp)
        Column(Modifier.weight(1f)) {
            Text(pt.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (pt.helps) TextMain else Amber)
            Text(pt.detail, fontSize = 12.5.sp, color = Muted)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HelpsSheet(p: MonthPatterns, onDone: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDone, sheetState = sheet, containerColor = Surface1) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            Text("${Patterns.monthName(p.month)}", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            if (p.summary.isNotEmpty()) Text(p.summary, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Muted)
            if (p.helps.isNotEmpty()) { GroupLabel("Helps"); p.helps.forEach { PatternRow(it) } }
            if (p.drags.isNotEmpty()) { Box(Modifier.height(10.dp)); GroupLabel("Drags"); p.drags.forEach { PatternRow(it) } }
            Text("Saved in ${Patterns.path(p.month)}", fontSize = 11.sp, color = Muted, modifier = Modifier.padding(top = 14.dp))
        }
    }
}

// ---- Flashcards (growth-v1) ----

/** Learn: today's cards (max 10), tap to flip, Again / Hard / Got it. Makes cards for new #learn notes first. */
@Composable
fun LearnScreen(vault: Uri?, prefs: AppPrefs, snack: SnackbarHostState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val today = LocalDate.now()
    var decks by remember { mutableStateOf<List<CardDeck>?>(null) }
    var queue by remember { mutableStateOf<List<Card>>(emptyList()) }
    var flipped by remember { mutableStateOf(false) }
    var making by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(vault, refresh) {
        val uri = vault ?: return@LaunchedEffect
        val ds = withContext(Dispatchers.IO) { runCatching { Flashcards.decks(appVault(ctx, uri), File(ctx.cacheDir, "vault-index.json")) }.getOrDefault(emptyList()) }
        decks = ds; queue = Flashcards.dueToday(ds, today); flipped = false
    }
    fun make(d: CardDeck) {
        val uri = vault ?: return
        making = d.title
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val v = appVault(ctx, uri)
                    val cards = Flashcards.cardsFrom(ask(ctx, prefs, VaultIo.read(v, d.path).take(12_000), Flashcards.SYSTEM), d.path)
                    require(cards.isNotEmpty()) { "Claude didn't send back any cards" }
                    Flashcards.addCards(v, d.path, cards); cards.size
                }
            }
            making = null
            r.onSuccess { snack.showSnackbar("Made $it cards from ${d.title}"); refresh++ }.onFailure { snack.showSnackbar("Failed: ${it.message}") }
        }
    }
    fun grade(c: Card, g: Grade) {
        val uri = vault ?: return
        queue = queue.drop(1) + if (g == Grade.AGAIN) listOf(c) else emptyList()
        flipped = false
        scope.launch { withContext(Dispatchers.IO) { runCatching { Flashcards.save(appVault(ctx, uri), Flashcards.review(c, g, today)) } } }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        Text("‹ Deck", color = Muted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable(onClick = onBack).padding(top = 10.dp))
        Text("Learn", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
        val ds = decks ?: return@Column
        Text("${queue.size} today · ${Flashcards.minutes(queue.size)} min", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Muted, modifier = Modifier.padding(bottom = 12.dp))
        queue.firstOrNull()?.let { c ->
            Column(
                Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(18.dp)).background(Surface1).border(1.dp, Outline, RoundedCornerShape(18.dp))
                    .clickable { flipped = !flipped }.padding(20.dp),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("FROM: ${VaultTasks.noteName(c.path).uppercase()}", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Muted)
                Text(if (flipped) c.a else c.q, fontSize = 19.sp, fontWeight = if (flipped) FontWeight.Normal else FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
                if (!flipped) Text("Tap to flip", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 14.dp))
            }
            if (flipped) Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Grade.values().forEach { g -> SheetButton(g.label, Modifier.weight(1f), primary = g == Grade.GOT_IT) { grade(c, g) } }
            }
        } ?: Text(if (ds.isEmpty()) "Tag any note #learn and come back here." else "All done for today. 🎉", fontSize = 14.sp, color = Muted, modifier = Modifier.padding(vertical = 20.dp))
        if (ds.isNotEmpty()) {
            GroupLabel("Decks")
            ListCard(Modifier.padding(top = 6.dp)) {
                ds.forEachIndexed { i, d ->
                    if (i > 0) HorizontalDivider(color = Outline)
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(d.title, fontSize = 14.sp)
                            Text(if (d.cards.isEmpty()) "no cards yet" else "${d.cards.size} cards · ${d.due(today)} due", fontSize = 12.sp, color = Muted)
                        }
                        if (d.cards.isEmpty() && claudeKey(ctx, prefs) != null)
                            Text(if (making == d.title) "Making…" else "Make 5 cards", color = Primary, fontSize = 13.sp, modifier = Modifier.clickable(enabled = making == null) { make(d) })
                    }
                }
            }
        }
        Text("＋ Tag any note #learn and Claude makes 5 cards from it", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(vertical = 12.dp))
    }
}
