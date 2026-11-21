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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
private val HM = DateTimeFormatter.ofPattern("HH:mm")

/** Where you got to when Heads Up pulled you out of something, so it's easy to pick up. */
object WhereIWas {
    const val PATH = "${FlightNotes.DIR}/Where I was.md"
    fun save(v: Vault, task: String, note: String, at: LocalDateTime = LocalDateTime.now()) {
        if (v.entry(PATH) == null) { v.mkdirs(FlightNotes.DIR); VaultIo.write(v, v.create(FlightNotes.DIR, "Where I was", "md"), "# Where I was\n\n") }
        VaultIo.edit(v, PATH) { lines ->
            while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.lastIndex)
            lines.add("- ${at.format(STAMP)} · $task · ${note.replace("\n", " ").trim()}")
        }
    }
}

/**
 * Heads Up (hangar-phase1). Settings for what buzzes and when; with [switching] it opens on the Focus switch view:
 * the time left, what's next, a one-line "where I was" note and one "5 more min".
 */
@Composable
fun HeadsUpScreen(vault: Uri?, prefs: AppPrefs, snack: SnackbarHostState, switching: Boolean, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var focus by remember { mutableStateOf(Focus.state(ctx)) }
    var clock by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1_000); clock = System.currentTimeMillis(); focus = Focus.state(ctx) } }
    val showSwitch = switching && focus != null

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = Muted) }
            Text("Heads Up", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Primary, modifier = Modifier.weight(1f))
        }
        if (showSwitch) SwitchView(vault, prefs, snack, focus!!, clock, onDone = onBack)
        else Settings(prefs) { scope.launch { withContext(Dispatchers.IO) { runCatching { HeadsAlarms.reschedule(ctx) } } } }
    }
}

@Composable
private fun SwitchView(vault: Uri?, prefs: AppPrefs, snack: SnackbarHostState, f: FocusState, clock: Long, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var next by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf("") }
    LaunchedEffect(vault) {
        val uri = vault ?: return@LaunchedEffect
        next = withContext(Dispatchers.IO) {
            runCatching { Deck.nextStep(VaultWriter(ctx, uri).listTasks().filter { it.raw != f.raw }, prefs.laterToday)?.let { TaskViews.plainText(it) } }.getOrNull()
        }
    }
    val left = f.remaining(clock)
    val frac = if (f.totalMs > 0) (left.toFloat() / f.totalMs).coerceIn(0f, 1f) else 0f
    Column(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(150.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(150.dp)) {
                val w = 14.dp.toPx()
                drawArc(Outline, 0f, 360f, false, Offset(w / 2, w / 2), Size(size.width - w, size.height - w), style = Stroke(w))
                drawArc(Primary, -90f, 360f * frac, false, Offset(w / 2, w / 2), Size(size.width - w, size.height - w), style = Stroke(w))
            }
            Text("%d:%02d".format(left / 60_000, (left / 1000) % 60), fontSize = 30.sp, fontWeight = FontWeight.Bold)
        }
        Text("Wrapping up: ${f.task}", color = Muted, fontSize = 13.sp, textAlign = TextAlign.Center)
        Text(next?.let { "Next: $it" } ?: "Next: take a breather", fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        OutlinedTextField(note, { note = it }, colors = fieldColors(), modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Where you got to, in one line") })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val canMore = prefs.headsExtendedFor != f.startedAt
            Text(if (canMore) "5 more min" else "Extra time used", color = if (canMore) TextMain else Muted, fontSize = 14.sp, textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).clip(CircleShape).border(1.dp, Outline, CircleShape).clickable(enabled = canMore) {
                    if (HeadsAlarms.moreTime(ctx)) scope.launch { snack.showSnackbar("5 more minutes") }
                }.padding(vertical = 13.dp))
            Text("Save note", color = OnPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).clip(CircleShape).background(Primary).clickable(enabled = note.isNotBlank()) {
                    val uri = vault ?: return@clickable
                    scope.launch {
                        withContext(Dispatchers.IO) { runCatching { WhereIWas.save(appVault(ctx, uri), f.task, note) } }
                            .onSuccess { snack.showSnackbar("Saved to Where I was"); onDone() }
                            .onFailure { snack.showSnackbar("Couldn't save: ${it.message}") }
                    }
                }.padding(vertical = 13.dp))
        }
    }
}

@Composable
private fun Settings(prefs: AppPrefs, onChanged: () -> Unit) {
    var leave by remember { mutableStateOf(prefs.headsLeave) }
    var events by remember { mutableStateOf(prefs.headsEvents) }
    var focus by remember { mutableStateOf(prefs.headsFocus) }
    var quiet by remember { mutableStateOf(prefs.headsQuiet) }
    var stages by remember { mutableStateOf(HeadsUp.parseStages(prefs.headsStages)) }
    val next = prefs.headsNext.takeIf { it > 0 }?.let { LocalDateTime.ofInstant(Instant.ofEpochMilli(it), ZoneId.systemDefault()) }
    Text(if (!prefs.headsOn) "Off. Add Heads Up in Hangar to turn it on." else next?.let { "Next buzz ${it.format(HM)}" } ?: "On. Nothing to warn you about yet today.",
        color = Muted, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp, bottom = 12.dp))
    GroupLabel("Buzz me before…")
    ListCard(Modifier.padding(top = 6.dp)) {
        Toggle("Leave-by times", leave) { leave = it; prefs.headsLeave = it; onChanged() }
        HorizontalDivider(color = Outline)
        Toggle("Calendar events", events) { events = it; prefs.headsEvents = it; onChanged() }
        HorizontalDivider(color = Outline)
        Toggle("Focus ending", focus) { focus = it; prefs.headsFocus = it; onChanged() }
        // The 21:00 Night Pad buzz went in evening-week-v1: the evening nudge opens Shut down, which ends with Night Pad.
    }
    GroupLabel("Timing")
    Row(Modifier.padding(top = 6.dp, bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        HeadsUp.STAGES.forEach { m ->
            val on = m in stages
            AppChip(on, {
                val n = if (on) stages - m else stages + m
                if (n.isNotEmpty()) { stages = n.sortedDescending(); prefs.headsStages = HeadsUp.formatStages(n); onChanged() }
            }, { Text("$m min") })
        }
    }
    GroupLabel("Quiet")
    ListCard(Modifier.padding(top = 6.dp)) { Toggle("Not after 21:00 or before 07:00", quiet) { quiet = it; prefs.headsQuiet = it; onChanged() } }
    Text("Each warning buzzes differently: one tap at 10, two at 5, three long ones at 1.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp, bottom = 20.dp))
}

@Composable
private fun Toggle(label: String, on: Boolean, onChange: (Boolean) -> Unit) =
    Row(Modifier.fillMaxWidth().clickable { onChange(!on) }.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(on, onChange, colors = SwitchDefaults.colors(checkedTrackColor = Primary, checkedThumbColor = OnPrimary))
    }
