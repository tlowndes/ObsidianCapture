package uk.tomlowndes.capture

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

private const val SLIP_OWNER = "Tom"

/** The Daily Slip choices, kept on the phone. */
private object SlipPrefs {
    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences("slip", Context.MODE_PRIVATE)
    fun load(ctx: Context) = p(ctx).let {
        SlipOptions(
            it.getBoolean("weather", true), it.getBoolean("calendar", true), it.getBoolean("month", true),
            it.getBoolean("todo", true), it.getBoolean("tomorrow", true), it.getBoolean("blank", true), it.getBoolean("sudoku", true),
            it.getBoolean("tomorrowEvents", true), it.getBoolean("brief", true), it.getBoolean("dontForget", true),
        )
    }
    fun save(ctx: Context, o: SlipOptions) = p(ctx).edit()
        .putBoolean("weather", o.weather).putBoolean("calendar", o.calendar).putBoolean("month", o.month)
        .putBoolean("todo", o.todo).putBoolean("tomorrow", o.tomorrow).putBoolean("blank", o.blank).putBoolean("sudoku", o.sudoku)
        .putBoolean("tomorrowEvents", o.tomorrowEvents).putBoolean("brief", o.brief).putBoolean("dontForget", o.dontForget)
        .apply()
}

/** What the slip is built from: loaded once when the screen opens. */
private data class SlipSources(val tasks: List<TaskItem>, val events: List<CalEvent>, val weather: WeatherSummary?, val jobs: List<Job> = emptyList())

/**
 * Daily Slip: pick what goes on today's printout, see it, and print it on the pocket thermal printer
 * (Phomemo M02 Pro, over Bluetooth). Reads the same tasks, Proton calendars and forecast as the Deck.
 */
@Composable
fun PrintScreen(vault: Uri?, snack: SnackbarHostState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var opts by remember { mutableStateOf(SlipPrefs.load(ctx)) }
    var sources by remember { mutableStateOf<SlipSources?>(null) }
    var slip by remember { mutableStateOf<Bitmap?>(null) }
    // The day the slip is for (slip-any-day-v1). Opens on today every time.
    val today = remember { LocalDate.now() }
    var day by remember { mutableStateOf(today) }
    var pickingDay by remember { mutableStateOf(false) }
    /** The forecast for [day]: today's comes with [sources]; later days are looked up when picked. */
    var dayWeather by remember { mutableStateOf<WeatherSummary?>(null) }
    var dayWeatherFor by remember { mutableStateOf<LocalDate?>(null) }
    var printer by remember { mutableStateOf(SlipPrinter.saved(ctx)) }
    val jarvisAddress = remember { AppPrefs(ctx).jarvisAddress }
    val jarvisToken = remember { SecretStore(ctx).jarvisToken.orEmpty() }
    val jarvisDemo = Jarvis.isDemo(jarvisAddress)
    val jarvisReady = jarvisDemo || (jarvisAddress.isNotBlank() && jarvisToken.isNotBlank())
    /** Jarvis's In brief lines; null until asked, or when it couldn't be reached. */
    var brief by remember { mutableStateOf<String?>(null) }
    var briefFor by remember { mutableStateOf<LocalDate?>(null) }
    var briefNote by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var allowed by remember { mutableStateOf(SlipPrinter.allowed(ctx)) }
    var afterAllow by remember { mutableStateOf<(() -> Unit)?>(null) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        allowed = r.values.all { it }
        if (allowed) afterAllow?.invoke() else status = "Flight Deck needs Bluetooth permission to reach the printer."
        afterAllow = null
    }
    fun withBluetooth(then: () -> Unit) {
        if (allowed) then() else { afterAllow = then; ask.launch(SlipPrinter.permissions) }
    }

    LaunchedEffect(vault) {
        sources = withContext(Dispatchers.IO) {
            val tasks = vault?.let { runCatching { VaultWriter(ctx, it).listTasks() }.getOrNull() }.orEmpty()
            val on = vault?.let { runCatching { CalendarConfig.load(appVault(ctx, it)) }.getOrNull() }?.filter { it.enabled }?.map { it.id }?.toSet()
            val events = CalendarSync.cached(ctx).filter { on == null || it.id in on }.flatMap { it.events }
            val weather = runCatching { WeatherFetch.today(ctx) }.getOrNull()
            val jobs = vault?.let { runCatching { Jobs.list(appVault(ctx, it)) }.getOrNull() }.orEmpty()
            SlipSources(tasks, events, weather, jobs)
        }
    }
    LaunchedEffect(sources, day) {
        val src = sources ?: return@LaunchedEffect
        dayWeather = if (day == today) src.weather else withContext(Dispatchers.IO) { runCatching { WeatherFetch.forDay(ctx, day) }.getOrNull() }
        dayWeatherFor = day
    }
    // In brief: asked once per day picked, only while the switch is on.
    LaunchedEffect(sources, opts.brief, day, dayWeatherFor) {
        val src = sources ?: return@LaunchedEffect
        if (dayWeatherFor != day) return@LaunchedEffect
        if (!opts.brief || (brief != null && briefFor == day)) return@LaunchedEffect
        if (!jarvisReady) { briefNote = "Set up Jarvis in Settings to use this"; return@LaunchedEffect }
        brief = null
        briefNote = "Asking Jarvis…"
        val forDay = day
        val r = withContext(Dispatchers.IO) {
            runCatching {
                if (jarvisDemo) Summary.demoBrief(forDay)
                else Summary.brief(Jarvis.sendText(jarvisAddress, jarvisToken,
                    Summary.dayPrompt(Slip.build(forDay, src.tasks, src.events, dayWeather, SlipOptions(), today)), "obsidian").reply)
            }
        }
        if (forDay != day) return@LaunchedEffect
        brief = r.getOrNull(); briefFor = forDay
        briefNote = if (brief != null) null else "Couldn't reach Jarvis, so the slip prints without it"
    }
    LaunchedEffect(sources, opts, brief, day, dayWeatherFor) {
        val src = sources ?: return@LaunchedEffect
        if (dayWeatherFor != day) return@LaunchedEffect
        slip = withContext(Dispatchers.Default) {
            runCatching {
                val content = Slip.build(day, src.tasks, src.events, dayWeather, opts, today, src.jobs)
                    .copy(brief = brief?.takeIf { opts.brief && briefFor == day })
                SlipRender.draw(content, SLIP_OWNER)
            }.getOrNull()
        }
    }
    fun set(o: SlipOptions) { opts = o; SlipPrefs.save(ctx, o) }

    fun printSlip() {
        val pr = printer ?: run { picking = true; return }
        val bmp = slip ?: return
        if (!SlipPrinter.bluetoothOn(ctx)) { status = "Bluetooth is off. Switch it on and try again."; return }
        busy = true; status = "Connecting to ${pr.name}…"
        scope.launch {
            val r = runCatching {
                val job = withContext(Dispatchers.Default) { SlipRender.job(bmp) }
                withContext(Dispatchers.IO) { SlipPrinter.print(ctx, pr, job) { pc -> status = "Printing on ${pr.name} · $pc%" } }
            }
            busy = false
            status = r.fold({ "Printed on ${pr.name}." }, { it.message ?: "Printing failed" })
            if (r.isFailure) snack.showSnackbar(status!!)
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        Text("‹ Deck", color = Muted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clickable(onClick = onBack).padding(top = 10.dp))
        Text("Daily Slip", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
        val dayName = day.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.UK)
        Text(if (day == today) "Today's weather, calendar and to-dos on your pocket printer." else "$dayName's weather, calendar and to-dos.",
            fontSize = 13.sp, color = Muted, modifier = Modifier.padding(bottom = 10.dp))

        GroupLabel("Day")
        DayStrip(day, today, onPick = { day = it }, onMore = { pickingDay = true })
        Spacer(Modifier.height(4.dp))

        GroupLabel("On the slip")
        SettingRow("In brief", briefNote ?: if (day == today) "Jarvis sums up the day in 3 lines" else "Jarvis sums up $dayName in 3 lines") { AppSwitch(opts.brief) { set(opts.copy(brief = it)) } }
        SettingRow("Weather", when {
            day != today && dayWeatherFor == day && dayWeather == null ->
                if (day.isAfter(today.plusDays(Weather.DAYS - 1L))) "No forecast that far out" else "No forecast for $dayName yet"
            day != today -> dayWeather?.let { "Forecast for ${dayName.take(3)}: ${it.high}°, ${it.headline.replaceFirstChar { c -> c.lowercase() }}" } ?: "Getting the forecast…"
            else -> sources?.weather?.let { if (it.tomorrow) "Tomorrow's forecast (it's after sunset)" else it.headline }
                ?: "No forecast yet: allow location on the Deck"
        }) { AppSwitch(opts.weather) { set(opts.copy(weather = it)) } }
        SettingRow("Calendar", "Your Proton events") { AppSwitch(opts.calendar) { set(opts.copy(calendar = it)) } }
        if (opts.calendar) {
            SettingRow("Tomorrow's events") { AppSwitch(opts.tomorrowEvents) { set(opts.copy(tomorrowEvents = it)) } }
            SettingRow("Month grid", "This month with today marked") { AppSwitch(opts.month) { set(opts.copy(month = it)) } }
        }
        SettingRow("Don't forget", "Bills due soon and job follow-ups") { AppSwitch(opts.dontForget) { set(opts.copy(dontForget = it)) } }
        SettingRow("To-dos", if (day == today) "Overdue and due today" else "Due $dayName, plus anything still late") { AppSwitch(opts.todo) { set(opts.copy(todo = it)) } }
        if (opts.todo) {
            SettingRow("Tomorrow's to-dos") { AppSwitch(opts.tomorrow) { set(opts.copy(tomorrow = it)) } }
            SettingRow("Blank lines", "Three empty boxes to write in") { AppSwitch(opts.blank) { set(opts.copy(blank = it)) } }
        }
        SettingRow("Sudoku", if (day == today) "A new puzzle each day" else "$dayName's puzzle") { AppSwitch(opts.sudoku) { set(opts.copy(sudoku = it)) } }

        Spacer(Modifier.height(10.dp))
        GroupLabel("Printer")
        SettingRow(printer?.name ?: "No printer yet", printer?.address ?: "Switch the printer on, then choose it") {
            TextButton(onClick = { withBluetooth { picking = true } }) { Text(if (printer == null) "Choose" else "Change", color = Primary) }
        }

        Button(
            onClick = { withBluetooth { printSlip() } }, enabled = !busy && slip != null,
            colors = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = OnPrimary),
            shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(52.dp)
        ) {
            if (busy) CircularProgressIndicator(Modifier.width(20.dp).height(20.dp), color = OnPrimary, strokeWidth = 2.dp)
            else Text(if (printer == null) "Choose a printer" else Slip.printLabel(day, today), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        }
        status?.let { Text(it, fontSize = 13.sp, color = Muted, modifier = Modifier.padding(top = 8.dp)) }

        Spacer(Modifier.height(18.dp))
        GroupLabel("Preview")
        Box(
            Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 28.dp).clip(RoundedCornerShape(6.dp)).background(Color.White)
                .border(1.dp, Outline, RoundedCornerShape(6.dp)).padding(vertical = 10.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            val b = slip
            if (b == null) Text("Getting the day's details…", color = Color.DarkGray, fontSize = 13.sp, modifier = Modifier.padding(24.dp))
            else Image(b.asImageBitmap(), "Preview of the slip", Modifier.fillMaxWidth(0.86f),
                contentScale = ContentScale.FillWidth, filterQuality = FilterQuality.Medium)
        }
    }

    if (pickingDay) PickDateDialog(day, onPick = { d -> if (d != null && !d.isBefore(today)) day = d; pickingDay = false }, onDismiss = { pickingDay = false })

    if (picking) PrinterPicker(
        onPick = { SlipPrinter.save(ctx, it); printer = it; picking = false; status = "${it.name} saved. Tap Print." },
        onDismiss = { picking = false }
    )
}

/** Today, Tomorrow, then the next five days as tiles, and 📅 for a later date (slip-any-day-v1). */
@Composable
private fun DayStrip(day: LocalDate, today: LocalDate, onPick: (LocalDate) -> Unit, onMore: () -> Unit) {
    val days = (0 until Slip.STRIP_DAYS).map { today.plusDays(it.toLong()) }
    val beyond = day !in days
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        days.forEach { d ->
            DayTile(Slip.stripLabel(d, today), d.dayOfMonth.toString(), d == day) { onPick(d) }
        }
        DayTile(if (beyond) day.format(java.time.format.DateTimeFormatter.ofPattern("EEE", java.util.Locale.UK)) else "📅",
            if (beyond) day.dayOfMonth.toString() else "", beyond, onMore)
    }
}

@Composable
private fun DayTile(top: String, num: String, on: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.clip(shape).background(if (on) PrimaryTint else Surface1).border(1.dp, if (on) Primary else Outline, shape)
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(top, fontSize = 12.sp, color = if (on) Primary else Muted)
        if (num.isNotEmpty()) Text(num, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (on) Primary else TextMain)
    }
}

/** Scans for nearby Bluetooth devices, printers first, and lets you tap one. */
@Composable
internal fun PrinterPicker(onPick: (SlipPrinterInfo) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val found = remember { mutableStateListOf<SlipPrinterInfo>() }
    var scanning by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var round by remember { mutableStateOf(0) }
    LaunchedEffect(round) {
        scanning = true; error = null
        runCatching {
            withContext(Dispatchers.IO) {
                SlipPrinter.scan(ctx) { d -> scopeSafe { if (found.none { it.address == d.address }) found.add(d) } }
            }
        }.onFailure { error = it.message }
        scanning = false
    }
    val sorted = found.sortedByDescending { SlipPrinter.looksLikePrinter(it.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose your printer") },
        text = {
            Column {
                Text("Switch the printer on. It shows up here as something like M02 Pro.", fontSize = 13.sp, color = Muted)
                Spacer(Modifier.height(8.dp))
                error?.let { Text(it, color = Warn, fontSize = 13.sp) }
                sorted.forEach { d ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onPick(d) }.padding(vertical = 10.dp, horizontal = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(d.name, fontWeight = if (SlipPrinter.looksLikePrinter(d.name)) FontWeight.SemiBold else FontWeight.Normal)
                        Text(d.address.takeLast(5), fontSize = 12.sp, color = Muted, fontFamily = FontFamily.Monospace)
                    }
                }
                if (scanning) Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.width(16.dp).height(16.dp), strokeWidth = 2.dp, color = Primary)
                    Text("  Looking…", fontSize = 13.sp, color = Muted)
                } else if (found.isEmpty() && error == null) Text("Nothing found.", fontSize = 13.sp, color = Muted)
            }
        },
        confirmButton = { if (!scanning) TextButton(onClick = { found.clear(); round++ }) { Text("Look again", color = Primary) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Muted) } }
    )
}

/** Scan results arrive on a Bluetooth thread; Compose state lists want the main thread. */
private fun scopeSafe(f: () -> Unit) = android.os.Handler(android.os.Looper.getMainLooper()).post(f)
