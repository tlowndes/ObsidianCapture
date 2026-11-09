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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Monthly migration (paper-hybrid-v1): each task still dated last month or earlier gets > carry on (date cleared),
 * < a new date, or ✕ drop (cancelled). Each choice is written straight away. Done (or sorting the last one) marks the month.
 */
@Composable
fun MigrateScreen(vault: Uri?, prefs: AppPrefs, snack: SnackbarHostState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val today = LocalDate.now()
    var list by remember { mutableStateOf<List<TaskItem>?>(null) }
    val chosen = remember { mutableStateMapOf<String, MigrateChoice>() }
    var dating by remember { mutableStateOf<TaskItem?>(null) }

    LaunchedEffect(vault) {
        val uri = vault ?: return@LaunchedEffect
        list = withContext(Dispatchers.IO) { runCatching { Migrate.candidates(VaultWriter(ctx, uri).listTasks(), today) }.getOrNull() }
            ?: emptyList()
    }

    fun finish() { prefs.migratedMonth = Migrate.monthKey(today); onBack() }

    fun choose(t: TaskItem, c: MigrateChoice, date: LocalDate? = null) {
        val uri = vault ?: return
        chosen[t.raw] = c
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val w = VaultWriter(ctx, uri)
                    when (c) {
                        MigrateChoice.CARRY -> w.rescheduleTask(t, null)
                        MigrateChoice.SCHEDULE -> w.rescheduleTask(t, date)
                        MigrateChoice.DROP -> w.cancelTask(t, today)
                    }
                }
            }
            r.onFailure { chosen.remove(t.raw); snack.showSnackbar("Failed: ${it.message}") }
            if (r.isSuccess && list?.all { it.raw in chosen } == true) prefs.migratedMonth = Migrate.monthKey(today)
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = Muted) }
            Text("Migrate", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("Done", color = Primary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { finish() }.padding(8.dp))
        }
        val tasks = list
        Text(
            Migrate.title(today) + (tasks?.let { " · ${it.size} open" } ?: ""),
            fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Muted, modifier = Modifier.padding(bottom = 10.dp)
        )
        if (tasks == null) return@Column
        if (tasks.isEmpty()) {
            Text("Nothing left over from last month", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(vertical = 16.dp))
            return@Column
        }
        val done = tasks.count { it.raw in chosen }
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
            Text("$done of ${tasks.size} sorted", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Muted, modifier = Modifier.weight(1f))
        }
        Box(Modifier.fillMaxWidth().height(5.dp).clip(CircleShape).background(Surface2)) {
            Box(Modifier.fillMaxWidth(done / tasks.size.toFloat()).height(5.dp).clip(CircleShape).background(Primary))
        }
        Box(Modifier.height(10.dp))
        ListCard {
            tasks.forEachIndexed { i, t ->
                if (i > 0) HorizontalDivider(color = Outline)
                val c = chosen[t.raw]
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(TaskViews.plainText(t), fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            color = if (c != null) Muted else TextMain, textDecoration = if (c == MigrateChoice.DROP) TextDecoration.LineThrough else null)
                        Text(
                            when (c) {
                                MigrateChoice.CARRY -> "carried on"
                                MigrateChoice.SCHEDULE -> "moved"
                                MigrateChoice.DROP -> "dropped"
                                null -> "since " + t.due!!.format(DateTimeFormatter.ofPattern("d MMM", Locale.UK))
                            },
                            fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Muted, modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    MigrateButton(">", c == MigrateChoice.CARRY, Primary, enabled = c == null) { choose(t, MigrateChoice.CARRY) }
                    MigrateButton("<", c == MigrateChoice.SCHEDULE, Primary, enabled = c == null) { dating = t }
                    MigrateButton("✕", c == MigrateChoice.DROP, Warn, enabled = c == null) { choose(t, MigrateChoice.DROP) }
                }
            }
        }
        Text("> carry on, no date · < pick a date · ✕ drop it", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
        if (done == tasks.size) Text(
            "All sorted", color = OnPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clip(CircleShape).background(Primary).clickable { finish() }.padding(vertical = 13.dp)
        )
        Box(Modifier.height(16.dp))
    }

    dating?.let { t ->
        PickDateDialog(today, onPick = { d -> dating = null; if (d != null) choose(t, MigrateChoice.SCHEDULE, d) }, onDismiss = { dating = null })
    }
}

@Composable
private fun MigrateButton(label: String, on: Boolean, colour: Color, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(32.dp).clip(RoundedCornerShape(9.dp)).background(if (on) colour else Color.Transparent)
            .border(1.dp, if (on) colour else Outline, RoundedCornerShape(9.dp)).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontSize = 14.sp, fontFamily = FontFamily.Monospace, color = if (on) OnPrimary else Muted)
    }
}
