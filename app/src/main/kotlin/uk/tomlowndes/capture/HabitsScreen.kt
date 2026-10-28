package uk.tomlowndes.capture

import android.content.Intent
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** Habits: counters, today's ticks with streaks, this week's dots. All in one vault note. "Edit ›" opens Manage habits. */
@Composable
fun HabitsScreen(vault: Uri?, snack: SnackbarHostState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf<HabitsNote?>(null) }
    var managing by remember { mutableStateOf(false) }
    var lastPress by remember { mutableStateOf<Habit?>(null) }
    val today = LocalDate.now()

    suspend fun load() {
        val uri = vault ?: return
        note = withContext(Dispatchers.IO) { runCatching { Habits.load(SafVault(ctx, uri)) }.getOrNull() }
    }
    LaunchedEffect(vault, managing) { if (!managing) load() }

    if (managing) {
        ManageHabitsScreen(vault, snack, onOpenNote = { openInObsidian(ctx, vault) }) { managing = false }
        return
    }

    fun change(f: (HabitDay) -> HabitDay) {
        val uri = vault ?: return
        note?.let { n -> note = n.copy(days = n.days + (today to f(n.day(today)))) }
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { Habits.update(SafVault(ctx, uri), today, f) } }
                .onFailure { snack.showSnackbar("Failed: ${it.message}") }
            load()
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = Muted) }
            Text("Habits", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        val n = note ?: return@Column
        val day = n.day(today)
        val counters = n.on(today).filter { it.counter }
        val ticks = n.on(today).filter { !it.counter }

        if (counters.isNotEmpty()) {
            GroupLabel("Counters today")
            ListCard(Modifier.padding(top = 6.dp)) {
                counters.forEachIndexed { i, h ->
                    if (i > 0) HorizontalDivider(color = Outline)
                    val v = day.value(h)
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(h.label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                Text(Habits.long(h, v), fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = if (h.max && v > h.goal) Amber else Muted)
                            }
                            Text(
                                if (h.water) "+ ${h.step} ml" else "+ ${h.step}", color = Primary, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.clip(CircleShape).background(PrimaryTint)
                                    .clickable { lastPress = h; change { it.add(h, h.step) } }.padding(horizontal = 14.dp, vertical = 8.dp)
                            )
                        }
                        Box(Modifier.fillMaxWidth().padding(top = 10.dp).height(6.dp).clip(CircleShape).background(Surface2)) {
                            Box(Modifier.fillMaxWidth((v.toFloat() / h.goal.coerceAtLeast(1)).coerceIn(0f, 1f)).height(6.dp)
                                .background(if (h.max && v > h.goal) Amber else Primary))
                        }
                    }
                }
                val undo = lastPress?.takeIf { h -> counters.any { it.name == h.name } && day.value(h) > 0 }
                HorizontalDivider(color = Outline)
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text("Undo last press", fontSize = 13.sp, color = Muted, modifier = Modifier.weight(1f))
                    Text("Undo", fontSize = 13.sp, color = if (undo != null) Primary else Muted,
                        modifier = Modifier.clickable(enabled = undo != null) { undo?.let { h -> change { it.add(h, -h.step) } } })
                }
            }
        }

        if (ticks.isNotEmpty()) {
            GroupLabel("Ticks today · ${ticks.count { it.name in day.done }} of ${ticks.size}")
            ListCard(Modifier.padding(top = 6.dp)) {
                ticks.forEachIndexed { i, h ->
                    if (i > 0) HorizontalDivider(color = Outline)
                    val on = h.name in day.done
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TickBox(done = on, onClick = { change { it.toggle(h) } })
                        Text(h.label, fontSize = 13.5.sp, modifier = Modifier.weight(1f))
                        val streak = n.streak(h, today)
                        if (streak > 0) Text("🔥 $streak", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Muted)
                    }
                }
            }
        }

        if (n.active.isNotEmpty()) {
            GroupLabel("This week")
            ListCard(Modifier.padding(top = 6.dp)) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val week = n.week(today)
                    WeekRow("", week.map { null }, labels = listOf("M", "T", "W", "T", "F", "S", "S"))
                    n.active.forEach { h -> WeekRow(h.name, week.map { d -> if (!h.due(d) || d.isAfter(today)) null else n.met(h, n.day(d)) }) }
                }
            }
        }

        ListCard(Modifier.padding(top = 6.dp)) {
            Row(Modifier.fillMaxWidth().clickable { managing = true }.padding(horizontal = 16.dp, vertical = 13.dp)) {
                Text("Manage habits", fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text("Edit ›", fontSize = 13.sp, color = Primary, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** One row of the week grid. A null day (not due, or still to come) shows as a small ring. */
@Composable
private fun WeekRow(name: String, filled: List<Boolean?>, labels: List<String>? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(name, fontSize = 12.sp, color = Muted, maxLines = 1, modifier = Modifier.width(110.dp))
        filled.forEachIndexed { i, f ->
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                when {
                    labels != null -> Text(labels[i], fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Muted, textAlign = TextAlign.Center)
                    f == null -> Box(Modifier.size(4.dp).clip(CircleShape).background(Outline))
                    else -> Box(Modifier.size(10.dp).clip(CircleShape).background(if (f) Purple else Surface2).then(
                        if (f) Modifier else Modifier.border(1.dp, Outline, CircleShape)))
                }
            }
        }
    }
}

/** Opens the Habits note in Obsidian through its URI scheme. */
internal fun openInObsidian(ctx: android.content.Context, vault: Uri?) {
    val vaultName = vault?.lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/') ?: return
    val uri = Uri.parse("obsidian://open?vault=${Uri.encode(vaultName)}&file=${Uri.encode(Habits.PATH.removeSuffix(".md"))}")
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
