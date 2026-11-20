package uk.tomlowndes.capture

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.util.concurrent.TimeUnit

/** A shop page shared to "Cool-off", waiting for Cool-off's add sheet. */
internal object CoolInbox {
    @Volatile var pending: CoolItem? = null
}

/**
 * Cool-off's price tracking (hangar-phase3): every 12 hours in the background, and when Cool-off opens if it's been
 * 6 hours, each cooling item with a link has its page checked. A drop buzzes once.
 */
internal object CoolPrices {
    private const val WORK = "cool_off_prices"
    private const val OPEN_EVERY_MS = 6 * 3_600_000L

    private fun p(ctx: Context) = ctx.getSharedPreferences("cool_off", Context.MODE_PRIVATE)

    fun keep(ctx: Context, on: Boolean) {
        val wm = WorkManager.getInstance(ctx)
        if (!on) { wm.cancelUniqueWork(WORK); return }
        val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<PriceWorker>(12, TimeUnit.HOURS).setConstraints(net).build())
    }

    fun due(ctx: Context) = System.currentTimeMillis() - p(ctx).getLong("checked", 0) > OPEN_EVERY_MS

    /** Checks each tracked price and saves what changed. Returns the items that came down. Blocking (network and vault). */
    fun check(ctx: Context, v: Vault, buzz: Boolean): List<CoolItem> {
        val today = LocalDate.now()
        val changed = mutableListOf<CoolItem>()
        val dropped = mutableListOf<CoolItem>()
        CoolOff.tracked(CoolOff.load(v)).forEach { c ->
            val price = PriceScrape.check(c.link ?: return@forEach) ?: return@forEach
            val n = CoolOff.track(c, price, today)
            if (n != c) { changed += n; if (CoolOff.droppedSince(c, n)) dropped += n }
        }
        if (changed.isNotEmpty()) CoolOff.saveAll(v, changed)
        p(ctx).edit().putLong("checked", System.currentTimeMillis()).apply()
        if (buzz) dropped.forEach { c ->
            val now = LocalDateTime.now()
            val (title, text) = HeadsUp.words(HeadsAlert(now, 10, HeadsTarget(HeadsTarget.Kind.PRICE, "${c.what} · ${CoolOff.priceWords(c)}", now)))
            HeadsAlarms.notify(ctx, title, text, 10, HeadsTarget.Kind.PRICE)
        }
        return dropped
    }
}

class PriceWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext
        val prefs = AppPrefs(app)
        if (Hangar.parse(prefs.hangarTools)?.contains(HangarTool.COOL_OFF) != true) return Result.success()
        val uri = prefs.vault ?: return Result.success()
        runCatching { CoolPrices.check(app, appVault(app, uri), buzz = true) }
        return Result.success()
    }
}

@Composable
private fun DayTitle(title: String, color: Color, onBack: () -> Unit, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = Muted) }
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = color, modifier = Modifier.weight(1f))
        if (action != null) Text(action, color = Primary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(CircleShape).clickable(onClick = onAction).padding(horizontal = 12.dp, vertical = 8.dp))
    }
}

private fun hostOf(link: String?) = link?.let { runCatching { Uri.parse(it).host?.removePrefix("www.") }.getOrNull() }

// ---- Cool-off ----

/**
 * Cool-off (hangar-phase3): impulse buys wait 48 hours. When the wait's up, one question and three answers: buy it,
 * wait 48 hours more, or let it go (which adds to "not spent"). Prices of items with a link are tracked while they cool.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CoolOffScreen(vault: Uri?, snack: SnackbarHostState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var items by remember { mutableStateOf<List<CoolItem>?>(null) }
    var shared by remember { mutableStateOf(CoolInbox.pending.also { CoolInbox.pending = null }) }
    var adding by remember { mutableStateOf(shared != null) }
    var deleting by remember { mutableStateOf<CoolItem?>(null) }
    var checking by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(vault, reload) {
        val uri = vault ?: return@LaunchedEffect
        withContext(Dispatchers.IO) { runCatching { CoolOff.load(appVault(ctx, uri)) } }
            .onSuccess { items = it }.onFailure { snack.showSnackbar("Couldn't load Cool-off: ${it.message}") }
    }
    // Prices: checked now if it's been a while, and every 12 hours in the background.
    LaunchedEffect(vault) {
        val uri = vault ?: return@LaunchedEffect
        CoolPrices.keep(ctx, true)
        if (!CoolPrices.due(ctx)) return@LaunchedEffect
        val v = withContext(Dispatchers.IO) { runCatching { appVault(ctx, uri) }.getOrNull() } ?: return@LaunchedEffect
        if (withContext(Dispatchers.IO) { runCatching { CoolOff.tracked(CoolOff.load(v)).isEmpty() }.getOrDefault(true) }) return@LaunchedEffect
        checking = true
        val down = withContext(Dispatchers.IO) { runCatching { CoolPrices.check(ctx, v, buzz = false) }.getOrDefault(emptyList()) }
        checking = false
        reload++
        down.firstOrNull()?.let { snack.showSnackbar("📉 ${it.what}: ${CoolOff.priceWords(it)}") }
    }
    BackHandler(onBack = onBack)

    fun save(block: (Vault) -> Unit) {
        val uri = vault ?: return
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { block(appVault(ctx, uri)) } }
                .onSuccess { reload++; HeadsAlarms.changed(ctx) }.onFailure { snack.showSnackbar("Couldn't save: ${it.message}") }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        DayTitle("Cool-off", Ice, onBack, if (vault != null) "＋ Add" else null) { adding = true }
        val all = items
        val now = LocalDateTime.now()
        val today = now.toLocalDate()
        when {
            vault == null -> Text("Pick your vault in Settings first.", color = Muted, modifier = Modifier.padding(top = 12.dp))
            all == null -> Text("Loading…", color = Muted, modifier = Modifier.padding(top = 12.dp))
            else -> Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Surface1).padding(14.dp)) {
                    Text("NOT SPENT THIS MONTH", color = Muted, fontSize = 11.sp, letterSpacing = 1.sp)
                    Text(money(CoolOff.notSpent(all, YearMonth.from(today))), fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Ice)
                }
                CoolOff.due(all, now).forEach { c ->
                    TimesUp(c,
                        onYes = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            save { v -> CoolOff.update(v, c, CoolOff.bought(c, today)) }
                            c.link?.let { l -> runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(l)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
                        },
                        onMore = { save { v -> CoolOff.update(v, c, CoolOff.wait(c, LocalDateTime.now())) } },
                        onLetGo = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            save { v -> CoolOff.update(v, c, CoolOff.letGo(c, today)) }
                            scope.launch { snack.showSnackbar("Let go" + (c.now?.let { " · +${money(it)} not spent" } ?: "")) }
                        })
                }
                val cooling = CoolOff.cooling(all).filter { !CoolOff.isDue(it, now) }
                if (cooling.isEmpty() && CoolOff.due(all, now).isEmpty()) {
                    Text("Nothing cooling.", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text("Want something? Add it here, or share the shop page and pick Cool-off. It asks again in 48 hours, and keeps an eye on the price.",
                        color = Muted, fontSize = 13.sp)
                    Big("＋ Add something", Ice) { adding = true }
                }
                if (cooling.isNotEmpty()) {
                    GroupLabel("Cooling" + if (checking) " · checking prices…" else "")
                    cooling.forEach { c -> CoolRow(c, CoolOff.leftWords(c, now), Ice, onLong = { deleting = c }) }
                }
                val decided = all.filter { it.step != CoolStep.COOLING && it.decided?.let(YearMonth::from) == YearMonth.from(today) }
                if (decided.isNotEmpty()) {
                    GroupLabel("This month")
                    decided.forEach { c ->
                        CoolRow(c, if (c.step == CoolStep.LET_GO) (c.now?.let { "${money(it)} saved" } ?: "Let go") else "Bought",
                            if (c.step == CoolStep.LET_GO) Green else Muted, onLong = { deleting = c })
                    }
                }
                Text("Hold an item to delete it. They live in ${CoolOff.NOTE}. Prices are checked about twice a day; a drop buzzes once.",
                    color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp, bottom = 20.dp))
            }
        }
    }

    deleting?.let { c ->
        AlertDialog(
            onDismissRequest = { deleting = null }, containerColor = Surface1,
            title = { Text("Delete ${c.what}?") },
            confirmButton = { TextButton(onClick = { deleting = null; save { v -> CoolOff.remove(v, c) } }) { Text("Delete", color = Coral) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel", color = Muted) } }
        )
    }
    if (adding) AddCoolDialog(shared, onDismiss = { adding = false; shared = null }) { c ->
        adding = false; shared = null
        save { v -> CoolOff.add(v, c) }
        scope.launch { snack.showSnackbar("Cooling for ${c.hours}h" + if (c.link != null) " · watching the price" else "") }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CoolRow(c: CoolItem, right: String, color: Color, onLong: () -> Unit) {
    val drop = CoolOff.drop(c)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Surface1).combinedClickable(onClick = {}, onLongClick = onLong).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(c.what, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, color = if (c.step == CoolStep.COOLING) TextMain else Muted)
            val sub = listOfNotNull(CoolOff.priceWords(c), hostOf(c.link)).joinToString(" · ")
            if (sub.isNotEmpty()) Text((if (drop != null) "📉 " else "") + sub, fontSize = 12.sp, color = if (drop != null) Green else Muted, maxLines = 1)
        }
        Text(right, color = color, fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun TimesUp(c: CoolItem, onYes: () -> Unit, onMore: () -> Unit, onLetGo: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surface1).border(1.dp, Ice.copy(alpha = 0.5f), RoundedCornerShape(16.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("${c.hours} HOURS AGO YOU WANTED", color = Muted, fontSize = 11.sp, letterSpacing = 1.sp)
        Text(listOfNotNull(c.what, c.now?.let(::money)).joinToString(" · "), fontSize = 20.sp, fontWeight = FontWeight.Bold)
        CoolOff.drop(c)?.let { Text("📉 ${CoolOff.priceWords(c)}", color = Green, fontSize = 13.sp) }
        Text("Still want it?", color = Ice, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Big(if (c.link != null) "Yes, open the page" else "Yes, I'll buy it", Ice, onYes)
        CoolChoice("Not sure, ${CoolOff.HOURS}h more", onMore)
        CoolChoice("Let it go" + (c.now?.let { " · +${money(it)} not spent" } ?: ""), onLetGo)
    }
}

@Composable
private fun CoolChoice(label: String, onClick: () -> Unit) =
    Text(label, color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 1,
        modifier = Modifier.fillMaxWidth().clip(CircleShape).border(1.dp, Outline, CircleShape).clickable(onClick = onClick).padding(vertical = 13.dp))

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddCoolDialog(shared: CoolItem?, onDismiss: () -> Unit, onAdd: (CoolItem) -> Unit) {
    var what by remember { mutableStateOf(shared?.what.orEmpty()) }
    var price by remember { mutableStateOf(shared?.price?.let { money(it).removePrefix("£") }.orEmpty()) }
    var link by remember { mutableStateOf(shared?.link.orEmpty()) }
    var hours by remember { mutableIntStateOf(CoolOff.HOURS) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Surface1,
        title = { Text(if (shared != null) "Cool off on this?" else "What do you want?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(what, { what = it.replace("·", "") }, singleLine = true, colors = fieldColors(), placeholder = { Text("e.g. Desk lamp") })
                OutlinedTextField(price, { price = it.filter { ch -> ch.isDigit() || ch == '.' } }, singleLine = true, colors = fieldColors(),
                    placeholder = { Text("Price (optional)") }, prefix = { Text("£") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(link, { link = it.trim() }, singleLine = true, colors = fieldColors(), placeholder = { Text("Link, to watch the price (optional)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CoolOff.WAITS.forEach { h -> AppChip(hours == h, { hours = h }, { Text(if (h == 168) "1 week" else "${h}h") }) }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = what.isNotBlank(), onClick = {
                onAdd(CoolItem(what.trim(), shared?.added ?: LocalDateTime.now().withSecond(0).withNano(0), price.toDoubleOrNull(),
                    link.takeIf { it.startsWith("http") }, hours))
            }) { Text("Start the wait", color = Primary) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Muted) } }
    )
}

// ---- Refill ----

private data class MedView(val med: Med, val left: Int)

/**
 * Refill (hangar-phase3): each med counts down from your Did I? ticks. Ordered marks it on its way, Picked up adds
 * the new box. The reorder buzz comes a week before it runs out.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RefillScreen(vault: Uri?, snack: SnackbarHostState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var meds by remember { mutableStateOf<List<MedView>?>(null) }
    var checks by remember { mutableStateOf<List<String>>(emptyList()) }
    var adding by remember { mutableStateOf(false) }
    var counting by remember { mutableStateOf<MedView?>(null) }
    var deleting by remember { mutableStateOf<Med?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(vault, reload) {
        val uri = vault ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            runCatching {
                val v = appVault(ctx, uri)
                val ms = Refill.load(v)
                val stamps = Refill.stamps(v, ms, LocalDate.now())
                checks = DidI.loadChecks(v).map { it.name }
                ms.map { MedView(it, Refill.left(it, stamps)) }
            }
        }.onSuccess { meds = it }.onFailure { snack.showSnackbar("Couldn't load Refill: ${it.message}") }
    }
    BackHandler(onBack = onBack)

    fun save(block: (Vault) -> Unit) {
        val uri = vault ?: return
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { block(appVault(ctx, uri)) } }
                .onSuccess { reload++; HeadsAlarms.changed(ctx) }.onFailure { snack.showSnackbar("Couldn't save: ${it.message}") }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        DayTitle("Refill", Lime, onBack, if (vault != null) "＋ Add" else null) { adding = true }
        val all = meds
        val today = LocalDate.now()
        when {
            vault == null -> Text("Pick your vault in Settings first.", color = Muted, modifier = Modifier.padding(top = 12.dp))
            all == null -> Text("Loading…", color = Muted, modifier = Modifier.padding(top = 12.dp))
            all.isEmpty() -> Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("No meds yet.", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("Add one with how many you've got. Each Did I? tick takes one off, and you get a buzz a week before you run out.",
                    color = Muted, fontSize = 13.sp)
                Big("＋ Add a med", Lime) { adding = true }
            }
            else -> Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                all.forEach { mv ->
                    val m = mv.med
                    val order = Refill.needsOrder(m, mv.left, today)
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Surface1)
                            .then(if (order) Modifier.border(1.dp, Lime.copy(alpha = 0.6f), RoundedCornerShape(14.dp)) else Modifier)
                            .combinedClickable(onClick = { counting = mv }, onLongClick = { deleting = m }).padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(m.name, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f), maxLines = 1)
                            Text("${mv.left} left", color = if (order || mv.left == 0) Lime else TextMain, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        }
                        Text("${Refill.perDayWords(m.perDay)} from Did I? \"${m.check}\" · ${Refill.runsOutWords(m, mv.left, today)}" +
                            if (order) " · reorder now" else "", color = if (order) Lime else Muted, fontSize = 12.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            RefillButton(if (m.ordered) "✓ Ordered" else "Ordered", m.ordered, Modifier.weight(1f)) {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                save { v -> Refill.update(v, m, m.copy(ordered = !m.ordered)) }
                            }
                            RefillButton("Picked up +${m.box}", false, Modifier.weight(1f)) {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                save { v -> Refill.update(v, m, Refill.pickedUp(m, mv.left, LocalDateTime.now())) }
                                scope.launch { snack.showSnackbar("${m.name}: ${mv.left + m.box} left") }
                            }
                        }
                    }
                }
                Text("Each Did I? tick takes one off; skipped days don't. Tap a med to correct the count, hold it to delete. They live in ${Refill.NOTE}.",
                    color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp, bottom = 20.dp))
            }
        }
    }

    deleting?.let { m ->
        AlertDialog(
            onDismissRequest = { deleting = null }, containerColor = Surface1,
            title = { Text("Delete ${m.name}?") },
            confirmButton = { TextButton(onClick = { deleting = null; save { v -> Refill.remove(v, m) } }) { Text("Delete", color = Coral) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel", color = Muted) } }
        )
    }
    counting?.let { mv ->
        var n by remember(mv) { mutableStateOf(mv.left.toString()) }
        AlertDialog(
            onDismissRequest = { counting = null }, containerColor = Surface1,
            title = { Text("How many ${mv.med.name} left?") },
            text = {
                OutlinedTextField(n, { n = it.filter(Char::isDigit).take(4) }, singleLine = true, colors = fieldColors(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            },
            confirmButton = {
                TextButton(enabled = n.isNotEmpty(), onClick = {
                    counting = null
                    save { v -> Refill.update(v, mv.med, Refill.recount(mv.med, n.toInt(), LocalDateTime.now())) }
                }) { Text("Save", color = Primary) }
            },
            dismissButton = { TextButton(onClick = { counting = null }) { Text("Cancel", color = Muted) } }
        )
    }
    if (adding) AddMedDialog(checks, onDismiss = { adding = false }) { m -> adding = false; save { v -> Refill.add(v, m) } }
}

@Composable
private fun RefillButton(label: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) =
    Text(label, fontSize = 12.sp, textAlign = TextAlign.Center, maxLines = 1, color = if (on) Lime else TextMain,
        modifier = modifier.clip(RoundedCornerShape(8.dp)).background(Surface2).border(1.dp, if (on) Lime.copy(alpha = 0.5f) else Outline, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick).padding(vertical = 8.dp))

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddMedDialog(checks: List<String>, onDismiss: () -> Unit, onAdd: (Med) -> Unit) {
    var name by remember { mutableStateOf("") }
    var count by remember { mutableStateOf("") }
    var perDay by remember { mutableStateOf(1.0) }
    var check by remember { mutableStateOf(checks.firstOrNull { it.equals(Refill.CHECK, true) } ?: checks.firstOrNull() ?: Refill.CHECK) }
    var box by remember { mutableStateOf(Refill.BOX.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Surface1,
        title = { Text("Add a med") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.replace("·", "") }, singleLine = true, colors = fieldColors(), placeholder = { Text("e.g. Sertraline 50mg") })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(count, { count = it.filter(Char::isDigit).take(4) }, singleLine = true, colors = fieldColors(), modifier = Modifier.weight(1f),
                        placeholder = { Text("How many now") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(box, { box = it.filter(Char::isDigit).take(3) }, singleLine = true, colors = fieldColors(), modifier = Modifier.width(96.dp),
                        label = { Text("Box") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0.5, 1.0, 2.0, 3.0).forEach { d -> AppChip(perDay == d, { perDay = d }, { Text(Refill.perDayWords(d)) }) }
                }
                Text("Counts down when you tick", color = Muted, fontSize = 12.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (checks.ifEmpty { listOf(Refill.CHECK) }).forEach { c -> AppChip(check == c, { check = c }, { Text(c) }) }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && count.isNotEmpty(), onClick = {
                onAdd(Med(name.trim(), count.toInt(), LocalDateTime.now().withSecond(0).withNano(0), perDay, check, box.toIntOrNull() ?: Refill.BOX))
            }) { Text("Add", color = Primary) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Muted) } }
    )
}
