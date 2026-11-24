package uk.tomlowndes.capture

import android.content.Context
import android.graphics.Bitmap
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.coroutines.resume

/** Ticket Rail's colour: printer paper, so it reads as the printed tool in Hangar. */
internal val Paper = Color(0xFFE9D9B4)
internal val PaperTint = Color(0xFF2A2414)
internal val PaperInk = Color(0xFF1D1608)
private val HM = DateTimeFormatter.ofPattern("HH:mm")

/** A ticket scanned with the phone's own camera, waiting for Ticket Rail to open and clear it. */
object TicketInbox {
    var pending by mutableStateOf<String?>(null)
}

/** Printing tickets: picks ids, prints, then writes the ids to the tasks' lines so the QR finds them. */
object TicketPrint {
    /**
     * Prints a ticket for each of [tasks] (with [before], the Daily Slip, on top when given). Tasks that already
     * have a rail id print again with the same one. Returns how many tickets printed. Throws with a plain message.
     */
    suspend fun print(ctx: Context, vault: Uri, tasks: List<TaskItem>, before: Bitmap? = null, status: (String) -> Unit = {}): Int {
        val pr = SlipPrinter.saved(ctx) ?: error("Pick your printer in Daily Slip first")
        if (!SlipPrinter.allowed(ctx)) error("Allow Nearby devices for Flight Deck (open Daily Slip once)")
        if (!SlipPrinter.bluetoothOn(ctx)) error("Bluetooth is off. Switch it on and try again")
        val writer = VaultWriter(ctx, vault)
        val today = LocalDate.now()
        val now = LocalTime.now()
        val wantSteps = AppPrefs(ctx).ticketSteps
        var openLines: List<TaskItem> = emptyList()
        val ids = withContext(Dispatchers.IO) {
            val lists = writer.listTaskLists()
            openLines = lists.open
            val taken = TicketRail.taken(lists.open, lists.done).toMutableSet()
            tasks.distinctBy { it.raw }.map { t ->
                t to (TicketRail.railId(t.raw) ?: TicketRail.nextId(TicketRail.prefix(t.text), taken).also { taken += it })
            }
        }
        if (ids.isEmpty() && before == null) return 0
        status("Connecting to ${pr.name}…")
        val bmp = withContext(Dispatchers.Default) {
            val tickets = ids.takeIf { it.isNotEmpty() }?.let { l -> TicketRender.draw(l.map { (t, id) -> TicketRail.ticket(t, id, today, now, if (wantSteps) TicketRail.stepsOf(t, openLines) else emptyList()) }) }
            when {
                before != null && tickets != null -> TicketRender.stack(before, tickets)
                tickets != null -> tickets
                else -> before!!
            }
        }
        val job = withContext(Dispatchers.Default) { SlipRender.job(bmp) }
        withContext(Dispatchers.IO) { SlipPrinter.print(ctx, pr, job) { pc -> status("Printing on ${pr.name} · $pc%") } }
        // Only once it's on paper: the ids go onto the tasks, so each QR finds its task again.
        withContext(Dispatchers.IO) {
            ids.forEach { (t, id) -> if (TicketRail.railId(t.raw) == null) runCatching { writer.giveTicketId(t, id) } }
        }
        return ids.size
    }

    /**
     * New ticket for later (ticket-rail-future-v1): adds the job to Quick Task with its 📅 and tag, then prints it
     * when [print]. Returns how many tickets printed (0 for Save only).
     */
    suspend fun addJob(ctx: Context, vault: Uri, text: String, tag: String?, day: LocalDate, print: Boolean, status: (String) -> Unit = {}): Int {
        val job = TicketRail.jobText(text, tag)
        val line = TaskNote.newTask(job, day.toString())
        val task = withContext(Dispatchers.IO) {
            val w = VaultWriter(ctx, vault)
            w.addTask(job, day.toString())
            w.listQuickTasks().lastOrNull { it.raw.trimEnd() == line }
        }
        if (!print) return 0
        return print(ctx, vault, listOfNotNull(task ?: error("Saved, but couldn't find it to print. Print it from Coming up")), status = status)
    }

    /** The morning run for [today], from the vault. */
    suspend fun morning(ctx: Context, vault: Uri, today: LocalDate = LocalDate.now()): List<TaskItem> =
        withContext(Dispatchers.IO) { TicketRail.morningRun(VaultWriter(ctx, vault).listTasks(), today) }
}

/** What happened when a ticket was scanned. */
sealed interface ClearResult {
    data class Cleared(val task: TaskItem, val done: Completion, val at: LocalTime) : ClearResult
    data class Already(val task: TickedTask) : ClearResult
    data object NotFound : ClearResult
}

object TicketClear {
    /** Ticks the task with [id], wherever it is in the vault. */
    suspend fun clear(ctx: Context, vault: Uri, prefs: AppPrefs, id: String): ClearResult = withContext(Dispatchers.IO) {
        val w = VaultWriter(ctx, vault)
        val lists = w.listTaskLists()
        val t = TicketRail.find(lists.open, id)
            ?: return@withContext TicketRail.findDone(lists.done, id)?.let { ClearResult.Already(it) } ?: ClearResult.NotFound
        val c = w.completeTask(t, prefs.recurring)
        prefs.logTick(c.doneLine, c.nextLine)
        ClearResult.Cleared(t, c, LocalTime.now())
    }

    suspend fun undo(ctx: Context, vault: Uri, r: ClearResult.Cleared) = withContext(Dispatchers.IO) {
        VaultWriter(ctx, vault).restoreTask(r.task, r.done)
    }
}

/** Opens Google's ready-made QR scanner (no camera permission needed). Returns the code's text, or null. */
private suspend fun scanCode(ctx: Context): String? = suspendCancellableCoroutine { k ->
    val opts = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).enableAutoZoom().build()
    GmsBarcodeScanning.getClient(ctx, opts).startScan()
        .addOnSuccessListener { if (k.isActive) k.resume(it.rawValue) }
        .addOnCanceledListener { if (k.isActive) k.resume(null) }
        .addOnFailureListener { if (k.isActive) k.resume(null) }
}

/**
 * Ticket Rail (ticket-rail-v1): what's hanging on the rail, the morning run, and Scan to clear. [clearId] comes
 * from a `flightdeck://clear/<id>` link (the phone's own camera reading a ticket) and is cleared on open.
 */
@Composable
fun TicketRailScreen(vault: Uri?, prefs: AppPrefs, snack: SnackbarHostState, clearId: String?, onClearHandled: () -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var lists by remember { mutableStateOf<TaskLists?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var cleared by remember { mutableStateOf<ClearResult.Cleared?>(null) }
    var withSlip by remember { mutableStateOf(prefs.ticketsWithSlip) }
    var picked by remember { mutableStateOf(setOf<String>()) }
    var upDays by remember { mutableIntStateOf(7) }
    var rangeOpen by remember { mutableStateOf(false) }
    var newOpen by remember { mutableStateOf(false) }
    val today = LocalDate.now()

    LaunchedEffect(vault, reload) {
        val uri = vault ?: return@LaunchedEffect
        lists = withContext(Dispatchers.IO) { runCatching { VaultWriter(ctx, uri).listTaskLists() }.getOrNull() }
    }

    fun clear(id: String) {
        val uri = vault ?: return
        scope.launch {
            val r = runCatching { TicketClear.clear(ctx, uri, prefs, id) }
                .getOrElse { snack.showSnackbar("Couldn't clear it: ${it.message}"); return@launch }
            when (r) {
                is ClearResult.NotFound -> snack.showSnackbar("${TicketRail.code(id)} isn't in the vault any more")
                is ClearResult.Already -> snack.showSnackbar("Already cleared${r.task.done?.let { " on ${dueLabel(it)}" } ?: ""}")
                is ClearResult.Cleared -> {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    cleared = r
                    reload++
                    if (snack.showSnackbar("${TicketRail.code(id)} cleared", "Undo", duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) {
                        runCatching { TicketClear.undo(ctx, uri, r) }.onFailure { snack.showSnackbar("Couldn't undo: ${it.message}") }
                        cleared = null
                        reload++
                    }
                }
            }
        }
    }

    LaunchedEffect(clearId, vault) {
        val id = clearId ?: return@LaunchedEffect
        if (vault == null) return@LaunchedEffect
        onClearHandled()
        clear(id)
    }

    fun print(tasks: List<TaskItem>) {
        val uri = vault ?: return
        if (busy || tasks.isEmpty()) return
        busy = true
        scope.launch {
            val r = runCatching { TicketPrint.print(ctx, uri, tasks) { status = it } }
            busy = false
            status = r.fold({ n -> if (n == 1) "1 ticket printed." else "$n tickets printed." }, { it.message ?: "Printing failed" })
            if (r.isFailure) snack.showSnackbar(status!!) else picked = picked - tasks.map { it.raw }.toSet()
            reload++
        }
    }

    fun addJob(text: String, tag: String?, day: LocalDate, print: Boolean) {
        val uri = vault ?: return
        if (busy) return
        busy = true
        scope.launch {
            val r = runCatching { TicketPrint.addJob(ctx, uri, text, tag, day, print) { status = it } }
            busy = false
            reload++
            r.onSuccess { n ->
                newOpen = false
                status = if (n > 0) "Printed. It hangs under Hanging for later." else "Saved. It's in Coming up."
            }.onFailure {
                status = it.message ?: "Printing failed"
                newOpen = false
                snack.showSnackbar(status!!)
            }
        }
    }

    if (newOpen) TicketLaterSheet(busy, { t, tag, d, p -> addJob(t, tag, d, p) }, { if (!busy) newOpen = false })

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = Muted) }
            Text("Ticket Rail", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Paper, modifier = Modifier.weight(1f))
        }
        val l = lists
        if (vault == null) { Text("Pick your vault in Settings first.", color = Muted, modifier = Modifier.padding(top = 12.dp)); return@Column }
        if (l == null) { Text("Loading…", color = Muted, modifier = Modifier.padding(start = 4.dp, top = 8.dp)); return@Column }
        val rail = TicketRail.onRail(l.open)
        val later = rail.filter { TicketRail.isLater(it, today) }
        val railNow = rail - later.toSet()
        val clearedToday = TicketRail.clearedOn(l.done, today)
        val run = TicketRail.morningRun(l.open, today)
        val up = TicketRail.comingUp(l.open, today, upDays)
        // Paper each print will take (ticket-adaptive-v1): tickets are as long as their job now.
        fun paper(ts: List<TaskItem>): String {
            val now = LocalTime.now()
            val wantSteps = prefs.ticketSteps
            return TicketRail.paperLabel(TicketRender.lengthOf(ts.map { TicketRail.ticket(it, "tsk000", today, now, if (wantSteps) TicketRail.stepsOf(it, l.open) else emptyList()) }))
        }
        Text("${rail.size} on the rail${if (later.isNotEmpty()) " · ${later.size} for later" else ""} · ${clearedToday.size} cleared today", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp, bottom = 12.dp))

        cleared?.let { ClearedCard(it) }

        GroupLabel("On the rail")
        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (rail.isEmpty()) Text("Nothing hanging yet. Print the morning run, or Print ticket from any task.", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(4.dp))
            railNow.forEach { t ->
                val id = TicketRail.railId(t.raw)!!
                RailRow(TicketRail.code(id), Tags.strip(t.text), TicketRail.slot(t.due, today).let { (a, b) -> listOf(a, b).filter { it.isNotBlank() }.joinToString(" · ") },
                    onReprint = { print(listOf(t)) }, onClear = { clear(id) })
            }
            if (later.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Hanging for later", color = Muted, fontSize = 12.sp)
                    Box(Modifier.weight(1f).padding(start = 8.dp).height(1.dp).background(Outline))
                }
                later.forEach { t ->
                    val id = TicketRail.railId(t.raw)!!
                    val (slot, note) = TicketRail.slot(t.due, today)
                    RailRow(TicketRail.code(id), Tags.strip(t.text), listOf(slot, note).filter { it.isNotBlank() }.joinToString(" · "),
                        dayTag = TicketRail.dayTag(t.due!!, today), onReprint = { print(listOf(t)) }, onClear = { clear(id) })
                }
            }
        }

        GroupLabel("Morning run")
        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (run.isEmpty()) Text("Nothing due today that isn't already on the rail.", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(4.dp))
            run.forEach { t ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Surface1).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(Tags.strip(t.text), fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 2)
                    Text(TicketRail.slot(t.due, today).first, color = Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
            }
            if (run.isNotEmpty()) Text(
                if (busy) "Printing…" else "Print ${run.size} ticket${if (run.size == 1) "" else "s"} · ${paper(run)}",
                color = PaperInk, fontWeight = FontWeight.Bold, fontSize = 14.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp).clip(CircleShape).background(if (busy) Paper.copy(alpha = 0.5f) else Paper)
                    .clickable(enabled = !busy) { print(run) }.padding(vertical = 12.dp)
            )
        }
        // Coming up (ticket-rail-future-v1): print later jobs now, so they're ready to hang.
        Row(Modifier.fillMaxWidth().padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { GroupLabel("Coming up") }
            Box {
                Text("Next $upDays days ▾", color = Paper, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(CircleShape).clickable { rangeOpen = true }.padding(horizontal = 6.dp, vertical = 4.dp))
                DropdownMenu(rangeOpen, { rangeOpen = false }) {
                    listOf(7, 14, 30).forEach { n -> DropdownMenuItem({ Text("Next $n days") }, { upDays = n; rangeOpen = false }) }
                }
            }
        }
        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (up.isEmpty()) Text("Nothing due in the next $upDays days that isn't printed.", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(4.dp))
            up.forEach { t ->
                val on = t.raw in picked
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Surface1)
                    .clickable { picked = if (on) picked - t.raw else picked + t.raw }.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(20.dp).clip(RoundedCornerShape(6.dp)).background(if (on) Paper else Color.Transparent)
                        .border(2.dp, if (on) Paper else Muted, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
                        if (on) Text("✓", color = PaperInk, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(Tags.strip(t.text), fontSize = 14.sp, modifier = Modifier.weight(1f).padding(horizontal = 10.dp), maxLines = 2)
                    Text(TicketRail.upChip(t.due!!), color = Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.border(1.dp, Outline, RoundedCornerShape(8.dp)).padding(horizontal = 7.dp, vertical = 3.dp))
                }
            }
            val chosen = up.filter { it.raw in picked }
            if (chosen.isNotEmpty()) Text(
                if (busy) "Printing…" else "Print ${chosen.size} now · ${paper(chosen)}",
                color = PaperInk, fontWeight = FontWeight.Bold, fontSize = 14.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp).clip(CircleShape).background(if (busy) Paper.copy(alpha = 0.5f) else Paper)
                    .clickable(enabled = !busy) { print(chosen) }.padding(vertical = 12.dp)
            )
            Text(
                "+ New ticket for later", color = Paper, fontWeight = FontWeight.Bold, fontSize = 14.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp).clip(CircleShape).border(1.5.dp, Paper, CircleShape)
                    .clickable(enabled = !busy) { newOpen = true }.padding(vertical = 12.dp)
            )
        }

        Row(Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(14.dp)).background(Surface1).padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Print with the Daily Slip", fontSize = 14.sp)
                Text("Up to ${TicketRail.MORNING_MAX} tickets under the slip each morning", color = Muted, fontSize = 12.sp)
            }
            Switch(withSlip, { withSlip = it; prefs.ticketsWithSlip = it },
                colors = SwitchDefaults.colors(checkedTrackColor = Paper, checkedThumbColor = PaperInk))
        }
        status?.let { Text(it, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp, top = 10.dp)) }

        Text(
            "Scan a ticket", color = PaperInk, fontWeight = FontWeight.Bold, fontSize = 16.sp, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 24.dp).clip(CircleShape).background(Paper)
                .clickable {
                    scope.launch {
                        val raw = scanCode(ctx) ?: return@launch
                        val id = TicketRail.parseLink(raw)
                        if (id == null) snack.showSnackbar("That isn't a Ticket Rail code") else clear(id)
                    }
                }.padding(vertical = 15.dp)
        )
    }
}

@Composable
private fun RailRow(code: String, text: String, slot: String, dayTag: String? = null, onReprint: () -> Unit, onClear: () -> Unit) {
    // A ticket printed ahead of its day (dayTag set) sits on darker paper with a dashed edge and its day as a black tag.
    val later = dayTag != null
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (later) Color(0xFFEFE6D0) else Color(0xFFFBFAF6))
        .then(if (later) Modifier.drawBehind {
            val w = 4.dp.toPx()
            var y = 0f
            while (y < size.height) { drawRect(Color(0xFFB49A5E), Offset(0f, y), Size(w, 6.dp.toPx())); y += 10.dp.toPx() }
        } else Modifier)
        .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(code, color = PaperInk, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.width(76.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text, color = PaperInk, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, modifier = Modifier.weight(1f, fill = false))
                if (dayTag != null) Text(dayTag, color = Paper, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 10.sp,
                    modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(5.dp)).background(PaperInk).padding(horizontal = 6.dp, vertical = 1.dp))
            }
            Text(slot, color = Color(0xFF4A5368), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
        Text("Reprint", color = Color(0xFF4A5368), fontSize = 12.sp, modifier = Modifier.clip(CircleShape).clickable(onClick = onReprint).padding(8.dp))
        Text("Clear", color = PaperInk, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clip(CircleShape).clickable(onClick = onClear).padding(8.dp))
    }
}

@Composable
private fun ClearedCard(r: ClearResult.Cleared) {
    val id = TicketRail.railId(r.task.raw) ?: ""
    Column(
        Modifier.fillMaxWidth().padding(bottom = 16.dp).clip(RoundedCornerShape(18.dp)).background(GreenTint)
            .border(1.dp, Green.copy(alpha = 0.35f), RoundedCornerShape(18.dp)).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("CLEARED", color = Green, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 24.sp, letterSpacing = 2.sp,
            modifier = Modifier.rotate(-5f).border(3.dp, Green, RoundedCornerShape(8.dp)).padding(horizontal = 14.dp, vertical = 4.dp))
        Text(TicketRail.code(id), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.padding(top = 6.dp))
        Text(Tags.strip(r.task.text), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Text("Ticked in ${TicketRail.source(r.task.path)} at ${r.at.format(HM)}", color = Muted, fontSize = 12.sp)
    }
}
