package uk.tomlowndes.capture.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import uk.tomlowndes.capture.TaskNote
import uk.tomlowndes.capture.Tags
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import uk.tomlowndes.capture.KitGroup
import uk.tomlowndes.capture.KitItem
import uk.tomlowndes.capture.Kits
import uk.tomlowndes.capture.LastSeen
import uk.tomlowndes.capture.LaunchPad
import uk.tomlowndes.capture.NightPad
import uk.tomlowndes.capture.Seen
import uk.tomlowndes.capture.Sensory
import uk.tomlowndes.capture.SensoryLevel
import uk.tomlowndes.capture.TaskItem
import uk.tomlowndes.capture.TicketRail
import uk.tomlowndes.capture.Trip
import uk.tomlowndes.capture.Vault
import uk.tomlowndes.capture.WaitingMode
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * Hangar tools on Windows, batch 2 (hangar-windows-v1 stage 2): Launch Pad, Night Pad, Last Seen, Ticket Rail and
 * Sensory Dial. Same notes as the phone. Things that need the phone (Bluetooth printing, the camera, Home Assistant)
 * stay there, and each page says so.
 */

private val HM2 = DateTimeFormatter.ofPattern("HH:mm")
private val DAY2 = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)

/** "45 min" or "2h 10m". */
private fun minsWords(m: Long): String = if (m < 60) "$m min" else "${m / 60}h${if (m % 60 > 0) " ${m % 60}m" else ""}"

/** The kit, item by item, ticked on this computer for the session. */
@Composable
private fun KitList(items: List<KitItem>, ticked: Set<String>, onTick: (String, Boolean) -> Unit) {
    items.forEach { k ->
        val done = k.name in ticked
        ToolRow("${k.emoji} ${k.name}", k.reason?.let { "For $it" }, if (done) TaskGreen else TextMain) {
            if (done) TextAction("Undo", Muted) { onTick(k.name, false) } else PrimaryButton("Packed") { onTick(k.name, true) }
        }
    }
}

// ---- Launch Pad ----

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LaunchPadDesk(state: AppState, v: Vault) {
    val data = loaded(state, "Couldn't read Launch Pad") {
        val now = LocalDateTime.now()
        val events = runCatching { DesktopCalendar.load(v).second }.getOrDefault(emptyList())
        val e = LaunchPad.nextEvent(events, now)
        val w = runCatching { DesktopWeather.today(state.prefs) }.getOrNull()
        val trip = Trip(e?.title, e?.start, e?.end, null, null, w?.parts?.maxOfOrNull { it.rain } ?: 0, w?.low)
        trip to Kits.loadAll(v)
    } ?: return
    val (trip, kits) = data
    var ticked by remember { mutableStateOf(setOf<String>()) }
    // Kits ticked on or off on this computer (kits-backpack-v1).
    var on by remember { mutableStateOf(setOf<String>()) }
    var off by remember { mutableStateOf(setOf<String>()) }
    var editing by remember { mutableStateOf(false) }
    val active = Kits.active(kits, trip, on, off)
    val packs = Kits.packs(active, trip, on)
    val items = packs.flatMap { it.items }
    val title = trip.title
    if (title == null) Sub("Nothing else on today. Here's what you always take.")
    else {
        val timing = LaunchPad.timing(trip, LocalDateTime.now())
        val by = LaunchPad.leaveBy(trip)
        ToolRow("Next: $title", listOfNotNull(
            by?.let { "Starts ${it.format(HM2)}" },
            timing.minutesLeft?.takeIf { it > 0 }?.let { "in ${minsWords(it)}" },
            timing.lateBy.takeIf { it > 0 }?.let { "running ${minsWords(it)} late" },
            trip.rainChance.takeIf { it >= LaunchPad.RAIN_AT }?.let { "$it% rain" },
        ).joinToString(" · "), Amber)
    }
    if (kits.size > 1) FlowRow(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        kits.forEach { kit ->
            val isOn = active.any { it.name == kit.name }
            val why = packs.firstOrNull { it.kit.name == kit.name }?.why
            Chip("${kit.emoji} ${kit.name}" + (why?.let { " · $it" } ?: ""), selected = isOn, small = true, color = if (isOn) Amber else Muted) {
                if (isOn) { on = on - kit.name; off = off + kit.name } else { off = off - kit.name; on = on + kit.name }
            }
        }
    }
    GroupLabel("Take with you · ${items.count { it.name !in ticked }} to go")
    packs.forEach { p ->
        if (packs.size > 1) Text("${p.kit.emoji} ${p.kit.name} · ${p.items.count { it.name in ticked }}/${p.items.size}", color = Muted, fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
        KitList(p.items, ticked) { name, tick -> ticked = if (tick) ticked + name else ticked - name }
    }
    if (editing) KitsDialog(state) { editing = false }
    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        TextAction("Edit kits", Primary) { editing = true }
        if (ticked.isNotEmpty()) TextAction("Start again", Muted) { ticked = emptySet() }
    }
    Sub("Ticks here stay on this computer. The leave-by time from travel is on your phone.")
}

// ---- Night Pad ----

@Composable
internal fun NightPadDesk(state: AppState, v: Vault) {
    val data = loaded(state, "Couldn't read Night Pad") {
        val now = LocalDateTime.now()
        val events = runCatching { DesktopCalendar.load(v).second }.getOrDefault(emptyList())
        val e = NightPad.firstTomorrow(events, now)
        val w = runCatching { DesktopWeather.forDay(state.prefs, now.toLocalDate().plusDays(1)) }.getOrNull()
        val trip = Trip(e?.title, e?.start, e?.end, null, null, w?.parts?.maxOfOrNull { it.rain } ?: 0, w?.low)
        trip to NightPad.items(Kits.loadAll(v), trip)
    } ?: return
    val (trip, items) = data
    var ticked by remember { mutableStateOf(setOf<String>()) }
    val tomorrow = LocalDate.now().plusDays(1).format(DAY2)
    val title = trip.title
    if (title == null) Sub("Nothing planned for $tomorrow. Just charge up.")
    else ToolRow("Tomorrow: $title", listOfNotNull(
        trip.start?.let { "$tomorrow · ${it.format(HM2)}" },
        NightPad.wakeAt(trip)?.let { "Up by ${it.format(HM2)}" },
        trip.low?.let { "Low $it°" },
        trip.rainChance.takeIf { it >= LaunchPad.RAIN_AT }?.let { "$it% rain" },
    ).joinToString(" · "), Purple)
    listOf(KitGroup.CHARGE to "Charge", KitGroup.BAG to "Pack", KitGroup.WEAR to "Lay out").forEach { (g, label) ->
        val inGroup = items.filter { it.group == g }.map { it.item }
        if (inGroup.isNotEmpty()) {
            GroupLabel(label)
            KitList(inGroup, ticked) { name, on -> ticked = if (on) ticked + name else ticked - name }
        }
    }
    var editing by remember { mutableStateOf(false) }
    if (editing) KitsDialog(state) { editing = false }
    TextAction("Edit kits", Primary, Modifier.padding(top = 6.dp)) { editing = true }
    Sub("Ticks here stay on this computer. Alarms are set from your phone.")
}

// ---- Last Seen ----

@Composable
internal fun LastSeenDesk(state: AppState, v: Vault) {
    val all = loaded(state, "Couldn't read Last Seen") { LastSeen.load(v) } ?: return
    var q by remember { mutableStateOf("") }
    val now = LocalDateTime.now()
    Field(q, { q = it }, Modifier.width(420.dp), placeholder = "Where's my…")
    val shown = LastSeen.ordered(if (q.isBlank()) all else LastSeen.search(all, q), now.toLocalDate())
    if (shown.isEmpty()) Sub(if (q.isBlank()) "Nothing logged yet." else "Nothing matches \"$q\".")
    shown.forEach { s: Seen ->
        val found = s.found
        ToolRow("${s.emoji} ${s.what}",
            listOfNotNull(s.note.ifBlank { null }, s.place?.let { "📍 $it" }, LastSeen.ago(s.at, now),
                found?.let { "found ${it.format(DAY2)}" }).joinToString(" · "),
            if (found != null) Muted else TextMain) {
            s.photo?.let { p -> TextAction("Photo", Muted) { state.openInObsidian("${LastSeen.PHOTOS}/$p") } }
            if (found == null) PrimaryButton("Found it") { write(state, "Couldn't save") { LastSeen.markFound(v, s, LocalDate.now()) } }
            else TextAction("Not found", Muted) { write(state, "Couldn't save") { LastSeen.unfound(v, s) } }
        }
    }
    var kind by remember { mutableStateOf(LastSeen.KINDS.first()) }
    var what by remember { mutableStateOf("") }
    var where by remember { mutableStateOf("") }
    GroupLabel("Log where something is")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        LastSeen.KINDS.forEach { k -> Chip("${k.first} ${k.second}", selected = k == kind, small = true) { kind = k; what = "" } }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Field(what, { what = it }, Modifier.width(200.dp), placeholder = kind.second)
        Field(where, { where = it }, Modifier.weight(1f), placeholder = "Where: bay 41, hall drawer…")
        PrimaryButton("Log it", enabled = where.isNotBlank()) {
            val s = Seen(LocalDateTime.now().withSecond(0).withNano(0), kind.first, what.trim().ifEmpty { kind.second }, where.trim())
            write(state, "Couldn't save", "Logged") { LastSeen.add(v, s) }
            what = ""; where = ""
        }
    }
    Sub("Photos and map pins are added from your phone.")
}

// ---- Ticket Rail ----

/** Prints [tasks] as tickets on the PC's printer (the Daily Slip one), then gives each its rail id, like the phone. */
private fun printTickets(state: AppState, tasks: List<TaskItem>) = state.scope.launch {
    val port = state.prefs.printerAddress
    if (port == null) { state.say("Pick your printer in Daily Slip first"); return@launch }
    val n = state.io("Couldn't reach the printer. Is it on?") {
        val w = state.writer()!!
        val lists = w.listTaskLists()
        val taken = TicketRail.taken(lists.open, lists.done).toMutableSet()
        val today = LocalDate.now()
        val now = LocalTime.now()
        var printed = 0
        var unsaved = 0
        tasks.forEach { t ->
            val had = TicketRail.railId(t.raw)
            val id = had ?: w.printedId(t, lists.open) ?: TicketRail.nextId(TicketRail.prefix(t.text), taken).also { taken += it }
            DesktopPrinter.send(port, DesktopSlip.job(DesktopTicket.draw(TicketRail.ticket(t, id, today, now))))
            // printed-marks-v1: noted first, so a line that won't take its 🆔 now gets it next time.
            w.recordPrinted(listOf(uk.tomlowndes.capture.PrintLog.of(t, id, java.time.LocalDateTime.now())))
            if (had == null && runCatching { w.giveTicketId(t, id) }.isFailure) unsaved++
            printed++
        }
        printed to unsaved
    } ?: return@launch
    state.changed()
    val (count, unsaved) = n
    state.say((if (count == 1) "Printed 1 ticket" else "Printed $count tickets") +
        if (unsaved > 0) ", but $unsaved couldn't be marked in the vault yet. Flight Deck will mark ${if (unsaved == 1) "it" else "them"} next time it looks." else "")
}

/** The paper [tasks] will take as tickets (ticket-adaptive-v1): "about 17 cm". */
private fun paperFor(tasks: List<TaskItem>): String {
    val today = LocalDate.now()
    val now = LocalTime.now()
    return TicketRail.paperLabel(tasks.sumOf { DesktopTicket.heightOf(TicketRail.ticket(it, "tsk000", today, now)) })
}

/** New ticket for later (ticket-rail-future-v1): adds the job to Quick Task with its 📅 and tag, then prints it when [print]. */
private fun addJob(state: AppState, text: String, tag: String?, day: LocalDate, print: Boolean, done: () -> Unit) = state.scope.launch {
    val job = TicketRail.jobText(text, tag)
    val line = TaskNote.newTask(job, day.toString())
    val t = state.io("Couldn't save it") {
        val w = state.writer()!!
        w.addTask(job, day.toString())
        w.listQuickTasks().lastOrNull { it.raw.trimEnd() == line }
    }
    state.changed()
    done()
    when {
        !print -> state.say("Saved. It's in Coming up.")
        t == null -> state.say("Saved, but couldn't find it to print. Print it from Coming up")
        else -> printTickets(state, listOf(t))
    }
}

@Composable
internal fun TicketRailDesk(state: AppState, v: Vault) {
    val lists = loaded(state, "Couldn't read your tasks") { state.writer()!!.listTaskLists() } ?: return
    val today = LocalDate.now()
    // Where things live: with Bills off, #bill tasks stay off the rail, the morning run and Coming up.
    val places = remember(lists) { runCatching { uk.tomlowndes.capture.Places.load(v) }.getOrNull() }
    val open = remember(lists, places) { uk.tomlowndes.capture.Places.forBills(lists.open, places) }
    val rail = TicketRail.onRail(open)
    val later = rail.filter { TicketRail.isLater(it, today) }
    val railNow = rail - later.toSet()
    val cleared = TicketRail.clearedOn(lists.done, today).size
    // When each ticket printed, from this PC's own list (printed-marks-v1).
    val log = remember(lists) { runCatching { state.writer()?.printed() }.getOrNull().orEmpty() }
    fun stampOf(id: String): String? = uk.tomlowndes.capture.PrintLog.at(log, id)?.let { uk.tomlowndes.capture.PrintLog.stamp(it, today) }
    fun clear(t: TaskItem) = state.scope.launch {
        val c = state.io("Couldn't clear it") { state.writer()!!.completeTask(t, state.prefs.recurring) } ?: return@launch
        state.changed()
        state.offerUndo("Ticket cleared") { state.io { state.writer()!!.restoreTask(t, c) }; state.changed() }
    }
    Sub(listOfNotNull(
        if (rail.isEmpty()) "Nothing on the rail" else "${rail.size} on the rail",
        later.size.takeIf { it > 0 }?.let { "$it for later" },
        cleared.takeIf { it > 0 }?.let { "$it cleared today" },
    ).joinToString(" · "))
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            GroupLabel("On the rail")
            if (rail.isEmpty()) Sub("Nothing hanging yet.")
            railNow.forEach { t ->
                val id = TicketRail.railId(t.raw) ?: return@forEach
                val (slot, note) = TicketRail.slot(t.due, today)
                ToolRow("${TicketRail.code(id)} · ${t.text}", (listOf(slot, note) + listOfNotNull(stampOf(id))).filter { it.isNotBlank() }.joinToString(" · "),
                    if (slot.startsWith("LATE")) Warn else TextMain) {
                    TextAction("Reprint", Muted) { printTickets(state, listOf(t)) }
                    PrimaryButton("Clear") { clear(t) }
                }
            }
            if (later.isNotEmpty()) {
                Sub("Hanging for later", Modifier.padding(top = 6.dp))
                later.forEach { t ->
                    val id = TicketRail.railId(t.raw) ?: return@forEach
                    val (slot, note) = TicketRail.slot(t.due, today)
                    ToolRow("${TicketRail.code(id)} · ${t.text} · ${TicketRail.dayTag(t.due!!, today)}",
                        (listOf(slot, note) + listOf(stampOf(id) ?: "printed early")).filter { it.isNotBlank() }.joinToString(" · "), Muted) {
                        TextAction("Reprint", Muted) { printTickets(state, listOf(t)) }
                        PrimaryButton("Clear") { clear(t) }
                    }
                }
            }
            var code by remember { mutableStateOf("") }
            GroupLabel("Clear by code")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(code, { code = it }, Modifier.width(200.dp), placeholder = "HOM 042")
                PrimaryButton("Clear", enabled = code.isNotBlank()) {
                    val id = TicketRail.parseLink(code) ?: code.lowercase(Locale.UK).filter { it.isLetterOrDigit() }
                    val t = TicketRail.find(lists.open, id)
                    when {
                        t != null -> { clear(t); code = "" }
                        TicketRail.findDone(lists.done, id) != null -> state.say("Already cleared")
                        else -> state.say("No ticket with that code")
                    }
                }
            }
            val morning = TicketRail.morningRun(open, today)
            if (morning.isNotEmpty()) {
                GroupLabel("Morning run · not printed yet")
                morning.forEach { t -> ToolRow(t.text, TicketRail.slot(t.due, today).first, Muted) }
                PrimaryButton("Print morning run (${morning.size}) · ${paperFor(morning)}", Modifier.padding(top = 4.dp)) { printTickets(state, morning) }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            NewTicketCard(state, today)
            ComingUpCard(state, open, today)
        }
    }
    Sub(if (state.prefs.printerAddress == null) "To print here, pick your printer in Daily Slip first." else "Prints on the Daily Slip printer.",
        Modifier.padding(top = 10.dp))
}

/** New ticket for later: the job, its day (chips for the next six days, 📅 for further), its tag, then Save only or Print now. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NewTicketCard(state: AppState, today: LocalDate) {
    val chips = remember(today) { TicketRail.dayChips(today) }
    var text by remember { mutableStateOf("") }
    var day by remember(today) { mutableStateOf(chips.first()) }
    var tag by remember { mutableStateOf<String?>(TicketRail.JOB_TAGS.first()) }
    var picking by remember { mutableStateOf(false) }
    Card {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GroupLabel("New ticket for later")
            Field(text, { text = it }, Modifier.fillMaxWidth(), placeholder = "What's the job?")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                chips.forEach { d -> Chip(TicketRail.chipLabel(d, today) + if (d == day && d != chips.first()) " ${d.dayOfMonth}" else "", d == day, small = true) { day = d } }
                val custom = day !in chips
                Chip(if (custom) "📅 ${dayChip(day)}" else "📅 Pick", custom, small = true) { picking = true }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TicketRail.JOB_TAGS.forEach { t -> Chip(t, tag == t, small = true) { tag = t } }
                Chip("None", tag == null, small = true) { tag = null }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                TextAction("Save only", if (text.isBlank()) Muted else TextMain) {
                    if (text.isNotBlank()) addJob(state, text, tag, day, false) { text = "" }
                }
                PrimaryButton("Print now", enabled = text.isNotBlank()) { addJob(state, text, tag, day, true) { text = "" } }
            }
        }
    }
    if (picking) PickDateDialog(day, { d -> picking = false; if (d != null && d.isAfter(today)) day = d }, { picking = false })
}

/** Coming up: open tasks due in the next 7, 14 or 30 days that aren't printed yet. Tick, then Print N now. */
@Composable
private fun ComingUpCard(state: AppState, open: List<TaskItem>, today: LocalDate) {
    var days by remember { mutableStateOf(7) }
    var picked by remember { mutableStateOf(setOf<String>()) }
    val up = TicketRail.comingUp(open, today, days)
    Card {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { GroupLabel("Coming up") }
                listOf(7, 14, 30).forEach { n -> Chip("$n days", days == n, Modifier.padding(start = 4.dp), small = true) { days = n } }
            }
            if (up.isEmpty()) Sub("Nothing due in the next $days days that isn't printed.")
            up.forEach { t ->
                val on = t.raw in picked
                Row(Modifier.fillMaxWidth().hand().clickable { picked = if (on) picked - t.raw else picked + t.raw }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (on) "☑" else "☐", fontSize = 18.sp, color = if (on) Primary else Muted)
                    Text(Tags.strip(t.text), fontSize = 14.sp, color = TextMain, modifier = Modifier.weight(1f), maxLines = 1)
                    Text(TicketRail.upChip(t.due!!), fontSize = 12.sp, color = Muted)
                }
            }
            val chosen = up.filter { it.raw in picked }
            if (chosen.isNotEmpty()) PrimaryButton("Print ${chosen.size} now · ${paperFor(chosen)}", Modifier.padding(top = 4.dp)) {
                printTickets(state, chosen); picked = emptySet()
            }
        }
    }
}

// ---- Sensory Dial ----

@Composable
internal fun SensoryDesk(state: AppState, v: Vault) {
    val changes = loaded(state, "Couldn't read the Sensory log") { Sensory.load(v) } ?: return
    val now = LocalDateTime.now()
    val level = Sensory.current(changes)
    Card {
        androidx.compose.foundation.layout.Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            Text(level.option, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = if (level.down) Purple else TaskGreen)
            Text(listOfNotNull(level.words, Sensory.sinceWords(changes, now)).joinToString(" · "), fontSize = 13.sp, color = Muted)
        }
    }
    GroupLabel("This week · time turned down")
    Sensory.week(changes, now).forEach { (day, mins) ->
        ToolRow(day.format(DAY2), if (mins > 0) minsWords(mins.toLong()) else "—",
            if (day == now.toLocalDate()) Primary else TextMain)
    }
    Sub("Turn the dial or use your phone to change it. Windows reads the log. Levels: " +
        SensoryLevel.values().joinToString(", ") { it.option } + ".")
}

// ---- Waiting Mode ----

@Composable
internal fun WaitingDesk(state: AppState, v: Vault) {
    val data = loaded(state, "Couldn't read your tasks") {
        WaitingMode.quickTasks(state.writer()!!.listTasks()) to
            runCatching { WaitingMode.shortReads(state.readStore()!!.inbox()) }.getOrDefault(emptyList())
    } ?: return
    val (quick, reads) = data
    if (quick.isEmpty() && reads.isEmpty()) Sub("Nothing quick waiting. Enjoy the break.")
    if (quick.isNotEmpty()) GroupLabel("Quick tasks")
    quick.forEach { t ->
        ToolRow(t.text, t.due?.format(DAY2)) {
            PrimaryButton("Done") {
                state.scope.launch {
                    val c = state.io("Couldn't tick it") { state.writer()!!.completeTask(t, state.prefs.recurring) } ?: return@launch
                    state.changed()
                    state.offerUndo("Task completed") { state.io { state.writer()!!.restoreTask(t, c) }; state.changed() }
                }
            }
        }
    }
    if (reads.isNotEmpty()) GroupLabel("Short reads")
    reads.forEach { a ->
        ToolRow(a.title, "${WaitingMode.readMinutes(a.words)} min read") {
            PrimaryButton("Read") { state.openArticle(a.ref) }
        }
    }
}
