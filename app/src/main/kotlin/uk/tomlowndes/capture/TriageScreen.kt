package uk.tomlowndes.capture

import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
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

private enum class TriageMove { TODAY, LATER, DROP }

/**
 * Triage (build-queue O1): overdue and undated tasks one card at a time. Swipe or tap: right = today,
 * left = later (the reschedule sheet), up or ✕ = drop (cancelled, with Undo).
 */
@Composable
fun TriageScreen(vault: Uri?, prefs: AppPrefs, snack: SnackbarHostState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val today = LocalDate.now()
    var all by remember { mutableStateOf<List<TaskItem>>(emptyList()) }
    var queue by remember { mutableStateOf<List<TaskItem>?>(null) }
    var done by remember { mutableIntStateOf(0) }
    var later by remember { mutableStateOf<TaskItem?>(null) }
    val dx = remember { Animatable(0f) }
    val dy = remember { Animatable(0f) }

    LaunchedEffect(vault) {
        val uri = vault ?: return@LaunchedEffect
        // The last lists read show at once; the vault read below then refreshes the queue only if nothing has been moved yet.
        TaskSnapshot.load(ctx)?.let { all = it.open; queue = Triage.candidates(all, today) }
        val lists = withContext(Dispatchers.IO) { runCatching { VaultWriter(ctx, uri).listTaskLists() } }.getOrNull()
        if (lists != null) {
            TaskSnapshot.save(ctx, lists)
            all = lists.open
            if (done == 0) queue = Triage.candidates(all, today)
        } else if (queue == null) { all = emptyList(); queue = emptyList() }
    }

    fun act(t: TaskItem, move: TriageMove, date: LocalDate? = null) {
        val uri = vault ?: return
        queue = queue?.drop(1); done++
        scope.launch {
            dx.snapTo(0f); dy.snapTo(0f)
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val w = VaultWriter(ctx, uri)
                    when (move) {
                        TriageMove.TODAY -> w.rescheduleTask(t, today)
                        TriageMove.LATER -> w.rescheduleTask(t, date)
                        TriageMove.DROP -> { w.cancelTask(t, today); null }
                    }
                }
            }
            r.onFailure { snack.showSnackbar("Failed: ${it.message}") }
            r.onSuccess { newRaw ->
                val msg = when (move) { TriageMove.TODAY -> "Moved to today"; TriageMove.LATER -> date?.let { "Moved to ${dayChip(it)}" } ?: "Date cleared"; TriageMove.DROP -> "Dropped" }
                if (snack.showSnackbar(msg, "Undo", duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) {
                    val back = if (move == TriageMove.DROP) WeeklyReview.cancelled(t.raw, today) else newRaw
                    withContext(Dispatchers.IO) { runCatching { VaultWriter(ctx, uri).restoreTask(t, null, back) } }
                    queue = listOf(t) + queue.orEmpty(); done--
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = Muted) }
            Text("Triage", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("Done", color = Primary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable(onClick = onBack).padding(8.dp))
        }
        val q = queue ?: return@Column
        val total = q.size + done
        Text("Overdue + no date · ${(done + 1).coerceAtMost(total)} of $total", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Muted)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            (0 until total.coerceAtMost(30)).forEach { i ->
                Box(Modifier.weight(1f).height(4.dp).clip(CircleShape).background(if (i < done) Primary else Surface2))
            }
        }
        val t = q.firstOrNull()
        if (t == null) {
            Text("All clear ✓", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 40.dp).fillMaxWidth(), textAlign = TextAlign.Center)
            Text("Nothing overdue or undated left.", fontSize = 13.sp, color = Muted, modifier = Modifier.fillMaxWidth().padding(top = 6.dp), textAlign = TextAlign.Center)
            return@Column
        }
        Box(Modifier.fillMaxWidth().height(340.dp)) {
            q.getOrNull(1)?.let { TriageCard(it, today, Modifier.graphicsLayer { translationY = -24f; scaleX = 0.95f; scaleY = 0.95f; alpha = 0.5f }, null) }
            val hint = when {
                dy.value < -120f -> "DROP"
                dx.value > 120f -> "TODAY"
                dx.value < -120f -> "LATER"
                else -> null
            }
            key(t.raw) {
                TriageCard(t, today,
                    Modifier.graphicsLayer { translationX = dx.value; translationY = dy.value.coerceAtMost(0f) }
                        .rotate(dx.value / 40f)
                        .pointerInput(t.raw) {
                            detectDragGestures(
                                onDragEnd = {
                                    when {
                                        dy.value < -220f -> act(t, TriageMove.DROP)
                                        dx.value > 220f -> act(t, TriageMove.TODAY)
                                        dx.value < -220f -> { later = t; scope.launch { dx.animateTo(0f); dy.animateTo(0f) } }
                                        else -> scope.launch { launch { dx.animateTo(0f) }; dy.animateTo(0f) }
                                    }
                                },
                            ) { change, drag ->
                                change.consume()
                                scope.launch { dx.snapTo(dx.value + drag.x); dy.snapTo(dy.value + drag.y) }
                            }
                        },
                    hint
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            RoundButton("←", "later", Muted) { later = t }
            RoundButton("✕", "drop", Warn) { act(t, TriageMove.DROP) }
            RoundButton("→", "today", Green) { act(t, TriageMove.TODAY) }
        }
        Text("Right = today · left = later · up = drop", fontSize = 12.sp, color = Muted, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 14.dp))
    }

    later?.let { t ->
        RescheduleSheet(t, all, CalendarSync.cached(ctx).flatMap { it.events },
            onMove = { d -> later = null; act(t, TriageMove.LATER, d) }, onDismiss = { later = null })
    }
}

@Composable
private fun TriageCard(t: TaskItem, today: LocalDate, modifier: Modifier, hint: String?) {
    Column(
        modifier.fillMaxWidth().height(300.dp).padding(top = 20.dp).clip(RoundedCornerShape(22.dp)).background(Surface1)
            .border(1.dp, if (hint != null) Primary else Outline, RoundedCornerShape(22.dp)).padding(20.dp)
    ) {
        Text(Triage.label(t, today) + (Deck.minutes(t)?.let { " · ${TaskRules.minutesLabel(it)}" } ?: ""),
            fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Muted)
        Text(TaskViews.plainText(t), fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
        TaskViews.tags(t).takeIf { it.isNotEmpty() }?.let { Text(it.joinToString(" "), fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Purple, modifier = Modifier.padding(top = 8.dp)) }
        Box(Modifier.weight(1f))
        if (hint != null) Text(hint, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = when (hint) { "TODAY" -> Green; "DROP" -> Warn; else -> Amber },
            modifier = Modifier.align(Alignment.End).border(2.dp, when (hint) { "TODAY" -> Green; "DROP" -> Warn; else -> Amber }, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

@Composable
private fun RoundButton(icon: String, label: String, colour: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(58.dp).clip(CircleShape).border(1.5.dp, colour, CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
            Text(icon, fontSize = 22.sp, color = colour)
        }
        Text(label, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Muted, modifier = Modifier.padding(top = 4.dp))
    }
}
