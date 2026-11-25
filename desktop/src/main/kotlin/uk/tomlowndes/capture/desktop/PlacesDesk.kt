package uk.tomlowndes.capture.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import uk.tomlowndes.capture.BillPage
import uk.tomlowndes.capture.BillRow
import uk.tomlowndes.capture.Bills
import uk.tomlowndes.capture.Element
import uk.tomlowndes.capture.Lives
import uk.tomlowndes.capture.PlaceState
import uk.tomlowndes.capture.Places
import uk.tomlowndes.capture.TaskLists
import uk.tomlowndes.capture.TaskViews
import uk.tomlowndes.capture.Vault
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Where things live on Windows (where-things-live-v1): the same list as the phone's Settings page, saved to the
 * vault config so a change on one shows on the other. Windows makes the one-off move (Bills to Hangar only) if it
 * reads the vault before the phone has.
 */
internal object DeskPlaces {
    fun moveOnce(v: Vault) {
        val json = v.read(uk.tomlowndes.capture.DeckConfig.PATH)
        // Wait for the phone's Hangar list and More tiles, so the move starts from what you actually use.
        // In practice the phone makes the move first.
        if (Places.moved(json) || uk.tomlowndes.capture.DeckConfig.hangar(json) == null || Places.tiles(json) == null) return
        Places.save(v, Places.migrated(Places.state(json)))
    }

    /** True when bills belong in Tasks and on the Deck (Bills set to Both). */
    fun billsInTasks(v: Vault): Boolean = Places.load(v).main(Element.BILLS)
}

@Composable
internal fun PlacesSection(state: AppState) {
    var s by remember { mutableStateOf<PlaceState?>(null) }
    LaunchedEffect(state.vault, state.changes) { s = state.vault?.let { v -> state.io { Places.load(v) } } }
    fun set(e: Element, p: Lives) {
        val v = state.vault ?: return
        val now = s ?: return
        val next = now.with(e, p)
        s = next
        state.scope.launch { state.io("Couldn't save what's in the app") { Places.save(v, next) }; state.changed() }
    }
    var showOff by remember { mutableStateOf(false) }
    Text("WHAT'S IN THE APP", fontSize = 12.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold, color = Muted, modifier = Modifier.padding(top = 26.dp, bottom = 4.dp))
    Sub("Same list as your phone. A change on one shows on the other.", Modifier.padding(bottom = 6.dp))
    val st = s
    if (st == null) { Sub("Pick a vault first."); return }
    @Composable
    fun row(e: Element) {
        val p = st.place(e)
        val on = p != Lives.OFF
        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(e.icon, fontSize = 15.sp, color = if (on) TextMain else Muted, modifier = Modifier.width(30.dp))
            Column(Modifier.weight(1f)) {
                Text(e.label, fontSize = 14.sp, color = if (on) TextMain else Muted)
                if (e.tool in PHONE_ONLY) Text("Phone only", fontSize = 11.5.sp, color = Muted)
            }
            if (e == Element.NEXT_STEP) Sub("Always on")
            else e.choices.forEachIndexed { i, c ->
                if (i > 0) Spacer(Modifier.width(6.dp))
                val label = when (c) { Lives.OFF -> "Off"; Lives.HANGAR -> "Hangar"; Lives.BOTH -> "Hangar + " + e.mainShort; else -> "On" }
                Chip(label, p == c || (c == Lives.BOTH && p == Lives.MAIN), small = true) { set(e, c) }
            }
        }
    }
    Element.GROUPS.forEach { g ->
        val rows = Element.values().filter { it.group == g && st.on(it) }
        if (rows.isEmpty()) return@forEach
        Text(g.uppercase(Locale.UK), fontSize = 11.sp, letterSpacing = 1.1.sp, color = Faint, modifier = Modifier.padding(top = 12.dp, bottom = 2.dp))
        rows.forEach { row(it) }
    }
    val off = Element.values().filter { !st.on(it) }
    if (off.isNotEmpty()) {
        Text("OFF", fontSize = 11.sp, letterSpacing = 1.1.sp, color = Faint, modifier = Modifier.padding(top = 12.dp, bottom = 2.dp))
        Row(Modifier.fillMaxWidth().hand().clickable { showOff = !showOff }.padding(vertical = 6.dp)) {
            Sub("${off.size} switched off" + if (showOff) "" else " · " + off.take(3).joinToString(", ") { it.label } + if (off.size > 3) "…" else "", Modifier.weight(1f))
            Sub(if (showOff) "Hide" else "Show ›")
        }
        if (showOff) off.forEach { row(it) }
    }
}

private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)
private val MON = DateTimeFormatter.ofPattern("MMM", Locale.UK)
private val MONTH = DateTimeFormatter.ofPattern("MMMM", Locale.UK)

/** Hangar › Bills on Windows (where-things-live-v1): the phone's Bills page, a month at a time. */
@Composable
internal fun BillsDesk(state: AppState, v: Vault) {
    val today = remember { LocalDate.now() }
    val lists = loaded<TaskLists>(state, "Couldn't read your bills") { state.writer()!!.listTaskLists() }
    var month by remember { mutableStateOf<YearMonth?>(YearMonth.from(today)) }
    var adding by remember { mutableStateOf(false) }
    val l = lists ?: run { Sub("Loading…"); return }
    val all = BillPage.all(l.open)
    val thisMonth = YearMonth.from(today)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        (0..2).map { thisMonth.plusMonths(it.toLong()) }.forEach { m -> Chip(m.format(MON), month == m, small = true) { month = m } }
        Chip("All ${all.size}", month == null, small = true) { month = null }
        Chip("＋ Add bill", small = true, color = Amber) { adding = true }
    }
    val m = month
    val days: List<Pair<LocalDate, List<BillRow>>>
    if (m == null) {
        BigLine(TaskViews.money(TaskViews.total(all.map { it.bill })), "to pay across ${all.size} open")
        days = all.groupBy { it.due }.toSortedMap().map { it.key to it.value }
    } else {
        val bm = BillPage.month(l.open, l.done, m, today)
        BigLine(TaskViews.money(bm.toPay), "to pay in ${m.format(MONTH)} · ${TaskViews.money(bm.paid)} paid")
        if (bm.rows.isEmpty()) Sub("No bills in ${m.format(MONTH)}.")
        days = bm.byDay
    }
    days.forEach { (d, rows) ->
        Row(Modifier.fillMaxWidth().padding(top = 10.dp)) {
            Text(d.format(DAY).uppercase(Locale.UK), fontSize = 11.sp, letterSpacing = 1.1.sp, color = Amber, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
            Text(TaskViews.money(TaskViews.total(rows.map { it.bill })), fontSize = 11.sp, color = Amber, fontFamily = FontFamily.Monospace)
        }
        rows.forEach { r ->
            Box(Modifier.fillMaxWidth().height(1.dp).background(Outline))
            Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                if (r.projected) Text("↻", color = Muted, fontSize = 14.sp, modifier = Modifier.width(24.dp))
                else Box(Modifier.width(24.dp)) {
                    Box(Modifier.width(16.dp).height(16.dp).clip(RoundedCornerShape(4.dp)).background(if (r.paid) TaskGreen else Surface1)
                        .border(1.5.dp, if (r.paid) TaskGreen else Muted, RoundedCornerShape(4.dp))
                        .then(if (r.paid) Modifier else Modifier.hand().clickable {
                            write(state, "Couldn't mark it paid", "${r.bill.name} paid") { state.writer()!!.completeTask(r.bill.task, state.prefs.recurring) }
                        }))
                }
                Text(r.bill.name, fontSize = 14.sp, color = if (r.paid || r.projected) Muted else TextMain,
                    textDecoration = if (r.paid) TextDecoration.LineThrough else null, modifier = Modifier.weight(1f).padding(start = 8.dp))
                Text(listOfNotNull(if (r.projected) "coming" else null, BillPage.repeatWords(r.bill.task.raw)).joinToString(" · "), fontSize = 12.sp, color = Muted,
                    modifier = Modifier.padding(end = 14.dp))
                Text(r.bill.amount?.let { TaskViews.money(it) } ?: "", fontSize = 13.5.sp, fontFamily = FontFamily.Monospace, color = if (r.paid) Muted else TextMain)
            }
        }
    }
    Sub("Bills are kept in ⚡ Quick Task, as before. Tick = paid, and a repeating bill writes its next line.", Modifier.padding(top = 12.dp))
    if (adding) AddBillDialog(Bills.suggestions(l.open, l.done), onAdd = { text, due ->
        adding = false
        write(state, "Couldn't add the bill", "Bill added") { state.writer()!!.addTask(text, due.toString()) }
    }, onDismiss = { adding = false })
}

@Composable
private fun BigLine(big: String, sub: String) {
    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 10.dp)) {
        Text(big, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = TextMain)
        Text(" $sub", fontSize = 13.sp, color = Muted, modifier = Modifier.padding(bottom = 5.dp))
    }
}
