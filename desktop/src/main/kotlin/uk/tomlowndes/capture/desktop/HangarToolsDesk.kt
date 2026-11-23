package uk.tomlowndes.capture.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import uk.tomlowndes.capture.CoolItem
import uk.tomlowndes.capture.CoolOff
import uk.tomlowndes.capture.DidCheck
import uk.tomlowndes.capture.DidI
import uk.tomlowndes.capture.DidStamp
import uk.tomlowndes.capture.DidStatus
import uk.tomlowndes.capture.HangarTool
import uk.tomlowndes.capture.Med
import uk.tomlowndes.capture.PassCode
import uk.tomlowndes.capture.PocketPass
import uk.tomlowndes.capture.Refill
import uk.tomlowndes.capture.Return
import uk.tomlowndes.capture.ReturnStep
import uk.tomlowndes.capture.Returns
import uk.tomlowndes.capture.Vault
import uk.tomlowndes.capture.money
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/*
 * Hangar tools on Windows, batch 1 (hangar-windows-v1 stage 2): Did I?, Refill, Cool-off, Return Radar and
 * Pocket Pass. Same notes as the phone, so either device sees the other's changes.
 */

/** The Hangar tools that have a Windows page so far. */
internal val DESK_TOOLS = setOf(
    HangarTool.DID_I, HangarTool.REFILL, HangarTool.COOL_OFF, HangarTool.RETURN_RADAR, HangarTool.POCKET_PASS,
    HangarTool.LAUNCH_PAD, HangarTool.NIGHT_PAD, HangarTool.LAST_SEEN, HangarTool.TICKET_RAIL, HangarTool.SENSORY,
    HangarTool.WAITING,
)

private val HM = DateTimeFormatter.ofPattern("HH:mm")

@Composable
internal fun HangarToolPane(state: AppState, t: HangarTool) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 22.dp)) {
        TextAction("‹ Hangar", Muted) { state.hangarTool = null }
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(t.icon, fontSize = 24.sp, color = t.deskColour())
            Text(t.title, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = TextMain)
        }
        Sub(t.blurb, Modifier.padding(top = 2.dp, bottom = 16.dp))
        val v = state.vault ?: return@Column
        Column(Modifier.widthIn(max = 760.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (t) {
                HangarTool.DID_I -> DidIDesk(state, v)
                HangarTool.REFILL -> RefillDesk(state, v)
                HangarTool.COOL_OFF -> CoolOffDesk(state, v)
                HangarTool.RETURN_RADAR -> ReturnsDesk(state, v)
                HangarTool.POCKET_PASS -> PocketPassDesk(state, v)
                HangarTool.LAUNCH_PAD -> LaunchPadDesk(state, v)
                HangarTool.NIGHT_PAD -> NightPadDesk(state, v)
                HangarTool.LAST_SEEN -> LastSeenDesk(state, v)
                HangarTool.TICKET_RAIL -> TicketRailDesk(state, v)
                HangarTool.SENSORY -> SensoryDesk(state, v)
                HangarTool.WAITING -> WaitingDesk(state, v)
                else -> Sub("${t.title} is on your phone for now.")
            }
        }
    }
}

/** Loads [load] off the UI thread whenever the vault changes. */
@Composable
internal fun <T> loaded(state: AppState, failure: String, load: () -> T): T? {
    var value by remember { mutableStateOf<T?>(null) }
    LaunchedEffect(state.vault, state.changes) { value = state.io(failure) { load() } }
    return value
}

/** Writes off the UI thread, then refreshes every page (and the Hangar dots). */
internal fun write(state: AppState, failure: String, done: String? = null, work: () -> Unit) {
    state.scope.launch {
        if (state.io(failure) { work() } != null) {
            state.changed()
            done?.let(state::say)
        }
    }
}

/** One line in a tool: a title, a muted line under it, and actions on the right. */
@Composable
internal fun ToolRow(title: String, sub: String?, colour: Color = TextMain, actions: @Composable () -> Unit = {}) {
    Card {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, color = colour, maxLines = 1, overflow = TextOverflow.Ellipsis)
                sub?.let { Text(it, fontSize = 12.5.sp, color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
            actions()
        }
    }
}

// ---- Did I? ----

@Composable
private fun DidIDesk(state: AppState, v: Vault) {
    val status = loaded(state, "Couldn't read Did I?") { DidI.today(v) to DidI.loadStamps(v, LocalDate.now()) } ?: return
    val (checks, stamps) = status
    Sub(if (checks.isEmpty()) "No checks yet" else "Today · ${DidI.progress(checks)}")
    checks.forEach { s: DidStatus ->
        val done = s.at
        ToolRow("${s.check.emoji} ${s.check.name}", done?.let { "Done at ${it.format(HM)}" } ?: s.check.question,
            if (done != null) TaskGreen else TextMain) {
            if (done == null) PrimaryButton("Yes, done") {
                write(state, "Couldn't save") { DidI.stamp(v, s.check.name) }
            } else {
                val stamp = stamps.lastOrNull { it.name == s.check.name }
                if (stamp != null) TextAction("Undo", Muted) { write(state, "Couldn't undo") { DidI.undo(v, LocalDate.now(), stamp) } }
            }
        }
    }
    var name by remember { mutableStateOf("") }
    var emoji by remember { mutableStateOf("") }
    GroupLabel("Add a check")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Field(emoji, { emoji = it.take(2) }, Modifier.width(70.dp), placeholder = "🔑")
        Field(name, { name = it }, Modifier.weight(1f), placeholder = "Door, oven, hair straighteners…")
        PrimaryButton("Add", enabled = name.isNotBlank()) {
            val c = DidCheck(emoji.ifBlank { "✓" }, name.trim())
            name = ""; emoji = ""
            write(state, "Couldn't add the check") { DidI.saveCheck(v, c) }
        }
    }
}

// ---- Refill ----

@Composable
private fun RefillDesk(state: AppState, v: Vault) {
    val today = LocalDate.now()
    val meds = loaded(state, "Couldn't read Refill") {
        val ms = Refill.load(v)
        val st = Refill.stamps(v, ms, today)
        ms.map { it to Refill.left(it, st) }
    } ?: return
    if (meds.isEmpty()) Sub("Add a med to count it down from your Did I? ticks.")
    meds.forEach { (m: Med, left: Int) ->
        val order = Refill.needsOrder(m, left, today)
        ToolRow(m.name, "$left left · ${Refill.perDayWords(m.perDay)} · ${Refill.runsOutWords(m, left, today)}" +
            (if (m.ordered) " · ordered" else if (order) " · reorder today" else ""), if (order) Amber else TextMain) {
            if (!m.ordered) TextAction("Ordered") { write(state, "Couldn't save") { Refill.update(v, m, m.copy(ordered = true)) } }
            TextAction("Picked up") { write(state, "Couldn't save", "${m.name}: new box counted") { Refill.update(v, m, Refill.pickedUp(m, left, LocalDateTime.now())) } }
            TextAction("Remove", Muted) { write(state, "Couldn't remove") { Refill.remove(v, m) } }
        }
    }
    var name by remember { mutableStateOf("") }
    var count by remember { mutableStateOf("") }
    var perDay by remember { mutableStateOf("1") }
    GroupLabel("Add a med")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Field(name, { name = it }, Modifier.weight(1f), placeholder = "Name")
        Field(count, { count = it.filter(Char::isDigit) }, Modifier.width(110.dp), placeholder = "How many")
        Field(perDay, { perDay = it.filter { c -> c.isDigit() || c == '.' } }, Modifier.width(90.dp), placeholder = "A day")
        val n = count.toIntOrNull()
        val d = perDay.toDoubleOrNull()?.takeIf { it > 0 }
        PrimaryButton("Add", enabled = name.isNotBlank() && n != null && d != null) {
            val m = Med(name.trim(), n!!, LocalDateTime.now().withSecond(0).withNano(0), d!!)
            name = ""; count = ""; perDay = "1"
            write(state, "Couldn't add the med") { Refill.add(v, m) }
        }
    }
}

// ---- Cool-off ----

@Composable
private fun CoolOffDesk(state: AppState, v: Vault) {
    val now = LocalDateTime.now()
    val today = now.toLocalDate()
    val all = loaded(state, "Couldn't read Cool-off") { CoolOff.load(v) } ?: return
    val saved = CoolOff.notSpent(all, java.time.YearMonth.from(now))
    if (saved > 0) Sub("${money(saved)} not spent this month")
    val cooling = CoolOff.cooling(all)
    if (cooling.isEmpty()) Sub("Nothing cooling. Add something you want to buy and wait 48 hours.")
    cooling.forEach { c: CoolItem ->
        val due = CoolOff.isDue(c, now)
        ToolRow(c.what + (c.now?.let { " · ${money(it)}" } ?: ""),
            listOfNotNull(if (due) "Still want it?" else CoolOff.leftWords(c, now) + " left", CoolOff.priceWords(c)).joinToString(" · "),
            if (due) Amber else TextMain) {
            c.link?.let { link -> TextAction("Open", Muted) { state.browse(link) } }
            TextAction("Bought") { write(state, "Couldn't save") { CoolOff.update(v, c, CoolOff.bought(c, today)) } }
            TextAction("Let go") { write(state, "Couldn't save", "Let go. That's money not spent.") { CoolOff.update(v, c, CoolOff.letGo(c, today)) } }
            if (due) TextAction("Wait more", Muted) { write(state, "Couldn't save") { CoolOff.update(v, c, CoolOff.wait(c, now)) } }
        }
    }
    var what by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    var link by remember { mutableStateOf("") }
    GroupLabel("Cool off on something")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Field(what, { what = it }, Modifier.weight(1f), placeholder = "What is it?")
        Field(price, { price = it.filter { c -> c.isDigit() || c == '.' } }, Modifier.width(100.dp), placeholder = "£")
        Field(link, { link = it }, Modifier.weight(1f), placeholder = "Link (optional, price is tracked)")
        PrimaryButton("Add", enabled = what.isNotBlank()) {
            val c = CoolItem(what.trim(), LocalDateTime.now().withSecond(0).withNano(0), price.toDoubleOrNull(), link.trim().ifBlank { null })
            what = ""; price = ""; link = ""
            write(state, "Couldn't add it") { CoolOff.add(v, c) }
        }
    }
}

// ---- Return Radar ----

@Composable
private fun ReturnsDesk(state: AppState, v: Vault) {
    val today = LocalDate.now()
    val all = loaded(state, "Couldn't read Return Radar") { Returns.load(v) } ?: return
    val list = Returns.ordered(all, today)
    if (list.isEmpty()) Sub("Nothing to send back.")
    list.forEach { r: Return ->
        val next = ReturnStep.values().getOrNull(r.step.ordinal + 1)
        val late = Returns.open(r) && Returns.daysLeft(r, today) <= 2
        ToolRow("${r.item} · ${r.shop}", "${r.step.label} · ${Returns.countdown(r, today)}", if (late) Amber else TextMain) {
            next?.let { s -> TextAction(s.label) { write(state, "Couldn't save") { Returns.update(v, r, r.copy(step = s)) } } }
            TextAction("Remove", Muted) { write(state, "Couldn't remove") { Returns.remove(v, r) } }
        }
    }
    var item by remember { mutableStateOf("") }
    var shop by remember { mutableStateOf("") }
    var days by remember { mutableStateOf(Returns.WINDOWS.first()) }
    GroupLabel("Add a return")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Field(item, { item = it }, Modifier.weight(1f), placeholder = "Item")
        Field(shop, { shop = it }, Modifier.weight(1f), placeholder = "Shop")
        Returns.WINDOWS.forEach { d -> Chip("${d}d", selected = d == days, small = true) { days = d } }
        PrimaryButton("Add", enabled = item.isNotBlank()) {
            val r = Return(item.trim(), shop.trim().ifBlank { "Shop" }, Returns.byFor(today, days))
            item = ""; shop = ""
            write(state, "Couldn't add the return") { Returns.add(v, r) }
        }
    }
}

// ---- Pocket Pass ----

@Composable
private fun PocketPassDesk(state: AppState, v: Vault) {
    val today = LocalDate.now()
    val codes = loaded(state, "Couldn't read Pocket Pass") { PocketPass.visible(PocketPass.load(v), today) } ?: return
    var shown by remember { mutableStateOf(emptySet<PassCode>()) }
    if (codes.isEmpty()) Sub("Add the codes you always hunt for: NHS number, car reg, Wi-Fi…")
    codes.groupBy { it.group }.forEach { (group, rows) ->
        GroupLabel(group)
        rows.forEach { c: PassCode ->
            val open = !c.hidden || c in shown
            ToolRow(c.name, if (open) c.value else PocketPass.masked(c.value)) {
                if (c.hidden) TextAction(if (open) "Hide" else "Show", Muted) { shown = if (open) shown - c else shown + c }
                TextAction("Copy") { copyToClipboard(c.value); state.say("${c.name} copied") }
                TextAction("Remove", Muted) { write(state, "Couldn't remove") { PocketPass.remove(v, c) } }
            }
        }
    }
    var group by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    var hidden by remember { mutableStateOf(false) }
    GroupLabel("Add a code")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Field(group, { group = it }, Modifier.width(130.dp), placeholder = "Group")
        Field(name, { name = it }, Modifier.weight(1f), placeholder = "Name")
        Field(value, { value = it }, Modifier.weight(1f), placeholder = "Code")
        Chip("🔒", selected = hidden, small = true) { hidden = !hidden }
        PrimaryButton("Add", enabled = name.isNotBlank() && value.isNotBlank()) {
            val c = PassCode(group.trim().ifBlank { "Other" }, name.trim(), value.trim(), hidden)
            group = ""; name = ""; value = ""; hidden = false
            write(state, "Couldn't add the code") { PocketPass.add(v, c) }
        }
    }
}
