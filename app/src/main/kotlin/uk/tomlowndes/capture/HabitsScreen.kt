package uk.tomlowndes.capture

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
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

/** Habits: water counter, daily ticks with streaks, this week's dots. All in one vault note. */
@Composable
fun HabitsScreen(vault: Uri?, snack: SnackbarHostState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf<HabitsNote?>(null) }
    val today = LocalDate.now()

    suspend fun load() {
        val uri = vault ?: return
        note = withContext(Dispatchers.IO) { runCatching { Habits.load(SafVault(ctx, uri)) }.getOrNull() }
    }
    LaunchedEffect(vault) { load() }

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

        GroupLabel("Counters today")
        ListCard(Modifier.padding(top = 6.dp)) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Water", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${litres(day.waterMl)} L of ${litres(n.waterGoalMl)} L · ${day.waterMl / Habits.GLASS_ML} glasses",
                            fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Muted
                        )
                    }
                    Text(
                        "+ ${Habits.GLASS_ML} ml", color = Primary, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(CircleShape).background(PrimaryTint)
                            .clickable { change { it.copy(waterMl = it.waterMl + Habits.GLASS_ML) } }.padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
                Box(Modifier.fillMaxWidth().padding(top = 10.dp).height(6.dp).clip(CircleShape).background(Surface2)) {
                    Box(Modifier.fillMaxWidth((day.waterMl.toFloat() / n.waterGoalMl).coerceIn(0f, 1f)).height(6.dp).background(Primary))
                }
            }
            HorizontalDivider(color = Outline)
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text("Undo last press", fontSize = 13.sp, color = Muted, modifier = Modifier.weight(1f))
                Text("Undo", fontSize = 13.sp, color = if (day.waterMl > 0) Primary else Muted,
                    modifier = Modifier.clickable(enabled = day.waterMl > 0) { change { it.copy(waterMl = (it.waterMl - Habits.GLASS_ML).coerceAtLeast(0)) } })
            }
        }

        GroupLabel("Ticks today · ${n.habits.count { it in day.done }} of ${n.habits.size}")
        ListCard(Modifier.padding(top = 6.dp)) {
            n.habits.forEachIndexed { i, h ->
                if (i > 0) HorizontalDivider(color = Outline)
                val on = h in day.done
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TickBox(done = on, onClick = { change { it.copy(done = if (on) it.done - h else it.done + h) } })
                    Text(h, fontSize = 13.5.sp, modifier = Modifier.weight(1f))
                    Text("🔥 ${n.streak(h, today)}", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Muted)
                }
            }
        }

        GroupLabel("This week")
        ListCard(Modifier.padding(top = 6.dp)) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val week = n.week(today)
                WeekRow("", week.map { null }, labels = listOf("M", "T", "W", "T", "F", "S", "S"))
                WeekRow("Water", week.map { n.day(it).waterMl >= n.waterGoalMl })
                n.habits.forEach { h -> WeekRow(h, week.map { h in n.day(it).done }) }
            }
        }

        GroupLabel("Edit")
        ListCard(Modifier.padding(top = 6.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp)) {
                Text("Add or remove habits", fontSize = 13.5.sp, modifier = Modifier.weight(1f))
                Text("Open note", fontSize = 13.sp, color = Primary, modifier = Modifier.clickable { openInObsidian(ctx, vault) })
            }
        }
    }
}

internal fun litres(ml: Int) = if (ml % 1000 == 0) "${ml / 1000}" else "%.1f".format(ml / 1000.0)

@Composable
private fun WeekRow(name: String, filled: List<Boolean?>, labels: List<String>? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(name, fontSize = 12.sp, color = Muted, maxLines = 1, modifier = Modifier.width(110.dp))
        filled.forEachIndexed { i, f ->
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                if (labels != null) Text(labels[i], fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Muted, textAlign = TextAlign.Center)
                else Box(Modifier.size(10.dp).clip(CircleShape).background(if (f == true) Purple else Surface2))
            }
        }
    }
}

/** Opens the Habits note in Obsidian through its URI scheme. */
private fun openInObsidian(ctx: android.content.Context, vault: Uri?) {
    val vaultName = vault?.lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/') ?: return
    val uri = Uri.parse("obsidian://open?vault=${Uri.encode(vaultName)}&file=${Uri.encode(Habits.PATH.removeSuffix(".md"))}")
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
