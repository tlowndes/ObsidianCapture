package uk.tomlowndes.capture

import org.json.JSONObject
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/** "Not important" for a sender: everything from them, or only their alerts (anything that isn't a real reply). */
data class HideRule(val sender: String, val alertsOnly: Boolean = false, val since: LocalDate? = null)

/** One run of emails from the same sender, newest first. */
data class SenderGroup(val sender: String, val mails: List<Mail>) {
    val first: Mail get() = mails.first()
}

/** The Inbox page's sections, in the order they show (inbox-flags-groups-v1). */
enum class InboxSection(val label: String) {
    IMPORTANT("★ Important"), BILLS("£ Bills"), DELIVERIES("📦 Deliveries"), NEEDS_YOU("Needs you"),
    RECEIPTS("🧾 Receipts"), READ("Worth knowing"), HANDLED("Handled"), HIDDEN("Hidden")
}

/** An event Tom asked to add to Proton Calendar; the box emails him an invite for it, then ticks it off. */
data class CalendarAsk(
    val id: String, val title: String, val date: LocalDate, val start: String? = null, val end: String? = null,
    val sent: Boolean = false,
    /** Where and notes, for an event added in the app (events-to-proton-v1). */
    val location: String? = null, val note: String? = null,
)

/**
 * Inbox flags, sender groups and sections (inbox-flags-groups-v1). Important and hidden senders live in
 * "Email settings.md" so the box and both apps read the same lists; important single emails live in "Email state.md".
 */
object InboxSort {
    const val CALENDAR = "99 - Meta/Inbox/calendar-out.md"
    private const val HIDE_HEAD = "## Hidden senders"
    private const val IMPORTANT_HEAD = "## Always important"
    private val CAL_TAG = Regex("<!--cal:(\\{.*\\})-->")

    /** Who an email is from, for matching and grouping: the name without any address, lower case. */
    fun key(from: String): String = from.substringBefore(" <").trim().trim('"').lowercase().replace(Regex("\\s+"), " ")

    fun name(from: String): String = from.substringBefore(" <").trim().trim('"')

    fun hidden(m: Mail, s: MailDigest.BoxSettings): Boolean =
        s.hide.any { key(it.sender) == key(m.from) && (!it.alertsOnly || m.kind != MailKind.REPLY) }

    fun important(m: Mail, s: MailDigest.BoxSettings, state: Map<String, String>): Boolean =
        state[m.id] == MailDigest.IMPORTANT || s.important.any { key(it) == key(m.from) }

    /** Folds emails from the same sender into one group each, keeping the order of each sender's newest email. */
    fun groups(mails: List<Mail>): List<SenderGroup> =
        mails.sortedByDescending { it.date ?: LocalDateTime.MIN }.groupBy { key(it.from) }.values.map { SenderGroup(name(it.first().from), it) }

    /** Every section with its emails, empty ones included; each email shows in one section only. */
    fun sections(d: Digest, state: Map<String, String>, s: MailDigest.BoxSettings, today: LocalDate): Map<InboxSection, List<Mail>> {
        val out = linkedMapOf<InboxSection, MutableList<Mail>>()
        InboxSection.values().forEach { out[it] = mutableListOf() }
        d.items.sortedByDescending { it.date ?: LocalDateTime.MIN }.forEach { m ->
            val sec = when {
                hidden(m, s) && !important(m, s, state) -> InboxSection.HIDDEN
                !MailDigest.open(m, state, today) -> InboxSection.HANDLED
                important(m, s, state) -> InboxSection.IMPORTANT
                m.kind == MailKind.BILL -> InboxSection.BILLS
                m.kind == MailKind.DELIVERY -> if (m.due != null && m.due.isBefore(today)) InboxSection.HANDLED else InboxSection.DELIVERIES
                m.kind in MailDigest.NEEDS_YOU -> InboxSection.NEEDS_YOU
                m.kind == MailKind.RECEIPT -> InboxSection.RECEIPTS
                else -> InboxSection.READ
            }
            out.getValue(sec).add(m)
        }
        out[InboxSection.DELIVERIES]?.sortBy { it.due ?: LocalDate.MAX }
        out[InboxSection.BILLS]?.sortBy { it.billDue ?: it.due ?: LocalDate.MAX }
        return out
    }

    /** What's left in Needs you, Important and Bills: the count the Deck and the sidebar show. */
    fun needsCount(sec: Map<InboxSection, List<Mail>>): Int =
        listOf(InboxSection.IMPORTANT, InboxSection.BILLS, InboxSection.NEEDS_YOU).sumOf { sec[it].orEmpty().size }

    /** The total of the bills that have an amount, or null when none do. */
    fun billsTotal(bills: List<Mail>): BigDecimal? = bills.mapNotNull { it.amount }.takeIf { it.isNotEmpty() }?.fold(BigDecimal.ZERO, BigDecimal::add)

    // ---- Settings lists ----

    private fun list(lines: List<String>, head: String): List<String> {
        val i = lines.indexOfFirst { it.trim().equals(head, true) }
        if (i < 0) return emptyList()
        return lines.drop(i + 1).takeWhile { !it.startsWith("## ") }.mapNotNull { l -> l.trim().takeIf { it.startsWith("- ") }?.removePrefix("- ")?.trim() }
    }

    /** "- Indeed · everything · 2026-11-16" or "- LinkedIn · alerts · 2026-11-09". */
    fun hideRules(lines: List<String>): List<HideRule> = list(lines, HIDE_HEAD).mapNotNull { l ->
        val p = l.split(" · ").map { it.trim() }
        val who = name(p[0]).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        HideRule(who, p.getOrNull(1)?.lowercase() == "alerts", p.getOrNull(2)?.let { runCatching { LocalDate.parse(it) }.getOrNull() })
    }.distinctBy { key(it.sender) }

    fun importantSenders(lines: List<String>): List<String> = list(lines, IMPORTANT_HEAD).map { name(it.substringBefore(" · ")) }.filter { it.isNotEmpty() }.distinctBy { key(it) }

    fun listsBody(s: MailDigest.BoxSettings): String {
        val sb = StringBuilder()
        if (s.hide.isNotEmpty()) {
            sb.append("\n$HIDE_HEAD\n\nNot important: the box skips these and Flight Deck hides them. Nothing is deleted in Proton.\n\n")
            s.hide.forEach { r -> sb.append("- ${r.sender} · ${if (r.alertsOnly) "alerts" else "everything"}${r.since?.let { " · $it" } ?: ""}\n") }
        }
        if (s.important.isNotEmpty()) {
            sb.append("\n$IMPORTANT_HEAD\n\n")
            s.important.forEach { sb.append("- $it\n") }
        }
        return sb.toString()
    }

    fun hide(s: MailDigest.BoxSettings, from: String, alertsOnly: Boolean, today: LocalDate): MailDigest.BoxSettings =
        s.copy(hide = s.hide.filter { key(it.sender) != key(from) } + HideRule(name(from), alertsOnly, today), important = s.important.filter { key(it) != key(from) })

    fun unhide(s: MailDigest.BoxSettings, sender: String): MailDigest.BoxSettings = s.copy(hide = s.hide.filter { key(it.sender) != key(sender) })

    fun setAlwaysImportant(s: MailDigest.BoxSettings, from: String, on: Boolean): MailDigest.BoxSettings =
        if (on) s.copy(important = s.important.filter { key(it) != key(from) } + name(from), hide = s.hide.filter { key(it.sender) != key(from) })
        else s.copy(important = s.important.filter { key(it) != key(from) })

    // ---- Proton Calendar (route B: the box emails Tom an invite) ----

    /** What "Add to Proton Calendar" adds for [m]: a bill's pay-by day, a delivery's day and slot, or the email's date. */
    fun calendarAsk(m: Mail, bill: MailDigest.BillDraft? = null): CalendarAsk? {
        val (title, date) = when (m.kind) {
            MailKind.BILL -> {
                val b = bill ?: MailDigest.billDraft(m)
                "Pay ${b.name}" + (b.amount?.let { " " + TaskViews.money(it) } ?: "") to b.due
            }
            MailKind.DELIVERY -> "📦 ${name(m.from)}: ${m.gist.ifEmpty { m.subject }.take(60)}" to m.due
            else -> (m.task ?: m.gist.ifEmpty { m.subject }).take(80) to m.due
        }
        date ?: return null
        val w = m.window?.let { Regex("(\\d{1,2}:\\d{2})\\s*[-–]\\s*(\\d{1,2}:\\d{2})").find(it) }
        return CalendarAsk(m.id, title, date, w?.groupValues?.get(1)?.padStart(5, '0'), w?.groupValues?.get(2)?.padStart(5, '0'))
    }

    fun parseCalendar(text: String?): List<CalendarAsk> = text.orEmpty().replace("\r", "").split("\n").mapNotNull { l ->
        val o = CAL_TAG.find(l)?.groupValues?.get(1)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return@mapNotNull null
        runCatching {
            CalendarAsk(o.getString("id"), o.getString("title"), LocalDate.parse(o.getString("date")),
                o.optString("start").takeIf { it.isNotBlank() }, o.optString("end").takeIf { it.isNotBlank() }, l.trim().startsWith("- [x]"),
                o.optString("location").takeIf { it.isNotBlank() }, o.optString("note").takeIf { it.isNotBlank() })
        }.getOrNull()
    }

    fun calendarLine(a: CalendarAsk): String {
        val o = JSONObject().put("id", a.id).put("title", a.title).put("date", a.date.toString())
        a.start?.let { o.put("start", it) }; a.end?.let { o.put("end", it) }
        a.location?.let { o.put("location", it) }; a.note?.let { o.put("note", it) }
        return "- [${if (a.sent) "x" else " "}] 📅 ${a.title} · ${a.date}${a.start?.let { " $it" } ?: ""}${a.end?.let { "–$it" } ?: ""} <!--cal:$o-->"
    }

    fun calendar(v: Vault): List<CalendarAsk> = parseCalendar(v.read(CALENDAR))

    /** Adds [a] to the box's queue (once per email). The box sends the invite on its next run and ticks the line. */
    fun askCalendar(v: Vault, a: CalendarAsk) {
        val old = calendar(v)
        if (old.any { it.id == a.id }) return
        val now = old + a
        val body = "---\ntype: calendar-out\n---\n\nEvents for Proton Calendar. The box emails you an invite for each unticked line, then ticks it.\n\n" +
            now.joinToString("\n") { calendarLine(it) } + "\n"
        val dir = CALENDAR.substringBeforeLast('/')
        if (v.entry(CALENDAR) == null) { v.mkdirs(dir); VaultIo.write(v, v.create(dir, CALENDAR.substringAfterLast('/').removeSuffix(".md"), "md"), body) }
        else VaultIo.write(v, CALENDAR, body)
    }

    /** "Sent ✓", "Sending…" or null when it hasn't been asked for. */
    fun calendarStatus(asks: List<CalendarAsk>, id: String): String? =
        asks.filter { it.id == id }.let { l -> if (l.any { it.sent }) "In Proton Calendar ✓" else if (l.isNotEmpty()) "Invite on its way…" else null }
}
