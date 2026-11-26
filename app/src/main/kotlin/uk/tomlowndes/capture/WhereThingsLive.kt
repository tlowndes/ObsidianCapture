package uk.tomlowndes.capture

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Where things live on the phone (where-things-live-v1). The state is the Deck layout, the Hangar list, the More
 * tiles and the Tasks views switched off, all in the vault config so Windows reads the same; the phone keeps its
 * own copy of the Hangar list and More tiles for screens that read them straight away.
 */
internal object PhonePlaces {
    fun load(ctx: Context, uri: Uri, prefs: AppPrefs): PlaceState =
        Places.load(appVault(ctx, uri), Hangar.parse(prefs.hangarTools), deckTiles(prefs))

    /** Saves [s] to the vault and the phone, then starts or stops what depends on the Hangar list. */
    fun save(ctx: Context, uri: Uri, prefs: AppPrefs, s: PlaceState) {
        val at = System.currentTimeMillis()
        Places.save(appVault(ctx, uri), s, at)
        prefs.hangarTools = Hangar.format(s.hangar)
        prefs.deckTiles = Hangar.format(s.tiles)
        prefs.hangarAt = at
        hangarChanged(ctx, prefs, s.hangar)
    }

    /**
     * Once per vault: the move that comes with this version (see [Places.migrated]). Bills goes to Hangar only and
     * every element that was on stays on. Windows makes the same move if it gets there first.
     */
    fun moveOnce(ctx: Context, uri: Uri, prefs: AppPrefs) {
        val v = appVault(ctx, uri)
        if (Places.moved(v.read(DeckConfig.PATH))) return
        save(ctx, uri, prefs, Places.migrated(load(ctx, uri, prefs)))
    }

    /** True when bills belong in Tasks and on the Deck (Bills set to Both). */
    fun billsInTasks(ctx: Context, uri: Uri): Boolean =
        DeckConfig.layout(appVault(ctx, uri).read(DeckConfig.PATH)).let { HomeCard.BILLS !in it.hidden }
}

/** Settings › What's in the app: every element with one word for where it is; tap it to change (settings-cleanup-v1). */
@Composable
fun PlacesScreen(vault: Uri?, prefs: AppPrefs, snack: SnackbarHostState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var state by remember { mutableStateOf<PlaceState?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<Element?>(null) }
    var showOff by remember { mutableStateOf(false) }
    LaunchedEffect(vault, reload) {
        val uri = vault ?: return@LaunchedEffect
        state = withContext(Dispatchers.IO) { runCatching { PhonePlaces.load(ctx, uri, prefs) }.getOrNull() }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = Muted) }
            Text("What's in the app", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Text("Tap anything to switch it off or move it.", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
        val s = state
        if (vault == null) { Text("Pick your vault in Settings first.", color = Muted); return@Column }
        if (s == null) { Text("Loading…", color = Muted); return@Column }
        Element.GROUPS.forEach { g ->
            val rows = Element.values().filter { it.group == g && s.on(it) }
            if (rows.isEmpty()) return@forEach
            Box(Modifier.padding(top = 16.dp, bottom = 4.dp)) { GroupLabel(g) }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surface1)) {
                rows.forEachIndexed { i, e ->
                    if (i > 0) HorizontalDivider(color = Outline)
                    PlaceRow(e, s.place(e)) { editing = e }
                }
            }
        }
        val off = Element.values().filter { !s.on(it) }
        if (off.isNotEmpty()) {
            Box(Modifier.padding(top = 16.dp, bottom = 4.dp)) { GroupLabel("Off") }
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surface1).clickable { showOff = !showOff }.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("${off.size} switched off" + if (showOff) "" else " · " + off.take(3).joinToString(", ") { it.label } + if (off.size > 3) "…" else "",
                    fontSize = 13.sp, color = Muted, modifier = Modifier.weight(1f))
                Text(if (showOff) "Hide" else "Show ›", fontSize = 13.sp, color = Muted)
            }
            if (showOff) Column(Modifier.padding(top = 6.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surface1)) {
                off.forEachIndexed { i, e ->
                    if (i > 0) HorizontalDivider(color = Outline)
                    PlaceRow(e, Lives.OFF) { editing = e }
                }
            }
        }
        Text("Heads Up works on the phone only. Switching something off keeps its notes in the vault.",
            color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 14.dp, bottom = 28.dp))
    }
    editing?.let { e -> PlaceSheet(vault, prefs, snack, e, onDone = { editing = null; reload++ }) { editing = null } }
}

/** One element: icon, name, and one coloured word for where it is. Tap to change. */
@Composable
private fun PlaceRow(e: Element, p: Lives, onClick: () -> Unit) {
    val on = p != Lives.OFF
    val alwaysOn = e == Element.NEXT_STEP
    Row(Modifier.fillMaxWidth().clickable(enabled = !alwaysOn, onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(e.icon, fontSize = 17.sp, color = if (on) TextMain else Muted, modifier = Modifier.width(30.dp))
        Text(e.label, fontSize = 14.5.sp, color = if (on) TextMain else Muted, modifier = Modifier.weight(1f))
        val word = e.pill(p)
        val (fg, bg) = when (p) {
            Lives.HANGAR -> Primary to PrimaryTint
            Lives.BOTH, Lives.MAIN -> Green to Green.copy(alpha = 0.14f)
            Lives.OFF -> Muted to Surface2
        }
        if (alwaysOn) Text(word, fontSize = 12.5.sp, color = Muted)
        else {
            Text(word, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = fg,
                modifier = Modifier.clip(CircleShape).background(bg).padding(horizontal = 10.dp, vertical = 3.dp))
            Text("›", fontSize = 18.sp, color = Muted, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

/** Tap a row, or long-press a Hangar tile: Off, Hangar, or Hangar + main app (settings-cleanup-v1). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaceSheet(vault: Uri?, prefs: AppPrefs, snack: SnackbarHostState, e: Element, onDone: () -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<PlaceState?>(null) }
    LaunchedEffect(vault) {
        val uri = vault ?: return@LaunchedEffect
        state = withContext(Dispatchers.IO) { runCatching { PhonePlaces.load(ctx, uri, prefs) }.getOrNull() }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Surface2) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            Text("${e.icon} ${e.label}", fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 10.dp))
            val s = state ?: return@Column
            val now = s.place(e)
            e.choices.forEach { p ->
                val words = when (p) {
                    Lives.OFF -> "Off" to (if (e == Element.BILLS) "Gone from everywhere: Deck, Tasks, Hangar, Ticket Rail, Brief and slips. Your bills stay in the vault."
                        else "Hidden everywhere. Its notes stay in the vault.")
                    Lives.HANGAR -> "Hangar" to "A Hangar tile only."
                    Lives.BOTH -> "Hangar + main app" to ("Also " + e.mainSpot.replaceFirstChar { it.lowercase(Locale.UK) } + ".")
                    Lives.MAIN -> "On" to e.mainSpot
                }
                val picked = p == now || (p == Lives.BOTH && now == Lives.MAIN)
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(14.dp))
                    .background(if (picked) PrimaryTint else Color.Transparent)
                    .border(1.dp, if (picked) Primary else Outline, RoundedCornerShape(14.dp))
                    .clickable {
                        val uri = vault ?: return@clickable
                        scope.launch {
                            withContext(Dispatchers.IO) { runCatching { PhonePlaces.save(ctx, uri, prefs, s.with(e, p)) } }
                                .onSuccess { onDone() }.onFailure { snack.showSnackbar("Couldn't save: ${it.message}") }
                        }
                    }.padding(14.dp), verticalAlignment = Alignment.Top) {
                    Text(if (picked) "●" else "○", color = if (picked) Primary else Muted, fontSize = 16.sp, modifier = Modifier.width(28.dp))
                    Column(Modifier.weight(1f)) {
                        Text(words.first, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(words.second, fontSize = 12.5.sp, color = Muted)
                    }
                }
            }
            if (e.hangarOk) Text("To move a tile, hold it and drag.", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)
private val MON = DateTimeFormatter.ofPattern("MMM", Locale.UK)
private val MONTH = DateTimeFormatter.ofPattern("MMMM", Locale.UK)

/**
 * Bills in Hangar (where-things-live-v1): a month at a time with its total, bills grouped by the day they're due,
 * and a tick for paid (which writes next month's line, as before). The bills stay in ⚡ Quick Task.
 */
@Composable
fun BillsScreen(vault: Uri?, prefs: AppPrefs, snack: SnackbarHostState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val today = remember { LocalDate.now() }
    var lists by remember { mutableStateOf<TaskLists?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var month by remember { mutableStateOf<YearMonth?>(YearMonth.from(today)) }
    var adding by remember { mutableStateOf(false) }
    var paying by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(vault, reload) {
        val uri = vault ?: return@LaunchedEffect
        lists = withContext(Dispatchers.IO) { runCatching { VaultWriter(ctx, uri).listTaskLists() }.getOrNull() }
        // Start on the first month with something to pay.
        if (reload == 0) lists?.let { l -> BillPage.all(l.open).firstOrNull()?.let { r -> if (YearMonth.from(r.due).isAfter(YearMonth.from(today))) month = YearMonth.from(r.due) } }
    }
    fun pay(r: BillRow) {
        val uri = vault ?: return
        paying = paying + r.bill.task.raw
        scope.launch {
            val done = withContext(Dispatchers.IO) { runCatching { VaultWriter(ctx, uri).completeTask(r.bill.task, prefs.recurring) } }
            done.onFailure { paying = paying - r.bill.task.raw; snack.showSnackbar("Couldn't save: ${it.message}"); return@launch }
            reload++
            if (snack.showSnackbar("${r.bill.name} paid", "Undo", duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) {
                withContext(Dispatchers.IO) { runCatching { VaultWriter(ctx, uri).restoreTask(r.bill.task, done.getOrThrow()) } }
                paying = paying - r.bill.task.raw
                reload++
            }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back to Hangar", tint = Muted) }
            Text("£ Bills", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("＋ Add bill", color = Primary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(CircleShape).border(1.dp, Outline, CircleShape).clickable { adding = true }.padding(horizontal = 12.dp, vertical = 7.dp))
        }
        val l = lists
        if (vault == null) { Text("Pick your vault in Settings first.", color = Muted); return@Column }
        if (l == null) { Text("Loading…", color = Muted, modifier = Modifier.padding(top = 12.dp)); return@Column }
        val all = BillPage.all(l.open)
        val thisMonth = YearMonth.from(today)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (0..2).map { thisMonth.plusMonths(it.toLong()) }.forEach { m -> AppChip(month == m, { month = m }, { Text(m.format(MON)) }) }
            AppChip(month == null, { month = null }, { Text("All ${all.size}") })
        }
        val m = month
        if (m == null) {
            BillCard("Every bill", "${all.size} open", TaskViews.money(TaskViews.total(all.map { it.bill })), "to pay across them all")
            BillRows(all.groupBy { it.due }.toSortedMap().map { it.key to it.value }, paying, ::pay)
        } else {
            val bm = BillPage.month(l.open, l.done, m, today)
            val week = bm.rows.filter { !it.paid && !it.projected && it.due.isBefore(today.plusDays(7)) }
            val weekLine = if (m != thisMonth) null
                else if (week.isNotEmpty()) "Next 7 days ${TaskViews.money(TaskViews.total(week.map { it.bill }))}"
                else BillPage.nextLine(l.open, today)?.let { "Nothing this week · next $it" }
            BillCard(m.format(MONTH), "${bm.rows.size} bill${if (bm.rows.size == 1) "" else "s"}", TaskViews.money(bm.toPay),
                "to pay · ${TaskViews.money(bm.paid)} paid", weekLine)
            if (bm.rows.isEmpty()) Text("No bills in ${m.format(MONTH)}.", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp))
            BillRows(bm.byDay, paying, ::pay)
        }
        Text("Tick = paid, and a repeating bill writes its next line. Bills are kept in ⚡ Quick Task, as before.",
            fontSize = 11.sp, color = Muted, fontFamily = FontFamily.Monospace, lineHeight = 15.sp, modifier = Modifier.padding(top = 14.dp, bottom = 28.dp))
    }
    if (adding) AddBillSheet(Bills.suggestions(lists?.open.orEmpty(), lists?.done.orEmpty()), onAdd = { text, due ->
        adding = false
        val uri = vault
        if (uri != null) scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { VaultWriter(ctx, uri).addTask(text, due.toString()) } }
            reload++
            snack.showSnackbar(r.fold({ "Bill added" }, { "Couldn't add the bill: ${it.message}" }))
        }
    }, onDismiss = { adding = false })
}

@Composable
private fun BillCard(title: String, right: String, big: String, sub: String, line: String? = null) {
    Column(Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surface1).border(1.dp, Outline, RoundedCornerShape(16.dp)).padding(14.dp)) {
        Row {
            Text(title.uppercase(Locale.UK), fontSize = 11.sp, letterSpacing = 1.1.sp, color = Muted, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
            Text(right, fontSize = 11.sp, color = Muted, fontFamily = FontFamily.Monospace)
        }
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 4.dp)) {
            Text(big, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text(" $sub", fontSize = 13.sp, color = Muted, modifier = Modifier.padding(bottom = 5.dp))
        }
        line?.let { Text(it, fontSize = 12.5.sp, color = Muted) }
    }
}

@Composable
private fun BillRows(days: List<Pair<LocalDate, List<BillRow>>>, paying: Set<String>, onPay: (BillRow) -> Unit) {
    days.forEach { (d, rows) ->
        Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 2.dp)) {
            Text(d.format(DAY).uppercase(Locale.UK), fontSize = 11.sp, letterSpacing = 1.1.sp, color = Amber, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
            Text(TaskViews.money(TaskViews.total(rows.map { it.bill })), fontSize = 11.sp, color = Amber, fontFamily = FontFamily.Monospace)
        }
        rows.forEach { r ->
            val paid = r.paid || r.bill.task.raw in paying
            HorizontalDivider(color = Outline)
            Row(Modifier.fillMaxWidth().padding(vertical = 9.dp).semantics(mergeDescendants = true) {
                contentDescription = r.bill.name + (r.bill.amount?.let { " " + TaskViews.money(it) } ?: "") + if (paid) ", paid" else if (r.projected) ", coming" else ""
            }, verticalAlignment = Alignment.CenterVertically) {
                if (r.projected) Box(Modifier.width(20.dp)) { Text("↻", color = Muted, fontSize = 14.sp) }
                else TickBox(done = paid, onClick = { if (!paid) onPay(r) })
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(r.bill.name, fontSize = 15.sp, color = if (paid || r.projected) Muted else TextMain,
                        textDecoration = if (paid) TextDecoration.LineThrough else null)
                    Text(listOfNotNull(if (r.projected) "coming" else null, if (paid) "paid" else null,
                        BillPage.repeatWords(r.bill.task.raw)).joinToString(" · "), fontSize = 11.5.sp, color = Muted)
                }
                Text(r.bill.amount?.let { TaskViews.money(it) } ?: "", fontSize = 14.sp, fontFamily = FontFamily.Monospace, color = if (paid) Muted else TextMain)
            }
        }
    }
}
