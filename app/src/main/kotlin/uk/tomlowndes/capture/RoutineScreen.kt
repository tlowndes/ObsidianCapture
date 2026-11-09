package uk.tomlowndes.capture

import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Routines, step by step (build-queue T6): a ring counts down each step. Done ticks a habit of the same name; Skip
 * moves on. A routine that ends on "Plan today" opens it.
 */
@Composable
fun RoutineScreen(vault: Uri?, name: String?, snack: SnackbarHostState, onPlan: () -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var routine by remember { mutableStateOf<Routine?>(null) }
    var habits by remember { mutableStateOf<HabitsNote?>(null) }
    var step by remember { mutableIntStateOf(0) }
    var stepStart by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val done = remember { mutableStateListOf<Int>() }
    val skipped = remember { mutableStateListOf<Int>() }
    val startedAt = remember { LocalTime.now() }

    LaunchedEffect(vault) {
        val uri = vault ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val v = appVault(ctx, uri)
            routine = runCatching { Routines.load(v) }.getOrDefault(emptyList()).let { l -> l.firstOrNull { it.name == name } ?: l.firstOrNull() }
            habits = runCatching { Habits.load(v) }.getOrNull()
        }
    }
    LaunchedEffect(step) { while (true) { now = System.currentTimeMillis(); delay(500) } }

    fun next(r: Routine, ticked: Boolean) {
        val s = r.steps[step]
        if (ticked) done.add(step) else skipped.add(step)
        if (ticked) habits?.all?.let { Routines.habitFor(s, it) }?.let { h ->
            val uri = vault
            if (uri != null) scope.launch {
                withContext(Dispatchers.IO) { runCatching { Habits.update(appVault(ctx, uri), LocalDate.now()) { d -> if (h.name in d.done) d else d.toggle(h) } } }
                    .onFailure { snack.showSnackbar("Couldn't tick ${h.name}: ${it.message}") }
            }
        }
        if (step + 1 < r.steps.size) { step++; stepStart = System.currentTimeMillis() }
        else if (s.name.equals("Plan today", ignoreCase = true) && ticked) onPlan() else step = r.steps.size
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = Muted) }
            Text(routine?.name ?: "Routine", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        val r = routine ?: run {
            Text("Add routines in 99 - Meta/Routines.md: a ## heading each, then steps like \"- 🧘 Stretch · 10m\".", fontSize = 13.sp, color = Muted)
            return@Column
        }
        val finished = step >= r.steps.size
        Text(
            if (finished) "All done · ${done.size} of ${r.steps.size}" else "Step ${step + 1} of ${r.steps.size} · done by ${Routines.doneBy(r, step, LocalTime.now()).format(DateTimeFormatter.ofPattern("HH:mm"))}",
            fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Muted, modifier = Modifier.padding(bottom = 12.dp)
        )
        if (!finished) {
            val s = r.steps[step]
            val total = s.minutes * 60_000L
            val left = (total - (now - stepStart)).coerceAtLeast(0)
            Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(190.dp)) {
                    val w = 14.dp.toPx()
                    drawArc(Surface2, 0f, 360f, false, style = Stroke(w))
                    drawArc(Primary, -90f, 360f * left / total.coerceAtLeast(1), false, style = Stroke(w, cap = StrokeCap.Round))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("%d:%02d".format(left / 60_000, (left / 1000) % 60), fontSize = 40.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    Text("of ${s.minutes}:00", fontSize = 12.sp, color = Muted)
                }
            }
            Text(s.label, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            habits?.all?.let { Routines.habitFor(s, it) }?.let {
                Text("ticks the ${it.name} habit", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
        }
        ListCard(Modifier.padding(top = 14.dp)) {
            r.steps.forEachIndexed { i, s ->
                if (i > 0) HorizontalDivider(color = Outline)
                val cur = i == step
                val past = i in done || i in skipped
                Row(Modifier.fillMaxWidth().background(if (cur) PrimaryTint else androidx.compose.ui.graphics.Color.Transparent).padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text(s.label, fontSize = 13.5.sp, color = when { cur -> Primary; past -> Muted; else -> TextMain }, modifier = Modifier.weight(1f),
                        textDecoration = if (past) TextDecoration.LineThrough else null)
                    Text("${s.minutes}m", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Muted)
                }
            }
        }
        Row(Modifier.padding(top = 12.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!finished) {
                Text("Skip", fontSize = 14.sp, color = Muted, textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).clip(CircleShape).border(1.dp, Outline, CircleShape).clickable { next(r, false) }.padding(vertical = 12.dp))
                Text("Done ›", fontSize = 14.sp, color = OnPrimary, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).clip(CircleShape).background(Primary).clickable { next(r, true) }.padding(vertical = 12.dp))
            } else Text("Close", fontSize = 14.sp, color = OnPrimary, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).clip(CircleShape).background(Primary).clickable(onClick = onBack).padding(vertical = 12.dp))
        }
        Text("Started ${startedAt.format(DateTimeFormatter.ofPattern("HH:mm"))} · edit steps in Routines.md", fontSize = 11.sp, color = Muted, modifier = Modifier.padding(bottom = 16.dp))
    }
}
