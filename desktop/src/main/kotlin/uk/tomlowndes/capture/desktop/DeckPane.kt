package uk.tomlowndes.capture.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import uk.tomlowndes.capture.Bill
import uk.tomlowndes.capture.Deck
import uk.tomlowndes.capture.DeckConfig
import uk.tomlowndes.capture.DeckLayout
import uk.tomlowndes.capture.FlightNotes
import uk.tomlowndes.capture.FocusSession
import uk.tomlowndes.capture.HabitDay
import uk.tomlowndes.capture.Habits
import uk.tomlowndes.capture.HabitsNote
import uk.tomlowndes.capture.HomeCard
import uk.tomlowndes.capture.IdeaSummary
import uk.tomlowndes.capture.ReadNote
import uk.tomlowndes.capture.TaskItem
import uk.tomlowndes.capture.TaskViews
import uk.tomlowndes.capture.WeatherSummary
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val OWNER = "Tom"
private const val SHORT_MIN = TaskViews.SHORT_MIN

/** A focus timer started from Next step. [pausedLeft] is set while paused; [asking] once it's finished. */
data class FocusRun(val task: TaskItem, val minutes: Int, val endsAt: Long, val pausedLeft: Long? = null, val asking: Boolean = false) {
    fun left(now: Long = System.currentTimeMillis()) = pausedLeft ?: (endsAt - now).coerceAtLeast(0)
}

/** Cards the desktop Deck shows, and which column each sits in. The rest (Today, Stuck?, Jobs) stay on the phone. */
private val LEFT = setOf(HomeCard.NEXT_STEP, HomeCard.BILLS, HomeCard.TOP_TASKS, HomeCard.HABITS)
private val RIGHT = setOf(HomeCard.WEATHER, HomeCard.PROJECTS, HomeCard.READ_LATER)

private data class DeckData(
    val tasks: List<TaskItem> = emptyList(),
    val project: IdeaSummary? = null,
    val unread: Int = 0,
    val layout: DeckLayout = DeckLayout(),
    val focusMin: Int = 0,
    val habits: HabitsNote? = null,
)

private fun greeting(now: LocalTime = LocalTime.now()) = when {
    now.hour < 12 -> "good morning"
    now.hour < 18 -> "good afternoon"
    else -> "good evening"
}

/** Flight Deck on Windows: the phone's home cards in two columns, from the same vault and card settings. */
@Composable
fun DeckPane(state: AppState) {
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf(DeckData()) }
    var weather by remember { mutableStateOf<WeatherSummary?>(null) }
    var later by remember { mutableStateOf(state.prefs.laterToday) }
    var showStrip by remember { mutableStateOf(state.prefs.weatherStrip) }
    var paying by remember { mutableStateOf(emptySet<String>()) }
    var managing by remember { mutableStateOf(false) }

    fun reload() = scope.launch {
        val v = state.vault ?: return@launch
        data = state.io("Couldn't read the vault") {
            DeckData(
                tasks = runCatching { state.writer()!!.listTasks() }.getOrDefault(emptyList()),
                project = runCatching { state.ideaStore()!!.list().firstOrNull { it.status.lowercase() == "active" } }.getOrNull(),
                unread = runCatching { ReadNote.unread(state.readStore()!!.inbox()).first }.getOrDefault(0),
                layout = runCatching { DeckConfig.load(v) }.getOrDefault(DeckLayout()),
                focusMin = runCatching { FlightNotes.loadToday(v).sumOf { it.minutes } }.getOrDefault(0),
                habits = runCatching { Habits.load(v) }.getOrNull(),
            )
        } ?: return@launch
    }
    LaunchedEffect(state.vault, state.changes) { reload() }
    LaunchedEffect(state.changes) {
        weather = state.io { DesktopWeather.today(state.prefs) }
    }

    fun tick(t: TaskItem) = scope.launch {
        val c = state.io { state.writer()!!.completeTask(t, state.prefs.recurring) } ?: return@launch
        reload()
        state.offerUndo("Task completed") { state.io { state.writer()!!.restoreTask(t, c) }; reload() }
    }

    fun changeHabits(f: (HabitDay) -> HabitDay) {
        val v = state.vault ?: return
        val today = LocalDate.now()
        data.habits?.let { n -> data = data.copy(habits = n.copy(days = n.days + (today to f(n.day(today))))) }
        scope.launch {
            state.io { Habits.update(v, today, f) }
            state.io { Habits.load(v) }?.let { data = data.copy(habits = it) }
        }
    }

    fun finishFocus(run: FocusRun) = scope.launch {
        val v = state.vault ?: return@launch
        val spent = ((run.minutes * 60_000L - run.left()) / 60_000L).toInt().coerceAtLeast(1)
        state.focus = run.copy(pausedLeft = 0, asking = true)
        state.io("Couldn't write the focus log") { FlightNotes.logFocus(v, FocusSession(LocalDateTime.now(), run.task.text, spent)) }
        reload()
    }

    val next = Deck.nextStep(data.tasks, later)
    val top = Deck.topTasks(data.tasks, 2, later)
    val today = LocalDate.now()
    val visible = data.layout.visible(lowEnergy = false)

    @Composable
    fun Show(card: HomeCard) {
        when (card) {
            HomeCard.NEXT_STEP -> NextStepCard(
                next, state.focus,
                onStart = { t, m -> state.focus = FocusRun(t, m, System.currentTimeMillis() + m * 60_000L) },
                onLater = { t -> later = later + t.raw; state.prefs.laterToday = later },
                onPause = { r -> state.focus = if (r.pausedLeft != null) r.copy(endsAt = System.currentTimeMillis() + r.pausedLeft, pausedLeft = null) else r.copy(pausedLeft = r.left()) },
                onFinish = { finishFocus(it) },
                onAnswer = { r, done -> state.focus = null; if (done && r.task.raw.isNotEmpty()) tick(r.task) },
            )
            HomeCard.BILLS -> TaskViews.billsDue(data.tasks, today).takeIf { it.isNotEmpty() }?.let { bills ->
                BillsCard(bills, paying) { b -> paying = paying + b.task.raw; tick(b.task) }
            }
            HomeCard.TOP_TASKS -> if (top.isNotEmpty()) TopTasksCard(top, ::tick) { state.tab = Tab.TASKS }
            HomeCard.HABITS -> data.habits?.let { HabitsCard(it, ::changeHabits) { managing = true } }
            HomeCard.WEATHER -> {
                val w = weather
                if (w != null) WeatherCard(w, showStrip) { showStrip = !showStrip; state.prefs.weatherStrip = showStrip }
                else if (state.prefs.weatherLatLon == null) RowCard("📍 Weather", "Set your town ›") { state.tab = Tab.SETTINGS }
            }
            HomeCard.PROJECTS -> data.project?.let { p -> ProjectCard(p) { state.openProjects(p.title) } }
            HomeCard.READ_LATER -> RowCard("Read it later", "${data.unread} ›") { state.tab = Tab.READ }
            else -> Unit
        }
    }

    if (managing) ManageHabitsDialog(state) { managing = false; reload() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 26.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                H1("Flight Deck", 28)
                Text(
                    "${today.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK))} · ${greeting()}, $OWNER",
                    color = Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp)
                )
            }
            Text("⟳", fontSize = 18.sp, color = Muted, modifier = Modifier.clip(CircleShape).hand().clickable { state.changed() }.padding(8.dp))
        }
        UpdateBanner(state)
        Row(Modifier.padding(bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DeckPill("Tasks due ${Deck.dueCount(data.tasks, today)}", Amber) { state.tab = Tab.TASKS }
            DeckPill("Focus today ${data.focusMin}m", TaskGreen) { state.openInObsidian(FlightNotes.FOCUS_LOG) }
            DeckPill(data.habits?.progress(today)?.let { (done, total) -> "Habits $done/$total" } ?: "Habits", Purple) {
                state.openInObsidian(Habits.PATH)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) { visible.filter { it in LEFT }.forEach { Show(it) } }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) { visible.filter { it in RIGHT }.forEach { Show(it) } }
        }
    }
}

@Composable
private fun DeckPill(text: String, dot: Color, onClick: () -> Unit) {
    Row(
        Modifier.clip(CircleShape).border(1.dp, Outline, CircleShape).hand().clickable(onClick = onClick).padding(horizontal = 11.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
        Text(text, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, color = TextMain)
    }
}

@Composable
private fun DeckCard(
    border: Color = Outline, bg: Color = Surface1, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(bg).border(1.dp, border, shape)
            .then(if (onClick != null) Modifier.hand().clickable(onClick = onClick) else Modifier),
        content = content
    )
}

@Composable
private fun CardHead(title: String, trailing: String? = null, onTrailing: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextMain)
        if (trailing != null) Text(
            trailing, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Muted,
            modifier = if (onTrailing != null) Modifier.hand().clickable(onClick = onTrailing) else Modifier
        )
    }
}

@Composable
private fun Line() = Box(Modifier.fillMaxWidth().height(1.dp).background(Outline))

@Composable
private fun MonoLabel(text: String, color: Color = Primary) =
    Text(text.uppercase(Locale.UK), fontSize = 11.sp, letterSpacing = 1.4.sp, fontFamily = FontFamily.Monospace, color = color)

@Composable
private fun TickBox(done: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier.size(17.dp).clip(shape).background(if (done) Purple else Color.Transparent)
            .border(1.5.dp, if (done) Purple else Muted, shape).hand().clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { if (done) Text("✓", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1A1530)) }
}

@Composable
private fun DuePill(d: LocalDate) {
    val overdue = d.isBefore(LocalDate.now())
    Text(
        d.format(DateTimeFormatter.ofPattern("EEE d", Locale.UK)), fontSize = 10.5.sp, fontFamily = FontFamily.Monospace,
        color = if (overdue) Warn else Muted,
        modifier = Modifier.clip(CircleShape).border(1.dp, if (overdue) OverdueLine else Outline, CircleShape).padding(horizontal = 8.dp, vertical = 2.dp)
    )
}

@Composable
private fun DeckButton(text: String, primary: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        text, textAlign = TextAlign.Center, fontSize = 13.5.sp,
        fontWeight = if (primary) FontWeight.Bold else FontWeight.Normal,
        color = if (primary) OnPrimary else Muted,
        modifier = modifier.clip(CircleShape)
            .then(if (primary) Modifier.background(Primary) else Modifier.border(1.dp, Outline, CircleShape))
            .hand().clickable(onClick = onClick).padding(horizontal = 22.dp, vertical = 9.dp)
    )
}

private fun duePhrase(d: LocalDate, today: LocalDate = LocalDate.now()) = when {
    d.isBefore(today) -> "overdue"
    d == today -> "due today"
    d == today.plusDays(1) -> "due tomorrow"
    else -> "due " + d.format(DateTimeFormatter.ofPattern("EEE d", Locale.UK))
}

private fun clock(ms: Long): String {
    val s = (ms + 999) / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

/** Next step, or the focus timer it started: Start counts down here, Finish writes the focus log and asks if it's done. */
@Composable
private fun NextStepCard(
    t: TaskItem?, run: FocusRun?,
    onStart: (TaskItem, Int) -> Unit, onLater: (TaskItem) -> Unit,
    onPause: (FocusRun) -> Unit, onFinish: (FocusRun) -> Unit, onAnswer: (FocusRun, Boolean) -> Unit,
) {
    DeckCard(border = NextBorder, bg = NextBg) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            if (run != null) {
                MonoLabel(if (run.asking) "Focus · finished" else "Focus")
                Text(run.task.text, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = TextMain, modifier = Modifier.padding(top = 6.dp))
                if (run.asking) {
                    Text("Is it done?", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Muted, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        DeckButton("Done", primary = true) { onAnswer(run, true) }
                        DeckButton("Not yet", primary = false) { onAnswer(run, false) }
                    }
                } else {
                    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                    LaunchedEffect(run) { while (true) { now = System.currentTimeMillis(); delay(500) } }
                    val left = run.left(now)
                    Text(
                        clock(left) + if (run.pausedLeft != null) " · paused" else "",
                        fontSize = 28.sp, fontFamily = FontFamily.Monospace, color = if (left == 0L) TaskGreen else TextMain,
                        modifier = Modifier.padding(top = 6.dp, bottom = 12.dp)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        DeckButton("Finish", primary = true) { onFinish(run) }
                        if (left > 0) DeckButton(if (run.pausedLeft != null) "Resume" else "Pause", primary = false) { onPause(run) }
                    }
                }
                return@Column
            }
            MonoLabel("Next step")
            if (t == null) {
                Text("✓ All clear", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = TextMain, modifier = Modifier.padding(top = 6.dp))
                return@Column
            }
            Text(t.text, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = TextMain, modifier = Modifier.padding(top = 6.dp))
            val mins = Deck.minutes(t)
            val meta = listOfNotNull(TaskViews.tags(t).firstOrNull()?.let { "#$it" }, t.due?.let { duePhrase(it) }, mins?.let { "about $it min" }).joinToString(" · ")
            Text(meta, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, color = Muted, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DeckButton("Start ${mins ?: SHORT_MIN} min", primary = true) { onStart(t, mins ?: SHORT_MIN) }
                DeckButton("Later", primary = false) { onLater(t) }
            }
        }
    }
}

@Composable
private fun TopTasksCard(tasks: List<TaskItem>, onTick: (TaskItem) -> Unit, onAll: () -> Unit) {
    DeckCard {
        CardHead("Top tasks", "All tasks ›", onAll)
        tasks.forEach { t ->
            Line()
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TickBox(false) { onTick(t) }
                Text(TaskViews.plainText(t), fontSize = 13.5.sp, color = TextMain, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                TaskViews.tags(t).firstOrNull()?.let { Text("#$it", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Purple) }
                Box(Modifier.weight(1f))
                t.due?.let { DuePill(it) }
            }
        }
    }
}

/** Habits: a bar and press button per counter, today's ticks with streaks. "Manage ›" opens Manage habits. */
@Composable
private fun HabitsCard(n: HabitsNote, onChange: ((HabitDay) -> HabitDay) -> Unit, onManage: () -> Unit) {
    val today = LocalDate.now()
    val day = n.day(today)
    val (done, total) = n.progress(today)
    DeckCard {
        CardHead("Habits", "$done of $total · Manage ›", onManage)
        n.on(today).forEach { h ->
            Line()
            if (h.counter) {
                val v = day.value(h)
                val over = h.max && v > h.goal
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(h.emoji.ifEmpty { h.name.take(1) }, fontSize = 13.sp)
                    Box(Modifier.weight(1f).height(6.dp).clip(CircleShape).background(Surface2)) {
                        Box(Modifier.fillMaxWidth((v.toFloat() / h.goal.coerceAtLeast(1)).coerceIn(0f, 1f)).height(6.dp).background(if (over) Amber else Primary))
                    }
                    Text(Habits.short(h, v), fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = if (over) Amber else Muted)
                    Text(
                        Habits.press(h), color = Primary, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.clip(CircleShape).border(1.dp, Primary, CircleShape).hand()
                            .clickable { onChange { it.add(h, h.step) } }.padding(horizontal = 10.dp, vertical = 3.dp)
                    )
                }
            } else {
                val on = h.name in day.done
                val streak = n.streak(h, today)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    TickBox(on) { onChange { it.toggle(h) } }
                    Text(h.label, fontSize = 13.5.sp, color = TextMain, modifier = Modifier.weight(1f))
                    if (streak > 0) Text("🔥 $streak", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Muted)
                }
            }
        }
    }
}

/** The detailed weather card. Clicking it shows or hides the Morning / Afternoon / Evening strip. */
@Composable
private fun WeatherCard(w: WeatherSummary, showStrip: Boolean, onToggle: () -> Unit) {
    DeckCard(border = WeatherBorder, bg = WeatherBg, onClick = onToggle) {
        Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                MonoLabel(if (w.tomorrow) "Weather · Tomorrow" else "Weather", WeatherLabel)
                if (w.parts.isNotEmpty()) Text(if (showStrip) "▴" else "▾", fontSize = 12.sp, color = Muted)
            }
            Text("${w.high}° / ${w.low}° · ${w.headline}", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextMain,
                modifier = Modifier.padding(top = 6.dp, bottom = 10.dp))
            if (showStrip && w.parts.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    w.parts.forEach { p ->
                        val shape = RoundedCornerShape(12.dp)
                        Column(
                            Modifier.weight(1f).clip(shape).background(WeatherSlot)
                                .border(1.dp, if (p.rain >= 50) WeatherSlotWet else WeatherSlotBorder, shape)
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            Text(p.name.uppercase(Locale.UK), fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.2.sp, color = Muted)
                            Text(p.icon, fontSize = 18.sp, modifier = Modifier.padding(top = 4.dp, bottom = 2.dp))
                            Text("${p.temp}°", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextMain)
                            Text("${p.rain}%", fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, color = WeatherLabel)
                        }
                    }
                }
            }
            Text(w.detail, fontSize = 13.5.sp, color = Muted, lineHeight = 20.sp)
            w.tip?.let { Text(it, fontSize = 13.5.sp, color = TextMain, lineHeight = 20.sp, modifier = Modifier.padding(top = 8.dp)) }
            w.tonight?.let {
                HorizontalDivider(color = WeatherBorder, modifier = Modifier.padding(top = 12.dp, bottom = 10.dp))
                Text(it, fontSize = 13.sp, color = Muted, lineHeight = 19.sp)
            }
        }
    }
}

@Composable
private fun ProjectCard(p: IdeaSummary, onOpen: () -> Unit) {
    DeckCard(onClick = onOpen) {
        CardHead("Projects", "Open ›")
        Line()
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(end = 18.dp)) {
            Box(Modifier.width(4.dp).fillMaxHeight().clip(RoundedCornerShape(topEnd = 3.dp, bottomEnd = 3.dp))
                .background(if (p.area == "work") WorkEdge else PersonalEdge))
            Column(Modifier.padding(start = 14.dp, top = 11.dp, bottom = 11.dp)) {
                Text(p.title, fontSize = 13.5.sp, color = TextMain)
                p.next?.let { Text("Next: $it", fontSize = 11.5.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp)) }
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
            Line()
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TickBox(paid) { if (!paid) onPaid(b) }
                Text(b.name, fontSize = 13.5.sp, color = if (paid) Muted else TextMain, modifier = Modifier.weight(1f),
                    textDecoration = if (paid) TextDecoration.LineThrough else null)
                b.amount?.let { Text(TaskViews.money(it), fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = if (paid) Muted else TextMain) }
                b.task.due?.let { DuePill(it) }
            }
        }
    }
}

@Composable
private fun RowCard(title: String, trailing: String, onClick: () -> Unit) {
    DeckCard(onClick = onClick) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, fontSize = 14.sp, color = TextMain)
            Text(trailing, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Muted)
        }
    }
}
