package uk.tomlowndes.capture.desktop

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.tomlowndes.capture.CalEvent
import uk.tomlowndes.capture.Jarvis
import uk.tomlowndes.capture.Slip
import uk.tomlowndes.capture.SlipOptions
import uk.tomlowndes.capture.Summary
import uk.tomlowndes.capture.Job
import uk.tomlowndes.capture.Jobs
import uk.tomlowndes.capture.NoteIndex
import uk.tomlowndes.capture.PrintDocs
import uk.tomlowndes.capture.ShareAs
import uk.tomlowndes.capture.TickedTask
import uk.tomlowndes.capture.TaskItem
import uk.tomlowndes.capture.Weather
import uk.tomlowndes.capture.WeatherSummary
import uk.tomlowndes.capture.WeekOptions
import uk.tomlowndes.capture.WeekSlip
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.image.BufferedImage
import java.time.LocalDate
import java.time.LocalDateTime

private const val SLIP_OWNER = "Tom"

/** What Print summary sums up (windows-printing-v1): a vault note, or the open Jarvis chat. */
sealed class PrintSource {
    data class Note(val title: String, val body: String) : PrintSource()
    data class Chat(val said: String, val reply: String) : PrintSource()
}

/** The Daily Slip switches, kept in [DesktopPrefs.slipParts] so the next slip uses the same choices. */
object SlipSettings {
    fun load(p: DesktopPrefs): SlipOptions {
        val on = p.slipParts?.split(",")?.toSet() ?: return SlipOptions()
        return SlipOptions(
            weather = "weather" in on, calendar = "calendar" in on, month = "month" in on, todo = "todo" in on,
            tomorrow = "tomorrow" in on, blank = "blank" in on, sudoku = "sudoku" in on,
            tomorrowEvents = "tomorrowEvents" in on, brief = "brief" in on,
            // Saved as "off" so slips set up before it existed get it switched on.
            dontForget = "noDontForget" !in on,
        )
    }

    fun save(p: DesktopPrefs, o: SlipOptions) {
        p.slipParts = listOfNotNull(
            "weather".takeIf { o.weather }, "calendar".takeIf { o.calendar }, "month".takeIf { o.month },
            "todo".takeIf { o.todo }, "tomorrow".takeIf { o.tomorrow }, "blank".takeIf { o.blank },
            "sudoku".takeIf { o.sudoku }, "tomorrowEvents".takeIf { o.tomorrowEvents }, "brief".takeIf { o.brief },
            "noDontForget".takeIf { !o.dontForget },
        ).joinToString(",")
    }
}

/** The Week slip switches (slip-week-v2), kept as the ones switched off so new ones start on. */
object WeekSettings {
    fun load(p: DesktopPrefs): WeekOptions {
        val off = p.weekSlipOff?.split(",")?.toSet().orEmpty()
        return WeekOptions("events" !in off, "todo" !in off, "bills" !in off, "writeLine" !in off, "skipPast" !in off)
    }

    fun save(p: DesktopPrefs, o: WeekOptions) {
        p.weekSlipOff = listOfNotNull(
            "events".takeIf { !o.events }, "todo".takeIf { !o.todo }, "bills".takeIf { !o.bills },
            "writeLine".takeIf { !o.writeLine }, "skipPast".takeIf { !o.skipPast },
        ).joinToString(",")
    }
}

/** Puts the slip on the clipboard as a picture, for when there's no printer to hand. */
fun copyImage(img: BufferedImage) {
    val t = object : Transferable {
        override fun getTransferDataFlavors() = arrayOf(DataFlavor.imageFlavor)
        override fun isDataFlavorSupported(f: DataFlavor) = f == DataFlavor.imageFlavor
        override fun getTransferData(f: DataFlavor): Any = if (f == DataFlavor.imageFlavor) img else throw UnsupportedFlavorException(f)
    }
    Toolkit.getDefaultToolkit().systemClipboard.setContents(t, null)
}

/** Sends [img] to the saved printer. Returns false when there's no printer saved yet. */
private suspend fun printImage(state: AppState, img: BufferedImage, progress: (Float?) -> Unit): Boolean {
    val port = state.prefs.printerAddress ?: return false
    progress(0f)
    val ok = state.io("Couldn't print. Is the printer on and paired?") {
        DesktopPrinter.send(port, DesktopSlip.job(img)) { progress(it) }
    }
    progress(null)
    if (ok != null) state.say("Sent to ${state.prefs.printerName}")
    return true
}

@Composable
private fun PrintFrame(width: Dp, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    // usePlatformDefaultWidth = false: the default caps the dialog at a narrow width, which squeezed the
    // Daily Slip's left column to a sliver (one word per line, vertical "Copy as image", clipped day tiles).
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        CaptureTheme {
            val shape = RoundedCornerShape(18.dp)
            Box(Modifier.width(width).clip(shape).background(Popup).border(1.dp, Outline, shape).padding(22.dp)) { content() }
        }
    }
}

/** White paper with the slip on it, the way it comes out of the printer. */
@Composable
private fun Paper(img: BufferedImage?, message: String?, modifier: Modifier = Modifier) {
    val bmp = remember(img) { img?.toComposeImageBitmap() }
    Box(
        modifier.clip(RoundedCornerShape(6.dp)).background(Color.White).verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        when {
            message != null -> Text(message, fontSize = 13.sp, color = Color.DarkGray, modifier = Modifier.padding(20.dp))
            bmp != null -> Image(bmp, "Preview of the slip", Modifier.width(300.dp), contentScale = ContentScale.FillWidth, filterQuality = FilterQuality.Medium)
            else -> Text("Drawing the slip…", fontSize = 13.sp, color = Color.DarkGray, modifier = Modifier.padding(20.dp))
        }
    }
}

/** 🖨 M02 Pro ● ready · COM5   Change */
@Composable
private fun PrinterLine(state: AppState, progress: Float?, onChange: () -> Unit) {
    val port = state.prefs.printerAddress
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Surface2).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("🖨", fontSize = 16.sp)
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(if (port == null) "No printer yet" else state.prefs.printerName, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = TextMain)
            Text(
                when {
                    progress != null -> "Printing… ${(progress * 100).toInt()}%"
                    port == null -> "Pair it in Windows Bluetooth, then choose it here"
                    else -> "Bluetooth · $port"
                },
                fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, color = if (port == null) Warn else Muted
            )
        }
        TextAction(if (port == null) "Choose" else "Change", onClick = onChange)
    }
}

/**
 * Choosing the printer (windows-printing-v1, frame 3). Windows makes a COM port for a paired Bluetooth printer
 * ("Standard Serial over Bluetooth link"); those are listed first.
 */
@Composable
fun PrinterChooser(state: AppState, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var ports by remember { mutableStateOf<List<DesktopPrinter.Port>?>(null) }
    var looks by remember { mutableIntStateOf(0) }
    LaunchedEffect(looks) { ports = null; ports = withContext(Dispatchers.IO) { DesktopPrinter.ports() } }
    Column {
        Text("Choose your printer", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextMain)
        Sub("Switch the printer on and pair it in Windows Bluetooth settings first. It shows up here as a COM port, like “Standard Serial over Bluetooth link (COM5)”.",
            Modifier.padding(top = 4.dp, bottom = 12.dp))
        val list = ports
        when {
            list == null -> Sub("Looking…", Modifier.padding(vertical = 10.dp))
            list.isEmpty() -> Sub("Nothing yet. Pair the printer, then Look again.", Modifier.padding(vertical = 10.dp))
            else -> list.forEach { pt ->
                val bt = pt.label.contains("Bluetooth", ignoreCase = true)
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 6.dp).clip(RoundedCornerShape(12.dp)).background(Surface2)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(if (bt) "🖨" else "🔌", fontSize = 16.sp)
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text(pt.name, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = TextMain)
                        Text(pt.label, fontSize = 11.5.sp, color = Muted, maxLines = 1)
                    }
                    Chip("Use", selected = state.prefs.printerAddress == pt.name, small = true) {
                        state.prefs.printerAddress = pt.name
                        state.prefs.printerName = "M02 Pro"
                        onDone()
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextAction("Open Bluetooth settings", Muted) { state.browse("ms-settings:bluetooth") }
            Spacer(Modifier.weight(1f))
            TextAction("Look again") { scope.launch { looks++ } }
            TextAction("Cancel", Muted, onClick = onDone)
        }
    }
}

private data class SlipSources(
    val tasks: List<TaskItem>, val events: List<CalEvent>, val weather: WeatherSummary?, val jobs: List<Job> = emptyList(),
    val done: List<TickedTask> = emptyList(), val lists: List<String> = emptyList(),
)

/** Which printout the window is making (printables-v1). */
private enum class SlipMode(val label: String) { DAILY("Daily"), WEEK("Week"), BILLS("Bills"), LIST("List") }

/** Text on the Windows clipboard, or "" when there's none. */
private fun clipboardText(): String = runCatching {
    Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String
}.getOrNull().orEmpty()

/** Today, Tomorrow, then the next five days as tiles, and 📅 for a later date (slip-any-day-v1). */
@Composable
private fun DayStrip(day: LocalDate, today: LocalDate, onPick: (LocalDate) -> Unit, onMore: () -> Unit) {
    val days = (0 until Slip.STRIP_DAYS).map { today.plusDays(it.toLong()) }
    val beyond = day !in days
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        days.forEach { d -> DayTile(Slip.stripLabel(d, today), d.dayOfMonth.toString(), d == day) { onPick(d) } }
        DayTile(if (beyond) day.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.UK) else "📅",
            if (beyond) day.dayOfMonth.toString() else "", beyond, onMore)
    }
}

@Composable
private fun DayTile(top: String, num: String, on: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier.clip(shape).background(if (on) PrimaryTint else Surface1).border(1.dp, if (on) Primary else Outline, shape)
            .clickable(onClick = onClick).padding(horizontal = 9.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(top, fontSize = 11.sp, color = if (on) Primary else Muted)
        if (num.isNotEmpty()) Text(num, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (on) Primary else TextMain)
    }
}

/** Daily Slip (windows-printing-v1, frame 1): switches on the left, the paper on the right, Print. */
@Composable
fun DailySlipDialog(state: AppState, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val p = state.prefs
    var opts by remember { mutableStateOf(SlipSettings.load(p)) }
    var sources by remember { mutableStateOf<SlipSources?>(null) }
    var brief by remember { mutableStateOf<String?>(null) }
    var briefFor by remember { mutableStateOf<LocalDate?>(null) }
    var briefNote by remember { mutableStateOf<String?>(null) }
    // The day the slip is for (slip-any-day-v1). Opens on today every time.
    val today = remember { LocalDate.now() }
    var day by remember { mutableStateOf(today) }
    var pickingDay by remember { mutableStateOf(false) }
    var dayWeather by remember { mutableStateOf<WeatherSummary?>(null) }
    var dayWeatherFor by remember { mutableStateOf<LocalDate?>(null) }
    var img by remember { mutableStateOf<BufferedImage?>(null) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var choosing by remember { mutableStateOf(false) }
    var printerTick by remember { mutableIntStateOf(0) }
    // Daily | Week (slip-week-v2). The Week slip is its own printout.
    var mode by remember { mutableStateOf(if (state.slipClipboard) SlipMode.LIST else SlipMode.DAILY) }
    val weekMode = mode == SlipMode.WEEK
    var large by remember { mutableStateOf(p.slipLarge.also { DesktopSlip.large = it }) }
    var billsNext by remember { mutableStateOf(false) }
    var showPaid by remember { mutableStateOf(true) }
    var billsImg by remember { mutableStateOf<BufferedImage?>(null) }
    // List: a list note, typed text, or the clipboard (tray › Print clipboard).
    val clip = remember { if (state.slipClipboard) clipboardText() else "" }
    LaunchedEffect(Unit) { state.slipClipboard = false }
    var listPath by remember { mutableStateOf<String?>(null) }
    var useClip by remember { mutableStateOf(clip.isNotBlank()) }
    var clipText by remember { mutableStateOf(clip) }
    var clipAs by remember { mutableStateOf(ShareAs.TEXT) }
    var typed by remember { mutableStateOf("") }
    var byHeading by remember { mutableStateOf(true) }
    var skipTicked by remember { mutableStateOf(true) }
    var listImg by remember { mutableStateOf<BufferedImage?>(null) }
    var nextWeek by remember { mutableStateOf(WeekSlip.startsOnNext(today)) }
    var wopts by remember { mutableStateOf(WeekSettings.load(p)) }
    var weekImg by remember { mutableStateOf<BufferedImage?>(null) }

    LaunchedEffect(state.vault) {
        val v = state.vault ?: return@LaunchedEffect
        sources = withContext(Dispatchers.IO) {
            val lists = runCatching { state.writer()!!.listTaskLists() }.getOrNull()
            SlipSources(
                lists?.open.orEmpty(),
                runCatching { DesktopCalendar.load(v).second }.getOrDefault(emptyList()),
                runCatching { DesktopWeather.today(p) }.getOrNull(),
                runCatching { Jobs.list(v) }.getOrDefault(emptyList()),
                lists?.done.orEmpty(),
                runCatching { NoteIndex.paths(v).filter { PrintDocs.looksLikeList(it.path) }.sortedByDescending { it.modified }.take(6).map { it.path } }.getOrDefault(emptyList()),
            )
        }
    }
    LaunchedEffect(sources, day) {
        val src = sources ?: return@LaunchedEffect
        dayWeather = if (day == today) src.weather else withContext(Dispatchers.IO) { runCatching { DesktopWeather.forDay(p, day) }.getOrNull() }
        dayWeatherFor = day
    }
    // In brief: asked once per day picked, only while the switch is on.
    LaunchedEffect(sources, opts.brief, day, dayWeatherFor) {
        val src = sources ?: return@LaunchedEffect
        if (dayWeatherFor != day) return@LaunchedEffect
        if (!opts.brief || (brief != null && briefFor == day)) return@LaunchedEffect
        if (!DesktopJarvis.ready(p)) { briefNote = "Set up Jarvis in Settings to use this"; return@LaunchedEffect }
        brief = null
        briefNote = "Asking Jarvis…"
        val forDay = day
        val r = withContext(Dispatchers.IO) {
            runCatching {
                if (Jarvis.isDemo(p.jarvisAddress)) Summary.demoBrief(forDay)
                else Summary.brief(Jarvis.sendText(p.jarvisAddress, p.jarvisToken!!,
                    Summary.dayPrompt(Slip.build(forDay, src.tasks, src.events, dayWeather, SlipOptions(), today)), "obsidian", speak = false).reply)
            }
        }
        if (forDay != day) return@LaunchedEffect
        brief = r.getOrNull(); briefFor = forDay
        briefNote = if (brief != null) null else "Couldn't reach Jarvis, so it prints without it"
    }
    LaunchedEffect(sources, opts, brief, day, dayWeatherFor, large) {
        val src = sources ?: return@LaunchedEffect
        if (dayWeatherFor != day) return@LaunchedEffect
        img = withContext(Dispatchers.Default) {
            runCatching {
                val content = Slip.build(day, src.tasks, src.events, dayWeather, opts, today, src.jobs)
                    .copy(brief = brief?.takeIf { opts.brief && briefFor == day })
                DesktopSlip.draw(content, SLIP_OWNER)
            }.getOrNull()
        }
    }

    LaunchedEffect(sources, billsNext, showPaid, large) {
        val src = sources ?: return@LaunchedEffect
        val month = today.withDayOfMonth(1).plusMonths(if (billsNext) 1 else 0)
        billsImg = withContext(Dispatchers.Default) { runCatching { DesktopSlip.drawBills(PrintDocs.bills(src.tasks, src.done, month, showPaid, today)) }.getOrNull() }
    }
    LaunchedEffect(sources, listPath, useClip, clipText, clipAs, typed, byHeading, skipTicked, large) {
        val path = listPath
        val v = state.vault
        val doc = when {
            useClip -> clipText.takeIf { it.isNotBlank() }?.let { PrintDocs.shared(PrintDocs.shareTitle(null, it), it, clipAs, "clipboard", Slip.printedAt(LocalDateTime.now()).removePrefix("Printed ")) }
            path != null && v != null -> withContext(Dispatchers.IO) {
                runCatching { PrintDocs.listFrom(path.substringAfterLast('/').removeSuffix(".md"), v.read(path).orEmpty(), byHeading, skipTicked, today) }.getOrNull()
            }
            typed.isNotBlank() -> PrintDocs.typed(typed, today)
            else -> null
        }
        listImg = doc?.let { d -> withContext(Dispatchers.Default) { runCatching { DesktopSlip.drawList(d) }.getOrNull() } }
    }
    LaunchedEffect(sources, wopts, nextWeek, large) {
        val src = sources ?: return@LaunchedEffect
        weekImg = withContext(Dispatchers.Default) {
            runCatching { DesktopSlip.drawWeek(WeekSlip.build(today, nextWeek, src.tasks, src.events, wopts)) }.getOrNull()
        }
    }

    fun set(o: SlipOptions) { opts = o; SlipSettings.save(p, o) }
    fun setWeek(o: WeekOptions) { wopts = o; WeekSettings.save(p, o) }
    val shown = when (mode) { SlipMode.DAILY -> img; SlipMode.WEEK -> weekImg; SlipMode.BILLS -> billsImg; SlipMode.LIST -> listImg }
    fun print() {
        val i = shown ?: return
        if (progress != null) return
        scope.launch { if (!printImage(state, i) { progress = it }) choosing = true }
    }
    val printsAtOpen = remember { state.slipPrint }
    LaunchedEffect(state.slipPrint) { if (state.slipPrint > printsAtOpen) print() }

    if (pickingDay) PickDateDialog(day, onPick = { d -> if (d != null && !d.isBefore(today)) day = d; pickingDay = false }, onDismiss = { pickingDay = false })

    PrintFrame(if (choosing) 520.dp else 920.dp, onClose) {
        if (choosing) { PrinterChooser(state) { choosing = false; printerTick++ }; return@PrintFrame }
        Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            // The switches keep their width; the preview gives way first if the window is small.
            Column(Modifier.width(440.dp)) {
                Text(when (mode) { SlipMode.DAILY -> "Daily Slip"; SlipMode.WEEK -> "Week slip"; SlipMode.BILLS -> "Bills slip"; SlipMode.LIST -> "Print a list" }, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextMain)
                val isToday = day == today
                val dayName = day.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.UK)
                Sub(when {
                    weekMode -> "The week ahead, one day under another."
                    mode == SlipMode.BILLS -> "The month's bills: left to pay, and paid."
                    mode == SlipMode.LIST -> "From a vault note, the clipboard, or type one now."
                    isToday -> "Today's weather, calendar and to-dos on your pocket printer."
                    else -> "$dayName's weather, calendar and to-dos."
                }, Modifier.padding(top = 2.dp, bottom = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 10.dp)) {
                    SlipMode.values().forEach { m -> DayTile(m.label, "", mode == m) { mode = m } }
                }
                val monthName = { plus: Long -> today.withDayOfMonth(1).plusMonths(plus).format(java.time.format.DateTimeFormatter.ofPattern("MMMM", java.util.Locale.UK)) }
                if (mode == SlipMode.BILLS) {
                    Text("MONTH", fontSize = 11.sp, letterSpacing = 1.4.sp, color = Faint, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(bottom = 6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        DayTile("This month", monthName(0), !billsNext) { billsNext = false }
                        DayTile("Next month", monthName(1), billsNext) { billsNext = true }
                    }
                    Spacer(Modifier.height(8.dp))
                    SlipSwitch("Show paid bills", "Ticked, with a strike", showPaid) { showPaid = it }
                } else if (mode == SlipMode.LIST) {
                    Text("FROM", fontSize = 11.sp, letterSpacing = 1.4.sp, color = Faint, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(bottom = 6.dp))
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        sources?.lists.orEmpty().forEach { path ->
                            Chip(path.substringAfterLast('/').removeSuffix(".md"), !useClip && listPath == path) { useClip = false; listPath = path }
                        }
                        Chip("📋 Clipboard", useClip) { useClip = true; clipText = clipboardText() }
                        Chip("📝 Type a list", !useClip && listPath == null) { useClip = false; listPath = null }
                    }
                    Spacer(Modifier.height(8.dp))
                    when {
                        useClip -> {
                            if (clipText.isBlank()) Sub("Nothing on the clipboard. Copy some text, then pick Clipboard again.")
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(ShareAs.TEXT, ShareAs.TICKS).forEach { a -> Chip(a.label, clipAs == a, small = true) { clipAs = a } }
                            }
                        }
                        listPath == null -> Field(typed, { typed = it }, Modifier.fillMaxWidth(), "One item per line", singleLine = false, minLines = 5)
                        else -> {
                            SlipSwitch("Group by heading", "Uses the note's ## headings", byHeading) { byHeading = it }
                            SlipSwitch("Leave out ticked items", "Only what's still to get", skipTicked) { skipTicked = it }
                        }
                    }
                } else if (weekMode) {
                    Text("WEEK", fontSize = 11.sp, letterSpacing = 1.4.sp, color = Faint, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(bottom = 6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val mon = WeekSlip.monday(today)
                        DayTile("This week", WeekSlip.pickLabel(mon), !nextWeek) { nextWeek = false }
                        DayTile("Next week", WeekSlip.pickLabel(mon.plusDays(7)), nextWeek) { nextWeek = true }
                    }
                    Spacer(Modifier.height(8.dp))
                    SlipSwitch("Events", "Times and titles from your Proton calendars", wopts.events) { setWeek(wopts.copy(events = it)) }
                    SlipSwitch("To-dos", "Due that day, as tick boxes", wopts.todo) { setWeek(wopts.copy(todo = it)) }
                    SlipSwitch("Bills", "With the amount", wopts.bills) { setWeek(wopts.copy(bills = it)) }
                    SlipSwitch("Write-in line", "A blank line under each day", wopts.writeLine) { setWeek(wopts.copy(writeLine = it)) }
                    SlipSwitch("Past days", "Leave out days already gone", wopts.skipPast) { setWeek(wopts.copy(skipPast = it)) }
                } else {
                    Text("DAY", fontSize = 11.sp, letterSpacing = 1.4.sp, color = Faint, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(bottom = 6.dp))
                    DayStrip(day, today, onPick = { day = it }, onMore = { pickingDay = true })
                    Spacer(Modifier.height(8.dp))
                    val w = dayWeather?.takeIf { dayWeatherFor == day }
                    SlipSwitch("In brief", briefNote ?: if (isToday) "Jarvis sums up the day in 3 lines" else "Jarvis sums up $dayName in 3 lines", opts.brief) { set(opts.copy(brief = it)) }
                    SlipSwitch("Weather", when {
                        w != null -> if (isToday) w.headline else "Forecast for ${dayName.take(3)}: ${w.high}°, ${w.headline.replaceFirstChar { it.lowercase() }}"
                        isToday -> "Today's forecast"
                        dayWeatherFor != day -> "Getting the forecast…"
                        day.isAfter(today.plusDays(Weather.DAYS - 1L)) -> "No forecast that far out"
                        else -> "No forecast for $dayName yet"
                    }, opts.weather) { set(opts.copy(weather = it)) }
                    SlipSwitch("Calendar", if (isToday) "Today's events, and tomorrow's underneath" else "$dayName's events, and the next day's underneath", opts.calendar) { set(opts.copy(calendar = it)) }
                    SlipSwitch("Month grid", if (isToday) "This month with today marked" else "That month with $dayName marked", opts.month) { set(opts.copy(month = it)) }
                    SlipSwitch("Don't forget", "Bills due soon and job follow-ups", opts.dontForget) { set(opts.copy(dontForget = it)) }
                    SlipSwitch("To-dos", if (isToday) "Overdue and due today, then tomorrow" else "Due $dayName, plus anything still late", opts.todo) { set(opts.copy(todo = it)) }
                    SlipSwitch("Blank lines", "Three empty boxes to write in", opts.blank) { set(opts.copy(blank = it)) }
                    SlipSwitch("Sudoku", if (isToday) "A new puzzle each day" else "$dayName's puzzle", opts.sudoku) { set(opts.copy(sudoku = it)) }
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                    Text("Text size", fontSize = 13.sp, color = Muted, modifier = Modifier.padding(end = 4.dp))
                    Chip("Normal", !large, small = true) { large = false; p.slipLarge = false; DesktopSlip.large = false }
                    Chip("Large", large, small = true) { large = true; p.slipLarge = true; DesktopSlip.large = true }
                }
                key(printerTick) { PrinterLine(state, progress) { choosing = true } }
                Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton(when (mode) {
                        SlipMode.WEEK -> if (nextWeek) "Print next week" else "Print this week"
                        SlipMode.BILLS -> "Print ${monthName(if (billsNext) 1 else 0)}'s bills"
                        SlipMode.LIST -> "Print list"
                        SlipMode.DAILY -> Slip.printLabel(day, today)
                    },
                        enabled = shown != null && progress == null, hint = "Ctrl P") { print() }
                    TextAction("Copy as image", Muted) { shown?.let { copyImage(it); state.say("Slip copied as a picture") } }
                    Spacer(Modifier.weight(1f))
                    TextAction("Close", Muted, onClick = onClose)
                }
            }
            Column(Modifier.weight(1f, fill = false).widthIn(max = 360.dp)) {
                Text("PREVIEW", fontSize = 11.sp, letterSpacing = 1.4.sp, color = Primary, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(bottom = 6.dp))
                Paper(shown, if (state.vault == null) "Choose your vault first" else null, Modifier.fillMaxWidth().height(560.dp))
            }
        }
    }
}

@Composable
private fun SlipSwitch(title: String, sub: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontSize = 14.sp, color = TextMain)
            Text(sub, fontSize = 12.sp, color = Muted, maxLines = 1)
        }
        Toggle(on, onChange)
    }
}

/** Print summary (windows-printing-v1, frame 2): Jarvis sums it up, Short / Bullets / Longer, Redo, Edit, Print. */
@Composable
fun PrintSummaryDialog(state: AppState, source: PrintSource, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val p = state.prefs
    remember { DesktopSlip.large = p.slipLarge }
    val ready = DesktopJarvis.ready(p)
    val chat = source is PrintSource.Chat
    var length by remember { mutableStateOf(Summary.Length.BULLETS) }
    var ticks by remember { mutableStateOf(chat) }
    var asks by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var doc by remember { mutableStateOf<Summary.Doc?>(null) }
    var img by remember { mutableStateOf<BufferedImage?>(null) }
    var editing by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var choosing by remember { mutableStateOf(false) }
    var printerTick by remember { mutableIntStateOf(0) }
    val now = remember(asks, length) { LocalDateTime.now() }

    val title = when (source) { is PrintSource.Note -> source.title; is PrintSource.Chat -> "Jarvis chat" }
    val subtitle = (if (chat) "Jarvis chat · " else "Summary · ") + Summary.stamp(now)

    LaunchedEffect(length, asks) {
        if (!ready) return@LaunchedEffect
        loading = true; error = null
        val r = withContext(Dispatchers.IO) {
            runCatching {
                val reply = if (Jarvis.isDemo(p.jarvisAddress)) Summary.demo(length, chat) else when (source) {
                    is PrintSource.Note -> Jarvis.sendText(p.jarvisAddress, p.jarvisToken!!, Summary.notePrompt(source.title, source.body, length), "obsidian", speak = false).reply
                    is PrintSource.Chat -> Jarvis.sendText(p.jarvisAddress, p.jarvisToken!!, Summary.chatPrompt(source.said, source.reply, length), "jarvis", speak = false).reply
                }
                Summary.parse(reply, title, subtitle, length, ticks)
            }
        }
        loading = false
        r.onSuccess { if (it.text.isBlank() && it.bullets.isEmpty()) error = "Jarvis sent back nothing. Try Redo." else doc = it }
        r.onFailure { error = if (it is Jarvis.HttpError) it.message else "Can't reach Jarvis. Is Tailscale on?" }
    }
    LaunchedEffect(doc, ticks) {
        val d = doc ?: return@LaunchedEffect
        img = withContext(Dispatchers.Default) { runCatching { DesktopSlip.drawSummary(d.copy(ticks = ticks), now) }.getOrNull() }
    }

    fun print() {
        val i = img ?: return
        if (progress != null || loading || error != null) return
        scope.launch { if (!printImage(state, i) { progress = it }) choosing = true }
    }

    PrintFrame(560.dp, onClose) {
        if (choosing) { PrinterChooser(state) { choosing = false; printerTick++ }; return@PrintFrame }
        Column {
            Text("Print summary", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextMain)
            Sub(if (chat) "This Jarvis chat" else title, Modifier.padding(top = 2.dp, bottom = 12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Summary.Length.values().forEach { l -> Chip(l.label, length == l, small = true) { length = l } }
                if (chat) Chip("Tick boxes", ticks, small = true) { ticks = !ticks }
                Spacer(Modifier.weight(1f))
                Chip("↻ Redo", small = true) { if (ready && !loading) asks++ }
                Chip("✎ Edit", small = true) { doc?.let { editing = it.editable() } }
            }
            val e = editing
            if (e != null) {
                Field(e, { editing = it }, Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = 180.dp), singleLine = false, minLines = 7)
                Sub("Lines starting with - print as points.", Modifier.padding(top = 4.dp))
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                    TextAction("Cancel", Muted) { editing = null }
                    TextAction("Done") { doc = doc?.edited(e); editing = null }
                }
            } else {
                Paper(
                    img.takeIf { !loading && error == null },
                    when {
                        !ready -> "Set up Jarvis in Settings first (address and token), or type demo as the address to try it."
                        loading -> "Jarvis is summing it up…"
                        else -> error
                    },
                    Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = 140.dp, max = 430.dp)
                )
            }
            Spacer(Modifier.height(12.dp))
            key(printerTick) { PrinterLine(state, progress) { choosing = true } }
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton("Print", enabled = img != null && !loading && error == null && progress == null && editing == null) { print() }
                TextAction("Copy as image", Muted) { img?.let { copyImage(it); state.say("Summary copied as a picture") } }
                Spacer(Modifier.weight(1f))
                TextAction("Close", Muted, onClick = onClose)
            }
        }
    }
}

/** Settings › Printer test: a short slip that says it worked. */
suspend fun testPrint(state: AppState): Boolean {
    val img = withContext(Dispatchers.Default) {
        DesktopSlip.drawSummary(Summary.Doc("Test print", "Flight Deck · " + Summary.stamp(LocalDateTime.now()),
            "If you can read this, the printer and Windows are talking.", emptyList()))
    }
    return printImage(state, img) {}
}
