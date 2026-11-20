package uk.tomlowndes.capture

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

// ---- Proton inbox digest (proton-inbox-v1, inbox-flags-groups-v1) ----

/** Everything the Inbox card and page need, read in one go. Blocking. */
internal data class InboxData(
    val digest: Digest?, val state: Map<String, String>,
    val settings: MailDigest.BoxSettings = MailDigest.BoxSettings(), val calendar: List<CalendarAsk> = emptyList(),
) {
    fun sections(today: LocalDate): Map<InboxSection, List<Mail>> = digest?.let { InboxSort.sections(it, state, settings, today) }.orEmpty()
    /** Important, bills and the rest that need you, in that order. */
    fun open(today: LocalDate): List<Mail> = sections(today).let { s -> listOf(InboxSection.IMPORTANT, InboxSection.BILLS, InboxSection.NEEDS_YOU).flatMap { s[it].orEmpty() } }
    fun deliveries(today: LocalDate) = sections(today)[InboxSection.DELIVERIES].orEmpty()
    fun important(m: Mail) = InboxSort.important(m, settings, state)
}

internal fun loadInbox(ctx: Context, vault: Uri): InboxData {
    val v = appVault(ctx, vault)
    val d = runCatching { MailDigest.load(v) }.getOrNull()
    // Email receipts go into this month's spend as soon as they're seen.
    d?.let { runCatching { Spend.syncMail(v, it) } }
    return InboxData(
        d, runCatching { MailDigest.state(v) }.getOrDefault(emptyMap()),
        runCatching { MailDigest.settings(v) }.getOrDefault(MailDigest.BoxSettings()), runCatching { InboxSort.calendar(v) }.getOrDefault(emptyList()),
    )
}

internal fun kindColour(k: MailKind): Color = when (k) {
    MailKind.REPLY -> Primary; MailKind.BILL -> Amber; MailKind.JOB -> Purple; MailKind.DELIVERY -> Teal; MailKind.RECEIPT -> Green; MailKind.READ -> Muted
}

private val Star = Color(0xFFFFD36A)
private val dayFmt = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)

@Composable
private fun KindPill(k: MailKind, text: String = k.label) = Text(
    text, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, color = kindColour(k),
    modifier = Modifier.border(1.dp, kindColour(k).copy(alpha = .5f), RoundedCornerShape(99.dp)).padding(horizontal = 7.dp, vertical = 1.dp)
)

/** The pill on the right: the due day for bills and deliveries, else the kind. */
private fun pillText(m: Mail, today: LocalDate): String = when (m.kind) {
    MailKind.BILL -> (m.billDue ?: m.due)?.format(DateTimeFormatter.ofPattern("d MMM", Locale.UK)) ?: m.kind.label
    MailKind.DELIVERY -> m.due?.let { if (it == today) "Today" else if (it == today.plusDays(1)) "Tomorrow" else it.format(DateTimeFormatter.ofPattern("EEE", Locale.UK)) } ?: m.kind.label
    else -> m.kind.label
}

@Composable
private fun MailRow(m: Mail, count: Int = 1, important: Boolean = false, expanded: Boolean? = null, onExpand: () -> Unit = {}, onClick: () -> Unit) {
    val today = LocalDate.now()
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(11.dp)) {
        Text(if (important) "★" else m.kind.emoji, fontSize = 15.sp, color = if (important) Star else kindColour(m.kind))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(InboxSort.name(m.from), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (count > 1) Text(" $count ", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Muted,
                    modifier = Modifier.padding(start = 6.dp).border(1.dp, Outline, RoundedCornerShape(99.dp)).padding(horizontal = 3.dp))
            }
            val extra = if (m.kind == MailKind.BILL) MailDigest.billDraft(m).amount?.let { TaskViews.money(it) + " · " }.orEmpty() else ""
            Text(extra + m.gist.ifEmpty { m.subject }, fontSize = 12.5.sp, color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (expanded != null) Text(if (expanded) "⌃" else "⌄", fontSize = 16.sp, color = Muted, modifier = Modifier.clickable(onClick = onExpand).padding(horizontal = 6.dp))
        else KindPill(m.kind, pillText(m, today))
    }
}

/** Deck: the top 3 that need you (important first, one row per sender), then bills and deliveries in one line. */
@Composable
internal fun InboxCard(data: InboxData, onOpen: (List<Mail>) -> Unit, onAll: () -> Unit) {
    val d = data.digest ?: return
    val today = LocalDate.now()
    val sec = data.sections(today)
    val groups = InboxSort.groups(sec[InboxSection.IMPORTANT].orEmpty()) + InboxSort.groups(sec[InboxSection.NEEDS_YOU].orEmpty())
    val bills = sec[InboxSection.BILLS].orEmpty()
    val deliveries = sec[InboxSection.DELIVERIES].orEmpty()
    SectionLabel("Inbox · " + MailDigest.heading(d, InboxSort.needsCount(sec), LocalDateTime.now()), "All ${d.items.size} ›", onAll)
    if (groups.isEmpty() && bills.isEmpty() && deliveries.isEmpty()) {
        DeckCard { Text("Nothing needs you. ✓", fontSize = 13.sp, color = Muted, modifier = Modifier.padding(14.dp)) }
        return
    }
    DeckCard {
        Column {
            groups.take(3).forEachIndexed { i, g ->
                if (i > 0) HorizontalDivider(color = Outline)
                MailRow(g.first, g.mails.size, data.important(g.first)) { onOpen(g.mails) }
            }
            val bits = listOfNotNull(
                bills.takeIf { it.isNotEmpty() }?.let { "£ ${it.size} ${if (it.size == 1) "bill" else "bills"}" },
                deliveries.takeIf { it.isNotEmpty() }?.let { l -> "📦 ${l.size} " + if (l.any { it.due == today }) "today" else "coming" },
            )
            if (bits.isNotEmpty()) {
                if (groups.isNotEmpty()) HorizontalDivider(color = Outline)
                Text(bits.joinToString(" · "), fontSize = 13.sp, color = Muted,
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onAll).padding(horizontal = 14.dp, vertical = 10.dp))
            }
        }
    }
}

internal fun openProton(ctx: Context) {
    val app = ctx.packageManager.getLaunchIntentForPackage(MailDigest.PROTON_APP)
    runCatching { ctx.startActivity((app ?: Intent(Intent.ACTION_VIEW, Uri.parse(MailDigest.PROTON_WEB))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/**
 * Tap an email (or a sender's group): Claude's summary, ★ Important / Not important, the bill's amount and date to fix
 * before saving, Add to Proton Calendar, and up to three things to save. Untick any you don't want.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun MailSheet(group: List<Mail>, vault: Uri?, snack: SnackbarHostState, onDone: () -> Unit) {
    val m = group.first()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val today = LocalDate.now()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var save by remember { mutableStateOf<MailDigest.Save?>(null) }
    var settings by remember { mutableStateOf<MailDigest.BoxSettings?>(null) }
    var state by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var asks by remember { mutableStateOf<List<CalendarAsk>>(emptyList()) }
    var task by remember { mutableStateOf(true) }
    var bill by remember { mutableStateOf(true) }
    var job by remember { mutableStateOf(true) }
    var cal by remember { mutableStateOf(m.kind == MailKind.BILL) }
    var hideOpen by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(MailDigest.billDraft(m)) }
    var amountText by remember { mutableStateOf(draft.amount?.toPlainString().orEmpty()) }
    var pickDue by remember { mutableStateOf(false) }
    LaunchedEffect(m.id) {
        val uri = vault ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val v = appVault(ctx, uri)
            val jobs = runCatching { Jobs.list(v) }.getOrDefault(emptyList())
            save = MailDigest.suggest(m, jobs, today)
            settings = runCatching { MailDigest.settings(v) }.getOrDefault(MailDigest.BoxSettings())
            state = runCatching { MailDigest.state(v) }.getOrDefault(emptyMap())
            asks = runCatching { InboxSort.calendar(v) }.getOrDefault(emptyList())
        }
    }
    val sender = InboxSort.name(m.from)
    val n = group.size
    fun io(block: (Vault) -> Unit, after: suspend () -> Unit = {}) {
        val uri = vault ?: return
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { block(appVault(ctx, uri)) } }
            r.onSuccess { after() }.onFailure { snack.showSnackbar("Failed: ${it.message}") }
        }
    }
    fun mark(value: String?, msg: String) = io({ v -> group.forEach { MailDigest.setState(v, it.id, value) } }) { onDone(); snack.showSnackbar(msg) }
    /** Saves the box settings; with [undo], the snackbar offers Undo (outside this sheet's scope, which closes first). */
    fun putSettings(new: MailDigest.BoxSettings, msg: String, undo: MailDigest.BoxSettings? = null) = io({ v -> MailDigest.saveSettings(v, new) }) {
        onDone()
        val uri = vault
        MainScope().launch {
            val r = snack.showSnackbar(msg, actionLabel = if (undo != null) "Undo" else null, duration = SnackbarDuration.Short)
            if (undo != null && uri != null && r == SnackbarResult.ActionPerformed) {
                withContext(Dispatchers.IO) { runCatching { MailDigest.saveSettings(appVault(ctx, uri), undo) } }
                onDone()
            }
        }
    }
    val isImportant = group.any { state[it.id] == MailDigest.IMPORTANT } || settings?.let { it.important.any { w -> InboxSort.key(w) == InboxSort.key(m.from) } } == true
    val calAsk = InboxSort.calendarAsk(m, if (m.kind == MailKind.BILL) draft.copy(amount = amountText.toBigDecimalOrNull()) else null)
    val calStatus = InboxSort.calendarStatus(asks, m.id)
    ModalBottomSheet(onDismissRequest = onDone, sheetState = sheet, containerColor = Surface1) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(m.kind.emoji, fontSize = 20.sp, color = kindColour(m.kind))
                Column(Modifier.weight(1f)) {
                    Text(sender + if (n > 1) "  ·  $n emails" else "", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(m.subject + (m.date?.let { " · " + it.format(DateTimeFormatter.ofPattern("EEE HH:mm", Locale.UK)) } ?: ""), fontSize = 12.sp, color = Muted)
                }
                KindPill(m.kind)
            }
            GroupLabel("Claude summary")
            Text(m.summary.ifEmpty { m.gist }, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
            if (n > 1) group.drop(1).forEach { o ->
                Text("· " + (o.date?.format(DateTimeFormatter.ofPattern("EEE HH:mm", Locale.UK))?.plus(" · ") ?: "") + o.gist.ifEmpty { o.subject },
                    fontSize = 12.5.sp, color = Muted, modifier = Modifier.padding(top = 4.dp))
            }

            // ★ Important / Not important
            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val shape = RoundedCornerShape(14.dp)
                Box(Modifier.weight(1f).clip(shape).background(if (isImportant) Color(0xFF2A2412) else Color.Transparent)
                    .border(1.dp, if (isImportant) Color(0xFF5A4A1E) else Outline, shape)
                    .combinedClickable(
                        onClick = { if (isImportant) mark(null, "Not pinned") else mark(MailDigest.IMPORTANT, "★ Pinned to the top") },
                        onLongClick = { settings?.let { s -> putSettings(InboxSort.setAlwaysImportant(s, m.from, true), "★ Always important from $sender") } },
                    ).padding(vertical = 11.dp), contentAlignment = Alignment.Center) {
                    Text(if (isImportant) "★ Important" else "☆ Important", color = Star, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                }
                Box(Modifier.weight(1f).clip(shape).border(1.dp, if (hideOpen) Primary else Outline, shape).clickable { hideOpen = !hideOpen }.padding(vertical = 11.dp),
                    contentAlignment = Alignment.Center) { Text("Not important", fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }
            }
            Text("Long-press ★ to always pin $sender", fontSize = 11.sp, color = Muted, modifier = Modifier.padding(top = 4.dp, start = 2.dp))
            if (hideOpen) {
                ListCard(Modifier.padding(top = 8.dp)) {
                    Column {
                        Text("NOT IMPORTANT: HIDE…", fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, color = Muted, modifier = Modifier.padding(start = 14.dp, top = 10.dp, bottom = 2.dp))
                        Opt(if (n > 1) "Just these $n emails" else "Just this email", "Goes to Handled. New ones from $sender still show.") { mark("dismissed", "Hidden") }
                        Opt("Everything from $sender", "Hidden now and from now on. The box skips them too.") {
                            settings?.let { s -> putSettings(InboxSort.hide(s, m.from, false, today), "Hidden everything from $sender", undo = s) }
                        }
                        if (group.any { it.kind != MailKind.REPLY }) Opt("All $sender alerts", "Hides alerts, keeps real replies from people at $sender.") {
                            settings?.let { s -> putSettings(InboxSort.hide(s, m.from, true, today), "Hidden $sender alerts", undo = s) }
                        }
                    }
                }
            }

            // Bill: amount, due and repeat, editable before saving
            if (m.kind == MailKind.BILL) {
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(amountText, { amountText = it.filter { c -> c.isDigit() || c == '.' }.take(9) }, singleLine = true, colors = fieldColors(),
                        label = { Text("Amount £") }, placeholder = { Text("Add amount") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).border(1.dp, Outline, RoundedCornerShape(6.dp)).clickable { pickDue = true }.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text("DUE", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Muted)
                        Text(draft.due?.format(dayFmt) ?: "Add date", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (draft.due == null) Muted else TextMain)
                    }
                }
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BillRepeat.values().forEach { r -> AppChip(draft.repeat == r, { draft = draft.copy(repeat = r) }, { Text(r.label, fontSize = 12.sp) }) }
                }
                if (pickDue) PickDateDialog(draft.due ?: today, { d -> draft = draft.copy(due = d); pickDue = false }, { pickDue = false })
            }

            val s = save
            val billNow = draft.copy(name = draft.name, amount = amountText.toBigDecimalOrNull())
            val offersCal = m.kind == MailKind.BILL && calAsk != null && calStatus == null
            if (s != null && (s.task != null || s.bill || s.status != null)) {
                Box(Modifier.height(12.dp))
                ListCard {
                    Column {
                        val picks = buildList<Triple<Boolean, Triple<String, String, String?>, () -> Unit>> {
                            s.task?.let { add(Triple(task, Triple("Suggested task", it, s.due?.let { d -> "Due ${d.format(dayFmt)}" })) { task = !task }) }
                            if (s.bill) add(Triple(bill, Triple("Save to Bills", Bills.text(billNow.name, billNow.amount, billNow.repeat).replace(" #bill", ""),
                                billNow.due?.let { "Due ${it.format(dayFmt)} · reminder the day before" })) { bill = !bill })
                            if (offersCal) add(Triple(cal, Triple("Add to Proton Calendar", calAsk!!.title, "All day ${calAsk.date.format(dayFmt)}")) { cal = !cal })
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
            // Deliveries and other dated emails: one button for the calendar
            if (m.kind != MailKind.BILL && calAsk != null) {
                Box(Modifier.height(12.dp))
                if (calStatus != null) Text("📅 $calStatus", fontSize = 13.sp, color = Green)
                else SheetButton("📅 Add to Proton Calendar", Modifier.fillMaxWidth(), primary = m.kind == MailKind.DELIVERY) {
                    io({ v -> InboxSort.askCalendar(v, calAsk) }) { asks = asks + calAsk; snack.showSnackbar("Invite on its way to Proton Mail") }
                }
            } else if (m.kind == MailKind.BILL && calStatus != null) Text("📅 $calStatus", fontSize = 12.sp, color = Green, modifier = Modifier.padding(top = 6.dp))
            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SheetButton("Open in Proton Mail", Modifier.weight(1f)) { openProton(ctx) }
                if (s != null && (s.task != null || s.bill || s.status != null)) {
                    val count = listOf(task && s.task != null, bill && s.bill, job && s.status != null, cal && offersCal).count { it }
                    SheetButton(if (count == 2) "Save both" else "Save all", Modifier.weight(1f), primary = true, enabled = count > 0) {
                        val uri = vault ?: return@SheetButton
                        scope.launch {
                            val r = withContext(Dispatchers.IO) {
                                runCatching {
                                    val v = appVault(ctx, uri)
                                    if (cal && offersCal) InboxSort.askCalendar(v, calAsk!!)
                                    MailDigest.save(v, VaultWriter(ctx, uri), m, s, task, bill, job, today, billNow) + if (cal && offersCal) 1 else 0
                                }
                            }
                            if (r.isSuccess && n > 1) withContext(Dispatchers.IO) { runCatching { val v = appVault(ctx, uri); group.drop(1).forEach { MailDigest.setState(v, it.id, "done") } } }
                            onDone()
                            r.onSuccess { snack.showSnackbar("Saved $it from $sender") }.onFailure { snack.showSnackbar("Failed: ${it.message}") }
                        }
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

/** Vault › Inbox: Important, Bills, Deliveries, Needs you and the rest, one row per sender, handled at the bottom. */
@Composable
fun InboxScreen(vault: Uri?, prefs: AppPrefs, snack: SnackbarHostState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var data by remember { mutableStateOf<InboxData?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var opened by remember { mutableStateOf<List<Mail>?>(null) }
    var expanded by remember { mutableStateOf(setOf<String>()) }
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
        val sec = dd.sections(today)
        val hidden = sec[InboxSection.HIDDEN].orEmpty().size
        Text("${InboxSort.needsCount(sec)} need you · ${sec[InboxSection.HANDLED].orEmpty().size} handled · ${d.skipped} skipped" +
            (if (hidden > 0) " · $hidden hidden" else "") + if (MailDigest.paused(d, LocalDateTime.now())) " · paused" else "",
            fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Muted, modifier = Modifier.padding(bottom = 6.dp))
        InboxSection.values().filter { it != InboxSection.HIDDEN }.forEach { s ->
            val list = sec[s].orEmpty()
            if (list.isEmpty()) return@forEach
            Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Box(Modifier.weight(1f)) { GroupLabel("${s.label} ${list.size}", if (s == InboxSection.IMPORTANT) Star else Muted) }
                if (s == InboxSection.BILLS) InboxSort.billsTotal(list)?.let { GroupLabel(TaskViews.money(it)) }
            }
            ListCard(Modifier.padding(top = 6.dp, bottom = 8.dp)) {
                Column {
                    // Bills and deliveries stay one row per email (each has its own amount or day); the rest fold by sender.
                    val groups = if (s == InboxSection.BILLS || s == InboxSection.DELIVERIES) list.map { SenderGroup(InboxSort.name(it.from), listOf(it)) } else InboxSort.groups(list)
                    groups.forEachIndexed { i, g ->
                        if (i > 0) HorizontalDivider(color = Outline)
                        val key = s.name + InboxSort.key(g.sender)
                        val open = key in expanded
                        MailRow(g.first, g.mails.size, s == InboxSection.IMPORTANT, if (g.mails.size > 1) open else null,
                            onExpand = { expanded = if (open) expanded - key else expanded + key }) { opened = g.mails }
                        if (open) g.mails.forEach { one ->
                            HorizontalDivider(color = Outline)
                            Column(Modifier.fillMaxWidth().background(Color(0xFF121725)).clickable { opened = listOf(one) }.padding(start = 40.dp, end = 14.dp, top = 8.dp, bottom = 8.dp)) {
                                Text((one.date?.format(DateTimeFormatter.ofPattern("EEE HH:mm", Locale.UK))?.plus(" · ") ?: "") + one.gist.ifEmpty { one.subject },
                                    fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
        Text("Star an email or give it the Vault label in Proton and it shows here within the hour. Hidden senders are in Settings › Inbox.",
            fontSize = 12.sp, color = Muted, modifier = Modifier.padding(vertical = 12.dp))
    }
    opened?.let { g -> MailSheet(g, vault, snack) { opened = null; refresh++ } }
}

@Composable
private fun Opt(title: String, sub: String, onClick: () -> Unit) {
    HorizontalDivider(color = Outline)
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp)) {
        Text(title, fontSize = 14.sp)
        Text(sub, fontSize = 12.sp, color = Muted)
    }
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
    // Hidden senders and always-important senders (inbox-flags-groups-v1), undo any time.
    if (cur.hide.isNotEmpty() || cur.important.isNotEmpty()) {
        SectionLabel("Inbox · hidden and important")
        DeckCard {
            Column {
                Text("The box skips hidden senders. Nothing is deleted in Proton.", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
                cur.hide.forEach { r ->
                    HorizontalDivider(color = Outline)
                    Box(Modifier.padding(horizontal = 14.dp)) {
                        SettingRow(r.sender,
                            (if (r.alertsOnly) "Alerts only" else "Everything") + (r.since?.let { " · hidden ${it.format(DateTimeFormatter.ofPattern("d MMM", Locale.UK))}" } ?: "")) {
                            Text("Show again", color = Primary, fontSize = 13.sp, modifier = Modifier.clickable { put(InboxSort.unhide(cur, r.sender)) })
                        }
                    }
                }
                cur.important.forEach { who ->
                    HorizontalDivider(color = Outline)
                    Box(Modifier.padding(horizontal = 14.dp)) {
                        SettingRow("★ $who", "Always important") {
                            Text("Remove", color = Primary, fontSize = 13.sp, modifier = Modifier.clickable { put(InboxSort.setAlwaysImportant(cur, who, false)) })
                        }
                    }
                }
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
