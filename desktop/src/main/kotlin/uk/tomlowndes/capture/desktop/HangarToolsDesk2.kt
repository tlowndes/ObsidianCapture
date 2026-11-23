package uk.tomlowndes.capture.desktop

import androidx.compose.foundation.layout.Arrangement
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

@Composable
internal fun LaunchPadDesk(state: AppState, v: Vault) {
    val data = loaded(state, "Couldn't read Launch Pad") {
        val now = LocalDateTime.now()
        val events = runCatching { DesktopCalendar.load(v).second }.getOrDefault(emptyList())
        val e = LaunchPad.nextEvent(events, now)
        val w = runCatching { DesktopWeather.today(state.prefs) }.getOrNull()
        val trip = Trip(e?.title, e?.start, e?.end, null, null, w?.parts?.maxOfOrNull { it.rain } ?: 0, w?.low)
        trip to LaunchPad.itemsFor(LaunchPad.load(v), trip)
    } ?: return
    val (trip, items) = data
    var ticked by remember { mutableStateOf(setOf<String>()) }
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
    GroupLabel("Take with you · ${items.count { it.name !in ticked }} to go")
    KitList(items, ticked) { name, on -> ticked = if (on) ticked + name else ticked - name }
    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        TextAction("Edit kit in Obsidian", Primary) { state.openInObsidian(LaunchPad.KIT) }
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
        trip to NightPad.items(LaunchPad.load(v), trip)
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
    TextAction("Edit kit in Obsidian", Primary, Modifier.padding(top = 6.dp)) { state.openInObsidian(LaunchPad.KIT) }
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
        tasks.forEach { t ->
            val had = TicketRail.railId(t.raw)
            val id = had ?: TicketRail.nextId(TicketRail.prefix(t.text), taken).also { taken += it }
            DesktopPrinter.send(port, DesktopSlip.job(DesktopTicket.draw(TicketRail.ticket(t, id, today, now))))
            if (had == null) runCatching { w.giveTicketId(t, id) }
            printed++
        }
        printed
    } ?: return@launch
    state.changed()
    state.say(if (n == 1) "Printed 1 ticket" else "Printed $n tickets")
}

@Composable
internal fun TicketRailDesk(state: AppState, v: Vault) {
    val lists = loaded(state, "Couldn't read your tasks") { state.writer()!!.listTaskLists() } ?: return
    val today = LocalDate.now()
    val rail = TicketRail.onRail(lists.open)
    val cleared = TicketRail.clearedOn(lists.done, today).size
    fun clear(t: TaskItem) = state.scope.launch {
        val c = state.io("Couldn't clear it") { state.writer()!!.completeTask(t, state.prefs.recurring) } ?: return@launch
        state.changed()
        state.offerUndo("Ticket cleared") { state.io { state.writer()!!.restoreTask(t, c) }; state.changed() }
    }
    Sub(when {
        rail.isEmpty() -> "Nothing on the rail" + if (cleared > 0) " · $cleared cleared today" else ""
        else -> "${rail.size} on the rail" + if (cleared > 0) " · $cleared cleared today" else ""
    })
    rail.forEach { t ->
        val id = TicketRail.railId(t.raw) ?: return@forEach
        val (slot, note) = TicketRail.slot(t.due, today)
        ToolRow("${TicketRail.code(id)} · ${t.text}", listOf(slot, note).filter { it.isNotBlank() }.joinToString(" · "),
            if (slot.startsWith("LATE")) Warn else TextMain) {
            TextAction("Reprint", Muted) { printTickets(state, listOf(t)) }
            PrimaryButton("Clear") { clear(t) }
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
    val morning = TicketRail.morningRun(lists.open, today)
    if (morning.isNotEmpty()) {
        GroupLabel("Morning run · not printed yet")
        morning.forEach { t -> ToolRow(t.text, TicketRail.slot(t.due, today).first, Muted) }
        PrimaryButton("Print morning run (${morning.size})", Modifier.padding(top = 4.dp)) { printTickets(state, morning) }
    }
    Sub(if (state.prefs.printerAddress == null) "To print here, pick your printer in Daily Slip first." else "Prints on the Daily Slip printer.")
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
