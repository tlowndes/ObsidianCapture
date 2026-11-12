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

internal fun greeting(now: LocalTime = LocalTime.now()) = when {
    now.hour < 12 -> "good morning"
    now.hour < 18 -> "good afternoon"
    else -> "good evening"
}

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
    vault: Uri?, prefs: AppPrefs, snack: SnackbarHostState,
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
        val events = cal.filter { it.id in on }.flatMap { it.events }
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

    LaunchedEffect(vault, refresh) {
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
    // Evening check-in (journal-prompt-v2): written today? Read from this month's journal note.
    var journalDone by remember { mutableStateOf(false) }
    var journalOpen by remember { mutableStateOf(false) }
    var journalSkipped by remember { mutableStateOf(prefs.journalSkippedOn) }
    LaunchedEffect(vault, refresh) {
        val uri = vault ?: return@LaunchedEffect
        journalDone = withContext(Dispatchers.IO) { runCatching { Journal.written(appVault(ctx, uri), LocalDate.now()) }.getOrDefault(false) }
    }
    val nowDt = java.time.LocalDateTime.now()

    fun setMood(n: Int) = changeHabits { Mood.set(it, n) }

    val low = energy == Energy.LOW
    val maxMin = if (low) SHORT_MIN else null
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
            else if (DayPlan.isEvening(nowDt) && prefs.shutDownOn != today.toString() && !data.dayDone.shutDown(today) && left > 0)
                add(NowItem.Line("🌙 Shut down · $left still open", "Start ›") { onOpenSub(Sub.PLAN) })
        }
        val checkIn = HomeCard.HIGHLIGHTS !in hidden && nowDt.hour >= Journal.ASK_FROM && !journalDone && journalSkipped != today.toString()
        if (checkIn) add(NowItem.Line("📓 Evening check-in · 1 min", "Write ›", onDismiss = {
            journalSkipped = today.toString(); prefs.journalSkippedOn = journalSkipped
        }) { journalOpen = true })
        // The check-in asks for mood too, so the mood faces wait until it's done.
        else if (HomeCard.MOOD !in hidden && data.habits?.let { Mood.ask(it, today, nowDt.hour) } == true) add(NowItem.Mood(::setMood))
        if (Migrate.due(data.tasks, today, prefs.migratedMonth)) {
            val n = Migrate.candidates(data.tasks, today).size
            add(NowItem.Line("Migrate · $n from last month", "Start ›") { onOpenSub(Sub.MIGRATE) })
        }
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

    /** One section under Next step: Today (weather, calendar, bills), Top tasks, or the More tiles. */
    @Composable
    fun Section(section: DeckSection) {
        when (section) {
            DeckSection.TODAY -> {
                val w = weather
                val rows = mutableListOf<@Composable () -> Unit>()
                if (HomeCard.WEATHER !in hidden) {
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
                }
                if (HomeCard.TODAY !in hidden) rows.addAll(calendarRows(data.calendars, cal, onOpenSettings, earlierOpen) {
                    earlierOpen = !earlierOpen; prefs.earlierOpenOn = if (earlierOpen) LocalDate.now().toString() else null
                })
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
                val expanded = HomeCard.WEATHER !in hidden && w != null && showStrip
                if (rows.isNotEmpty() || expanded) SectionLabel("Today")
                if (expanded) {
                    WeatherCard(w!!, null, true) { showStrip = false; prefs.weatherStrip = false }
                    if (rows.isNotEmpty()) Box(Modifier.height(8.dp))
                }
                RowsCard(rows)
            }
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
                // Routines, Projects and Highlights as small tiles.
                val showRoutines = HomeCard.ROUTINES !in hidden && data.routines.isNotEmpty()
                val showProject = HomeCard.PROJECTS !in hidden && data.project != null
                val showHighlight = HomeCard.HIGHLIGHTS !in hidden && leftPassages.isNotEmpty()
                if (showRoutines || showProject || showHighlight) {
                    SectionLabel("More")
                    TileRow {
                        if (showRoutines) Tile("🔁", "Routines", data.routines.singleOrNull()?.name ?: "${data.routines.size} ready") {
                            data.routines.singleOrNull()?.let { onPlayRoutine(it.name) } ?: run { routinesOpen = true }
                        }
                        if (showProject) Tile("💡", "Projects", data.project?.title) { onOpenTab(Tab.PROJECTS) }
                        if (showHighlight) Tile("✨", "Highlight", "${leftPassages.size} today") { highlightOpen = true }
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
        DeckHeader(onOpenSearch = onOpenSearch, onOpenSettings = onOpenSettings, onPrint = { onOpenSub(Sub.PRINT) })
        SubLine("${today.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK))} · ${greeting()}, $OWNER") {
            EnergyChip(energy) { energy = it; prefs.energy = it }
        }
        UpdateBanner()
        ServerCopyChip(onClick = onOpenSettings)
        NowLine(nowItems, nowIdx) { nowIdx++ }

        if (HomeCard.NEXT_STEP !in hidden) {
            val meta = next?.let { t -> listOfNotNull(firstTag(t), t.due?.let { duePhrase(it) }, Deck.minutes(t)?.let { TaskRules.minutesLabel(it) }).joinToString(" · ") }.orEmpty()
            NextStepHero(
                next, meta, onStart = onStartFocus,
                onLater = { t -> later = later + t.raw; prefs.laterToday = later },
                onSplit = { breaking = it }, onStuck = { onOpenSub(Sub.STUCK) }
            )
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
    if (habitsOpen) data.habits?.let { n ->
        DeckSheet({ habitsOpen = false }) { HabitsCard(n, ::changeHabits) { habitsOpen = false; onOpenSub(Sub.HABITS) } }
    }
    if (routinesOpen) DeckSheet({ routinesOpen = false }) { RoutinesCard(data.routines) { routinesOpen = false; onPlayRoutine(it.name) } }
    if (journalOpen) DeckSheet({ journalOpen = false }) {
        val on = data.calendars.orEmpty().filter { it.enabled }.map { it.id }.toSet()
        val events = cal.filter { it.id in on }.flatMap { it.events }.count { it.on(LocalDate.now()) }
        JournalSheet(vault, events, snack) { journalDone = true; journalOpen = false; refresh++ }
    }
}

/** A bottom sheet holding one of the Deck's cards. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeckSheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Bg) {
        Box(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) { content() }
    }
}

@Composable
private fun DeckHeader(onOpenSearch: () -> Unit, onOpenSettings: () -> Unit, onPrint: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text("Flight Deck", fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp)
        Row {
            // Daily Slip: today's printout for the pocket thermal printer.
            IconButton(onClick = onPrint) { Text("🧾", fontSize = 19.sp) }
            IconButton(onClick = onOpenSearch) { Icon(Icons.Outlined.Search, "Search", tint = Muted) }
            IconButton(onClick = onOpenSettings) { Icon(Icons.Outlined.Settings, "Settings", tint = Muted) }
        }
    }
}

@Composable
private fun EnergyChip(selected: Energy, onSelect: (Energy) -> Unit) {
    Row(
        Modifier.clip(CircleShape).background(Surface1)
            .border(1.dp, Outline, CircleShape).padding(2.dp)
    ) {
        Energy.values().forEach { e ->
            val on = e == selected
            val (bg, fg) = when {
                !on -> Color.Transparent to Muted
                e == Energy.LOW -> AmberTint to Amber
                e == Energy.HIGH -> TaskTint to Green
                else -> PrimaryTint to Primary
            }
            Box(Modifier.clip(CircleShape).background(bg).clickable { onSelect(e) }.padding(horizontal = 12.dp, vertical = 4.dp)) {
                Text(
                    e.label, color = fg, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
private fun WeatherCard(w: WeatherSummary, travel: TravelLine?, showStrip: Boolean, onToggle: () -> Unit) {
    DeckCard(border = WeatherBorder, bg = WeatherBg, modifier = Modifier.clickable(onClick = onToggle)) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text((if (travel != null) "WEATHER & TRAVEL" else "WEATHER") + if (w.tomorrow) " · TOMORROW" else "", fontSize = 12.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp, color = WeatherLabel)
                if (w.parts.isNotEmpty()) Text(if (showStrip) "▴" else "▾", fontSize = 12.sp, color = Muted)
            }
            Text("${w.high}° / ${w.low}° · ${w.headline}", fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp, bottom = 6.dp))
            if (showStrip && w.parts.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    w.parts.forEach { p ->
                        Column(
                            Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(WeatherSlot)
                                .border(1.dp, if (p.rain >= 50) WeatherSlotWet else WeatherSlotBorder, RoundedCornerShape(12.dp))
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                        ) {
                            Text(p.name.uppercase(), fontSize = 11.sp, fontFamily = FontFamily.Monospace, letterSpacing = 0.5.sp, color = Muted)
                            Text(p.icon, fontSize = 18.sp, modifier = Modifier.padding(top = 4.dp, bottom = 2.dp))
                            Text("${p.temp}°", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Text("${p.rain}%", fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, color = WeatherLabel, modifier = Modifier.padding(top = 2.dp))
                        }
                    }
                }
            }
            Text(w.detail, fontSize = 13.sp, color = Muted, lineHeight = 19.sp)
            w.tip?.let { Text(it, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 8.dp)) }
            w.tonight?.let {
                HorizontalDivider(color = WeatherBorder, modifier = Modifier.padding(top = 10.dp, bottom = 8.dp))
                Text(it, fontSize = 12.5.sp, color = Muted, lineHeight = 18.sp)
            }
            if (travel != null) {
                HorizontalDivider(color = WeatherBorder, modifier = Modifier.padding(top = 12.dp, bottom = 10.dp))
                val hm = DateTimeFormatter.ofPattern("HH:mm")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("🚗 ${travel.minutes} min", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Primary)
                    Column {
                        Text("${travel.event.title} · ${travel.event.start.format(hm)}", fontSize = 13.5.sp)
                        Text("Leave by ${travel.leaveBy.format(hm)} · ${travel.event.location}", fontSize = 12.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun StuckCard(items: List<StuckItem>, onOpen: () -> Unit, onStart: (StuckItem) -> Unit) {
    DeckCard {
        CardHead("Stuck? Pick one.", "All ›", onOpen)
        items.forEach { i ->
            HorizontalDivider(color = Outline)
            Row(Modifier.fillMaxWidth().clickable { onStart(i) }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text("${i.emoji} ${i.name}", fontSize = 13.5.sp, modifier = Modifier.weight(1f))
                Text("${i.minutes} min", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Muted)
            }
        }
    }
}

@Composable
internal fun DeckCard(border: Color = Outline, bg: Color = Surface1, modifier: Modifier = Modifier, content: @Composable () -> Unit) =
    ListCard(modifier, border, bg, content)

@Composable
internal fun CardHead(title: String, trailing: String? = null, onTrailing: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 11.dp, bottom = 7.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        if (trailing != null) Text(
            trailing, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Muted,
            modifier = if (onTrailing != null) Modifier.clickable(onClick = onTrailing) else Modifier
        )
    }
}

private fun firstTag(t: TaskItem) = TaskViews.tags(t).firstOrNull()

private fun duePhrase(d: LocalDate, today: LocalDate = LocalDate.now()) = when {
    d.isBefore(today) -> "overdue"
    d == today -> "due today"
    d == today.plusDays(1) -> "due tomorrow"
    else -> "due " + d.format(DateTimeFormatter.ofPattern("EEE d", Locale.UK))
}

@Composable
internal fun DeckButton(text: String, primary: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        text, textAlign = TextAlign.Center, fontSize = 13.sp,
        fontWeight = if (primary) FontWeight.Bold else FontWeight.Normal,
        color = if (primary) OnPrimary else Muted,
        modifier = modifier.clip(CircleShape)
            .then(if (primary) Modifier.background(Primary) else Modifier.border(1.dp, Outline, CircleShape))
            .clickable(onClick = onClick).padding(vertical = 9.dp)
    )
}

/** Habits on home: a bar and press button per counter, today's ticks with streaks. "All ›" opens the full Habits screen. */
@Composable
private fun HabitsCard(n: HabitsNote, onChange: ((HabitDay) -> HabitDay) -> Unit, onAll: () -> Unit) {
    val today = LocalDate.now()
    val day = n.day(today)
    val (done, total) = n.progress(today)
    DeckCard {
        CardHead("Habits", "$done of $total · All ›", onAll)
        n.on(today).forEach { h ->
            HorizontalDivider(color = Outline)
            if (h.counter) {
                val v = day.value(h)
                val over = h.max && v > h.goal
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(h.emoji.ifEmpty { h.name.take(1) }, fontSize = 13.sp)
                    Box(Modifier.weight(1f).height(6.dp).clip(CircleShape).background(Surface2)) {
                        Box(Modifier.fillMaxWidth((v.toFloat() / h.goal.coerceAtLeast(1)).coerceIn(0f, 1f)).height(6.dp).background(if (over) Amber else Primary))
                    }
                    Text(Habits.short(h, v), fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = if (over) Amber else Muted)
                    Text(
                        Habits.press(h), color = Primary, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(CircleShape).background(PrimaryTint)
                            .clickable { onChange { it.add(h, h.step) } }.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            } else {
                val on = h.name in day.done
                val streak = n.streak(h, today)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TickBox(done = on, onClick = { onChange { it.toggle(h) } })
                    Text(h.label, fontSize = 13.5.sp, modifier = Modifier.weight(1f))
                    if (streak > 0) Text("🔥 $streak", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Muted)
                }
            }
        }
    }
}

@Composable
internal fun RowCard(title: String, trailing: String, accent: Boolean = false, onClick: () -> Unit) {
    DeckCard(modifier = Modifier.clickable(onClick = onClick)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, fontSize = 13.5.sp, color = if (accent) Primary else TextMain, fontWeight = if (accent) FontWeight.SemiBold else FontWeight.Normal)
            Text(trailing, fontSize = 13.sp, color = Muted, fontFamily = if (accent) FontFamily.Default else FontFamily.Monospace)
        }
    }
}
