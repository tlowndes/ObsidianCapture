package uk.tomlowndes.capture

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Each Hangar tool keeps one colour in the hub, on its own screen and on its widget. */
internal fun HangarTool.colour(): Color = when (this) {
    HangarTool.DID_I -> Green
    HangarTool.LAUNCH_PAD -> Amber
    HangarTool.HEADS_UP -> Primary
    HangarTool.NIGHT_PAD -> Violet
    HangarTool.POCKET_PASS -> Teal
    HangarTool.LAST_SEEN -> Coral
    HangarTool.RETURN_RADAR -> Pink
    HangarTool.COOL_OFF -> Ice
    HangarTool.REFILL -> Lime
    HangarTool.WAITING -> Sand
    HangarTool.ERRAND_RUN -> Orange
    HangarTool.TICKET_RAIL -> Paper
    HangarTool.SENSORY -> Ember
}

internal val GreenTint = Color(0xFF0D2A1D)

/** What the Finding things tiles show (hangar-phase2). */
private class FindStatus(val codes: List<PassCode>, val seen: List<Seen>, val returns: List<Return>)
private val HM = DateTimeFormatter.ofPattern("HH:mm")

/** What the Day to day tiles show (hangar-phase3): meds come with how many are left. */
private class DayStatus(val cools: List<CoolItem>, val meds: List<Pair<Med, Int>>, val quick: Int, val reads: Int, val stops: List<RunStop>)

/** A Right now card from a Day to day tool: a wait that's up, or a med to reorder. */
private data class DayNudge(val tool: HangarTool, val title: String, val sub: String, val button: String)

private fun dayNudge(d: DayStatus?, list: List<HangarTool>): DayNudge? {
    d ?: return null
    val now = java.time.LocalDateTime.now()
    if (HangarTool.REFILL in list) d.meds.firstOrNull { (m, left) -> Refill.needsOrder(m, left, now.toLocalDate()) }?.let { (m, left) ->
        return DayNudge(HangarTool.REFILL, "Reorder ${m.name}", "$left left · ${Refill.runsOutWords(m, left, now.toLocalDate())}", "Open Refill")
    }
    if (HangarTool.COOL_OFF in list) CoolOff.due(d.cools, now).firstOrNull()?.let { c ->
        return DayNudge(HangarTool.COOL_OFF, "Still want the ${c.what.replaceFirstChar { it.lowercase(java.util.Locale.UK) }}?",
            "The wait's up" + (CoolOff.priceWords(c)?.let { " · $it" } ?: ""), "Open Cool-off")
    }
    return null
}

/**
 * Hangar (hangar-phase1): the hub behind the one Hangar icon on Deck. The most urgent action sits on top, so
 * it can be done without opening a tool; the tools you've added are listed underneath with their live status.
 */
@Composable
fun HangarScreen(vault: Uri?, prefs: AppPrefs, snack: SnackbarHostState, onOpen: (HangarTool) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var tools by remember { mutableStateOf(Hangar.parse(prefs.hangarTools)) }
    var editing by remember { mutableStateOf(false) }
    var did by remember { mutableStateOf<List<DidStatus>?>(null) }
    var trip by remember { mutableStateOf<Trip?>(null) }
    var rail by remember { mutableStateOf<RailStatus?>(null) }
    var sensory by remember { mutableStateOf<List<SensoryChange>?>(null) }
    /** Tomorrow's trip and tonight's Night Pad list, when Night Pad is added. */
    var night by remember { mutableStateOf<Pair<Trip, List<NightItem>>?>(null) }
    var finding by remember { mutableStateOf<FindStatus?>(null) }
    var day by remember { mutableStateOf<DayStatus?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    fun save(t: List<HangarTool>) {
        tools = t; prefs.hangarTools = Hangar.format(t)
        // Heads Up only buzzes while it's in Hangar.
        val heads = HangarTool.HEADS_UP in t
        if (heads != prefs.headsOn) { prefs.headsOn = heads; HeadsAlarms.keepPlanning(ctx, heads); HeadsAlarms.changed(ctx) }
        // Cool-off checks prices in the background, and its and Refill's buzzes are planned with Heads Up's.
        CoolPrices.keep(ctx, HangarTool.COOL_OFF in t)
        // Home Did I? asks the house every 15 minutes while Did I? is in Hangar (it stops early when nothing's linked).
        HomeHouse.keep(ctx, HangarTool.DID_I in t)
        SensorySync.keep(ctx, HangarTool.SENSORY in t && SensoryHa.setup(ctx) != null)
        HeadsAlarms.changed(ctx)
    }

    LaunchedEffect(vault, tools, reload) {
        val uri = vault ?: return@LaunchedEffect
        if (tools?.contains(HangarTool.DID_I) == true)
            did = withContext(Dispatchers.IO) { runCatching { DidI.today(appVault(ctx, uri)) }.getOrNull() }
        if (tools?.contains(HangarTool.LAUNCH_PAD) == true)
            trip = withContext(Dispatchers.IO) { runCatching { TripLoader.load(ctx, appVault(ctx, uri)) }.getOrNull() }
        if (tools?.contains(HangarTool.NIGHT_PAD) == true)
            night = withContext(Dispatchers.IO) {
                runCatching { val v = appVault(ctx, uri); NightLoader.load(ctx, v).let { t -> t to NightPad.items(LaunchPad.load(v), t) } }.getOrNull()
            }
        if (tools?.any { it.group == "Finding things" } == true)
            finding = withContext(Dispatchers.IO) {
                runCatching {
                    val v = appVault(ctx, uri)
                    val t = tools.orEmpty()
                    FindStatus(
                        if (HangarTool.POCKET_PASS in t) PocketPass.visible(PocketPass.load(v), LocalDate.now()) else emptyList(),
                        if (HangarTool.LAST_SEEN in t) LastSeen.load(v) else emptyList(),
                        if (HangarTool.RETURN_RADAR in t) Returns.load(v) else emptyList(),
                    )
                }.getOrNull()
            }
        if (tools?.any { it.group == "Day to day" } == true)
            day = withContext(Dispatchers.IO) {
                runCatching {
                    val v = appVault(ctx, uri)
                    val t = tools.orEmpty()
                    val today = LocalDate.now()
                    val meds = if (HangarTool.REFILL in t) Refill.load(v).let { ms -> val st = Refill.stamps(v, ms, today); ms.map { it to Refill.left(it, st) } } else emptyList()
                    val tasks = if (HangarTool.WAITING in t || HangarTool.ERRAND_RUN in t) VaultWriter(ctx, uri).listTasks() else emptyList()
                    val stops = if (HangarTool.ERRAND_RUN in t) {
                        val places = Errands.places(v)
                        val shopping = Recipes.shopping(v)?.let { Recipes.shoppingOpen(it) }.orEmpty()
                        ErrandRun.stops(Errands.errands(tasks, shopping, places, ErrandNudges.shopTag(ctx)),
                            if (v.entry(Returns.NOTE) != null) Returns.load(v) else emptyList(), places, null, today)
                    } else emptyList()
                    DayStatus(
                        if (HangarTool.COOL_OFF in t) CoolOff.load(v) else emptyList(), meds,
                        if (HangarTool.WAITING in t) WaitingMode.quickTasks(tasks).size else 0,
                        if (HangarTool.WAITING in t) runCatching { WaitingMode.shortReads(ReadStore(ctx, uri).inbox()).size }.getOrDefault(0) else 0,
                        stops,
                    )
                }.getOrNull()
            }
        if (tools?.contains(HangarTool.TICKET_RAIL) == true)
            rail = withContext(Dispatchers.IO) { runCatching { RailStatus.load(ctx, uri) }.getOrNull() }
        if (tools?.contains(HangarTool.SENSORY) == true)
            sensory = withContext(Dispatchers.IO) { runCatching { Sensory.load(appVault(ctx, uri)) }.getOrNull() }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (editing) editing = false else onBack() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = Muted) }
            Text(if (editing) "Edit Hangar" else "Hangar", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (tools != null) Text(if (editing) "Done" else "Edit", color = Primary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(CircleShape).clickable { editing = !editing }.padding(horizontal = 12.dp, vertical = 8.dp))
        }
        val list = tools
        when {
            vault == null -> Text("Pick your vault in Settings first.", color = Muted, modifier = Modifier.padding(top = 12.dp))
            list == null -> FirstRun { t -> save(listOf(t)) }
            editing -> EditList(ctx, list, onSave = ::save)
            else -> {
                val next = did?.let { DidI.nextOpen(it) }
                val leave = trip?.takeIf { HangarTool.LAUNCH_PAD in list }?.let { t -> LaunchPad.leaveBy(t)?.let { t to it } }
                    ?.takeIf { (_, by) -> java.time.Duration.between(java.time.LocalDateTime.now(), by).toMinutes() in -15..30 }
                val ticks = prefs.nightTicks
                val tonight = night?.takeIf { HangarTool.NIGHT_PAD in list && leave == null && NightPad.nudge(java.time.LocalDateTime.now()) }
                    ?.let { (t, items) -> t to items.count { it.item.name !in ticks } }?.takeIf { it.second > 0 }
                // A return due within 2 days, still to send back (hangar-phase2).
                val due = finding?.returns?.takeIf { HangarTool.RETURN_RADAR in list && leave == null }
                    ?.filter { Returns.open(it) && Returns.daysLeft(it, LocalDate.now()) <= 2 }?.minByOrNull { it.by }
                // A med to reorder or a Cool-off wait that's up (hangar-phase3).
                val nudge = dayNudge(day, list).takeIf { leave == null && due == null }
                Text(if (next != null || leave != null || tonight != null || due != null || nudge != null) "1 thing needs you" else "All clear", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp, bottom = 12.dp))
                RightNow(next, onYes = { s ->
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    scope.launch {
                        val uri = vault ?: return@launch
                        val stamp = withContext(Dispatchers.IO) { runCatching { DidI.stamp(appVault(ctx, uri), s.check.name) } }
                            .getOrElse { snack.showSnackbar("Couldn't save: ${it.message}"); return@launch }
                        reload++
                        DidWidgets.changed(ctx)
                        val r = snack.showSnackbar("${s.check.name} logged at ${stamp.time.format(HM)}", actionLabel = "Undo", duration = SnackbarDuration.Short)
                        if (r == SnackbarResult.ActionPerformed) {
                            withContext(Dispatchers.IO) { runCatching { DidI.undo(appVault(ctx, uri), LocalDate.now(), stamp) } }
                            reload++
                            DidWidgets.changed(ctx)
                        }
                    }
                }, onOpen = { onOpen(HangarTool.DID_I) }, hasDid = HangarTool.DID_I in list,
                    leave = leave, onLaunch = { onOpen(HangarTool.LAUNCH_PAD) },
                    night = tonight?.takeIf { due == null && nudge == null }, onNight = { onOpen(HangarTool.NIGHT_PAD) },
                    ret = due, onReturn = { onOpen(HangarTool.RETURN_RADAR) },
                    day = nudge, onDay = { onOpen(it) })
                // Getting out, Finding things (hangar-phase2), Day to day (hangar-phase3), then At the desk (ticket-rail-v1).
                listOf("Getting out", "Finding things", "Day to day", "At the desk").forEachIndexed { gi, g ->
                    val rows = list.filter { it.group == g }
                    if (rows.isNotEmpty()) {
                        Box(Modifier.padding(top = if (gi == 0) 0.dp else 16.dp)) { GroupLabel(g) }
                        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            rows.forEach { t -> ToolTile(t, statusOf(t, did, trip, prefs, rail, night, finding, day, sensory)) { if (t.ready) onOpen(t) } }
                        }
                    }
                }
                val more = Hangar.available(list)
                if (more.isNotEmpty()) Text("＋ Add a tool", color = Primary, fontSize = 14.sp,
                    modifier = Modifier.padding(vertical = 14.dp).clickable { editing = true })
            }
        }
    }
}

/** Status line and the right-hand label for a tool's tile. */
private fun statusOf(
    t: HangarTool, did: List<DidStatus>?, trip: Trip?, prefs: AppPrefs, rail: RailStatus? = null,
    night: Pair<Trip, List<NightItem>>? = null, finding: FindStatus? = null, day: DayStatus? = null,
    sensory: List<SensoryChange>? = null,
): Pair<String, String> = when {
    !t.ready -> "Coming in the next update" to "soon"
    t == HangarTool.SENSORY && sensory == null -> "Loading…" to ""
    t == HangarTool.SENSORY -> sensoryLine(sensory!!)
    t.group == "Day to day" && day == null -> "Loading…" to ""
    t == HangarTool.COOL_OFF -> day!!.cools.let { cs ->
        val now = java.time.LocalDateTime.now()
        val cooling = CoolOff.cooling(cs)
        val saved = CoolOff.notSpent(cs, java.time.YearMonth.from(now))
        (cooling.firstOrNull()?.let { c -> listOfNotNull(c.what, CoolOff.leftWords(c, now) + " left", saved.takeIf { it > 0 }?.let { "${money(it)} not spent" }).joinToString(" · ") }
            ?: (if (saved > 0) "${money(saved)} not spent this month" else "Nothing cooling")) to (if (cooling.isEmpty()) "" else "${cooling.size} waiting")
    }
    t == HangarTool.REFILL -> day!!.meds.let { ms ->
        val today = LocalDate.now()
        val low = ms.minByOrNull { (m, left) -> Refill.runsOut(m, left, today) }
        (low?.let { (m, left) -> m.name + if (Refill.needsOrder(m, left, today)) " · reorder today" else " · ${Refill.runsOutWords(m, left, today)}" } ?: "Add a med to count down") to
            (low?.let { "${it.second} left" } ?: "")
    }
    t == HangarTool.WAITING -> day!!.let { d ->
        "${d.quick} quick task${if (d.quick == 1) "" else "s"} · ${d.reads} short read${if (d.reads == 1) "" else "s"}" to "tile"
    }
    t == HangarTool.ERRAND_RUN -> day!!.stops.let { st ->
        if (st.isEmpty()) "No errands waiting" to "" else ErrandRun.summary(st).let { (n, names) -> names to n }
    }
    t.group == "Finding things" && finding == null -> "Loading…" to ""
    t == HangarTool.POCKET_PASS -> finding!!.codes.let { c ->
        (if (c.isEmpty()) "Add your NHS number, car reg, Wi-Fi…" else c.take(3).joinToString(", ") { it.name } + if (c.size > 3) "…" else "") to
            (if (c.isEmpty()) "" else "${c.size} code${if (c.size == 1) "" else "s"}")
    }
    t == HangarTool.LAST_SEEN -> finding!!.seen.filter { it.found == null }.sortedByDescending { it.at }.let { out ->
        (out.firstOrNull()?.let { s -> listOfNotNull(s.what, s.place, LastSeen.ago(s.at, java.time.LocalDateTime.now())).joinToString(" · ") }
            ?: "Nothing out · snap where you put things") to (if (out.isEmpty()) "" else "${out.size} out")
    }
    t == HangarTool.RETURN_RADAR -> finding!!.returns.filter(Returns::open).sortedBy { it.by }.let { open ->
        val today = LocalDate.now()
        (if (open.isEmpty()) "Nothing to send back" else open.take(2).joinToString(" · ") { r ->
            val d = Returns.daysLeft(r, today)
            r.item + when { d < 0 -> " is late"; d == 0L -> " due today"; d == 1L -> " due tomorrow"; else -> " in $d days" }
        }) to (if (open.isEmpty()) "" else "${open.size} open")
    }
    t == HangarTool.NIGHT_PAD && night == null -> "Loading…" to ""
    t == HangarTool.NIGHT_PAD -> {
        val (nt, items) = night!!
        val done = items.count { it.item.name in prefs.nightTicks }
        (if (nt.title == null) "Nothing tomorrow · just charge up" else "Charge, bag, clothes for ${nt.title}") to "$done/${items.size}"
    }
    t == HangarTool.LAUNCH_PAD -> {
        val by = trip?.let { LaunchPad.leaveBy(it) }
        if (trip?.title != null && by != null) "${trip.title} · leave by ${by.format(HM)}" to "${prefs.launchTicks.size} packed"
        else "Nothing planned · everyday list" to ""
    }
    t == HangarTool.HEADS_UP -> {
        val next = prefs.headsNext.takeIf { it > System.currentTimeMillis() }
            ?.let { java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(it), java.time.ZoneId.systemDefault()) }
        when { !prefs.headsOn -> "Off" to ""; next != null -> "Next buzz ${next.format(HM)}" to "on"; else -> "On · nothing to warn you about yet" to "on" }
    }
    t == HangarTool.TICKET_RAIL && rail == null -> "Loading…" to ""
    t == HangarTool.TICKET_RAIL -> rail!!.line() to "${rail.onRail} on the rail"
    t == HangarTool.DID_I && did == null -> "Loading…" to ""
    t == HangarTool.DID_I -> {
        val s = did.orEmpty()
        val open = s.filter { it.at == null }.map { it.check.name }
        val done = s.filter { it.at != null }.joinToString(" · ") { "${it.check.name} ${it.at!!.format(HM)}" }
        (if (open.isEmpty()) done.ifEmpty { "No checks yet" } else "${open.joinToString(", ")} not checked yet") to DidI.progress(s)
    }
    else -> t.blurb to ""
}

@Composable
private fun RightNow(
    next: DidStatus?, hasDid: Boolean, onYes: (DidStatus) -> Unit, onOpen: () -> Unit,
    leave: Pair<Trip, java.time.LocalDateTime>? = null, onLaunch: () -> Unit = {},
    night: Pair<Trip, Int>? = null, onNight: () -> Unit = {},
    ret: Return? = null, onReturn: () -> Unit = {},
    day: DayNudge? = null, onDay: (HangarTool) -> Unit = {},
) {
    // A return due within 2 days comes after leaving soon, and before Night Pad and Did I?.
    if (ret != null && leave == null) {
        val d = Returns.daysLeft(ret, LocalDate.now())
        Column(
            Modifier.fillMaxWidth().padding(bottom = 16.dp).clip(RoundedCornerShape(18.dp)).background(PinkTint)
                .border(1.dp, Pink.copy(alpha = 0.4f), RoundedCornerShape(18.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("RIGHT NOW", fontSize = 11.sp, letterSpacing = 1.2.sp, color = Muted, fontWeight = FontWeight.SemiBold)
            Text("Return the ${ret.item.lowercase(java.util.Locale.UK)} " + when { d < 0 -> "(it's late)"; d == 0L -> "today"; d == 1L -> "by tomorrow"; else -> "in $d days" },
                fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Pink)
            Text(listOfNotNull(ret.shop.ifBlank { null }, ret.step.takeIf { it == ReturnStep.PACKED }?.let { "packed" } ?: "not packed yet").joinToString(" · "),
                color = Muted, fontSize = 13.sp)
            Text("Open Return Radar", color = Color(0xFF2A1622), fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                .clip(CircleShape).background(Pink).clickable(onClick = onReturn).padding(vertical = 10.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        return
    }
    // Then a med to reorder or a Cool-off wait that's up (hangar-phase3).
    if (day != null && leave == null) {
        val c = day.tool.colour()
        Column(
            Modifier.fillMaxWidth().padding(bottom = 16.dp).clip(RoundedCornerShape(18.dp)).background(Surface1)
                .border(1.dp, c.copy(alpha = 0.4f), RoundedCornerShape(18.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("RIGHT NOW", fontSize = 11.sp, letterSpacing = 1.2.sp, color = Muted, fontWeight = FontWeight.SemiBold)
            Text(day.title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = c)
            Text(day.sub, color = Muted, fontSize = 13.sp)
            Text(day.button, color = OnPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                .clip(CircleShape).background(c).clickable { onDay(day.tool) }.padding(vertical = 10.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        return
    }
    // After 19:00, packing for tomorrow comes before an open check (and after leaving soon, which is never at night).
    if (night != null && leave == null) {
        val (trip, left) = night
        Column(
            Modifier.fillMaxWidth().padding(bottom = 16.dp).clip(RoundedCornerShape(18.dp)).background(VioletTint)
                .border(1.dp, Violet.copy(alpha = 0.4f), RoundedCornerShape(18.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("RIGHT NOW", fontSize = 11.sp, letterSpacing = 1.2.sp, color = Muted, fontWeight = FontWeight.SemiBold)
            Text(if (trip.title == null) "Charge up for tomorrow" else "Pack for tomorrow", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Violet)
            Text(listOfNotNull(trip.title?.let { n -> trip.start?.let { "$n at ${it.format(HM)}" } ?: n },
                trip.rainChance.takeIf { it >= LaunchPad.RAIN_AT }?.let { "rain likely" }, if (left == 1) "1 thing" else "$left things").joinToString(" · "),
                color = Muted, fontSize = 13.sp)
            Text("Open Night Pad", color = Color(0xFF141230), fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                .clip(CircleShape).background(Violet).clickable(onClick = onNight).padding(vertical = 10.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        return
    }
    // Leaving soon beats an open check: Launch Pad shows the Did I? checks too.
    if (leave != null) {
        val (trip, by) = leave
        Column(
            Modifier.fillMaxWidth().padding(bottom = 16.dp).clip(RoundedCornerShape(18.dp)).background(Surface1)
                .border(1.dp, Amber.copy(alpha = 0.45f), RoundedCornerShape(18.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("RIGHT NOW", fontSize = 11.sp, letterSpacing = 1.2.sp, color = Muted, fontWeight = FontWeight.SemiBold)
            Text("Leave for ${trip.title ?: "your next thing"} by ${by.format(HM)}", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Amber)
            Text("Open Launch Pad", color = OnPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                .clip(CircleShape).background(Amber).clickable(onClick = onLaunch).padding(vertical = 10.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        return
    }
    Column(
        Modifier.fillMaxWidth().padding(bottom = 16.dp).clip(RoundedCornerShape(18.dp))
            .background(if (next != null) GreenTint else Surface1).border(1.dp, if (next != null) Green.copy(alpha = 0.35f) else Outline, RoundedCornerShape(18.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("RIGHT NOW", fontSize = 11.sp, letterSpacing = 1.2.sp, color = Muted, fontWeight = FontWeight.SemiBold)
        if (next == null) {
            Text(if (hasDid) "All clear" else "Nothing needs you", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(if (hasDid) "Every check is done today." else "Add Did I? to see your checks here.", color = Muted, fontSize = 13.sp)
        } else {
            Text("${next.check.emoji} ${next.check.question}", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Green)
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Open Did I?", color = TextMain, fontSize = 13.sp, modifier = Modifier.weight(1f).clip(CircleShape)
                    .border(1.dp, Outline, CircleShape).clickable(onClick = onOpen).padding(vertical = 10.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Text("Yes, done", color = OnPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).clip(CircleShape)
                    .background(Green).clickable { onYes(next) }.padding(vertical = 10.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}

@Composable
private fun ToolTile(t: HangarTool, status: Pair<String, String>, onClick: () -> Unit) {
    val c = if (t.ready) t.colour() else Muted
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Surface1).clickable(enabled = t.ready, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(4.dp).height(58.dp).background(c))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text("${t.icon} ${t.title}", color = c, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(status.first, color = Muted, fontSize = 12.5.sp, maxLines = 1)
        }
        if (status.second.isNotEmpty()) Text(status.second, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(end = 14.dp))
    }
}

@Composable
private fun FirstRun(onAdd: (HangarTool) -> Unit) {
    Text("Small tools for getting out the door. Pick the ones you want.", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(start = 4.dp, bottom = 14.dp))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HangarTool.values().forEach { t -> AddRow(t) { onAdd(t) } }
    }
    Text("You can add or hide tools at any time.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 14.dp))
}

@Composable
private fun AddRow(t: HangarTool, onAdd: () -> Unit) {
    val c = if (t.ready) t.colour() else Muted
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Surface1).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("${t.icon} ${t.title}", color = c, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(t.blurb, color = Muted, fontSize = 12.5.sp)
        }
        if (t.ready) Text("Add", color = OnPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp,
            modifier = Modifier.clip(CircleShape).background(Primary).clickable(onClick = onAdd).padding(horizontal = 14.dp, vertical = 7.dp))
        else Text("Soon", color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun EditList(ctx: Context, list: List<HangarTool>, onSave: (List<HangarTool>) -> Unit) {
    Text("Reorder, hide, or pin a tool to your home screen.", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp, bottom = 12.dp))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        list.forEachIndexed { i, t ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Surface1).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("↑", color = if (i > 0) TextMain else Outline, fontSize = 18.sp, modifier = Modifier.clip(CircleShape).clickable(enabled = i > 0) { onSave(Hangar.move(list, i, -1)) }.padding(8.dp))
                Text("↓", color = if (i < list.lastIndex) TextMain else Outline, fontSize = 18.sp, modifier = Modifier.clip(CircleShape).clickable(enabled = i < list.lastIndex) { onSave(Hangar.move(list, i, 1)) }.padding(8.dp))
                Text(t.title, fontSize = 15.sp, modifier = Modifier.weight(1f).padding(start = 4.dp))
                if (t.ready) Text("Pin", color = Primary, fontSize = 13.sp, modifier = Modifier.clip(CircleShape).clickable { pinShortcut(ctx, t) }.padding(8.dp))
                Text("Hide", color = Muted, fontSize = 13.sp, modifier = Modifier.clip(CircleShape).clickable { onSave(Hangar.remove(list, t)) }.padding(8.dp))
            }
        }
    }
    val more = Hangar.available(list)
    if (more.isNotEmpty()) {
        Box(Modifier.padding(top = 18.dp, bottom = 8.dp)) { GroupLabel("Add") }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { more.forEach { t -> AddRow(t) { onSave(Hangar.add(list, t)) } } }
    }
}

/** Asks the launcher to pin a home-screen icon that opens straight into [t]. It's still one app underneath. */
internal fun pinShortcut(ctx: Context, t: HangarTool) {
    val sm = ctx.getSystemService(ShortcutManager::class.java) ?: return
    if (!sm.isRequestPinShortcutSupported) return
    val intent = Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
        .putExtra("tab", when (t) { HangarTool.DID_I -> "DID_I"; HangarTool.LAUNCH_PAD -> "LAUNCH_PAD"; HangarTool.HEADS_UP -> "HEADS_UP"; HangarTool.NIGHT_PAD -> "NIGHT_PAD"; HangarTool.POCKET_PASS -> "POCKET_PASS"; HangarTool.LAST_SEEN -> "LAST_SEEN"; HangarTool.RETURN_RADAR -> "RETURN_RADAR"; HangarTool.COOL_OFF -> "COOL_OFF"; HangarTool.REFILL -> "REFILL"; HangarTool.WAITING -> "WAITING"; HangarTool.ERRAND_RUN -> "ERRAND_RUN"; HangarTool.TICKET_RAIL -> "RAIL"; HangarTool.SENSORY -> "SENSORY"; else -> "HANGAR" })
    val info = ShortcutInfo.Builder(ctx, "hangar-${t.id}")
        .setShortLabel(t.title).setIcon(Icon.createWithResource(ctx, R.mipmap.ic_launcher)).setIntent(intent).build()
    runCatching { sm.requestPinShortcut(info, null) }
}

/** Ticket Rail's line in Hangar: what's hanging, what's cleared, and whether the morning run is still to print. */
internal data class RailStatus(val onRail: Int, val cleared: Int, val toPrint: Int) {
    fun line(): String = when {
        toPrint > 0 -> "Morning run ready · $toPrint to print"
        cleared > 0 -> "$cleared cleared today"
        onRail > 0 -> "Scan a ticket when it's done"
        else -> "Nothing on the rail"
    }

    companion object {
        fun load(ctx: Context, vault: Uri, today: LocalDate = LocalDate.now()): RailStatus {
            val l = VaultWriter(ctx, vault).listTaskLists()
            return RailStatus(TicketRail.onRail(l.open).size, TicketRail.clearedOn(l.done, today).size, TicketRail.morningRun(l.open, today).size)
        }
    }
}
