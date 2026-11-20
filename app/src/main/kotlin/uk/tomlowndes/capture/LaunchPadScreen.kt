package uk.tomlowndes.capture

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val PassInk = Color(0xFF0D1630)
private val PassRule = Color(0xFFB7BFD2)
private val PassAmber = Color(0xFFB26B00)
private val PassViolet = Color(0xFF5B45C9)
private val HM = DateTimeFormatter.ofPattern("HH:mm")

/** Today's trip, read from the cached calendars, the travel line and the weather. Call off the main thread. */
object TripLoader {
    fun events(ctx: Context, v: Vault): List<CalEvent> {
        val on = runCatching { CalendarConfig.load(v).filter { it.enabled }.map { it.id }.toSet() }.getOrDefault(emptySet())
        return CalendarSync.cached(ctx).filter { it.id in on || it.id == AppEvents.CAL_ID }.flatMap { it.events }
    }

    fun load(ctx: Context, v: Vault, now: LocalDateTime = LocalDateTime.now()): Trip {
        val events = events(ctx, v)
        val skip = AppPrefs(ctx).launchSkip
        val travel = runCatching { TravelFetch.line(ctx, events, now) }.getOrNull()?.takeIf { it.event.start.toString() != skip }
        val e = travel?.event ?: LaunchPad.nextEvent(events.filter { it.start.toString() != skip }, now)
        val w = runCatching { WeatherFetch.today(ctx) }.getOrNull()
        return Trip(e?.title, e?.start, e?.end, travel?.leaveBy, travel?.minutes, w?.parts?.maxOfOrNull { it.rain } ?: 0, w?.low)
    }
}

/**
 * Launch Pad (hangar-phase1): the leave-the-house pass, opened from Hangar or by tapping an NFC tag that holds
 * flightdeck://launch. One big button steps through what's left; Did I? checks are the last lines.
 */
@Composable
fun LaunchPadScreen(vault: Uri?, prefs: AppPrefs, snack: SnackbarHostState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var trip by remember { mutableStateOf<Trip?>(null) }
    var kit by remember { mutableStateOf<Kit?>(null) }
    var did by remember { mutableStateOf<List<DidStatus>>(emptyList()) }
    // Bag items ticked in Night Pad last night start done, marked ☾.
    val night = remember { prefs.packedLastNight }
    var ticked by remember { mutableStateOf(prefs.launchTicks + night) }
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    var showKit by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) { while (true) { delay(20_000); now = LocalDateTime.now() } }
    LaunchedEffect(vault, reload) {
        val uri = vault ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            runCatching { val v = appVault(ctx, uri); Triple(LaunchPad.load(v), TripLoader.load(ctx, v), DidI.today(v)) }
        }.onSuccess { kit = it.first; trip = it.second; did = it.third }.onFailure { snack.showSnackbar("Couldn't load Launch Pad: ${it.message}") }
    }
    BackHandler { if (showKit) showKit = false else onBack() }

    fun tick(name: String) { haptics.performHapticFeedback(HapticFeedbackType.LongPress); ticked = ticked + name; prefs.launchTicks = ticked }
    fun stamp(c: DidCheck) {
        val uri = vault ?: return
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { DidI.stamp(appVault(ctx, uri), c.name) } }
                .onSuccess { s -> did = did.map { if (it.check == c) it.copy(at = s.time) else it }; DidWidgets.changed(ctx) }
                .onFailure { snack.showSnackbar("Couldn't save: ${it.message}") }
        }
    }

    val t = trip
    val k = kit
    val timing = t?.let { LaunchPad.timing(it, now) }
    val late = (timing?.lateBy ?: 0) > 0
    val all = if (t != null && k != null) LaunchPad.itemsFor(k, t) else emptyList()
    val items = if (late) LaunchPad.essentials(all) else all
    val nextItem = items.firstOrNull { it.name !in ticked }
    val nextDid = did.firstOrNull { it.at == null }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (showKit) showKit = false else onBack() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = Muted) }
            Text(if (showKit) "Your kit" else "Launch Pad", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Amber, modifier = Modifier.weight(1f))
            if (!showKit) Text("Kit", color = Primary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(CircleShape).clickable { showKit = true }.padding(horizontal = 12.dp, vertical = 8.dp))
        }
        when {
            vault == null -> Text("Pick your vault in Settings first.", color = Muted, modifier = Modifier.padding(top = 12.dp))
            t == null || k == null -> Text("Loading today's trip…", color = Muted, modifier = Modifier.padding(top = 12.dp))
            showKit -> KitView(k)
            else -> {
                if (late) LateBanner(timing!!, all.size - items.size)
                else timing?.minutesLeft?.let { m ->
                    Text(if (m <= 0) "Time to go" else "$m min until you leave", color = Amber, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
                }
                val fromNight = items.count { it.name in night }
                if (fromNight > 0 && !late) Text("☾ $fromNight packed last night · ${items.count { it.name !in ticked }} to go", color = Violet, fontSize = 13.sp,
                    modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
                TripPass(t, items, ticked, did, onTick = ::tick, onStamp = ::stamp, night = night)
                Spacer(Modifier.height(14.dp))
                when {
                    nextItem != null -> Big("Next: ${nextItem.name.lowercase(Locale.UK)}", Amber) { tick(nextItem.name) }
                    nextDid != null -> Big("Next: ${nextDid.check.question}", Green) { stamp(nextDid.check) }
                    else -> Big("All set, go", Amber) { scope.launch { snack.showSnackbar("Have a good one") }; onBack() }
                }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    if (t.start != null) Text("Not going", color = Muted, fontSize = 14.sp, modifier = Modifier.clickable {
                        prefs.launchSkip = t.start.toString(); ticked = night; prefs.launchTicks = ticked; reload++
                    }.padding(vertical = 8.dp)) else Spacer(Modifier)
                    if ((ticked - night).isNotEmpty()) Text("Start again", color = Muted, fontSize = 14.sp, modifier = Modifier.clickable {
                        ticked = night; prefs.launchTicks = ticked
                    }.padding(vertical = 8.dp))
                }
                Text("Tip: write flightdeck://launch to an NFC tag (any NFC writer app does it) and stick it by the door. Tapping it opens this screen.",
                    color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp, bottom = 20.dp))
            }
        }
    }
}

@Composable
private fun LateBanner(timing: Timing, trimmed: Int) {
    Column(
        Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(16.dp)).background(AmberTint).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text("Running ${timing.lateBy} min late", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Amber)
        Text(
            (timing.arriveAt?.let { "That's fine. Leave now and you'll get there about ${it.format(HM)}." } ?: "That's fine. Leave when you can.") +
                if (trimmed > 0) " Only the essentials are left on the list." else "",
            color = TextMain, fontSize = 13.sp
        )
    }
}

@Composable
private fun TripPass(
    t: Trip, items: List<KitItem>, ticked: Set<String>, did: List<DidStatus>, onTick: (String) -> Unit, onStamp: (DidCheck) -> Unit,
    night: Set<String> = emptySet(),
) {
    val by = LaunchPad.leaveBy(t)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(TextMain).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text((t.title ?: "Heading out").uppercase(Locale.UK), color = PassInk, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, modifier = Modifier.weight(1f))
            Text(by?.let { "BOARD ${it.format(HM)}" } ?: LocalDate.now().format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)).uppercase(Locale.UK),
                color = PassInk, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
        val meta = listOfNotNull(
            t.start?.let { "Starts ${it.format(HM)}" }, t.driveMinutes?.let { "$it min drive" },
            t.low?.let { "low $it°" }, t.rainChance.takeIf { it > 0 }?.let { "rain $it%" }
        ).joinToString(" · ")
        if (meta.isNotEmpty()) Text(meta, color = PassInk.copy(alpha = 0.6f), fontSize = 11.sp)
        Box(Modifier.fillMaxWidth().height(2.dp).background(PassRule))
        items.forEach { i ->
            val done = i.name in ticked
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(enabled = !done) { onTick(i.name) }.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${i.emoji}  ${i.name.uppercase(Locale.UK)}", color = PassInk.copy(alpha = if (done) 1f else 0.5f), fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                if (i.name in night) Text("  ☾ last night", color = PassViolet, fontSize = 10.sp)
                else if (i.reason != null) Text("  ${i.reason}", color = PassAmber, fontSize = 10.sp)
                Spacer(Modifier.weight(1f))
                Text(if (done) "✓" else "—", color = PassInk.copy(alpha = if (done) 1f else 0.5f), fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            }
        }
        if (did.isNotEmpty()) {
            Box(Modifier.fillMaxWidth().height(2.dp).background(PassRule))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("DID I?", color = PassInk, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 6.dp))
                did.forEach { s ->
                    val done = s.at != null
                    Text(if (done) "✓ ${s.check.name}" else s.check.name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        color = if (done) PassInk else PassInk.copy(alpha = 0.5f),
                        modifier = Modifier.clip(CircleShape).border(1.dp, PassRule, CircleShape).clickable(enabled = !done) { onStamp(s.check) }
                            .padding(horizontal = 9.dp, vertical = 5.dp))
                }
            }
        }
    }
}

@Composable
internal fun KitView(k: Kit, night: Boolean = false) {
    Text(if (night) "Charge and Wear are for the night before; bag extras come from the When rules. Edit the note in Obsidian: ${LaunchPad.KIT}"
        else "Always, plus extras when a rule applies. Edit the note in Obsidian: ${LaunchPad.KIT}", color = Muted, fontSize = 13.sp,
        modifier = Modifier.padding(start = 4.dp, bottom = 12.dp))
    if (night) {
        GroupLabel("Charge")
        ListCard(Modifier.padding(top = 6.dp)) {
            Text(k.charge.joinToString(" · ") { "${it.emoji} ${it.name}" }.ifEmpty { "Nothing yet" }, fontSize = 14.sp, modifier = Modifier.padding(16.dp))
        }
        if (k.wear.isNotEmpty()) {
            GroupLabel("Wear")
            ListCard(Modifier.padding(top = 6.dp)) {
                Text(k.wear.joinToString(" · ") { "${it.emoji} ${it.name}" }, fontSize = 14.sp, modifier = Modifier.padding(16.dp))
            }
        }
    } else {
        GroupLabel("Always")
        ListCard(Modifier.padding(top = 6.dp)) {
            Text(k.always.joinToString(" · ") { "${it.emoji} ${it.name}" }.ifEmpty { "Nothing yet" }, fontSize = 14.sp, modifier = Modifier.padding(16.dp))
        }
    }
    GroupLabel("When…")
    val rules = if (night) k.rules else k.rules.filter { it.group == KitGroup.BAG }
    ListCard(Modifier.padding(top = 6.dp)) {
        if (rules.isEmpty()) Text("No rules yet", color = Muted, modifier = Modifier.padding(16.dp))
        rules.forEachIndexed { i, r ->
            if (i > 0) HorizontalDivider(color = Outline)
            val head = when (r.kind) {
                KitRule.Kind.RAIN -> "Rain over ${LaunchPad.RAIN_AT}%"
                KitRule.Kind.COLD -> "Low of ${r.arg.toIntOrNull() ?: 5}° or less"
                KitRule.Kind.EVENT -> "Event says \"${r.arg}\""
                KitRule.Kind.LONG -> "Out ${r.arg.ifEmpty { "4" }} hours or more"
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp)) {
                val where = when (r.group) { KitGroup.BAG -> ""; KitGroup.CHARGE -> " · charge"; KitGroup.WEAR -> " · wear" }
                Text(head + if (night) where else "", fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text(r.items.joinToString(", ") { it.name }, color = Muted, fontSize = 13.sp, textAlign = TextAlign.End)
            }
        }
    }
}

@Composable
internal fun Big(label: String, bg: Color, onClick: () -> Unit) =
    Text(label, color = OnPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 1,
        modifier = Modifier.fillMaxWidth().clip(CircleShape).background(bg).clickable(onClick = onClick).padding(vertical = 15.dp))
