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
import androidx.compose.material3.HorizontalDivider
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
    val jobs: List<Job> = emptyList()
)

@Composable
fun DeckScreen(
    vault: Uri?, prefs: AppPrefs, snack: SnackbarHostState,
    onOpenTab: (Tab) -> Unit, onOpenSub: (Sub) -> Unit, onStartFocus: (TaskItem, Int) -> Unit,
    onOpenSearch: () -> Unit, onOpenSettings: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var energy by remember { mutableStateOf(prefs.energy) }
    var later by remember { mutableStateOf(prefs.laterToday) }
    var showStrip by remember { mutableStateOf(prefs.weatherStrip) }
    // Show the last Deck straight away, then refresh from the vault.
    var data by remember { mutableStateOf(DeckCache.load(ctx) ?: DeckData()) }
    var paying by remember { mutableStateOf(emptySet<String>()) }
    var vaultOk by remember { mutableStateOf(true) }
    LaunchedEffect(vault, refresh) {
        vaultOk = vault != null && withContext(Dispatchers.IO) { runCatching { SafVault(ctx, vault).list("") }.isSuccess }
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
        data = withContext(Dispatchers.IO) {
            DeckData(
                tasks = runCatching { VaultWriter(ctx, uri).listTasks() }.getOrDefault(emptyList()),
                project = runCatching { IdeaStore(ctx, uri, prefs).list().firstOrNull { it.status == "active" } }.getOrNull(),
                unread = runCatching { ReadNote.unread(ReadStore(ctx, uri).inbox()).first }.getOrDefault(0),
                layout = runCatching { DeckConfig.load(SafVault(ctx, uri)) }.getOrDefault(DeckLayout()),
                stuck = runCatching { FlightNotes.loadStuck(SafVault(ctx, uri)).getValue(StuckLength.QUICK) }.getOrDefault(emptyList()),
                focusMin = runCatching { FlightNotes.loadToday(SafVault(ctx, uri)).sumOf { it.minutes } }.getOrDefault(0),
                habits = runCatching { Habits.load(SafVault(ctx, uri)) }.getOrNull(),
                calendars = runCatching { CalendarConfig.load(SafVault(ctx, uri)) }.getOrNull(),
                jobs = runCatching { Jobs.list(SafVault(ctx, uri)) }.getOrDefault(emptyList())
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
            withContext(Dispatchers.IO) { runCatching { Habits.update(SafVault(ctx, uri), today, f) } }
                .onFailure { snack.showSnackbar("Failed: ${it.message}") }
            val fresh = withContext(Dispatchers.IO) { runCatching { Habits.load(SafVault(ctx, uri)) }.getOrNull() }
            if (fresh != null) data = data.copy(habits = fresh)
        }
    }

    val low = energy == Energy.LOW
    val maxMin = if (low) SHORT_MIN else null
    val next = Deck.nextStep(data.tasks, later, maxMin)
    val top = Deck.topTasks(data.tasks, if (energy == Energy.HIGH) 3 else 2, later, maxMin)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        DeckHeader(energy, onOpenSearch = onOpenSearch, onRefresh = { refresh++ }, onOpenSettings = onOpenSettings)
        EnergyChip(energy) { energy = it; prefs.energy = it }
        if (!vaultOk) {
            Text(
                "⚠ Vault not found · Pick folder ›", color = Amber, fontSize = 13.sp,
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(RoundedCornerShape(14.dp)).background(AmberTint)
                    .clickable(onClick = onOpenSettings).padding(horizontal = 14.dp, vertical = 10.dp)
            )
        }
        UpdateBanner()
        Pills(
            Deck.dueCount(data.tasks, LocalDate.now()), data.focusMin,
            data.habits?.progress(LocalDate.now()),
            onTasks = { onOpenTab(Tab.TASKS) }, onFocus = { onOpenSub(Sub.FOCUS) }, onHabits = { onOpenSub(Sub.HABITS) }
        )

        data.layout.visible(low).forEach { card ->
            when (card) {
                HomeCard.NEXT_STEP -> NextStepCard(
                    next, onStart = onStartFocus, onStuck = { onOpenSub(Sub.STUCK) },
                    onLater = { t -> later = later + t.raw; prefs.laterToday = later }
                )
                HomeCard.PROJECTS -> data.project?.let { ProjectCard(it) { onOpenTab(Tab.PROJECTS) } }
                HomeCard.TOP_TASKS -> if (top.isNotEmpty()) TopTasksCard(top, low, ::tick) { onOpenTab(Tab.TASKS) }
                HomeCard.STUCK -> if (low && data.stuck.isNotEmpty()) StuckCard(data.stuck.take(3), onOpen = { onOpenSub(Sub.STUCK) }) { onStartFocus(TaskItem("", "${it.emoji} ${it.name}", null, null, ""), it.minutes) }
                    else RowCard("Stuck? Pick one.", "Open ›", accent = true) { onOpenSub(Sub.STUCK) }
                HomeCard.HABITS -> data.habits?.let { HabitsCard(it, ::changeHabits) { onOpenSub(Sub.HABITS) } }
                    ?: RowCard("Habits", "Open ›") { onOpenSub(Sub.HABITS) }
                // Read it later and Jobs live in Vault now (chips), not on the phone Deck.
                HomeCard.READ_LATER, HomeCard.JOBS -> {}
                // Weather, Today and Jobs arrive with their own steps.
                HomeCard.BILLS -> TaskViews.billsDue(data.tasks, LocalDate.now()).takeIf { it.isNotEmpty() }?.let { bills ->
                    BillsCard(bills, paying) { b -> paying = paying + b.task.raw; tick(b.task) }
                }
                HomeCard.TODAY -> data.calendars?.let { sources -> TodayCard(sources, cal, onConnect = onOpenSettings) }
                HomeCard.WEATHER -> {
                    val w = weather
                    if (w != null) WeatherCard(w, travel, showStrip) { showStrip = !showStrip; prefs.weatherStrip = showStrip }
                    else if (!canLocate) RowCard("📍 Weather", "Allow location ›") { askLocation.launch(Manifest.permission.ACCESS_COARSE_LOCATION) }
                }
            }
        }
        Box(Modifier.height(12.dp))
    }
}

@Composable
private fun DeckHeader(energy: Energy, onOpenSearch: () -> Unit, onRefresh: () -> Unit, onOpenSettings: () -> Unit) {
    val today = LocalDate.now()
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text("Flight Deck", fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp)
        Row {
            IconButton(onClick = onOpenSearch) { Icon(Icons.Outlined.Search, "Search", tint = Muted) }
            IconButton(onClick = onRefresh) { Icon(Icons.Outlined.Refresh, "Refresh", tint = Muted) }
            IconButton(onClick = onOpenSettings) { Icon(Icons.Outlined.Settings, "Settings", tint = Muted) }
        }
    }
    Text(
        buildAnnotatedString {
            append("${today.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK))} · ${greeting()}, $OWNER")
            if (energy == Energy.LOW) {
                append(" · ")
                withStyle(SpanStyle(color = Amber)) { append("energy low") }
            }
        },
        color = Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
        modifier = Modifier.padding(top = 2.dp, bottom = 10.dp)
    )
}

@Composable
private fun EnergyChip(selected: Energy, onSelect: (Energy) -> Unit) {
    Row(
        Modifier.padding(bottom = 12.dp).clip(CircleShape).background(Surface1)
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
private fun Pills(due: Int, focusMin: Int, habits: Pair<Int, Int>?, onTasks: () -> Unit, onFocus: () -> Unit, onHabits: () -> Unit) {
    Row(Modifier.padding(bottom = 12.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Pill("Tasks due $due", Amber, onTasks)
        Pill("Focus today ${focusMin}m", Green, onFocus)
        Pill(habits?.let { (done, total) -> "Habits $done/$total" } ?: "Habits", Purple, onHabits)
    }
}

/** Today (Proton): today's events, the current one highlighted. Hidden when there are none. */
@Composable
private fun TodayCard(sources: List<CalendarSource>, cache: List<CalendarCache>, onConnect: () -> Unit) {
    if (sources.isEmpty()) { RowCard("📅 Calendar", "Connect ›", onClick = onConnect); return }
    val on = sources.filter { it.enabled }.map { it.id }.toSet()
    val mine = cache.filter { it.id in on }
    val today = LocalDate.now()
    val now = java.time.LocalDateTime.now()
    val events = mine.flatMap { it.events }.filter { it.on(today) }.sortedWith(compareBy({ !it.allDay }, { it.start }))
    val broken = mine.any { !it.ok }
    if (events.isEmpty() && !broken) return
    val synced = mine.maxOfOrNull { it.syncedAt } ?: 0L
    DeckCard {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 11.dp, bottom = 7.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Today", fontSize = 15.sp, fontWeight = FontWeight.Bold)
            if (broken) Text("Reconnect ›", fontSize = 12.sp, color = Amber, modifier = Modifier.clickable(onClick = onConnect))
            else Text("Proton", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Muted)
        }
        val colours = sources.associate { it.id to hexColour(it.colour) }
        events.forEach { e ->
            HorizontalDivider(color = Outline)
            val current = e.isNow(now)
            Row(
                Modifier.fillMaxWidth().background(if (current && !broken) PrimaryTint else Color.Transparent).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(colours[e.calendarId] ?: Primary))
                Text(
                    if (e.allDay) "all day" else "${e.start.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"))}–${e.end.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"))}",
                    fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = if (current && !broken) Primary else Muted
                )
                Text(e.title, fontSize = 13.5.sp, color = if (broken) Muted else TextMain, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (broken && synced > 0) Text(clockOf(synced), fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Muted)
            }
        }
    }
}

@Composable
private fun WeatherCard(w: WeatherSummary, travel: TravelLine?, showStrip: Boolean, onToggle: () -> Unit) {
    DeckCard(border = WeatherBorder, bg = WeatherBg, modifier = Modifier.clickable(onClick = onToggle)) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (travel != null) "WEATHER & TRAVEL" else "WEATHER", fontSize = 12.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp, color = WeatherLabel)
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
private fun Pill(text: String, dot: Color, onClick: () -> Unit) {
    Row(
        Modifier.clip(CircleShape).border(1.dp, Outline, CircleShape).clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
        Text(text, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun DeckCard(border: Color = Outline, bg: Color = Surface1, modifier: Modifier = Modifier, content: @Composable () -> Unit) =
    ListCard(modifier, border, bg, content)

@Composable
private fun CardHead(title: String, trailing: String? = null, onTrailing: (() -> Unit)? = null) {
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
private fun NextStepCard(t: TaskItem?, onStart: (TaskItem, Int) -> Unit, onStuck: () -> Unit, onLater: (TaskItem) -> Unit) {
    DeckCard(border = NextBorder, bg = NextBg) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text("NEXT STEP", fontSize = 12.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.2.sp, color = Primary)
            if (t == null) {
                Text("✓ All clear", fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
                DeckButton("Stuck? Pick one.", primary = true, modifier = Modifier.fillMaxWidth(), onClick = onStuck)
                return@Column
            }
            Text(t.text, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
            val mins = Deck.minutes(t)
            val meta = listOfNotNull(firstTag(t), t.due?.let { duePhrase(it) }, mins?.let { "about $it min" }).joinToString(" · ")
            Text(meta, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Muted, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DeckButton("Start ${mins ?: SHORT_MIN} min", primary = true, modifier = Modifier.weight(1f)) { onStart(t, mins ?: SHORT_MIN) }
                DeckButton("Later", primary = false, modifier = Modifier.weight(1f)) { onLater(t) }
            }
        }
    }
}

@Composable
private fun DeckButton(text: String, primary: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        text, textAlign = TextAlign.Center, fontSize = 13.sp,
        fontWeight = if (primary) FontWeight.Bold else FontWeight.Normal,
        color = if (primary) OnPrimary else Muted,
        modifier = modifier.clip(CircleShape)
            .then(if (primary) Modifier.background(Primary) else Modifier.border(1.dp, Outline, CircleShape))
            .clickable(onClick = onClick).padding(vertical = 9.dp)
    )
}

@Composable
private fun ProjectCard(p: IdeaSummary, onOpen: () -> Unit) {
    DeckCard(modifier = Modifier.clickable(onClick = onOpen)) {
        CardHead("Projects")
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(end = 14.dp, bottom = 10.dp, top = 2.dp)) {
            Box(Modifier.width(4.dp).fillMaxHeight().clip(RoundedCornerShape(topEnd = 3.dp, bottomEnd = 3.dp))
                .background(if (p.area == "work") WorkEdge else PersonalEdge))
            Column(Modifier.padding(start = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p.title, fontSize = 13.5.sp)
                    p.type?.let { TypeTag(it) }
                }
                p.next?.let { Text("Next: $it", fontSize = 12.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

@Composable
private fun TypeTag(type: String) {
    val (bg, fg) = when (type) {
        "claude" -> TagTint to Purple
        "concept" -> TaskTint to Green
        "idea" -> AmberTint to Amber
        else -> Surface2 to Muted
    }
    Text(
        type, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = fg,
        modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(7.dp)).background(bg).padding(horizontal = 6.dp, vertical = 1.dp)
    )
}

@Composable
private fun TopTasksCard(tasks: List<TaskItem>, low: Boolean, onTick: (TaskItem) -> Unit, onAll: () -> Unit) {
    DeckCard {
        CardHead("Top tasks", if (low) "under $SHORT_MIN min ›" else "All tasks ›", onAll)
        tasks.forEach { t ->
            HorizontalDivider(color = Outline)
            TaskLine(t, edge = false, onTick = { onTick(t) }) { t.due?.let { DuePill(it) } }
        }
    }
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
private fun BillsCard(bills: List<Bill>, paying: Set<String>, onPaid: (Bill) -> Unit) {
    val open = bills.filter { it.task.raw !in paying }
    DeckCard {
        CardHead("Bills", "next 7 days · ${TaskViews.money(TaskViews.total(open))}")
        bills.forEach { b ->
            val paid = b.task.raw in paying
            HorizontalDivider(color = Outline)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                TickBox(done = paid, onClick = { if (!paid) onPaid(b) })
                Text(
                    b.name, fontSize = 13.5.sp, color = if (paid) Muted else TextMain, modifier = Modifier.weight(1f),
                    textDecoration = if (paid) TextDecoration.LineThrough else null
                )
                b.amount?.let { Text(TaskViews.money(it), fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = if (paid) Muted else TextMain) }
                b.task.due?.let { DuePill(it) }
            }
        }
    }
}

@Composable
private fun RowCard(title: String, trailing: String, accent: Boolean = false, onClick: () -> Unit) {
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
