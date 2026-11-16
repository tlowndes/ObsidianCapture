package uk.tomlowndes.capture.desktop

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import uk.tomlowndes.capture.BillRepeat
import uk.tomlowndes.capture.Bills
import uk.tomlowndes.capture.Digest
import uk.tomlowndes.capture.Jobs
import uk.tomlowndes.capture.Mail
import uk.tomlowndes.capture.MailDigest
import uk.tomlowndes.capture.MailKind
import uk.tomlowndes.capture.MonthSpend
import uk.tomlowndes.capture.Receipt
import uk.tomlowndes.capture.Spend
import uk.tomlowndes.capture.SpendCategory
import uk.tomlowndes.capture.TaskViews
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

// ---- Proton inbox digest (proton-inbox-v1) ----

internal data class DeskInbox(val digest: Digest?, val state: Map<String, String>) {
    fun open(today: LocalDate) = digest?.let { MailDigest.needsYou(it, state, today) }.orEmpty()
}

/** Reads the digest and what's been done with it; adds any email receipts to spend. Blocking. */
internal fun loadDeskInbox(state: AppState): DeskInbox? {
    val v = state.vault ?: return null
    val d = runCatching { MailDigest.load(v) }.getOrNull()
    d?.let { runCatching { Spend.syncMail(v, it) } }
    return DeskInbox(d, runCatching { MailDigest.state(v) }.getOrDefault(emptyMap()))
}

internal fun mailColour(k: MailKind): Color = when (k) {
    MailKind.REPLY -> Primary; MailKind.BILL -> Amber; MailKind.JOB -> Purple; MailKind.DELIVERY -> LinkBlue; MailKind.RECEIPT -> TaskGreen; MailKind.READ -> Muted
}

internal fun openProtonWeb() = runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(MailDigest.PROTON_WEB)) }

@Composable
private fun KindTag(k: MailKind) = Text(
    k.label, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, color = mailColour(k),
    modifier = Modifier.border(1.dp, mailColour(k).copy(alpha = .5f), RoundedCornerShape(99.dp)).padding(horizontal = 7.dp, vertical = 1.dp)
)

@Composable
private fun MailLine(m: Mail, selected: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(if (selected) PrimaryTint else Color.Transparent).hand().clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically
    ) {
        Text(m.kind.emoji, fontSize = 15.sp, color = mailColour(m.kind))
        Column(Modifier.weight(1f)) {
            Text(m.from, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextMain, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(m.gist.ifEmpty { m.subject }, fontSize = 12.5.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        KindTag(m.kind)
    }
}

/** Deck: the same 3-item card as the phone. */
@Composable
internal fun InboxDeckCard(state: AppState, inbox: DeskInbox, onOpen: (Mail) -> Unit) {
    val d = inbox.digest ?: return
    val open = inbox.open(LocalDate.now())
    val shape = RoundedCornerShape(16.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(Surface1).border(1.dp, Outline, shape)) {
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 12.dp, top = 12.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Inbox", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextMain)
            Text("  " + MailDigest.heading(d, open.size, LocalDateTime.now()), fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Muted, modifier = Modifier.weight(1f))
            TextAction("All ${d.items.size} ›", Muted) { state.tab = Tab.INBOX }
        }
        if (open.isEmpty()) Text("Nothing needs you. ✓", fontSize = 13.sp, color = Muted, modifier = Modifier.padding(start = 18.dp, bottom = 14.dp))
        open.take(3).forEach { m -> MailLine(m) { onOpen(m) } }
        Box(Modifier.height(6.dp))
    }
}

/** The ticked parts of an email to save, with the summary. Used beside the Inbox list and as a dialog from the Deck. */
@Composable
internal fun MailDetail(state: AppState, m: Mail, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val today = LocalDate.now()
    var save by remember(m.id) { mutableStateOf<MailDigest.Save?>(null) }
    var task by remember(m.id) { mutableStateOf(true) }
    var bill by remember(m.id) { mutableStateOf(true) }
    var job by remember(m.id) { mutableStateOf(true) }
    LaunchedEffect(m.id) {
        val v = state.vault ?: return@LaunchedEffect
        save = MailDigest.suggest(m, state.io("Couldn't read Jobs") { Jobs.list(v) } ?: emptyList(), today)
    }
    fun mark(value: String, msg: String) = scope.launch {
        val v = state.vault ?: return@launch
        state.io("Couldn't save it") { MailDigest.setState(v, m.id, value) }
        state.say(msg); onDone()
    }
    val day = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(m.from, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = TextMain)
                Text(m.subject + (m.date?.let { " · " + it.format(DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.UK)) } ?: ""), fontSize = 12.sp, color = Muted)
            }
            KindTag(m.kind)
        }
        Text("CLAUDE SUMMARY", fontSize = 11.sp, letterSpacing = 1.4.sp, fontFamily = FontFamily.Monospace, color = Purple, modifier = Modifier.padding(top = 14.dp))
        Text(m.summary.ifEmpty { m.gist }, fontSize = 14.sp, color = TextMain, modifier = Modifier.padding(top = 4.dp))
        val s = save
        if (s != null) {
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                s.task?.let { t -> Tick(task, "Task: $t" + (s.due?.let { " · ${it.format(day)}" } ?: "")) { task = !task } }
                if (s.bill) Tick(bill, "Bill: " + Bills.text(m.billName!!, m.amount, BillRepeat.ONCE).replace(" #bill", "") + ((m.billDue ?: m.due)?.let { " · due ${it.format(day)}" } ?: "")) { bill = !bill }
                val j = s.job; val st = s.status
                if (j != null && st != null) Tick(job, "Jobs: ${j.company} → $st") { job = !job }
            }
        }
        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (s != null && (s.task != null || s.bill || s.status != null)) PrimaryButton("Save ticked", enabled = task || bill || job) {
                scope.launch {
                    val v = state.vault ?: return@launch
                    val w = state.writer() ?: return@launch
                    val n = state.io("Couldn't save it") { MailDigest.save(v, w, m, s, task, bill, job, today) }
                    if (n != null) state.say("Saved $n from ${m.from}")
                    state.changed(); onDone()
                }
            }
            TextAction("Open in Proton") { openProtonWeb() }
            TextAction("Snooze", Muted) { mark("snooze:${today.plusDays(1)}", "Back tomorrow") }
            TextAction("Dismiss", Muted) { mark("dismissed", "Dismissed") }
        }
    }
}

@Composable
private fun Tick(on: Boolean, text: String, flip: () -> Unit) = Row(
    Modifier.hand().clickable(onClick = flip), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
) {
    Text(if (on) "☑" else "☐", fontSize = 16.sp, color = if (on) TaskGreen else Muted)
    Text(text, fontSize = 13.5.sp, color = TextMain)
}

@Composable
internal fun MailDialog(state: AppState, m: Mail, onDone: () -> Unit) {
    WideDialog("Email", null, onDone) { MailDetail(state, m, onDone) }
}

private enum class InboxFilter(val label: String) { NEEDS("Needs you"), BILLS("Bills"), JOBS("Jobs"), DELIVERIES("Deliveries"), RECEIPTS("Receipts"), HANDLED("Handled") }

/** Sidebar › Inbox: filters, the list (right-click for actions) and the email beside it. */
@Composable
fun InboxPane(state: AppState) {
    val scope = rememberCoroutineScope()
    var inbox by remember { mutableStateOf<DeskInbox?>(null) }
    var filter by remember { mutableStateOf(InboxFilter.NEEDS) }
    var picked by remember { mutableStateOf<Mail?>(null) }
    var settings by remember { mutableStateOf<MailDigest.BoxSettings?>(null) }
    fun reload() = scope.launch {
        inbox = state.io("Couldn't read the inbox") { loadDeskInbox(state) }
        settings = state.vault?.let { v -> state.io { MailDigest.settings(v) } }
        state.inboxOpen = inbox?.open(LocalDate.now())?.size ?: 0
    }
    LaunchedEffect(state.vault, state.changes) { reload() }
    val today = LocalDate.now()
    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 26.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                H1("Inbox", 28)
                val d = inbox?.digest
                Text(
                    d?.let { "${inbox!!.open(today).size} need you · ${MailDigest.handled(it, inbox!!.state)} handled · ${it.skipped} skipped" +
                        if (MailDigest.paused(it, LocalDateTime.now())) " · box paused" else " · checked ${it.checked!!.toLocalTime().toString().take(5)}" }
                        ?: "Not set up yet: see box/mailbox in the repo",
                    color = Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp)
                )
            }
            settings?.let { s ->
                TextAction("Check now") {
                    val v = state.vault ?: return@TextAction
                    scope.launch { state.io("Couldn't ask the box") { MailDigest.checkNow(v, s) }; state.say("Asked the box to check") }
                }
            }
        }
        val all = inbox?.digest?.items.orEmpty()
        val st = inbox?.state.orEmpty()
        val open = all.filter { MailDigest.open(it, st, today) }
        val lists = mapOf(
            InboxFilter.NEEDS to (inbox?.open(today).orEmpty()),
            InboxFilter.BILLS to open.filter { it.kind == MailKind.BILL },
            InboxFilter.JOBS to open.filter { it.kind == MailKind.JOB },
            InboxFilter.DELIVERIES to open.filter { it.kind == MailKind.DELIVERY },
            InboxFilter.RECEIPTS to open.filter { it.kind == MailKind.RECEIPT },
            InboxFilter.HANDLED to all.filter { !MailDigest.open(it, st, today) },
        )
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.width(170.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                InboxFilter.values().forEach { f -> Chip("${f.label}  ${lists[f]?.size ?: 0}", selected = f == filter) { filter = f; picked = null } }
                Box(Modifier.height(12.dp))
                settings?.let { s ->
                    Text("Pick out", fontSize = 11.sp, color = Muted, fontFamily = FontFamily.Monospace)
                    listOf(
                        "↩ Replies" to s.replies, "£ Bills" to s.bills, "💼 Jobs" to s.jobs, "📦 Deliveries" to s.deliveries,
                    ).forEachIndexed { i, (label, on) ->
                        Text((if (on) "☑ " else "☐ ") + label, fontSize = 12.5.sp, color = if (on) TextMain else Muted, modifier = Modifier.hand().clickable {
                            val n = when (i) { 0 -> s.copy(replies = !on); 1 -> s.copy(bills = !on); 2 -> s.copy(jobs = !on); else -> s.copy(deliveries = !on) }
                            settings = n
                            val v = state.vault ?: return@clickable
                            scope.launch { state.io("Couldn't save it") { MailDigest.saveSettings(v, n) } }
                        }.padding(vertical = 2.dp))
                    }
                    inbox?.digest?.costMonth?.let { Text("Claude on the box £${"%.2f".format(it)} this month", fontSize = 11.sp, color = Faint, modifier = Modifier.padding(top = 8.dp)) }
                }
            }
            val shape = RoundedCornerShape(16.dp)
            val list = lists[filter].orEmpty()
            Column(Modifier.weight(1f).fillMaxHeight().clip(shape).background(Surface1).border(1.dp, Outline, shape).verticalScroll(rememberScrollState())) {
                if (list.isEmpty()) Text(if (filter == InboxFilter.NEEDS) "Nothing needs you. ✓" else "None.", fontSize = 13.sp, color = Muted, modifier = Modifier.padding(18.dp))
                list.forEach { m ->
                    ContextMenuArea(items = {
                        listOf(
                            ContextMenuItem("Open") { picked = m },
                            ContextMenuItem("Snooze till tomorrow") { scope.launch { state.vault?.let { v -> state.io { MailDigest.setState(v, m.id, "snooze:${today.plusDays(1)}") } }; reload() } },
                            ContextMenuItem("Dismiss") { scope.launch { state.vault?.let { v -> state.io { MailDigest.setState(v, m.id, "dismissed") } }; reload() } },
                            ContextMenuItem("Open in Proton") { openProtonWeb() },
                        ) + (if (filter == InboxFilter.HANDLED) listOf(ContextMenuItem("Back to the inbox") {
                            scope.launch { state.vault?.let { v -> state.io { MailDigest.setState(v, m.id, null) } }; reload() }
                        }) else emptyList())
                    }) { MailLine(m, selected = picked?.id == m.id) { picked = m } }
                }
                Text("Right-click any row for Snooze, Dismiss or Open in Proton.", fontSize = 11.5.sp, color = Faint, modifier = Modifier.padding(16.dp))
            }
            Column(Modifier.weight(1f).fillMaxHeight().clip(shape).background(Surface1).border(1.dp, Outline, shape).verticalScroll(rememberScrollState()).padding(20.dp)) {
                picked?.let { m -> MailDetail(state, m) { picked = null; reload() } }
                    ?: Text("Pick an email to see Claude's summary and what to save.", fontSize = 13.sp, color = Muted)
            }
        }
    }
}

// ---- Spend from receipts (meals-spend-v1) ----

internal fun spendColour(c: SpendCategory): Color = when (c) {
    SpendCategory.BILLS -> Amber; SpendCategory.FOOD -> TaskGreen; SpendCategory.HOME -> Primary; SpendCategory.FUN -> Purple; SpendCategory.OTHER -> Muted
}

internal fun loadDeskSpend(state: AppState, today: LocalDate): Pair<MonthSpend, MonthSpend>? {
    val v = state.vault ?: return null
    val done = runCatching { state.writer()!!.listTaskLists().done }.getOrDefault(emptyList())
    val m = YearMonth.from(today)
    return Spend.month(v, done, m) to Spend.month(v, done, m.minusMonths(1))
}

/** Deck: total, a bar by category (click to see its receipts), and the latest three. Right-click a receipt to recategorise. */
@Composable
internal fun SpendDeckCard(state: AppState, now: MonthSpend, last: MonthSpend, onChanged: () -> Unit) {
    if (now.receipts.isEmpty()) return
    val scope = rememberCoroutineScope()
    val today = LocalDate.now()
    var only by remember { mutableStateOf<SpendCategory?>(null) }
    fun recat(r: Receipt, c: SpendCategory) = scope.launch {
        val v = state.vault ?: return@launch
        state.io("Couldn't save it") { Spend.recategorise(v, r, c) }; onChanged()
    }
    val shape = RoundedCornerShape(16.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(Surface1).border(1.dp, Outline, shape).padding(horizontal = 18.dp, vertical = 12.dp)) {
        Text("Spent in ${now.month.month.getDisplayName(TextStyle.FULL, Locale.UK)}", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextMain)
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 4.dp)) {
            Text(TaskViews.money(now.total.setScale(0, RoundingMode.HALF_UP)), fontSize = 24.sp, fontWeight = FontWeight.Bold, color = TextMain)
            Text("  · ${today.dayOfMonth} days · from ${now.receipts.size} receipts", fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, color = Muted)
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp).height(8.dp).clip(RoundedCornerShape(99.dp))) {
            now.categories.forEach { (c, v) -> Box(Modifier.weight(v.toFloat().coerceAtLeast(0.01f)).fillMaxHeight().background(spendColour(c)).hand().clickable { only = if (only == c) null else c }) }
        }
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            now.categories.forEach { (c, v) ->
                Row(Modifier.hand().clickable { only = if (only == c) null else c }, verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(spendColour(c)))
                    Text(" ${c.label} ${TaskViews.money(v.setScale(0, RoundingMode.HALF_UP))}", fontSize = 11.5.sp, color = if (only == c) TextMain else Muted,
                        fontWeight = if (only == c) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
        Spend.compare(now, last, today)?.let { Text(it, fontSize = 12.5.sp, color = Amber, modifier = Modifier.padding(top = 8.dp)) }
        Text(only?.let { "${it.label.uppercase()} · click again for all" } ?: "LATEST", fontSize = 11.sp, letterSpacing = 1.4.sp, fontFamily = FontFamily.Monospace, color = Primary, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
        now.latest.filter { only == null || it.category == only }.take(if (only == null) 3 else 12).forEach { r ->
            ContextMenuArea(items = { SpendCategory.values().filter { it != r.category }.map { c -> ContextMenuItem("Move to ${c.label}") { recat(r, c) } } }) {
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.shop, fontSize = 13.5.sp, color = TextMain)
                        Text("${r.source.emoji} ${r.source.label} · ${r.category.label} · ${r.date.format(DateTimeFormatter.ofPattern("d MMM", Locale.UK))}", fontSize = 11.5.sp, color = Muted)
                    }
                    Text(TaskViews.money(r.amount), fontSize = 13.5.sp, fontFamily = FontFamily.Monospace, color = TextMain)
                }
            }
        }
        Text("Right-click a receipt to change its category", fontSize = 11.sp, color = Faint, modifier = Modifier.padding(top = 4.dp))
    }
}
