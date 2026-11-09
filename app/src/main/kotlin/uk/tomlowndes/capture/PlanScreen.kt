package uk.tomlowndes.capture

import android.net.Uri
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Plan today and Shut down (build-queue T1). Morning: pick up to 3, with planned minutes against free time from the
 * calendar; Start the day dates them today. Evening: each task still open today goes to Tomorrow, gets a date, or is
 * dropped.
 */
@Composable
fun PlanScreen(vault: Uri?, prefs: AppPrefs, snack: SnackbarHostState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val now = remember { LocalDateTime.now() }
    val today = now.toLocalDate()
    var evening by remember { mutableStateOf(DayPlan.isEvening(now)) }
    var tasks by remember { mutableStateOf<List<TaskItem>?>(null) }
    val picked = remember { mutableStateListOf<String>() }
    val sorted = remember { mutableStateListOf<String>() }
    var dating by remember { mutableStateOf<TaskItem?>(null) }
    val events = remember { CalendarSync.cached(ctx).flatMap { it.events } }

    LaunchedEffect(vault) {
        val uri = vault ?: return@LaunchedEffect
        val all = withContext(Dispatchers.IO) { runCatching { VaultWriter(ctx, uri).listTasks() }.getOrDefault(emptyList()) }
        tasks = all
        if (picked.isEmpty()) picked.addAll(all.filter { it.due == today && !Deck.isBill(it) }.take(DayPlan.PICK_MAX).map { it.raw })
    }

    fun write(f: VaultWriter.() -> Unit, after: () -> Unit = {}) {
        val uri = vault ?: return
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { VaultWriter(ctx, uri).f() } }
                .onSuccess { after() }.onFailure { snack.showSnackbar("Failed: ${it.message}") }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = Muted) }
            Text(if (evening) "Shut down" else "Plan today", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        }
        Row(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AppChip(!evening, { evening = false }, { Text("☀️ Plan") })
            AppChip(evening, { evening = true }, { Text("🌙 Shut down") })
        }
        val all = tasks ?: return@Column
        val todayEvents = events.filter { !it.allDay && it.on(today) }
        val busy = todayEvents.sumOf { java.time.Duration.between(it.start, it.end).toMinutes().toInt() }
        Text(
            today.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)) + " · ${todayEvents.size} events take ${TaskRules.minutesLabel(busy.coerceAtLeast(0))}",
            fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Muted, modifier = Modifier.padding(bottom = 10.dp)
        )

        if (!evening) {
            val list = DayPlan.candidates(all, today)
            val chosen = all.filter { it.raw in picked }
            val planned = DayPlan.planned(chosen)
            val free = DayPlan.freeMinutes(events, now)
            Row(Modifier.fillMaxWidth()) {
                Text("Planned ", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Muted)
                Text(TaskRules.minutesLabel(planned), fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = if (planned > free) Amber else Green, modifier = Modifier.weight(1f))
                Text("free ${TaskRules.minutesLabel(free)}", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Muted)
            }
            Box(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp).height(8.dp).clip(CircleShape).background(Surface2)) {
                Box(Modifier.fillMaxWidth((planned.toFloat() / free.coerceAtLeast(1)).coerceIn(0f, 1f)).height(8.dp).background(if (planned > free) Amber else Purple))
            }
            GroupLabel("Pick up to ${DayPlan.PICK_MAX} · from Upcoming")
            ListCard(Modifier.padding(top = 6.dp)) {
                list.forEachIndexed { i, t ->
                    if (i > 0) HorizontalDivider(color = Outline)
                    val on = t.raw in picked
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            if (on) picked.remove(t.raw) else if (picked.size < DayPlan.PICK_MAX) picked.add(t.raw)
                        }.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        TickBox(done = on, onClick = { if (on) picked.remove(t.raw) else if (picked.size < DayPlan.PICK_MAX) picked.add(t.raw) },
                            outline = if (TaskViews.isWork(t)) Amber else Primary, fill = if (TaskViews.isWork(t)) Amber else Primary)
                        Text(TaskViews.plainText(t), fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(TaskRules.minutesLabel(DayPlan.minutes(t)), fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Muted)
                    }
                }
            }
            Text(
                "Start the day", color = OnPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 14.dp).fillMaxWidth().clip(CircleShape).background(Primary).clickable {
                    val move = chosen.filter { it.due != today }
                    write({ move.forEach { rescheduleTask(it, today) } }) { prefs.plannedOn = today.toString(); onBack() }
                }.padding(vertical = 13.dp)
            )
            Text("Evening: Shut down shows what's left. Each task goes to Tomorrow, gets a date, or is dropped.",
                fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 10.dp, bottom = 16.dp))
        } else {
            val left = DayPlan.leftToday(all, today)
            if (left.isEmpty()) Text("Nothing left for today. Nice one.", fontSize = 14.sp, color = Muted, modifier = Modifier.padding(vertical = 12.dp))
            else ListCard {
                left.forEachIndexed { i, t ->
                    if (i > 0) HorizontalDivider(color = Outline)
                    val done = t.raw in sorted
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(TaskViews.plainText(t), fontSize = 13.5.sp, color = if (done) Muted else TextMain, modifier = Modifier.weight(1f),
                            maxLines = 2, overflow = TextOverflow.Ellipsis, textDecoration = if (done) TextDecoration.LineThrough else null)
                        if (!done) {
                            SmallPill("Tomorrow") { sorted.add(t.raw); write({ rescheduleTask(t, today.plusDays(1)) }) }
                            SmallPill("Date") { dating = t }
                            SmallPill("✕", Warn) { sorted.add(t.raw); write({ cancelTask(t, today) }) }
                        }
                    }
                }
            }
            Text(
                "Done for today", color = OnPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 14.dp, bottom = 16.dp).fillMaxWidth().clip(CircleShape).background(Primary).clickable {
                    prefs.shutDownOn = today.toString(); onBack()
                }.padding(vertical = 13.dp)
            )
        }
    }

    dating?.let { t ->
        PickDateDialog(today.plusDays(1), onPick = { d -> dating = null; sorted.add(t.raw); write({ rescheduleTask(t, d) }) }, onDismiss = { dating = null })
    }
}

@Composable
private fun SmallPill(text: String, colour: androidx.compose.ui.graphics.Color = Primary, onClick: () -> Unit) {
    Text(text, fontSize = 12.sp, color = colour, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(CircleShape).border(1.dp, Outline, CircleShape).clickable(onClick = onClick).padding(horizontal = 9.dp, vertical = 4.dp))
}
