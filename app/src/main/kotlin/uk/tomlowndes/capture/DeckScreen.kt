package uk.tomlowndes.capture

import android.net.Uri
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val OWNER = "Tom"
private const val SHORT_MIN = 15

/** Energy chip on Flight Deck. Low brings Stuck? to the top and short tasks; High shows 3 top tasks. */
enum class Energy(val label: String) { LOW("Low"), MED("Med"), HIGH("High") }

internal fun greeting(now: LocalTime = LocalTime.now()) = when {
    now.hour < 12 -> "good morning"
    now.hour < 18 -> "good afternoon"
    else -> "good evening"
}

/** What Flight Deck reads from the vault in one go. */
private data class DeckData(
    val tasks: List<TaskItem> = emptyList(),
    val project: IdeaSummary? = null,
    val unread: Int = 0,
    val layout: DeckLayout = DeckLayout()
)

@Composable
fun DeckScreen(
    vault: Uri?, prefs: AppPrefs, snack: SnackbarHostState,
    onOpenTab: (Tab) -> Unit, onOpenSearch: () -> Unit, onOpenSettings: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var energy by remember { mutableStateOf(prefs.energy) }
    var later by remember { mutableStateOf(prefs.laterToday) }
    var data by remember { mutableStateOf(DeckData()) }

    LaunchedEffect(vault, refresh) {
        val uri = vault ?: return@LaunchedEffect
        data = withContext(Dispatchers.IO) {
            DeckData(
                tasks = runCatching { VaultWriter(ctx, uri).listTasks() }.getOrDefault(emptyList()),
                project = runCatching { IdeaStore(ctx, uri, prefs).list().firstOrNull { it.status == "active" } }.getOrNull(),
                unread = runCatching { ReadNote.unread(ReadStore(ctx, uri).inbox()).first }.getOrDefault(0),
                layout = runCatching { DeckConfig.load(SafVault(ctx, uri)) }.getOrDefault(DeckLayout())
            )
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

    val low = energy == Energy.LOW
    val maxMin = if (low) SHORT_MIN else null
    val next = Deck.nextStep(data.tasks, later, maxMin)
    val top = Deck.topTasks(data.tasks, if (energy == Energy.HIGH) 3 else 2, later, maxMin)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        DeckHeader(energy, onOpenSearch = onOpenSearch, onRefresh = { refresh++ }, onOpenSettings = onOpenSettings)
        EnergyChip(energy) { energy = it; prefs.energy = it }
        Pills(Deck.dueCount(data.tasks, LocalDate.now())) { onOpenTab(Tab.TASKS) }

        data.layout.visible(low).forEach { card ->
            when (card) {
                HomeCard.NEXT_STEP -> NextStepCard(next, onLater = { t -> later = later + t.raw; prefs.laterToday = later })
                HomeCard.PROJECTS -> data.project?.let { ProjectCard(it) { onOpenTab(Tab.PROJECTS) } }
                HomeCard.TOP_TASKS -> if (top.isNotEmpty()) TopTasksCard(top, low, ::tick) { onOpenTab(Tab.TASKS) }
                HomeCard.STUCK -> RowCard("Stuck? Pick one.", "Open ›", accent = true) {}
                HomeCard.READ_LATER -> RowCard("Read it later", "${data.unread} ›") { onOpenTab(Tab.READ) }
                // Weather, Today, Bills and Jobs arrive with their own steps.
                HomeCard.WEATHER, HomeCard.TODAY, HomeCard.BILLS, HomeCard.JOBS -> {}
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

/** Focus today and Habits join this row with their own steps. */
@Composable
private fun Pills(due: Int, onTasks: () -> Unit) {
    Row(Modifier.padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Pill("Tasks due $due", Amber, onTasks)
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
private fun DeckCard(
    border: Color = Outline, bg: Color = Surface1, modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Column(
        modifier.fillMaxWidth().padding(bottom = 12.dp).clip(RoundedCornerShape(18.dp)).background(bg)
            .border(1.dp, border, RoundedCornerShape(18.dp))
    ) { content() }
}

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

private val TAG = Regex("(?:^|\\s)(#(?!task\\b)[\\w/-]+)")

private fun firstTag(t: TaskItem) = TAG.find(t.raw)?.groupValues?.get(1)

private fun duePhrase(d: LocalDate, today: LocalDate = LocalDate.now()) = when {
    d.isBefore(today) -> "overdue"
    d == today -> "due today"
    d == today.plusDays(1) -> "due tomorrow"
    else -> "due " + d.format(DateTimeFormatter.ofPattern("EEE d", Locale.UK))
}

@Composable
private fun NextStepCard(t: TaskItem?, onLater: (TaskItem) -> Unit) {
    DeckCard(border = NextBorder, bg = NextBg) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text("NEXT STEP", fontSize = 12.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.2.sp, color = Primary)
            if (t == null) {
                Text("✓ All clear", fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
                DeckButton("Stuck? Pick one.", primary = true, modifier = Modifier.fillMaxWidth()) {}
                return@Column
            }
            Text(t.text, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
            val mins = Deck.minutes(t)
            val meta = listOfNotNull(firstTag(t), t.due?.let { duePhrase(it) }, mins?.let { "about $it min" }).joinToString(" · ")
            Text(meta, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Muted, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Start opens Focus once that screen lands.
                DeckButton("Start ${mins ?: SHORT_MIN} min", primary = true, modifier = Modifier.weight(1f)) {}
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
            val overdue = t.due?.isBefore(LocalDate.now()) == true
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(Modifier.size(17.dp).clip(RoundedCornerShape(6.dp)).border(1.5.dp, Muted, RoundedCornerShape(6.dp)).clickable { onTick(t) })
                Text(
                    buildAnnotatedString {
                        append(t.text.replace(TAG, "").trim())
                        firstTag(t)?.let { withStyle(SpanStyle(color = Purple, fontFamily = FontFamily.Monospace, fontSize = 12.sp)) { append(" $it") } }
                    },
                    fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                )
                t.due?.let {
                    Text(
                        it.format(DateTimeFormatter.ofPattern("EEE d", Locale.UK)), fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                        color = if (overdue) Warn else Muted,
                        modifier = Modifier.clip(CircleShape).border(1.dp, if (overdue) OverdueLine else Outline, CircleShape)
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                    )
                }
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
