package uk.tomlowndes.capture

import android.Manifest
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column

import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val OWNER = "Tom"
private const val SHORT_MIN = TaskViews.SHORT_MIN

/** Energy chip on Flight Deck. Low brings Stuck? to the top and short tasks; High shows 3 top tasks. */
enum class Energy(val label: String) { LOW("Low"), MED("Med"), HIGH("High") }

/** What Flight Deck reads from the vault in one go. */
internal data class DeckData(
    val tasks: List<TaskItem> = emptyList(),
    val project: IdeaSummary? = null,
    val unread: Int = 0,
    val layout: DeckLayout = DeckLayout(),
    val stuck: List<StuckItem> = emptyList(),
    val focusMin: Int = 0,
    val habits: HabitsNote? = null,
    val calendars: List<CalendarSource>? = null,
    val jobs: List<Job> = emptyList(),
    val routines: List<Routine> = emptyList(),
    val passages: List<Passage> = emptyList(),
    val dayDone: DayDone = DayDone(),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeckScreen(
    vault: Uri?, prefs: AppPrefs, snack: SnackbarHostState, layoutRev: Int = 0, onEditDeck: () -> Unit = {}, onAddTile: () -> Unit = {}, onOpenTool: (HangarTool) -> Unit = {},
    onOpenTab: (Tab) -> Unit, onOpenSub: (Sub) -> Unit, onStartFocus: (TaskItem, Int) -> Unit,
    onOpenSearch: () -> Unit, onOpenArticle: (ReadRef) -> Unit = {}, onPlayRoutine: (String) -> Unit = {},
    onOpenSettings: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var rewalk by remember { mutableStateOf(false) }
    var energy by remember { mutableStateOf(prefs.energy) }
    var later by remember { mutableStateOf(prefs.laterToday) }
    var showStrip by remember { mutableStateOf(prefs.weatherStrip) }
    // Show the last Deck straight away, then refresh from the vault.
    var data by remember { mutableStateOf(DeckCache.load(ctx) ?: DeckData()) }
    var paying by remember { mutableStateOf(emptySet<String>()) }
    var vaultOk by remember { mutableStateOf(true) }
    LaunchedEffect(vault, refresh) {
        vaultOk = vault != null && withContext(Dispatchers.IO) { runCatching { appVault(ctx, vault).list("") }.isSuccess }
    }
    var cal by remember { mutableStateOf(CalendarSync.cached(ctx)) }
    var weather by remember { mutableStateOf<WeatherSummary?>(null) }
    var travel by remember { mutableStateOf<TravelLine?>(null) }
    LaunchedEffect(cal, refresh, data.calendars) {
        val on = data.calendars.orEmpty().filter { it.enabled }.map { it.id }.toSet()
        val events = cal.filter { it.id in on || it.id == AppEvents.CAL_ID }.flatMap { it.events }
        travel = withContext(Dispatchers.IO) { runCatching { TravelFetch.line(ctx, events) }.getOrNull() }
    }
    var canLocate by remember { mutableStateOf(WeatherFetch.allowed(ctx)) }
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { canLocate = it }
    LaunchedEffect(refresh, canLocate) {
        weather = withContext(Dispatchers.IO) { runCatching { WeatherFetch.today(ctx, force = refresh > 0) }.getOrNull() }
    }

    // Proton: sync on start, then every 30 min while Flight Deck is open.
    LaunchedEffect(vault) {
        val uri = vault ?: return@LaunchedEffect
        while (true) {
            cal = withContext(Dispatchers.IO) { runCatching { CalendarSync.sync(ctx, uri) }.getOrDefault(cal) }
            delay(CalendarSync.EVERY_MS)
        }
    }

    // Hangar tools picked as More tiles (add-to-more-v1) and which of them need you.
    val tileTools = remember(layoutRev) { deckTiles(prefs) }
    var tileNeeds by remember { mutableStateOf(emptySet<HangarTool>()) }
    LaunchedEffect(vault, refresh, layoutRev) {
        val uri = vault ?: return@LaunchedEffect
        tileNeeds = withContext(Dispatchers.IO) { runCatching { dockNeeds(ctx, uri, prefs, tileTools) }.getOrDefault(emptySet()) }
    }
    LaunchedEffect(vault, refresh, layoutRev) {
        val uri = vault ?: return@LaunchedEffect
        if (rewalk) { VaultScan.invalidate(); rewalk = false } // pull to refresh: walk the whole vault again
        data = withContext(Dispatchers.IO) {
            runCatching { VaultRoute.check(ctx, uri, force = refresh > 0) }
            DeckData(
                tasks = runCatching { VaultWriter(ctx, uri).listTasks() }.getOrDefault(emptyList()),
                project = runCatching { IdeaStore(ctx, uri, prefs).list().firstOrNull { it.status == "active" } }.getOrNull(),
                unread = runCatching { ReadNote.unread(ReadStore(ctx, uri).inbox()).first }.getOrDefault(0),
                layout = runCatching { DeckConfig.load(appVault(ctx, uri)) }.getOrDefault(DeckLayout()),
                dayDone = runCatching { DeckConfig.loadDayDone(appVault(ctx, uri)) }.getOrDefault(DayDone()),
                stuck = runCatching { FlightNotes.loadStuck(appVault(ctx, uri)).getValue(StuckLength.QUICK) }.getOrDefault(emptyList()),
                focusMin = runCatching { FlightNotes.loadToday(appVault(ctx, uri)).sumOf { it.minutes } }.getOrDefault(0),
                habits = runCatching { Habits.load(appVault(ctx, uri)) }.getOrNull(),
                calendars = runCatching { CalendarConfig.load(appVault(ctx, uri)) }.getOrNull(),
                jobs = runCatching { Jobs.list(appVault(ctx, uri)) }.getOrDefault(emptyList()),
                routines = runCatching { Routines.load(appVault(ctx, uri)) }.getOrDefault(emptyList()),
            ).also { DeckCache.save(ctx, it) }
        }
    }

    fun tick(t: TaskItem) {
        val uri = vault ?: return
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { VaultWriter(ctx, uri).completeTask(t, prefs.recurring) } }
            refresh++
            r.onFailure { snack.showSnackbar("Failed: ${it.message}") }
            r.onSuccess { c ->
                if (snack.showSnackbar("Task completed", "Undo", duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) {
                    withContext(Dispatchers.IO) { runCatching { VaultWriter(ctx, uri).restoreTask(t, c) } }
                    refresh++
                }
            }
        }
    }

    fun changeHabits(f: (HabitDay) -> HabitDay) {
        val uri = vault ?: return
        val today = LocalDate.now()
        data.habits?.let { n -> data = data.copy(habits = n.copy(days = n.days + (today to f(n.day(today))))) }
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { Habits.update(appVault(ctx, uri), today, f) } }
                .onFailure { snack.showSnackbar("Failed: ${it.message}") }
            val fresh = withContext(Dispatchers.IO) { runCatching { Habits.load(appVault(ctx, uri)) }.getOrNull() }
            if (fresh != null) data = data.copy(habits = fresh)
        }
    }

    var breaking by remember { mutableStateOf<TaskItem?>(null) }
    var talking by remember { mutableStateOf<TaskItem?>(null) }
    var snoozed by remember { mutableStateOf(prefs.talkSnoozed) }
    // Evening check-in (journal-prompt-v2): written today? Read from this month's journal note.
    var journalDone by remember { mutableStateOf(false) }
    var journalOpen by remember { mutableStateOf(false) }
    var journalSkipped by remember { mutableStateOf(prefs.journalSkippedOn) }
    LaunchedEffect(vault, refresh) {
        val uri = vault ?: return@LaunchedEffect
        journalDone = withContext(Dispatchers.IO) { runCatching { Journal.written(appVault(ctx, uri), LocalDate.now()) }.getOrDefault(false) }
    }
    // A quieter Deck? (quiet-day-v1): asked once, on any device, while a quiet card is still on.
    var quietAsk by remember { mutableStateOf(false) }
    var stillAsk by remember { mutableStateOf(false) }
    LaunchedEffect(vault) {
        val uri = vault ?: return@LaunchedEffect
        quietAsk = withContext(Dispatchers.IO) { runCatching { DeckConfig.loadShouldAskQuiet(appVault(ctx, uri)) }.getOrDefault(false) }
        // A bit quieter still? (care-welcome-v1): the second round, once the first is answered.
        stillAsk = !quietAsk && withContext(Dispatchers.IO) { runCatching { DeckConfig.loadShouldAskQuietStill(appVault(ctx, uri)) }.getOrDefault(false) }
    }
    val nowDt = java.time.LocalDateTime.now()

    // Stage 1 (home-life-v1): chores that decay, and one passage from your reading.
    var chores by remember { mutableStateOf<List<Chore>>(emptyList()) }
    var passages by remember { mutableStateOf<List<Passage>>(emptyList()) }
    var passageIdx by remember { mutableIntStateOf(0) }
    LaunchedEffect(vault, refresh) {
        val uri = vault ?: return@LaunchedEffect
        chores = withContext(Dispatchers.IO) { runCatching { Chores.load(appVault(ctx, uri)) }.getOrDefault(emptyList()) }
    }
    LaunchedEffect(vault) {
        val uri = vault ?: return@LaunchedEffect
        passages = withContext(Dispatchers.IO) { runCatching { Highlights.forDay(appVault(ctx, uri), LocalDate.now()) }.getOrDefault(emptyList()) }
        passageIdx = 0
    }
    fun choreDone(c: Chore) {
        val uri = vault ?: return
        val day = LocalDate.now()
        chores = chores.map { if (it.name == c.name) it.copy(done = day) else it }
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Chores.markDone(appVault(ctx, uri), c, day) } }
            r.onFailure { snack.showSnackbar("Failed: ${it.message}") }
            r.onSuccess { before ->
                if (snack.showSnackbar("${c.label} done", "Undo", duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) {
                    withContext(Dispatchers.IO) { runCatching { Chores.put(appVault(ctx, uri), c.name, before) } }
                    chores = chores.map { if (it.name == c.name) before else it }
                }
            }
        }
    }
    fun passageSeen(p: Passage, keep: Boolean) {
        val uri = vault ?: return
        passageIdx++
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    val v = appVault(ctx, uri)
                    if (keep) Highlights.keep(v, p, LocalDate.now())
                    HighlightsSeen.add(v, listOf(Highlights.key(p)))
                }
            }.onFailure { snack.showSnackbar("Failed: ${it.message}") }
            if (keep) snack.showSnackbar("Kept in Highlights")
        }
    }

    fun setMood(n: Int) = changeHabits { Mood.set(it, n) }

    // Stage 3 (body-people-errands-v1, wellbeing-v1, growth-v1): sleep and steps, What helps you, flashcards.
    LaunchedEffect(vault, refresh) {
        val uri = vault ?: return@LaunchedEffect
        if (!prefs.healthOn) return@LaunchedEffect
        val ok = runCatching { HealthSync.sync(ctx, uri) }.isSuccess
        if (ok) withContext(Dispatchers.IO) { runCatching { Habits.load(appVault(ctx, uri)) }.getOrNull() }?.let { data = data.copy(habits = it) }
    }
    var helps by remember { mutableStateOf<MonthPatterns?>(null) }
    var helpsOpen by remember { mutableStateOf(false) }
    var helpsClosed by remember { mutableStateOf(prefs.helpsClosed) }
    LaunchedEffect(vault) {
        val uri = vault ?: return@LaunchedEffect
        val day = LocalDate.now()
        if (!Patterns.showCard(day) || helpsClosed == Patterns.lastMonth(day).toString()) return@LaunchedEffect
        helps = withContext(Dispatchers.IO) { runCatching { loadOrMakePatterns(ctx, prefs, uri, day) }.getOrNull() }
    }
    var cardsDue by remember { mutableStateOf<Int?>(null) }
    // Stage 4 (proton-inbox-v1, meals-spend-v1): the email digest from the box, and spend from receipts.
    var inbox by remember { mutableStateOf<InboxData?>(null) }
    var mailOpen by remember { mutableStateOf<List<Mail>?>(null) }
    var spend by remember { mutableStateOf<Pair<MonthSpend, MonthSpend>?>(null) }
    var recat by remember { mutableStateOf<Receipt?>(null) }
    // Stage 5: people a week ahead, tonight's meal, errands.
    var home by remember { mutableStateOf<HomeBits?>(null) }
    // Ticket Rail's tile on the Deck (hangar-dock-v2): how many tickets hang on the rail.
    var railOn by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(vault, refresh) {
        val uri = vault ?: return@LaunchedEffect
        railOn = withContext(Dispatchers.IO) { runCatching { RailStatus.load(ctx, uri).onRail }.getOrNull() }
    }
    LaunchedEffect(vault, refresh) {
        val uri = vault ?: return@LaunchedEffect
        home = withContext(Dispatchers.IO) { runCatching { loadHomeBits(ctx, uri, prefs, LocalDate.now()) }.getOrNull() }
    }
    LaunchedEffect(vault, refresh) {
        val uri = vault ?: return@LaunchedEffect
        inbox = withContext(Dispatchers.IO) { runCatching { loadInbox(ctx, uri) }.getOrNull() }
        spend = withContext(Dispatchers.IO) { runCatching { loadSpend(ctx, uri, LocalDate.now()) }.getOrNull() }
    }
    LaunchedEffect(vault, refresh) {
        val uri = vault ?: return@LaunchedEffect
        cardsDue = withContext(Dispatchers.IO) {
            runCatching { Flashcards.decks(appVault(ctx, uri), java.io.File(ctx.cacheDir, "vault-index.json")) }.getOrNull()
                ?.takeIf { it.isNotEmpty() }?.let { Flashcards.dueToday(it, LocalDate.now()).size }
        }
    }

    val low = energy == Energy.LOW
    // After a short night, Next step and Top tasks only offer easy ones (settings › Health).
    val shortNight = !low && prefs.easyAfterShort && data.habits?.let { Body.shortNight(it, LocalDate.now()) } == true
    val maxMin = if (low) SHORT_MIN else if (shortNight) Body.EASY_TASK_MIN else null
    val next = Deck.nextStep(data.tasks, later, maxMin)
    val top = Deck.topTasks(data.tasks, if (energy == Energy.HIGH) 3 else 2, later, maxMin)
    val hidden = data.layout.hidden
    val today = LocalDate.now()

    // The Now line (tidy-v1): one waiting prompt at a time, "+N" steps through the rest.
    var nowIdx by remember { mutableIntStateOf(0) }
    val nowItems = buildList {
        if (!vaultOk) add(NowItem.Line("⚠ Vault not found", "Pick folder ›", warn = true, onClick = onOpenSettings))
        if (HomeCard.PLAN !in hidden) {
            val left = DayPlan.leftToday(data.tasks, today).size
            if (DayPlan.isMorning(nowDt) && prefs.plannedOn != today.toString() && !data.dayDone.planned(today))
                add(NowItem.Line("☀️ Plan today · pick up to ${DayPlan.PICK_MAX}", "Plan ›") { onOpenSub(Sub.PLAN) })
            else if (DayPlan.isEvening(nowDt) && prefs.shutDownOn != today.toString() && !data.dayDone.shutDown(today) && (left > 0 || !journalDone)) {
                // One evening line (evening-week-v1): Shut down walks through what's left, the check-in and Night Pad.
                val what = listOfNotNull(if (left > 0) "$left left" else null, if (!journalDone) "check in" else null).joinToString(", then ")
                add(NowItem.Line("🌙 Shut down · $what", "Start ›") { onOpenSub(Sub.PLAN) })
            }
        }
        // With Plan · Shut down on, the check-in and mood are steps in Shut down, not lines of their own.
        val shutDownOn = HomeCard.PLAN !in hidden
        val checkIn = !shutDownOn && HomeCard.HIGHLIGHTS !in hidden && nowDt.hour >= Journal.ASK_FROM && !journalDone && journalSkipped != today.toString()
        if (checkIn) add(NowItem.Line("📓 Evening check-in · 1 min", "Write ›", onDismiss = {
            journalSkipped = today.toString(); prefs.journalSkippedOn = journalSkipped
        }) { journalOpen = true })
        // The check-in asks for mood too, so the mood faces wait until it's done.
        else if (!shutDownOn && HomeCard.MOOD !in hidden && data.habits?.let { Mood.ask(it, today, nowDt.hour) } == true) add(NowItem.Mood(::setMood))
        // Monthly Migrate now sits in the weekly review (evening-week-v1).
    }

    var earlierOpen by remember { mutableStateOf(prefs.earlierOpenOn == LocalDate.now().toString()) }
    var habitsOpen by remember { mutableStateOf(false) }
    var routinesOpen by remember { mutableStateOf(false) }
    var pulling by remember { mutableStateOf(false) }
    LaunchedEffect(data) { pulling = false }

    // Long-press drag on the sections under Next step (deck-drag-v1). The order is shared with the PC through the vault.
    val drag = remember { SectionDrag() }
    val scroll = rememberScrollState()
    var origin by remember { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(Rect.Zero) }
    DragAutoScroll(drag, scroll) { viewport }

    fun saveLayout(l: DeckLayout) {
        data = data.copy(layout = l)
        val uri = vault ?: return
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { DeckConfig.save(appVault(ctx, uri), l) } }
                .onFailure { snack.showSnackbar("Couldn't save: ${it.message}") }
        }
    }

    fun dropSection() {
        val s = drag.section ?: return
        val index = drag.target
        val others = drag.others()
        drag.stop()
        if (index == null) return
        val old = data.layout
        val l = DeckSection.moved(old, s, others.getOrNull(index))
        if (DeckSection.inOrder(l) == DeckSection.inOrder(old)) return
        saveLayout(l)
        scope.launch {
            if (snack.showSnackbar("Deck order saved · PC too", "Undo", duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) saveLayout(old)
        }
    }

    /**
     * One block under Next step. Weather and the agenda are separate, so a long-press moves just that card.
     * The Today heading stays on whichever of the two is showing; with both, it stays on the weather.
     */
    @Composable
    fun Section(section: DeckSection) {
        val w = weather
        val weatherOn = HomeCard.WEATHER !in hidden && (w != null || !canLocate || travel != null)
        when (section) {
            DeckSection.WEATHER -> {
                if (HomeCard.WEATHER in hidden) return
                val rows = mutableListOf<@Composable () -> Unit>()
                if (w != null && !showStrip) rows.add {
                    QuietRow(
                        w.parts.firstOrNull()?.icon ?: "⛅", "${w.high}° / ${w.low}° · ${w.headline}",
                        if (w.tomorrow) "tomorrow ▾" else "▾", onClick = { showStrip = true; prefs.weatherStrip = true }
                    )
                }
                else if (w == null && !canLocate) rows.add {
                    QuietRow("📍", "Weather", "Allow location ›", Primary, onClick = { askLocation.launch(Manifest.permission.ACCESS_COARSE_LOCATION) })
                }
                travel?.let { tr ->
                    val hm = DateTimeFormatter.ofPattern("HH:mm")
                    rows.add { QuietRow("🚗", "Leave by ${tr.leaveBy.format(hm)} · ${tr.event.title}", "${tr.minutes} min", Primary) }
                }
                val expanded = w != null && showStrip
                if (rows.isEmpty() && !expanded) return
                SectionLabel("Today")
                if (expanded) {
                    WeatherCard(w!!, null, true) { showStrip = false; prefs.weatherStrip = false }
                    if (rows.isNotEmpty()) Box(Modifier.height(8.dp))
                }
                RowsCard(rows)
            }
            DeckSection.AGENDA -> {
                val rows = mutableListOf<@Composable () -> Unit>()
                if (HomeCard.TODAY !in hidden) rows.addAll(calendarRows(data.calendars, cal, onOpenSettings, earlierOpen) {
                    earlierOpen = !earlierOpen; prefs.earlierOpenOn = if (earlierOpen) LocalDate.now().toString() else null
                })
                data.habits?.let { h ->
                    Body.sleepLine(h, today)?.let { line -> rows.add { QuietRow(null, line, onClick = { onOpenSub(Sub.HABITS) }) } }
                    if (shortNight) rows.add { QuietRow(null, "🔋 Short night, go easy", "tasks under ${Body.EASY_TASK_MIN}m", rightColour = Amber) }
                    Body.stepsLine(h, today, prefs.stepGoal)?.let { line -> rows.add { QuietRow(null, line, onClick = { onOpenSub(Sub.HABITS) }) } }
                }
                home?.let { hb ->
                    hb.tonight?.let { m -> rows.add { QuietRow("🍽", "Tonight: ${m.label}", if (m.recipe != null) "Cook this ›" else "Week ›", rightColour = Primary,
                        onClick = { onOpenSub(if (m.recipe != null) Sub.RECIPES else Sub.MEALS) }) } }
                    hb.people.take(2).forEach { n -> rows.add { QuietRow(null, People.deckLine(n), onClick = { onOpenSub(Sub.PEOPLE) }) } }
                }
                if (HomeCard.BILLS !in hidden) TaskViews.billsDue(data.tasks, today).forEach { b ->
                    rows.add {
                        val paid = b.task.raw in paying
                        QuietRow(
                            null, b.name, listOfNotNull(b.amount?.let { TaskViews.money(it) }, b.task.due?.let { dueLabel(it) }).joinToString(" · "),
                            rightColour = if (paid) Muted else Amber, textColour = if (paid) Muted else TextMain, strike = paid,
                            lead = { TickBox(done = paid, onClick = { if (!paid) { paying = paying + b.task.raw; tick(b.task) } }) }
                        )
                    }
                }
                if (HomeCard.CHORES !in hidden) Chores.due(chores, today).firstOrNull()?.let { c ->
                    rows.add {
                        val fill = c.fill(today)
                        QuietRow(
                            null, c.label, c.ageLabel(today).substringBefore(" ·"), rightColour = choreColour(fill),
                            onClick = { onOpenSub(Sub.CHORES) },
                            lead = { TickBox(done = false, onClick = { choreDone(c) }) }
                        )
                    }
                }
                if (rows.isEmpty()) return
                if (!weatherOn) SectionLabel("Today")
                RowsCard(rows)
            }
            // Inbox moved to Hangar (hangar-dock-v2).
            DeckSection.INBOX -> {}
            DeckSection.SPEND -> if (HomeCard.SPEND !in hidden) spend?.let { (now, last) -> SpendCard(now, last) { recat = it } }
            DeckSection.TOP_TASKS -> if (HomeCard.TOP_TASKS !in hidden && top.isNotEmpty()) {
                SectionLabel(if (low) "Top tasks · under $SHORT_MIN min" else "Top tasks", "All ›") { onOpenTab(Tab.TASKS) }
                DeckCard {
                    top.forEachIndexed { i, t ->
                        if (i > 0) HorizontalDivider(color = Outline)
                        TaskLine(t, edge = false, onTick = { tick(t) }) { t.due?.let { DuePill(it) } }
                    }
                }
            }
            DeckSection.MORE -> {
                // Routines, Journal, Learn, People and Ticket Rail as small tiles. Home, Projects, Meals and Errands
                // moved to Hangar (hangar-dock-v2); Ticket Rail is in both.
                val showRoutines = HomeCard.ROUTINES !in hidden && data.routines.isNotEmpty()
                val showJournal = HomeCard.HIGHLIGHTS !in hidden
                val passage = passages.getOrNull(passageIdx).takeIf { HomeCard.READING !in hidden }
                if (passage != null) {
                    SectionLabel("From your reading")
                    HighlightsCard(passage, passageIdx, passages.size,
                        onOpen = { onOpenArticle(passage.ref) },
                        onKeep = { passageSeen(passage, keep = true) },
                        onNext = { passageSeen(passage, keep = false) })
                }
                val showLearn = HomeCard.LEARN !in hidden && cardsDue != null
                val showLife = vault != null
                run {
                    SectionLabel("More", "✎ Edit", onEditDeck)
                    val tiles = mutableListOf<@Composable RowScope.() -> Unit>()
                    if (showRoutines) tiles.add {
                        Tile("🔁", "Routines", data.routines.singleOrNull()?.name ?: "${data.routines.size} ready") {
                            data.routines.singleOrNull()?.let { onPlayRoutine(it.name) } ?: run { routinesOpen = true }
                        }
                    }
                    if (showJournal) tiles.add { Tile("📓", "Journal", if (journalDone) "✓ written" else "not yet today", dot = !journalDone && nowDt.hour >= Journal.ASK_FROM) { journalOpen = true } }
                    if (showLearn) tiles.add { Tile("🧠", "Learn", cardsDue?.takeIf { it > 0 }?.let { "$it due · ${Flashcards.minutes(it)} min" } ?: "all done", dot = (cardsDue ?: 0) > 0) { onOpenSub(Sub.LEARN) } }
                    if (showLife) {
                        if (HomeCard.PEOPLE !in hidden) tiles.add { Tile("👥", "People", home?.people?.firstOrNull()?.let { if (it.birthday != null) "🎂 ${it.person.name} ${it.days}d" else "say hi to ${it.person.name}" } ?: "all good") { onOpenSub(Sub.PEOPLE) } }
                        tileTools.forEach { t ->
                            tiles.add {
                                val sub = if (t == HangarTool.TICKET_RAIL) railOn?.let { "$it on the rail" } else if (t in tileNeeds) "needs you" else null
                                Tile(t.icon, t.title, sub, dot = t in tileNeeds, colour = t.colour()) { onOpenTool(t) }
                            }
                        }
                    }
                    tiles.add { Tile("＋", "Add", "any Hangar tool", onClick = onAddTile) }
                    // Four to a row, like Hangar; a short last row keeps the same tile width.
                    val perRow = 4
                    tiles.chunked(perRow).forEachIndexed { i, chunk ->
                        if (i > 0) Box(Modifier.height(8.dp))
                        TileRow {
                            chunk.forEach { it() }
                            repeat(perRow - chunk.size) { Box(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }

    PullToRefreshBox(isRefreshing = pulling, onRefresh = { pulling = true; rewalk = true; refresh++ }, modifier = Modifier.fillMaxSize()) {
    Column(
        Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot(); viewport = it.boundsInRoot() }
            .verticalScroll(scroll).padding(horizontal = 18.dp)
    ) {
        DeckHeader(onOpenSearch = onOpenSearch, onOpenSettings = onOpenSettings,
            // The Daily Slip button only once a printer is paired (care-welcome-v1); Settings › Printer pairs one.
            onPrint = if (remember { SlipPrinter.saved(ctx) != null }) ({ onOpenSub(Sub.PRINT) }) else null)
        SubLine("${today.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK))} · ${greeting()}, $OWNER") {
            EnergyChip(energy) { energy = it; prefs.energy = it }
        }
        UpdateBanner()
        CapBanner(prefs, vault, refresh)
        ServerCopyChip(onClick = onOpenSettings)
        NowLine(nowItems, nowIdx) { nowIdx++ }
        // Daily Brief (daily-brief-v1): the morning email as a card; shrinks to a row once heard or read.
        if (HomeCard.BRIEF !in hidden) DailyBriefCard(vault, prefs, weather, refresh) { onOpenSub(Sub.BRIEF) }

        if (HomeCard.NEXT_STEP !in hidden) {
            val meta = next?.let { t -> listOfNotNull(firstTag(t), t.due?.let { duePhrase(it) }, Deck.minutes(t)?.let { TaskRules.minutesLabel(it) }).joinToString(" · ") }.orEmpty()
            NextStepHero(
                next, meta, onStart = onStartFocus,
                onLater = { t -> later = later + t.raw; prefs.laterToday = later },
                onSplit = { breaking = it }, onStuck = { onOpenSub(Sub.STUCK) }
            )
        }
        // Talk me through it now lives in Stuck? (care-welcome-v1), not as a surprise card here.
        // What helps you (wellbeing-v1): first week of the month, until closed.
        helps?.let { p ->
            HelpsCard(p, onAll = { helpsOpen = true }) {
                helps = null; helpsClosed = p.month.toString(); prefs.helpsClosed = helpsClosed
            }
        }
        StatPills(
            Deck.dueCount(data.tasks, today), data.focusMin, data.habits?.progress(today),
            onTasks = { onOpenTab(Tab.TASKS) }, onFocus = { onOpenSub(Sub.FOCUS) },
            onHabits = { if (data.habits != null && HomeCard.HABITS !in hidden) habitsOpen = true else onOpenSub(Sub.HABITS) }
        )
        // Low energy keeps the Stuck? list near the top.
        if (low && HomeCard.STUCK !in hidden && data.stuck.isNotEmpty()) {
            Box(Modifier.height(12.dp))
            StuckCard(data.stuck.take(3), onOpen = { onOpenSub(Sub.STUCK) }) { onStartFocus(TaskItem("", "${it.emoji} ${it.name}", null, null, ""), it.minutes) }
        }

        val sections = DeckSection.inOrder(data.layout)
        drag.shown = sections
        var slotAt = 0
        var slotShown = drag.section == null
        sections.forEach { section ->
            if (section != drag.section) {
                if (!slotShown && drag.target == slotAt) { SectionSlot(drag); slotShown = true }
                if ((drag.bounds[section]?.height ?: 0f) > 1f) slotAt++
            }
            DraggableSection(section, drag, ::dropSection) { Section(section) }
        }
        if (!slotShown && drag.target == slotAt) SectionSlot(drag)
        Box(Modifier.height(16.dp))
    }
    drag.section?.let { s -> LiftedSection(drag, origin) { Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) { Section(s) } } }
    }
    breaking?.let { t -> BreakdownSheet(t, vault, prefs, snack) { breaking = null; refresh++ } }
    mailOpen?.let { g -> MailSheet(g, vault, snack) { mailOpen = null; refresh++ } }
    recat?.let { r ->
        CategorySheet(r) { c ->
            recat = null
            val uri = vault
            if (c != null && c != r.category && uri != null) scope.launch {
                withContext(Dispatchers.IO) { runCatching { Spend.recategorise(appVault(ctx, uri), r, c) } }
                refresh++
            }
        }
    }
    if (helpsOpen) helps?.let { p -> HelpsSheet(p) { helpsOpen = false } }
    talking?.let { t ->
        TalkSheet(t, vault, prefs, snack, onFocus = onStartFocus) {
            talking = null; snoozed = TalkThrough.snooze(snoozed, t, today); prefs.talkSnoozed = snoozed; refresh++
        }
    }
    if (habitsOpen) data.habits?.let { n ->
        DeckSheet({ habitsOpen = false }) { HabitsCard(n, ::changeHabits) { habitsOpen = false; onOpenSub(Sub.HABITS) } }
    }
    if (routinesOpen) DeckSheet({ routinesOpen = false }) { RoutinesCard(data.routines) { routinesOpen = false; onPlayRoutine(it.name) } }
    if (quietAsk && data.layout.loudCards.isNotEmpty()) {
        fun answer(l: DeckLayout) {
            quietAsk = false
            data = data.copy(layout = l)
            val uri = vault ?: return
            scope.launch {
                withContext(Dispatchers.IO) { runCatching { DeckConfig.saveQuiet(appVault(ctx, uri), l) } }
                    .onFailure { snack.showSnackbar("Couldn't save: ${it.message}") }
            }
        }
        QuietDeckSheet(data.layout.loudCards, onQuiet = { keep -> answer(data.layout.quieted(keep)) },
            onKeep = { answer(data.layout) }, onLater = { quietAsk = false })
    } else if (stillAsk && data.layout.loudStill.isNotEmpty()) {
        fun answer(l: DeckLayout) {
            stillAsk = false
            data = data.copy(layout = l)
            val uri = vault ?: return
            scope.launch {
                withContext(Dispatchers.IO) { runCatching { DeckConfig.saveQuietStill(appVault(ctx, uri), l) } }
                    .onFailure { snack.showSnackbar("Couldn't save: ${it.message}") }
            }
        }
        QuietDeckSheet(data.layout.loudStill, onQuiet = { keep -> answer(data.layout.quietedStill(keep)) },
            onKeep = { answer(data.layout) }, onLater = { stillAsk = false },
            title = "A bit quieter still?", body = "Shut down covers the evening now, and these are off for new installs. Pick any to keep. Their notes stay in the vault.")
    }
    if (journalOpen) DeckSheet({ journalOpen = false }, full = true) {
        val on = data.calendars.orEmpty().filter { it.enabled }.map { it.id }.toSet()
        val events = cal.filter { it.id in on || it.id == AppEvents.CAL_ID }.flatMap { it.events }.count { it.on(LocalDate.now()) }
        JournalSheet(vault, events, snack) { journalDone = true; journalOpen = false; refresh++ }
    }
}
