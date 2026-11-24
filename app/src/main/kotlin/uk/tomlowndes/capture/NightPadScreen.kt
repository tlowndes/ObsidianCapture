package uk.tomlowndes.capture

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val HM = DateTimeFormatter.ofPattern("HH:mm")
private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)
private val NightLine = Color(0xFF3A3570)
private val NightInk = Color(0xFF141230)
private val ReadyRule = Color(0xFFB7BFD2)

/** Tomorrow's trip: its first event, the drive from here, and the forecast. Call off the main thread. */
object NightLoader {
    fun load(ctx: Context, v: Vault, now: LocalDateTime = LocalDateTime.now()): Trip {
        val events = TripLoader.events(ctx, v)
        val e = NightPad.firstTomorrow(events, now)
        // The travel line looks at one day from a start time, so ask it about tomorrow from midnight.
        val travel = e?.let { runCatching { TravelFetch.line(ctx, events, it.start.toLocalDate().atStartOfDay()) }.getOrNull() }?.takeIf { it.event == e }
        val w = runCatching { WeatherFetch.forDay(ctx, now.toLocalDate().plusDays(1)) }.getOrNull()
        return Trip(e?.title, e?.start, e?.end, travel?.leaveBy, travel?.minutes, w?.parts?.maxOfOrNull { it.rain } ?: 0, w?.low)
    }
}

/**
 * Night Pad (hangar-phase2): the evening version of Launch Pad. Charge, Bag and Wear for tomorrow's first event,
 * one at a time. Bag ticks carry over to the morning's Launch Pad, and the ready pass offers one alarm.
 */
@Composable
fun NightPadScreen(vault: Uri?, prefs: AppPrefs, snack: SnackbarHostState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var trip by remember { mutableStateOf<Trip?>(null) }
    var kit by remember { mutableStateOf<List<Kit>?>(null) }
    var ticked by remember { mutableStateOf(prefs.nightTicks) }
    var ready by remember { mutableIntStateOf(prefs.nightReady) }
    var showKit by remember { mutableStateOf(false) }

    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(vault, reload) {
        val uri = vault ?: return@LaunchedEffect
        withContext(Dispatchers.IO) { runCatching { val v = appVault(ctx, uri); Kits.loadAll(v) to NightLoader.load(ctx, v) } }
            .onSuccess { kit = it.first; trip = it.second }.onFailure { snack.showSnackbar("Couldn't load Night Pad: ${it.message}") }
    }
    BackHandler { if (showKit) showKit = false else onBack() }

    val t = trip
    val k = kit
    val items = if (t != null && k != null) NightPad.items(k, t) else emptyList()
    fun tick(i: NightItem) {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        ticked = ticked + i.item.name; prefs.nightTicks = ticked
        if (i.group == KitGroup.BAG) prefs.nightBag = prefs.nightBag + i.item.name
    }
    val next = items.firstOrNull { it.item.name !in ticked }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (showKit) showKit = false else onBack() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = Muted) }
            Text(if (showKit) "Kits" else "Night Pad", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Violet, modifier = Modifier.weight(1f))
            if (!showKit) Text("Kits", color = Primary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(CircleShape).clickable { showKit = true }.padding(horizontal = 12.dp, vertical = 8.dp))
        }
        when {
            vault == null -> Text("Pick your vault in Settings first.", color = Muted, modifier = Modifier.padding(top = 12.dp))
            t == null || k == null -> Text("Loading tomorrow…", color = Muted, modifier = Modifier.padding(top = 12.dp))
            showKit -> KitsView(vault, snack) { reload++ }
            next == null && items.isNotEmpty() -> {
                ReadyPass(t, items)
                Spacer(Modifier.height(14.dp))
                NightPad.wakeAt(t, ready)?.let { wake ->
                    WakeCard(wake, ready, onChange = {
                        ready = listOf(30, 45, 60, 90).let { o -> o[(o.indexOf(ready) + 1).mod(o.size)] }; prefs.nightReady = ready
                    }) { setAlarm(ctx, wake, t) { scope.launch { snack.showSnackbar("No clock app found to set the alarm") } } }
                }
                val bag = items.count { it.group == KitGroup.BAG }
                if (bag > 0) Text("Launch Pad will start with ${if (bag == 1) "this 1" else "these $bag"} already packed.", color = Muted, fontSize = 13.sp,
                    modifier = Modifier.padding(start = 4.dp, top = 12.dp))
                StartAgain { ticked = emptySet(); prefs.nightTicks = ticked; prefs.nightBag = emptySet() }
            }
            else -> {
                NightPass(t)
                Spacer(Modifier.height(12.dp))
                listOf(KitGroup.CHARGE, KitGroup.BAG, KitGroup.WEAR).forEach { g ->
                    val rows = items.filter { it.group == g }
                    if (rows.isNotEmpty()) {
                        GroupLabel(groupName(g), color = Violet)
                        Column(Modifier.padding(top = 4.dp, bottom = 10.dp)) { rows.forEach { i -> NightRow(i, i.item.name in ticked) { tick(i) } } }
                    }
                }
                if (next != null) Big("Next: ${verb(next)}", Violet) { tick(next) }
                if (ticked.isNotEmpty()) StartAgain { ticked = emptySet(); prefs.nightTicks = ticked; prefs.nightBag = emptySet() }
                Text("Tip: Heads Up can buzz you at 21:00 to open this. Switch it on in Heads Up's settings.",
                    color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp, bottom = 20.dp))
            }
        }
    }
}

private fun groupName(g: KitGroup) = when (g) { KitGroup.CHARGE -> "Charge"; KitGroup.BAG -> "Bag"; KitGroup.WEAR -> "Wear" }

private fun verb(i: NightItem): String {
    val n = i.item.name.lowercase(Locale.UK)
    return when (i.group) { KitGroup.CHARGE -> "$n on charge"; KitGroup.BAG -> "pack $n"; KitGroup.WEAR -> "lay out $n" }
}

/** Opens the clock app with the alarm filled in. Flight Deck doesn't set alarms itself. */
private fun setAlarm(ctx: Context, at: LocalDateTime, t: Trip, onMissing: () -> Unit) {
    val by = LaunchPad.leaveBy(t)
    val intent = Intent(AlarmClock.ACTION_SET_ALARM)
        .putExtra(AlarmClock.EXTRA_HOUR, at.hour).putExtra(AlarmClock.EXTRA_MINUTES, at.minute)
        .putExtra(AlarmClock.EXTRA_MESSAGE, listOfNotNull(t.title, by?.let { "leave by ${it.format(HM)}" }).joinToString(" · ").ifEmpty { "Flight Deck" })
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { ctx.startActivity(intent) }.onFailure { onMissing() }
}

@Composable
private fun NightPass(t: Trip) {
    val tomorrow = LocalDate.now().plusDays(1)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(VioletTint).border(1.dp, NightLine, RoundedCornerShape(18.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("TOMORROW", color = Violet, fontWeight = FontWeight.Bold, fontSize = 15.sp, letterSpacing = 0.6.sp, modifier = Modifier.weight(1f))
            Text(tomorrow.format(DAY).uppercase(Locale.UK), color = Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
        Box(Modifier.fillMaxWidth().height(2.dp).background(NightLine))
        if (t.title == null) {
            Text("Nothing planned", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text("A slow day. Just charge up.", color = Muted, fontSize = 13.sp)
        } else Row(Modifier.fillMaxWidth()) {
            Cell("First up", "${t.title} ${t.start?.format(HM).orEmpty()}".trim(), Modifier.weight(1.4f))
            Cell("Leave", LaunchPad.leaveBy(t)?.format(HM) ?: "—", Modifier.weight(0.8f))
            Cell("Weather", listOfNotNull(t.low?.let { "$it°" }, t.rainChance.takeIf { it > 0 }?.let { "$it% ☂" }).joinToString(" · ").ifEmpty { "—" }, Modifier.weight(1f))
        }
    }
}

@Composable
private fun Cell(label: String, value: String, modifier: Modifier) = Column(modifier) {
    Text(label.uppercase(Locale.UK), color = Muted, fontSize = 10.sp, letterSpacing = 0.6.sp)
    Text(value, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
}

@Composable
private fun NightRow(i: NightItem, done: Boolean, onTick: () -> Unit) =
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(enabled = !done, onClick = onTick).padding(horizontal = 4.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.padding(end = 10.dp).clip(RoundedCornerShape(5.dp)).background(if (done) Green else Color.Transparent)
            .border(1.5.dp, if (done) Green else Muted, RoundedCornerShape(5.dp)).padding(8.dp))
        Text("${i.item.emoji}  ${i.item.name}", fontSize = 15.sp, color = if (done) Muted else TextMain, modifier = Modifier.weight(1f))
        (i.item.reason ?: i.kit)?.let { Text(it, color = if (i.item.reason != null) Amber else Violet, fontSize = 11.sp) }
    }

@Composable
private fun ReadyPass(t: Trip, items: List<NightItem>) {
    Column(
        Modifier.fillMaxWidth().border(2.dp, Violet, RoundedCornerShape(20.dp)).padding(4.dp).clip(RoundedCornerShape(16.dp)).background(TextMain).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("READY FOR TOMORROW", color = NightInk, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Text(LocalDate.now().plusDays(1).format(DAY).uppercase(Locale.UK), color = NightInk, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
        Box(Modifier.fillMaxWidth().height(2.dp).background(ReadyRule))
        listOf(KitGroup.CHARGE to "CHARGING", KitGroup.BAG to "BAG", KitGroup.WEAR to "WEAR").forEach { (g, label) ->
            val names = items.filter { it.group == g }.map { it.item.name }
            if (names.isNotEmpty()) Row(Modifier.fillMaxWidth()) {
                Text(label, color = NightInk, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(0.4f))
                Text("${names.joinToString(", ")} ✓", color = NightInk, fontSize = 13.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
            }
        }
        if (t.title != null) {
            Box(Modifier.fillMaxWidth().height(2.dp).background(ReadyRule))
            Row(Modifier.fillMaxWidth()) {
                Text("GATE: ${t.title}", color = NightInk, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1, modifier = Modifier.weight(1f))
                LaunchPad.leaveBy(t)?.let { Text("BOARD ${it.format(HM)}", color = NightInk, fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
            }
        }
    }
}

@Composable
private fun WakeCard(wake: LocalDateTime, ready: Int, onChange: () -> Unit, onSet: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surface1).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("WAKE UP", fontSize = 11.sp, letterSpacing = 1.2.sp, color = Muted, fontWeight = FontWeight.SemiBold)
        Text("Alarm ${wake.format(HM)} gives you ${if (ready % 60 == 0) "${ready / 60} hour${if (ready > 60) "s" else ""}" else "$ready min"}",
            fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Change", color = TextMain, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1f).clip(CircleShape)
                .border(1.dp, Outline, CircleShape).clickable(onClick = onChange).padding(vertical = 10.dp))
            Text("Set alarm", color = NightInk, fontSize = 13.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.weight(1f)
                .clip(CircleShape).background(Violet).clickable(onClick = onSet).padding(vertical = 10.dp))
        }
    }
}

@Composable
private fun StartAgain(onClick: () -> Unit) =
    Text("Start again", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp).clickable(onClick = onClick).padding(vertical = 8.dp))
