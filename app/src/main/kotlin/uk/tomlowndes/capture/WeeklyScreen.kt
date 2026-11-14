package uk.tomlowndes.capture

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.IsoFields
import java.util.Locale

/** Weekly review: done, carried over (Move), one to drop (Drop or Skip), and next week in one line. */
@Composable
fun WeeklyScreen(vault: Uri?, snack: SnackbarHostState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var review by remember { mutableStateOf<Review?>(null) }
    var recap by remember { mutableStateOf<Recap?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var allDone by remember { mutableStateOf(false) }
    var dropSkipped by remember { mutableStateOf(false) }
    val today = LocalDate.now()

    LaunchedEffect(vault, refresh) {
        val uri = vault ?: return@LaunchedEffect
        val snap = TaskSnapshot.load(ctx)
        // First pass: the last task lists read, so the review shows without waiting for a vault scan.
        // Second pass: fresh lists.
        for (fresh in listOf(false, true)) {
            if (!fresh && (snap == null || review != null)) continue
            val built = withContext(Dispatchers.IO) {
                runCatching {
                    val v = appVault(ctx, uri)
                    val done = WeeklyReview.done(v, today)
                    if (recap == null) recap = runCatching {
                        Recaps.build(done, FlightNotes.loadSince(v, today.minusDays(6)), runCatching { Habits.load(v) }.getOrNull(),
                            runCatching { Jobs.list(v) }.getOrDefault(emptyList()), today)
                    }.getOrNull()
                    val open = if (fresh) VaultWriter(ctx, uri).listTaskLists().also { TaskSnapshot.save(ctx, it) }.open else snap!!.open
                    WeeklyReview.build(done, open, today)
                }.getOrNull()
            }
            if (built != null) review = built
        }
    }

    fun act(f: VaultWriter.() -> Unit) {
        val uri = vault ?: return
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { VaultWriter(ctx, uri).f() } }.onFailure { snack.showSnackbar("Failed: ${it.message}") }
            refresh++
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = Muted) }
            Text("Weekly review", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("Skip", color = Muted, fontSize = 13.sp, modifier = Modifier.clickable(onClick = onBack).padding(8.dp))
        }
        val from = today.minusDays(6)
        Text(
            "Week ${today.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)} · ${from.dayOfMonth}–${today.format(DateTimeFormatter.ofPattern("d MMM", Locale.UK))}",
            fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Muted, modifier = Modifier.padding(bottom = 12.dp)
        )
        val r = review ?: return@Column
        recap?.let { rc ->
            RecapCard(rc, onSave = {
                val uri = vault ?: return@RecapCard
                scope.launch {
                    withContext(Dispatchers.IO) { runCatching { VaultWriter(ctx, uri).putInboxNote(Recaps.noteName(rc), Recaps.note(rc)) } }
                        .onSuccess { snack.showSnackbar("Saved to 01 - Inbox/${Recaps.noteName(rc)}") }
                        .onFailure { snack.showSnackbar("Failed: ${it.message}") }
                }
            }, onShare = { runCatching { shareRecap(ctx, rc) }.onFailure { scope.launch { snack.showSnackbar("Couldn't share: ${it.message}") } } })
        }

        GroupLabel("Done · ${r.done.size}")
        if (r.done.isNotEmpty()) ListCard(Modifier.padding(top = 6.dp)) {
            val shown = if (allDone) r.done else r.done.take(3)
            shown.forEachIndexed { i, d ->
                if (i > 0) HorizontalDivider(color = Outline)
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    TickBox(done = true, onClick = {})
                    Text(d.text, fontSize = 13.sp, color = Muted, modifier = Modifier.padding(start = 10.dp).weight(1f))
                }
            }
            if (!allDone && r.done.size > 3) {
                HorizontalDivider(color = Outline)
                Text("+ ${r.done.size - 3} more ›", fontSize = 13.sp, color = Muted,
                    modifier = Modifier.fillMaxWidth().clickable { allDone = true }.padding(horizontal = 16.dp, vertical = 10.dp))
            }
        }

        Box(Modifier.height(10.dp))
        GroupLabel("Carried over · ${r.carried.size}")
        if (r.carried.isNotEmpty()) ListCard(Modifier.padding(top = 6.dp)) {
            r.carried.forEachIndexed { i, t ->
                if (i > 0) HorizontalDivider(color = Outline)
                TaskLine(t, edge = true, onTick = { act { completeTask(t) } }) {
                    t.due?.let { DuePill(it) }
                    ActionPill("Move") { act { rescheduleTask(t, WeeklyReview.nextMonday(today)) } }
                }
            }
        }

        Box(Modifier.height(10.dp))
        GroupLabel("One to drop · pick 0 or 1")
        ListCard(Modifier.padding(top = 6.dp)) {
            val drop = r.drop?.takeIf { !dropSkipped }
            if (drop != null) {
                TaskLine(drop, edge = true, onTick = { act { completeTask(drop) } }) {
                    ActionPill("Drop") { act { cancelTask(drop) } }
                    ActionPill("Skip") { dropSkipped = true }
                }
            } else Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Nothing to drop this week", fontSize = 13.sp, color = Muted, modifier = Modifier.weight(1f))
                ActionPill("Skip", onClick = onBack)
            }
        }

        Box(Modifier.height(10.dp))
        val mon = WeeklyReview.nextMonday(today)
        val events = CalendarSync.cached(ctx).filter { it.ok || it.events.isNotEmpty() }.flatMap { it.events }
            .count { !it.start.toLocalDate().isBefore(mon) && !it.start.toLocalDate().isAfter(mon.plusDays(6)) }
        ListCard(border = NextBorder, bg = NextBg) {
            Column(Modifier.padding(16.dp)) {
                Text("NEXT WEEK", fontSize = 12.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.2.sp, color = Primary)
                Text(
                    listOf(
                        plural(r.nextTasks.size, "task", "tasks") + " with a due date",
                        plural(r.nextBills.size, "bill", "bills") + if (r.nextBills.isNotEmpty()) " · ${TaskViews.money(TaskViews.total(r.nextBills))}" else "",
                        plural(events, "calendar event", "calendar events")
                    ).joinToString(" · "),
                    fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
        Box(Modifier.height(16.dp))
    }
}

private fun plural(n: Int, one: String, many: String) = "$n ${if (n == 1) one else many}"

@Composable
private fun ActionPill(text: String, onClick: () -> Unit) {
    Text(
        text, fontSize = 12.sp, color = Primary, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 6.dp).clip(CircleShape).background(PrimaryTint).clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}
