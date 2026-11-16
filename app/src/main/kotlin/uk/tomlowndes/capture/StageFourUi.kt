package uk.tomlowndes.capture

import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

// ---- Proton inbox digest (proton-inbox-v1) ----

/** Everything the Inbox card and page need, read in one go. Blocking. */
internal data class InboxData(val digest: Digest?, val state: Map<String, String>) {
    fun open(today: LocalDate) = digest?.let { MailDigest.needsYou(it, state, today) }.orEmpty()
    fun deliveries(today: LocalDate) = digest?.let { MailDigest.deliveries(it, state, today) }.orEmpty()
}

internal fun loadInbox(ctx: Context, vault: Uri): InboxData {
    val v = appVault(ctx, vault)
    val d = runCatching { MailDigest.load(v) }.getOrNull()
    // Email receipts go into this month's spend as soon as they're seen.
    d?.let { runCatching { Spend.syncMail(v, it) } }
    return InboxData(d, runCatching { MailDigest.state(v) }.getOrDefault(emptyMap()))
}

internal fun kindColour(k: MailKind): Color = when (k) {
    MailKind.REPLY -> Primary; MailKind.BILL -> Amber; MailKind.JOB -> Purple; MailKind.DELIVERY -> Teal; MailKind.RECEIPT -> Green; MailKind.READ -> Muted
}

@Composable
private fun KindPill(k: MailKind) = Text(
    k.label, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, color = kindColour(k),
    modifier = Modifier.border(1.dp, kindColour(k).copy(alpha = .5f), RoundedCornerShape(99.dp)).padding(horizontal = 7.dp, vertical = 1.dp)
)

@Composable
private fun MailRow(m: Mail, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(11.dp)) {
        Text(m.kind.emoji, fontSize = 15.sp, color = kindColour(m.kind))
        Column(Modifier.weight(1f)) {
            Text(m.from, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(m.gist.ifEmpty { m.subject }, fontSize = 12.5.sp, color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        KindPill(m.kind)
    }
}

/** Deck: the top 3 that need you, deliveries folded into one line. */
@Composable
internal fun InboxCard(data: InboxData, onOpen: (Mail) -> Unit, onAll: () -> Unit) {
    val d = data.digest ?: return
    val today = LocalDate.now()
    val open = data.open(today)
    val deliveries = data.deliveries(today)
    SectionLabel("Inbox · " + MailDigest.heading(d, open.size, LocalDateTime.now()), "All ${d.items.size} ›", onAll)
    if (open.isEmpty() && deliveries.isEmpty()) {
        DeckCard { Text("Nothing needs you. ✓", fontSize = 13.sp, color = Muted, modifier = Modifier.padding(14.dp)) }
        return
    }
    DeckCard {
        Column {
            open.take(3).forEachIndexed { i, m ->
                if (i > 0) HorizontalDivider(color = Outline)
                MailRow(m) { onOpen(m) }
            }
            if (deliveries.isNotEmpty()) {
                if (open.isNotEmpty()) HorizontalDivider(color = Outline)
                Text("📦 ${deliveries.size} ${if (deliveries.size == 1) "delivery" else "deliveries"} " +
                    (if (deliveries.any { it.due == today }) "today" else "coming"),
                    fontSize = 13.sp, color = Muted, modifier = Modifier.fillMaxWidth().clickable(onClick = onAll).padding(horizontal = 14.dp, vertical = 10.dp))
            }
        }
    }
}

internal fun openProton(ctx: Context) {
    val app = ctx.packageManager.getLaunchIntentForPackage(MailDigest.PROTON_APP)
    runCatching { ctx.startActivity((app ?: Intent(Intent.ACTION_VIEW, Uri.parse(MailDigest.PROTON_WEB))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** Tap an email: Claude's summary and up to three things to save (task, bill, job status). Untick any you don't want. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MailSheet(m: Mail, vault: Uri?, snack: SnackbarHostState, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val today = LocalDate.now()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var save by remember { mutableStateOf<MailDigest.Save?>(null) }
    var task by remember { mutableStateOf(true) }
    var bill by remember { mutableStateOf(true) }
    var job by remember { mutableStateOf(true) }
    LaunchedEffect(m.id) {
        val uri = vault ?: return@LaunchedEffect
        val jobs = withContext(Dispatchers.IO) { runCatching { Jobs.list(appVault(ctx, uri)) }.getOrDefault(emptyList()) }
        save = MailDigest.suggest(m, jobs, today)
    }
    fun mark(value: String, msg: String) {
        val uri = vault ?: return
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { MailDigest.setState(appVault(ctx, uri), m.id, value) } }
            onDone(); snack.showSnackbar(msg)
        }
    }
    val day = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)
    ModalBottomSheet(onDismissRequest = onDone, sheetState = sheet, containerColor = Surface1) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(m.kind.emoji, fontSize = 20.sp, color = kindColour(m.kind))
                Column(Modifier.weight(1f)) {
                    Text(m.from, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(m.subject + (m.date?.let { " · " + it.format(DateTimeFormatter.ofPattern("EEE HH:mm", Locale.UK)) } ?: ""), fontSize = 12.sp, color = Muted)
                }
                KindPill(m.kind)
            }
            GroupLabel("Claude summary")
            Text(m.summary.ifEmpty { m.gist }, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
            val s = save
            if (s != null && (s.task != null || s.bill || s.status != null)) {
                Box(Modifier.height(12.dp))
                ListCard {
                    Column {
                        val picks = buildList<Triple<Boolean, Triple<String, String, String?>, () -> Unit>> {
                            s.task?.let { add(Triple(task, Triple("Suggested task", it, s.due?.let { d -> "Due ${d.format(day)}" })) { task = !task }) }
                            if (s.bill) add(Triple(bill, Triple("Bill", Bills.text(m.billName!!, m.amount, BillRepeat.ONCE).replace(" #bill", ""),
                                (m.billDue ?: m.due)?.let { "Due ${it.format(day)}" })) { bill = !bill })
                            val j = s.job; val st = s.status
                            if (j != null && st != null) add(Triple(job, Triple("Jobs", "${j.role} · ${j.company} → $st", "Adds a line to the job's log")) { job = !job })
                        }
                        picks.forEachIndexed { i, (on, t, flip) ->
                            if (i > 0) HorizontalDivider(color = Outline)
                            Row(Modifier.fillMaxWidth().clickable(onClick = flip).padding(horizontal = 14.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                                TickBox(done = on, onClick = flip)
                                Column(Modifier.weight(1f)) {
                                    Text(t.first.uppercase(), fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Muted)
                                    Text(t.second, fontSize = 14.sp)
                                    t.third?.let { Text(it, fontSize = 12.sp, color = Muted) }
                                }
                            }
                        }
                    }
                }
            }
            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SheetButton("Open in Proton", Modifier.weight(1f)) { openProton(ctx) }
                if (s != null && (s.task != null || s.bill || s.status != null)) SheetButton("Save all", Modifier.weight(1f), primary = true, enabled = task || bill || job) {
                    val uri = vault ?: return@SheetButton
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { runCatching { MailDigest.save(appVault(ctx, uri), VaultWriter(ctx, uri), m, s, task, bill, job, today) } }
                        onDone()
                        r.onSuccess { snack.showSnackbar("Saved $it from ${m.from}") }.onFailure { snack.showSnackbar("Failed: ${it.message}") }
                    }
                }
            }
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Text("Snooze till tomorrow", color = Muted, fontSize = 13.sp, modifier = Modifier.clickable { mark("snooze:${today.plusDays(1)}", "Back tomorrow") })
                Text("Dismiss", color = Muted, fontSize = 13.sp, modifier = Modifier.clickable { mark("dismissed", "Dismissed") })
            }
        }
    }
}

/** Vault › Inbox: everything the box picked out, grouped, with what's handled at the bottom. */
@Composable
fun InboxScreen(vault: Uri?, prefs: AppPrefs, snack: SnackbarHostState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var data by remember { mutableStateOf<InboxData?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var opened by remember { mutableStateOf<Mail?>(null) }
    LaunchedEffect(vault, refresh) {
        val uri = vault ?: return@LaunchedEffect
        data = withContext(Dispatchers.IO) { loadInbox(ctx, uri) }
    }
    val today = LocalDate.now()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        Text("‹ Deck", color = Muted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable(onClick = onBack).padding(top = 10.dp))
        Text("Inbox", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
        val dd = data ?: return@Column
        val d = dd.digest
        if (d == null) {
            Text("No digest yet. Once the box is set up, starred and Vault-labelled emails show here.", fontSize = 14.sp, color = Muted, modifier = Modifier.padding(vertical = 16.dp))
            return@Column
        }
        val open = dd.open(today)
        Text("${open.size} need you · ${MailDigest.handled(d, dd.state)} handled · ${d.skipped} skipped" +
            if (MailDigest.paused(d, LocalDateTime.now())) " · paused" else "",
            fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Muted, modifier = Modifier.padding(bottom = 6.dp))
        val rest = d.items.filter { it !in open && MailDigest.open(it, dd.state, today) }
        val handled = d.items.filter { !MailDigest.open(it, dd.state, today) }
        listOf("Needs you" to open, "Deliveries" to rest.filter { it.kind == MailKind.DELIVERY }, "Receipts" to rest.filter { it.kind == MailKind.RECEIPT },
            "Worth knowing" to rest.filter { it.kind == MailKind.READ }, "Handled" to handled).forEach { (title, list) ->
            if (list.isEmpty()) return@forEach
            GroupLabel("$title ${list.size}")
            ListCard(Modifier.padding(top = 6.dp, bottom = 8.dp)) {
                Column { list.forEachIndexed { i, m -> if (i > 0) HorizontalDivider(color = Outline); MailRow(m) { opened = m } } }
            }
        }
        Text("Star an email or give it the Vault label in Proton and it shows here within the hour.", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(vertical = 12.dp))
    }
    opened?.let { m -> MailSheet(m, vault, snack) { opened = null; refresh++ } }
}

/** Settings › Inbox: status, Check now, how often, and what to pick out. Saved for the box in the vault. */
@Composable
internal fun InboxSettings(vault: Uri?) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var s by remember { mutableStateOf<MailDigest.BoxSettings?>(null) }
    var d by remember { mutableStateOf<Digest?>(null) }
    LaunchedEffect(vault) {
        val uri = vault ?: return@LaunchedEffect
        withContext(Dispatchers.IO) { runCatching { val v = appVault(ctx, uri); s = MailDigest.settings(v); d = MailDigest.load(v) } }
    }
    val cur = s ?: return
    fun put(n: MailDigest.BoxSettings) {
        s = n
        val uri = vault ?: return
        scope.launch { withContext(Dispatchers.IO) { runCatching { MailDigest.saveSettings(appVault(ctx, uri), n) } } }
    }
    SectionLabel("Inbox")
    DeckCard {
        Column {
            val status = d?.let { dg ->
                if (MailDigest.paused(dg, LocalDateTime.now())) "Paused · box last checked ${dg.checked?.format(DateTimeFormatter.ofPattern("EEE HH:mm", Locale.UK)) ?: "never"}"
                else "● Connected · checked ${dg.checked!!.toLocalTime().toString().take(5)} · ${dg.read} read"
            } ?: "Not set up yet · see box/mailbox in the repo"
            Box(Modifier.padding(horizontal = 14.dp)) {
                SettingRow("Proton through the box", status) {
                    Text("Check now", color = Primary, fontSize = 13.sp, modifier = Modifier.clickable {
                        val now = LocalDateTime.now()
                        s = cur.copy(checkNow = now)
                        val uri = vault ?: return@clickable
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) { runCatching { MailDigest.checkNow(appVault(ctx, uri), cur, now) }.isSuccess }
                            android.widget.Toast.makeText(ctx, if (ok) "Asked the box to check" else "Couldn't ask the box", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    })
                }
            }
            HorizontalDivider(color = Outline)
            Box(Modifier.padding(horizontal = 14.dp)) {
                SettingRow("Check every", "When the box is on") {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(15, 30, 60).forEach { n -> AppChip(cur.every == n, { put(cur.copy(every = n)) }, { Text("${n}m") }) }
                    }
                }
            }
            listOf(
                Triple("↩ Replies owed", "Real people waiting on you", cur.replies) to { on: Boolean -> cur.copy(replies = on) },
                Triple("£ Bills and renewals", null, cur.bills) to { on: Boolean -> cur.copy(bills = on) },
                Triple("💼 Job replies", "Links to the job in Vault › Jobs", cur.jobs) to { on: Boolean -> cur.copy(jobs = on) },
                Triple("📦 Deliveries", null, cur.deliveries) to { on: Boolean -> cur.copy(deliveries = on) },
            ).forEach { (t, change) ->
                HorizontalDivider(color = Outline)
                Box(Modifier.padding(horizontal = 14.dp)) { SettingRow(t.first, t.second) { AppSwitch(t.third) { put(change(it)) } } }
            }
            d?.costMonth?.let {
                HorizontalDivider(color = Outline)
                Text("This month: Claude Fast on the box £${"%.2f".format(it)} · newsletters and promos always skipped", fontSize = 11.5.sp, color = Muted,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
            }
        }
    }
}

// ---- Spend from receipts (meals-spend-v1) ----

internal fun catColour(c: SpendCategory): Color = when (c) {
    SpendCategory.BILLS -> Amber; SpendCategory.FOOD -> Green; SpendCategory.HOME -> Primary; SpendCategory.FUN -> Purple; SpendCategory.OTHER -> Muted
}

/** This month and last month's spend. Blocking. */
internal fun loadSpend(ctx: Context, vault: Uri, today: LocalDate): Pair<MonthSpend, MonthSpend> {
    val v = appVault(ctx, vault)
    val done = runCatching { VaultWriter(ctx, vault).listTaskLists().done }.getOrDefault(emptyList())
    val m = YearMonth.from(today)
    return Spend.month(v, done, m) to Spend.month(v, done, m.minusMonths(1))
}

@Composable
internal fun SpendCard(now: MonthSpend, last: MonthSpend, onPick: (Receipt) -> Unit) {
    if (now.receipts.isEmpty()) return
    val today = LocalDate.now()
    SectionLabel("Spent in ${now.month.month.getDisplayName(TextStyle.FULL, Locale.UK)}")
    DeckCard {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(TaskViews.money(now.total.setScale(0, java.math.RoundingMode.HALF_UP)), fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("  · ${today.dayOfMonth} days · from ${now.receipts.size} receipts", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Muted)
            }
            val cats = now.categories
            Row(Modifier.fillMaxWidth().padding(top = 8.dp).height(8.dp).clip(RoundedCornerShape(99.dp))) {
                cats.forEach { (c, v) -> Box(Modifier.weight(v.toFloat().coerceAtLeast(0.01f)).fillMaxSize().background(catColour(c))) }
            }
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                cats.forEach { (c, v) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(catColour(c)))
                        Text(" ${c.label} ${TaskViews.money(v.setScale(0, java.math.RoundingMode.HALF_UP))}", fontSize = 11.sp, color = Muted)
                    }
                }
            }
            Spend.compare(now, last, today)?.let { Text(it, fontSize = 12.5.sp, color = Amber, modifier = Modifier.padding(top = 8.dp)) }
            GroupLabel("Latest")
            now.latest.take(3).forEach { r ->
                Row(Modifier.fillMaxWidth().clickable { onPick(r) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.shop, fontSize = 14.sp)
                        Text("${r.source.emoji} ${r.source.label} · ${r.category.label}", fontSize = 11.5.sp, color = Muted)
                    }
                    Text(TaskViews.money(r.amount), fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                }
            }
            Text("Tap one to change the category", fontSize = 11.sp, color = Muted, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CategorySheet(r: Receipt, onPick: (SpendCategory?) -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = { onPick(null) }, sheetState = sheet, containerColor = Surface1) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            Text("${r.shop} · ${TaskViews.money(r.amount)}", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("${r.date.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK))} · ${r.source.label}", fontSize = 12.sp, color = Muted)
            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SpendCategory.values().forEach { c -> AppChip(c == r.category, { onPick(c) }, { Text(c.label) }) }
            }
        }
    }
}
