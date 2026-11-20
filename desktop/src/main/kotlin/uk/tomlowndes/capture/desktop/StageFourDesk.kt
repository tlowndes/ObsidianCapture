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
import uk.tomlowndes.capture.CalendarAsk
import uk.tomlowndes.capture.InboxSection
import uk.tomlowndes.capture.InboxSort
import uk.tomlowndes.capture.SenderGroup
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

// ---- Proton inbox digest (proton-inbox-v1, inbox-flags-groups-v1) ----

internal data class DeskInbox(
    val digest: Digest?, val state: Map<String, String>,
    val settings: MailDigest.BoxSettings = MailDigest.BoxSettings(), val calendar: List<CalendarAsk> = emptyList(),
) {
    fun sections(today: LocalDate): Map<InboxSection, List<Mail>> = digest?.let { InboxSort.sections(it, state, settings, today) }.orEmpty()
    /** Important, bills and the rest that need you, in that order. */
    fun open(today: LocalDate): List<Mail> = sections(today).let { s -> listOf(InboxSection.IMPORTANT, InboxSection.BILLS, InboxSection.NEEDS_YOU).flatMap { s[it].orEmpty() } }
    fun important(m: Mail) = InboxSort.important(m, settings, state)
}

/** Reads the digest, what's been done with it, the hidden/important lists and the calendar queue; adds email receipts to spend. Blocking. */
internal fun loadDeskInbox(state: AppState): DeskInbox? {
    val v = state.vault ?: return null
    val d = runCatching { MailDigest.load(v) }.getOrNull()
    d?.let { runCatching { Spend.syncMail(v, it) } }
    return DeskInbox(
        d, runCatching { MailDigest.state(v) }.getOrDefault(emptyMap()),
        runCatching { MailDigest.settings(v) }.getOrDefault(MailDigest.BoxSettings()), runCatching { InboxSort.calendar(v) }.getOrDefault(emptyList()),
    )
}

internal fun mailColour(k: MailKind): Color = when (k) {
    MailKind.REPLY -> Primary; MailKind.BILL -> Amber; MailKind.JOB -> Purple; MailKind.DELIVERY -> LinkBlue; MailKind.RECEIPT -> TaskGreen; MailKind.READ -> Muted
}

private val Star = Color(0xFFFFD36A)
private val StarTint = Color(0xFF2A2412)

/** Opens the Proton Mail desktop app if it's installed (its Start menu shortcut or install folder), else Proton on the web. */
internal fun openProtonWeb() = runCatching {
    val app = protonMailApp()
    if (app != null && runCatching { ProcessBuilder("cmd", "/c", "start", "", app.absolutePath).start() }.isSuccess) return@runCatching
    java.awt.Desktop.getDesktop().browse(java.net.URI(MailDigest.PROTON_WEB))
}

private fun protonMailApp(): java.io.File? {
    if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) return null
    val env = System.getenv()
    val menus = listOfNotNull(env["APPDATA"], env["ProgramData"]).map { java.io.File(it, "Microsoft/Windows/Start Menu/Programs") }
    menus.forEach { dir ->
        dir.walk().maxDepth(3).firstOrNull { f ->
            val n = f.name.lowercase()
            f.isFile && n.endsWith(".lnk") && n.startsWith("proton mail") && "bridge" !in n
        }?.let { return it }
    }
    // The installer puts a launcher exe at the top of %LOCALAPPDATA%\proton_mail.
    return env["LOCALAPPDATA"]?.let { java.io.File(it, "proton_mail") }?.takeIf { it.isDirectory }
        ?.listFiles { f -> f.isFile && f.name.lowercase().let { it.endsWith(".exe") && "proton" in it && "update" !in it } }?.firstOrNull()
}


@Composable
private fun KindTag(k: MailKind, text: String = k.label) = Text(
    text, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, color = mailColour(k),
    modifier = Modifier.border(1.dp, mailColour(k).copy(alpha = .5f), RoundedCornerShape(99.dp)).padding(horizontal = 7.dp, vertical = 1.dp)
)

private fun tagText(m: Mail, today: LocalDate): String = when (m.kind) {
    MailKind.BILL -> (m.billDue ?: m.due)?.format(DateTimeFormatter.ofPattern("d MMM", Locale.UK)) ?: m.kind.label
    MailKind.DELIVERY -> m.due?.let { if (it == today) "Today" else if (it == today.plusDays(1)) "Tomorrow" else it.format(DateTimeFormatter.ofPattern("EEE d", Locale.UK)) } ?: m.kind.label
    else -> m.kind.label
}

@Composable
private fun MailLine(m: Mail, selected: Boolean = false, count: Int = 1, important: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(if (selected) PrimaryTint else Color.Transparent).hand().clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically
    ) {
        Text(if (important) "★" else m.kind.emoji, fontSize = 15.sp, color = if (important) Star else mailColour(m.kind))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(InboxSort.name(m.from), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextMain, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
                if (count > 1) Count(count, text = "$count")
            }
            val extra = if (m.kind == MailKind.BILL) MailDigest.billDraft(m).amount?.let { TaskViews.money(it) + " · " }.orEmpty() else ""
            Text(extra + m.gist.ifEmpty { m.subject }, fontSize = 12.5.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        KindTag(m.kind, tagText(m, LocalDate.now()))
    }
}

/** Deck: the same card as the phone, important first and one row per sender, bills and deliveries on the last line. */
@Composable
internal fun InboxDeckCard(state: AppState, inbox: DeskInbox, onOpen: (List<Mail>) -> Unit) {
    val d = inbox.digest ?: return
    val today = LocalDate.now()
    val sec = inbox.sections(today)
    val groups = InboxSort.groups(sec[InboxSection.IMPORTANT].orEmpty()) + InboxSort.groups(sec[InboxSection.NEEDS_YOU].orEmpty())
    val bills = sec[InboxSection.BILLS].orEmpty()
    val deliveries = sec[InboxSection.DELIVERIES].orEmpty()
    val shape = RoundedCornerShape(16.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(Surface1).border(1.dp, Outline, shape)) {
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 12.dp, top = 12.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Inbox", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextMain)
            Text("  " + MailDigest.heading(d, InboxSort.needsCount(sec), LocalDateTime.now()), fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Muted, modifier = Modifier.weight(1f))
            TextAction("All ${d.items.size} ›", Muted) { state.tab = Tab.INBOX }
        }
        if (groups.isEmpty() && bills.isEmpty() && deliveries.isEmpty()) Text("Nothing needs you. ✓", fontSize = 13.sp, color = Muted, modifier = Modifier.padding(start = 18.dp, bottom = 14.dp))
        groups.take(3).forEach { g -> MailLine(g.first, count = g.mails.size, important = inbox.important(g.first)) { onOpen(g.mails) } }
        val bits = listOfNotNull(
            bills.takeIf { it.isNotEmpty() }?.let { "£ ${it.size} ${if (it.size == 1) "bill" else "bills"}" },
            deliveries.takeIf { it.isNotEmpty() }?.let { l -> "📦 ${l.size} " + if (l.any { it.due == today }) "today" else "coming" },
        )
        if (bits.isNotEmpty()) Text(bits.joinToString(" · "), fontSize = 12.5.sp, color = Muted,
            modifier = Modifier.fillMaxWidth().hand().clickable { state.tab = Tab.INBOX }.padding(horizontal = 18.dp, vertical = 8.dp))
        Box(Modifier.height(6.dp))
    }
}

/**
 * One email (or a sender's group) with Claude's summary, ★ Important / Not important, the bill's amount and date to fix,
 * Add to Proton Calendar, and the ticked parts to save. Used beside the Inbox list and as a dialog from the Deck.
 */
@Composable
internal fun MailDetail(state: AppState, group: List<Mail>, onDone: () -> Unit) {
    val m = group.first()
    val scope = rememberCoroutineScope()
    val today = LocalDate.now()
    var save by remember(m.id) { mutableStateOf<MailDigest.Save?>(null) }
    var settings by remember(m.id) { mutableStateOf<MailDigest.BoxSettings?>(null) }
    var st by remember(m.id) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var asks by remember(m.id) { mutableStateOf<List<CalendarAsk>>(emptyList()) }
    var task by remember(m.id) { mutableStateOf(true) }
    var bill by remember(m.id) { mutableStateOf(true) }
    var job by remember(m.id) { mutableStateOf(true) }
    var cal by remember(m.id) { mutableStateOf(m.kind == MailKind.BILL) }
    var hideOpen by remember(m.id) { mutableStateOf(false) }
    var draft by remember(m.id) { mutableStateOf(MailDigest.billDraft(m)) }
    var amountText by remember(m.id) { mutableStateOf(draft.amount?.toPlainString().orEmpty()) }
    var pickDue by remember(m.id) { mutableStateOf(false) }
    LaunchedEffect(m.id) {
        val v = state.vault ?: return@LaunchedEffect
        save = MailDigest.suggest(m, state.io("Couldn't read Jobs") { Jobs.list(v) } ?: emptyList(), today)
        settings = state.io { MailDigest.settings(v) } ?: MailDigest.BoxSettings()
        st = state.io { MailDigest.state(v) } ?: emptyMap()
        asks = state.io { InboxSort.calendar(v) } ?: emptyList()
    }
    val sender = InboxSort.name(m.from)
    val n = group.size
    fun mark(value: String?, msg: String) = scope.launch {
        val v = state.vault ?: return@launch
        state.io("Couldn't save it") { group.forEach { MailDigest.setState(v, it.id, value) } }
        state.say(msg); state.changed(); onDone()
    }
    fun putSettings(new: MailDigest.BoxSettings, msg: String, undo: MailDigest.BoxSettings?) = scope.launch {
        val v = state.vault ?: return@launch
        state.io("Couldn't save it") { MailDigest.saveSettings(v, new) }
        state.changed(); onDone()
        if (undo != null) state.offerUndo(msg) { state.io { MailDigest.saveSettings(v, undo) }; state.changed() } else state.say(msg)
    }
    val isImportant = group.any { st[it.id] == MailDigest.IMPORTANT } || settings?.important?.any { InboxSort.key(it) == InboxSort.key(m.from) } == true
    val billNow = draft.copy(amount = amountText.toBigDecimalOrNull())
    val calAsk = InboxSort.calendarAsk(m, if (m.kind == MailKind.BILL) billNow else null)
    val calStatus = InboxSort.calendarStatus(asks, m.id)
    val offersCal = m.kind == MailKind.BILL && calAsk != null && calStatus == null
    val day = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(sender + if (n > 1) "  ·  $n emails" else "", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = TextMain)
                Text(m.subject + (m.date?.let { " · " + it.format(DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.UK)) } ?: ""), fontSize = 12.sp, color = Muted)
            }
            KindTag(m.kind)
        }
        Text("CLAUDE SUMMARY", fontSize = 11.sp, letterSpacing = 1.4.sp, fontFamily = FontFamily.Monospace, color = Purple, modifier = Modifier.padding(top = 14.dp))
        Text(m.summary.ifEmpty { m.gist }, fontSize = 14.sp, color = TextMain, modifier = Modifier.padding(top = 4.dp))
        group.drop(1).forEach { o ->
            Text("· " + (o.date?.format(DateTimeFormatter.ofPattern("EEE HH:mm", Locale.UK))?.plus(" · ") ?: "") + o.gist.ifEmpty { o.subject },
                fontSize = 12.5.sp, color = Muted, modifier = Modifier.padding(top = 4.dp))
        }

        // ★ Important / Not important
        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val shape = RoundedCornerShape(12.dp)
            Box(Modifier.weight(1f).clip(shape).background(if (isImportant) StarTint else Color.Transparent).border(1.dp, if (isImportant) Star.copy(alpha = .4f) else Outline, shape)
                .hand().clickable { if (isImportant) mark(null, "Not pinned") else mark(MailDigest.IMPORTANT, "★ Pinned to the top") }.padding(vertical = 9.dp),
                contentAlignment = Alignment.Center) { Text(if (isImportant) "★ Important" else "☆ Important", color = Star, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp) }
            Box(Modifier.weight(1f).clip(shape).border(1.dp, if (hideOpen) Primary else Outline, shape).hand().clickable { hideOpen = !hideOpen }.padding(vertical = 9.dp),
                contentAlignment = Alignment.Center) { Text("Not important ▾", color = TextMain, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp) }
        }
        TextAction("★ Always important from $sender", Muted, Modifier.padding(top = 4.dp)) {
            settings?.let { s -> putSettings(InboxSort.setAlwaysImportant(s, m.from, true), "★ Always important from $sender", null) }
        }
        if (hideOpen) Column(Modifier.padding(top = 6.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, Outline, RoundedCornerShape(12.dp))) {
            HideOpt(if (n > 1) "Just these $n emails" else "Just this email", "Goes to Handled. New ones from $sender still show.") { mark("dismissed", "Hidden") }
            HideOpt("Everything from $sender", "Hidden now and from now on. The box skips them too.") {
                settings?.let { s -> putSettings(InboxSort.hide(s, m.from, false, today), "Hidden everything from $sender", s) }
            }
            if (group.any { it.kind != MailKind.REPLY }) HideOpt("All $sender alerts", "Hides alerts, keeps real replies from people at $sender.") {
                settings?.let { s -> putSettings(InboxSort.hide(s, m.from, true, today), "Hidden $sender alerts", s) }
            }
        }

        // Bill: amount, due date and repeat
        if (m.kind == MailKind.BILL) {
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("AMOUNT £", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Muted)
                    Field(amountText, { amountText = it.filter { c -> c.isDigit() || c == '.' }.take(9) }, Modifier.fillMaxWidth(), placeholder = "Add amount")
                }
                Column(Modifier.weight(1f)) {
                    Text("DUE", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Muted)
                    Chip(draft.due?.format(day) ?: "Add date", selected = false) { pickDue = true }
                }
            }
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BillRepeat.values().forEach { r -> Chip(r.label, selected = draft.repeat == r) { draft = draft.copy(repeat = r) } }
            }
            if (pickDue) PickDateDialog(draft.due ?: today, { d -> draft = draft.copy(due = d); pickDue = false }, { pickDue = false })
        }

        val s = save
        if (s != null) {
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                s.task?.let { t -> Tick(task, "Task: $t" + (s.due?.let { " · ${it.format(day)}" } ?: "")) { task = !task } }
                if (s.bill) Tick(bill, "Save to Bills: " + Bills.text(billNow.name, billNow.amount, billNow.repeat).replace(" #bill", "") + (billNow.due?.let { " · due ${it.format(day)}" } ?: "")) { bill = !bill }
                if (offersCal) Tick(cal, "Add to Proton Calendar: ${calAsk!!.title} · ${calAsk.date.format(day)}") { cal = !cal }
                val j = s.job; val js = s.status
                if (j != null && js != null) Tick(job, "Jobs: ${j.company} → $js") { job = !job }
            }
        }
        if (calStatus != null) Text("📅 $calStatus", fontSize = 12.5.sp, color = TaskGreen, modifier = Modifier.padding(top = 8.dp))
        else if (m.kind != MailKind.BILL && calAsk != null) Box(Modifier.padding(top = 10.dp)) {
            PrimaryButton("📅 Add to Proton Calendar") {
                scope.launch {
                    val v = state.vault ?: return@launch
                    if (state.io("Couldn't add it") { InboxSort.askCalendar(v, calAsk) } != null) { asks = asks + calAsk; state.say("Invite on its way to Proton Mail") }
                }
            }
        }
        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (s != null && (s.task != null || s.bill || s.status != null)) PrimaryButton("Save ticked", enabled = task || bill || job || (cal && offersCal)) {
                scope.launch {
                    val v = state.vault ?: return@launch
                    val w = state.writer() ?: return@launch
                    val got = state.io("Couldn't save it") {
                        if (cal && offersCal) InboxSort.askCalendar(v, calAsk!!)
                        val k = MailDigest.save(v, w, m, s, task, bill, job, today, billNow) + if (cal && offersCal) 1 else 0
                        group.drop(1).forEach { MailDigest.setState(v, it.id, "done") }
                        k
                    }
                    if (got != null) state.say("Saved $got from $sender")
                    state.changed(); onDone()
                }
            }
            TextAction("Open in Proton Mail") { openProtonWeb() }
            TextAction("Snooze", Muted) { mark("snooze:${today.plusDays(1)}", "Back tomorrow") }
            TextAction("Dismiss", Muted) { mark("dismissed", "Dismissed") }
        }
    }
}

@Composable
private fun HideOpt(title: String, sub: String, onClick: () -> Unit) = Column(
    Modifier.fillMaxWidth().hand().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp)
) {
    Text(title, fontSize = 13.5.sp, color = TextMain)
    Text(sub, fontSize = 11.5.sp, color = Muted)
}

@Composable
private fun Tick(on: Boolean, text: String, flip: () -> Unit) = Row(
    Modifier.hand().clickable(onClick = flip), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
) {
    Text(if (on) "☑" else "☐", fontSize = 16.sp, color = if (on) TaskGreen else Muted)
    Text(text, fontSize = 13.5.sp, color = TextMain)
}

@Composable
internal fun MailDialog(state: AppState, group: List<Mail>, onDone: () -> Unit) {
    WideDialog("Email", null, onDone) { MailDetail(state, group, onDone) }
}

private enum class InboxFilter(val label: String, val section: InboxSection?) {
    IMPORTANT("★ Important", InboxSection.IMPORTANT), NEEDS("Needs you", InboxSection.NEEDS_YOU), BILLS("£ Bills", InboxSection.BILLS),
    DELIVERIES("📦 Deliveries", InboxSection.DELIVERIES), JOBS("💼 Jobs", null), RECEIPTS("🧾 Receipts", InboxSection.RECEIPTS),
    READ("Worth knowing", InboxSection.READ), HANDLED("Handled", InboxSection.HANDLED), HIDDEN("Hidden", InboxSection.HIDDEN)
}

/** Sidebar › Inbox: filters (Important to Hidden), the list folded by sender (right-click for actions) and the email beside it. */
@Composable
fun InboxPane(state: AppState) {
    val scope = rememberCoroutineScope()
    var inbox by remember { mutableStateOf<DeskInbox?>(null) }
    var filter by remember { mutableStateOf(InboxFilter.NEEDS) }
    var picked by remember { mutableStateOf<List<Mail>?>(null) }
    var expanded by remember { mutableStateOf(setOf<String>()) }
    fun reload() = scope.launch {
        inbox = state.io("Couldn't read the inbox") { loadDeskInbox(state) }
        state.inboxOpen = inbox?.open(LocalDate.now())?.size ?: 0
    }
    LaunchedEffect(state.vault, state.changes) { reload() }
    val today = LocalDate.now()
    val settings = inbox?.settings
    fun putSettings(n: MailDigest.BoxSettings, msg: String?, undo: MailDigest.BoxSettings? = null) = scope.launch {
        val v = state.vault ?: return@launch
        state.io("Couldn't save it") { MailDigest.saveSettings(v, n) }
        reload()
        if (msg != null) { if (undo != null) state.offerUndo(msg) { state.io { MailDigest.saveSettings(v, undo) }; reload() } else state.say(msg) }
    }
    fun setState(group: List<Mail>, value: String?) = scope.launch {
        state.vault?.let { v -> state.io { group.forEach { MailDigest.setState(v, it.id, value) } } }; reload()
    }
    val sec = inbox?.sections(today).orEmpty()
    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 26.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                H1("Inbox", 28)
                val d = inbox?.digest
                val hidden = sec[InboxSection.HIDDEN].orEmpty().size
                Text(
                    d?.let { "${InboxSort.needsCount(sec)} need you · ${sec[InboxSection.HANDLED].orEmpty().size} handled · ${it.skipped} skipped" +
                        (if (hidden > 0) " · $hidden hidden" else "") +
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
        val lists = InboxFilter.values().associateWith { f ->
            f.section?.let { sec[it].orEmpty() } ?: all.filter { it.kind == MailKind.JOB && it !in sec[InboxSection.HANDLED].orEmpty() && it !in sec[InboxSection.HIDDEN].orEmpty() }
        }
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.width(170.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                InboxFilter.values().forEach { f ->
                    val c = lists[f]?.size ?: 0
                    if (c > 0 || f == InboxFilter.NEEDS || f == filter) Chip("${f.label}  $c", selected = f == filter) { filter = f; picked = null }
                }
                Box(Modifier.height(12.dp))
                settings?.let { s ->
                    Text("Pick out", fontSize = 11.sp, color = Muted, fontFamily = FontFamily.Monospace)
                    listOf(
                        "↩ Replies" to s.replies, "£ Bills" to s.bills, "💼 Jobs" to s.jobs, "📦 Deliveries" to s.deliveries,
                    ).forEachIndexed { i, (label, on) ->
                        Text((if (on) "☑ " else "☐ ") + label, fontSize = 12.5.sp, color = if (on) TextMain else Muted, modifier = Modifier.hand().clickable {
                            putSettings(when (i) { 0 -> s.copy(replies = !on); 1 -> s.copy(bills = !on); 2 -> s.copy(jobs = !on); else -> s.copy(deliveries = !on) }, null)
                        }.padding(vertical = 2.dp))
                    }
                    inbox?.digest?.costMonth?.let { Text("Claude on the box £${"%.2f".format(it)} this month", fontSize = 11.sp, color = Faint, modifier = Modifier.padding(top = 8.dp)) }
                }
            }
            val shape = RoundedCornerShape(16.dp)
            val list = lists[filter].orEmpty()
            Column(Modifier.weight(1f).fillMaxHeight().clip(shape).background(Surface1).border(1.dp, Outline, shape).verticalScroll(rememberScrollState())) {
                if (filter == InboxFilter.HIDDEN && settings != null && (settings.hide.isNotEmpty() || settings.important.isNotEmpty())) {
                    Text("HIDDEN SENDERS · the box skips these, nothing is deleted in Proton", fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, color = Muted,
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
                    settings.hide.forEach { r ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(r.sender, fontSize = 14.sp, color = TextMain)
                                Text((if (r.alertsOnly) "Alerts only" else "Everything") + (r.since?.let { " · hidden ${it.format(DateTimeFormatter.ofPattern("d MMM", Locale.UK))}" } ?: ""), fontSize = 11.5.sp, color = Muted)
                            }
                            TextAction("Show again") { putSettings(InboxSort.unhide(settings, r.sender), "Showing ${r.sender} again") }
                        }
                    }
                    settings.important.forEach { who ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text("★ $who", fontSize = 14.sp, color = Star); Text("Always important", fontSize = 11.5.sp, color = Muted) }
                            TextAction("Remove") { putSettings(InboxSort.setAlwaysImportant(settings, who, false), null) }
                        }
                    }
                    if (list.isNotEmpty()) Text("EMAILS FROM HIDDEN SENDERS", fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, color = Muted, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
                }
                if (list.isEmpty() && filter != InboxFilter.HIDDEN) Text(if (filter == InboxFilter.NEEDS) "Nothing needs you. ✓" else "None.", fontSize = 13.sp, color = Muted, modifier = Modifier.padding(18.dp))
                if (filter == InboxFilter.BILLS) InboxSort.billsTotal(list)?.let {
                    Text("TOTAL ${TaskViews.money(it)}", fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, color = Amber, modifier = Modifier.padding(start = 16.dp, top = 10.dp))
                }
                // Bills and deliveries stay one row per email; the rest fold by sender.
                val groups = if (filter == InboxFilter.BILLS || filter == InboxFilter.DELIVERIES) list.map { SenderGroup(InboxSort.name(it.from), listOf(it)) } else InboxSort.groups(list)
                groups.forEach { g ->
                    val key = filter.name + InboxSort.key(g.sender)
                    val open = key in expanded
                    ContextMenuArea(items = {
                        val s = settings
                        listOfNotNull(
                            ContextMenuItem("Open") { picked = g.mails },
                            if (g.mails.size > 1) ContextMenuItem(if (open) "Fold up" else "Show all ${g.mails.size}") { expanded = if (open) expanded - key else expanded + key } else null,
                            ContextMenuItem("★ Mark important") { setState(g.mails, MailDigest.IMPORTANT) },
                            s?.let { ContextMenuItem("★ Always important from ${g.sender}") { putSettings(InboxSort.setAlwaysImportant(it, g.first.from, true), "★ Always important from ${g.sender}") } },
                            ContextMenuItem("Not important · just this") { setState(g.mails, "dismissed") },
                            s?.let { ContextMenuItem("Not important · hide ${g.sender}") { putSettings(InboxSort.hide(it, g.first.from, false, today), "Hidden everything from ${g.sender}", it) } },
                            if (s != null && g.mails.any { it.kind != MailKind.REPLY }) ContextMenuItem("Not important · hide ${g.sender} alerts") {
                                putSettings(InboxSort.hide(s, g.first.from, true, today), "Hidden ${g.sender} alerts", s)
                            } else null,
                            ContextMenuItem("Snooze till tomorrow") { setState(g.mails, "snooze:${today.plusDays(1)}") },
                            ContextMenuItem("Dismiss") { setState(g.mails, "dismissed") },
                            ContextMenuItem("Open in Proton Mail") { openProtonWeb() },
                            if (filter == InboxFilter.HANDLED || filter == InboxFilter.IMPORTANT) ContextMenuItem(if (filter == InboxFilter.HANDLED) "Back to the inbox" else "Unpin") { setState(g.mails, null) } else null,
                        )
                    }) {
                        MailLine(g.first, selected = picked?.firstOrNull()?.id == g.first.id, count = g.mails.size, important = filter == InboxFilter.IMPORTANT) {
                            picked = g.mails
                            if (g.mails.size > 1) expanded = if (open) expanded - key else expanded + key
                        }
                    }
                    if (open) g.mails.forEach { one ->
                        Text((one.date?.format(DateTimeFormatter.ofPattern("EEE HH:mm", Locale.UK))?.plus(" · ") ?: "") + one.gist.ifEmpty { one.subject },
                            fontSize = 12.5.sp, color = if (picked?.singleOrNull()?.id == one.id) Primary else TextMain, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().background(Color(0xFF121725)).hand().clickable { picked = listOf(one) }.padding(start = 44.dp, end = 16.dp, top = 6.dp, bottom = 6.dp))
                    }
                }
                Text("Right-click any row for Important, Not important, Snooze or Open in Proton Mail.", fontSize = 11.5.sp, color = Faint, modifier = Modifier.padding(16.dp))
            }
            Column(Modifier.weight(1f).fillMaxHeight().clip(shape).background(Surface1).border(1.dp, Outline, shape).verticalScroll(rememberScrollState()).padding(20.dp)) {
                picked?.let { g -> MailDetail(state, g) { picked = null; reload() } }
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
