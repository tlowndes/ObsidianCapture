package uk.tomlowndes.capture

import org.json.JSONObject
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

/** What an email needs from Tom, as the box sorted it. */
enum class MailKind(val emoji: String, val label: String) {
    REPLY("↩", "Reply"), BILL("£", "Bill"), JOB("💼", "Job"), DELIVERY("📦", "Delivery"), RECEIPT("🧾", "Receipt"), READ("📄", "Read");

    companion object {
        fun of(s: String?) = values().firstOrNull { it.name.equals(s?.trim(), true) } ?: READ
    }
}

/** One email from the digest note, with what Claude suggests saving from it. */
data class Mail(
    val id: String, val kind: MailKind, val from: String, val subject: String, val date: LocalDateTime?, val summary: String,
    val gist: String = "", val task: String? = null, val due: LocalDate? = null,
    val billName: String? = null, val amount: BigDecimal? = null, val billDue: LocalDate? = null,
    val company: String? = null, val jobStatus: String? = null, val shop: String? = null, val link: String? = null,
    /** Delivery slot like "07:00-13:00", and how often a bill repeats ("monthly"), when the box found them. */
    val window: String? = null, val repeat: String? = null,
)

/** The digest note the box writes: when it last looked, how many it read, and the emails it picked out. */
data class Digest(val checked: LocalDateTime?, val read: Int, val skipped: Int, val items: List<Mail>, val costMonth: Double? = null)

/**
 * Proton inbox digest (proton-inbox-v1). The Oracle box reads Starred and the Vault label through Proton Bridge, read-only,
 * and writes [PATH]: one line per email with a hidden JSON comment. The app never edits that note; what Tom did with each
 * email (saved, dismissed, snoozed) goes in [STATE], and the box's settings in [SETTINGS].
 */
object MailDigest {
    const val PATH = "99 - Meta/Email.md"
    /** Where the box wrote the digest before 1.38; read while the box still writes there. */
    const val OLD_PATH = "01 - Inbox/Email.md"
    /** The other box script (jarvis-server/PROTON-INBOX-PROMPT.md) writes this JSON and watches [CHECK_NOW]. */
    const val INBOX_JSON = "99 - Meta/Inbox/inbox.json"
    const val CHECK_NOW = "99 - Meta/Inbox/check-now"
    const val STATE = "99 - Meta/Email state.md"
    const val SETTINGS = "99 - Meta/Email settings.md"
    /** The box checks every 30 minutes by default; after two missed checks the card says paused. */
    const val PAUSED_MIN = 75L
    val NEEDS_YOU = setOf(MailKind.REPLY, MailKind.BILL, MailKind.JOB)
    /** State value for an email Tom starred in the app: pinned to the top until he handles it. */
    const val IMPORTANT = "important"
    private val TAG = Regex("<!--mail:(\\{.*\\})-->")

    fun parse(text: String): Digest {
        val lines = text.replace("\r", "").split("\n")
        fun fm(k: String) = Md.fmGet(lines, k)?.trim()?.trim('"')
        val items = lines.mapNotNull { l -> TAG.find(l)?.groupValues?.get(1)?.let { runCatching { mail(JSONObject(it)) }.getOrNull() } }
        return Digest(
            fm("checked")?.let { runCatching { LocalDateTime.parse(it.take(16)) }.getOrNull() },
            fm("read")?.toIntOrNull() ?: 0, fm("skipped")?.toIntOrNull() ?: 0, items.distinctBy { it.id }, fm("cost_month")?.toDoubleOrNull(),
        )
    }

    /**
     * The newest digest the box wrote: the note at [PATH] (or [OLD_PATH]), or [INBOX_JSON] from the other box script.
     * Whichever was checked last wins, so the status is right whichever script runs on the box.
     */
    fun load(v: Vault): Digest? {
        val md = (v.read(PATH) ?: v.read(OLD_PATH))?.let { parse(it) }
        val js = v.read(INBOX_JSON)?.let { runCatching { parseJson(it) }.getOrNull() }
        return listOfNotNull(md, js).maxByOrNull { it.checked ?: LocalDateTime.MIN }
    }

    /** Reads inbox.json: {"checked": "…Z", "counts": {…}, "items": [{id, from, subject, date, kind, summary, detail, task, bill, job, delivery}]}. */
    fun parseJson(text: String): Digest {
        val o = JSONObject(text)
        fun time(s: String?) = s?.takeIf { it.isNotBlank() }?.let {
            runCatching { java.time.OffsetDateTime.parse(it).atZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalDateTime() }.getOrNull()
                ?: runCatching { LocalDateTime.parse(it.take(16)) }.getOrNull()
        }
        fun JSONObject.str(k: String) = opt(k)?.takeIf { it != JSONObject.NULL }?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
        fun JSONObject.day(k: String) = str(k)?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }
        val arr = o.optJSONArray("items")
        val items = (0 until (arr?.length() ?: 0)).mapNotNull { i ->
            val m = arr!!.optJSONObject(i) ?: return@mapNotNull null
            val kind = m.str("kind")?.lowercase()
            if (kind == "skip") return@mapNotNull null
            val task = m.optJSONObject("task"); val bill = m.optJSONObject("bill"); val job = m.optJSONObject("job"); val del = m.optJSONObject("delivery")
            Mail(
                m.str("id") ?: return@mapNotNull null, MailKind.of(kind), m.str("from").orEmpty(), m.str("subject").orEmpty(), time(m.str("date")),
                m.str("detail") ?: m.str("summary").orEmpty(), m.str("summary").orEmpty(), task?.str("text"), task?.day("due") ?: del?.day("when"),
                bill?.str("name"), bill?.str("amount")?.replace("£", "")?.let { runCatching { BigDecimal(it) }.getOrNull() }, bill?.day("due"),
                job?.str("company"), job?.str("status")?.let { st -> Jobs.STATUSES.firstOrNull { it.equals(st, true) } },
                window = del?.str("window"), repeat = bill?.str("repeat"),
            )
        }
        val counts = o.optJSONObject("counts")
        return Digest(time(o.str("checked")), (counts?.optInt("handled") ?: 0) + items.size, counts?.optInt("skipped") ?: 0, items.distinctBy { it.id })
    }

    private fun mail(o: JSONObject): Mail? {
        fun s(k: String) = o.opt(k)?.takeIf { it != JSONObject.NULL }?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
        fun d(k: String) = s(k)?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }
        return Mail(
            s("id") ?: return null, MailKind.of(s("kind")), s("from").orEmpty(), s("subject").orEmpty(),
            s("date")?.let { runCatching { LocalDateTime.parse(it.take(16)) }.getOrNull() }, s("summary").orEmpty(),
            s("gist").orEmpty(), s("task"), d("due"), s("bill"), s("amount")?.replace("£", "")?.let { runCatching { BigDecimal(it) }.getOrNull() },
            d("bill_due"), s("company"), s("job_status")?.let { st -> Jobs.STATUSES.firstOrNull { it.equals(st, true) } }, s("shop"), s("link"),
            s("window"), s("repeat"),
        )
    }

    /** The line the box writes for [m] (kept here so tests and the box agree on the format). */
    fun line(m: Mail): String {
        val o = JSONObject().put("id", m.id).put("kind", m.kind.name.lowercase()).put("from", m.from).put("subject", m.subject)
            .put("summary", m.summary).put("gist", m.gist)
        m.date?.let { o.put("date", it.toString()) }
        m.task?.let { o.put("task", it) }; m.due?.let { o.put("due", it.toString()) }
        m.billName?.let { o.put("bill", it) }; m.amount?.let { o.put("amount", it.toPlainString()) }; m.billDue?.let { o.put("bill_due", it.toString()) }
        m.company?.let { o.put("company", it) }; m.jobStatus?.let { o.put("job_status", it) }; m.shop?.let { o.put("shop", it) }; m.link?.let { o.put("link", it) }
        m.window?.let { o.put("window", it) }; m.repeat?.let { o.put("repeat", it) }
        return "- ${m.kind.emoji} **${m.from}** · ${m.gist.ifEmpty { m.subject }} <!--mail:$o-->"
    }

    // ---- What Tom did with each email ----

    /** id → "done", "dismissed" or "snooze:yyyy-mm-dd". */
    fun readState(text: String?): Map<String, String> = text.orEmpty().replace("\r", "").split("\n").mapNotNull { l ->
        Regex("^- ([^ ]+) = (.+)$").find(l.trim())?.groupValues?.let { it[1] to it[2].trim() }
    }.toMap()

    fun state(v: Vault): Map<String, String> = readState(v.read(STATE))

    fun setState(v: Vault, id: String, value: String?) {
        val now = state(v).toMutableMap()
        if (value == null) now.remove(id) else now[id] = value
        val body = "---\ntype: email-state\n---\n\nWhat you did with each email in [[Email]]. Flight Deck keeps this; it's safe to clear.\n\n" +
            now.entries.joinToString("\n") { "- ${it.key} = ${it.value}" } + "\n"
        if (v.entry(STATE) == null) { v.mkdirs(STATE.substringBeforeLast('/')); VaultIo.write(v, v.create(STATE.substringBeforeLast('/'), STATE.substringAfterLast('/').removeSuffix(".md"), "md"), body) }
        else VaultIo.write(v, STATE, body)
    }

    fun open(m: Mail, state: Map<String, String>, today: LocalDate): Boolean = when (val s = state[m.id]) {
        null -> true
        "done", "dismissed" -> false
        IMPORTANT -> true
        else -> s.removePrefix("snooze:").let { runCatching { !today.isBefore(LocalDate.parse(it)) }.getOrDefault(true) }
    }

    /** Emails that need Tom (replies, bills, job replies), newest first. */
    fun needsYou(d: Digest, state: Map<String, String>, today: LocalDate): List<Mail> =
        d.items.filter { it.kind in NEEDS_YOU && open(it, state, today) }.sortedByDescending { it.date ?: LocalDateTime.MIN }

    fun deliveries(d: Digest, state: Map<String, String>, today: LocalDate): List<Mail> =
        d.items.filter { it.kind == MailKind.DELIVERY && open(it, state, today) && (it.due == null || !it.due.isBefore(today)) }

    fun handled(d: Digest, state: Map<String, String>): Int = d.items.count { state[it.id] == "done" || state[it.id] == "dismissed" }

    /** True when the box hasn't written for a while (it's off, or Bridge is signed out). */
    fun paused(d: Digest?, now: LocalDateTime): Boolean = d?.checked?.let { Duration.between(it, now).toMinutes() > PAUSED_MIN } ?: true

    /** The card heading line, e.g. "3 need you · 18:40". */
    fun heading(d: Digest, open: Int, now: LocalDateTime): String =
        if (paused(d, now)) "Inbox paused" + (d.checked?.let { " · last ${it.toLocalTime().toString().take(5)}" } ?: "")
        else "$open need you · ${d.checked!!.toLocalTime().toString().take(5)}"

    /** The job this email is about: same company in the Jobs notes, open first. */
    fun job(m: Mail, jobs: List<Job>): Job? {
        val c = m.company?.lowercase()?.trim()?.takeIf { it.length >= 2 } ?: return null
        return jobs.sortedBy { it.closed }.firstOrNull { j -> j.company.lowercase().let { it.isNotEmpty() && (c in it || it in c) } }
    }

    /** A bill as the sheet shows it: payee, amount, due date and repeat, each one Tom can change before saving. */
    data class BillDraft(val name: String, val amount: BigDecimal?, val due: LocalDate?, val repeat: BillRepeat)

    /** The bill the box pulled out of [m]; payee falls back to the sender, repeat to what the box said (or once). */
    fun billDraft(m: Mail): BillDraft = BillDraft(
        (m.billName ?: m.from.substringBefore(" <")).trim().ifEmpty { m.subject },
        m.amount, m.billDue ?: m.due,
        BillRepeat.values().firstOrNull { it.name.equals(m.repeat?.trim(), true) || it.label.equals(m.repeat?.trim(), true) } ?: BillRepeat.ONCE,
    )

    /** What the sheet offers to save: a task, a bill and a job status change. Only those that apply. */
    data class Save(val task: String?, val due: LocalDate?, val bill: Boolean, val job: Job?, val status: String?)

    fun suggest(m: Mail, jobs: List<Job>, today: LocalDate): Save {
        val job = if (m.kind == MailKind.JOB) job(m, jobs) else null
        val task = m.task ?: when (m.kind) {
            MailKind.REPLY -> "Reply to ${m.from.substringBefore(' ')}: ${m.subject.removePrefix("Re: ")}"
            MailKind.JOB -> "Reply to ${m.from.substringBefore(' ')} about ${job?.role ?: m.subject.removePrefix("Re: ")}"
            else -> null
        }
        val due = m.due ?: if (task != null) today.plusDays(1) else null
        return Save(task, due, m.kind == MailKind.BILL, job, m.jobStatus?.takeIf { job != null && !it.equals(job.status, true) })
    }

    /**
     * Saves the ticked parts: the task to Quick Task, the bill as a #bill task, the job's new status (with a line in its
     * log), then marks the email done. Returns how many things were saved.
     */
    fun save(v: Vault, w: VaultWriter, m: Mail, s: Save, task: Boolean, bill: Boolean, job: Boolean, today: LocalDate = LocalDate.now(), draft: BillDraft? = null): Int {
        var n = 0
        if (task && s.task != null) { w.addTask(s.task, s.due?.toString()); n++ }
        if (bill && s.bill) {
            val b = draft ?: billDraft(m)
            w.addTask(Bills.text(b.name, b.amount, b.repeat), b.due?.toString()); n++
        }
        if (job && s.job != null && s.status != null) {
            Jobs.setStatus(v, s.job.path, s.status)
            VaultIo.edit(v, s.job.path) { lines ->
                while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.lastIndex)
                lines.addAll(listOf("", "- $today · ✉ ${m.from}: ${m.summary.take(200)} → ${s.status}", ""))
            }
            n++
        }
        setState(v, m.id, "done")
        return n
    }

    // ---- Box settings (Settings › Inbox), read by the box script ----

    data class BoxSettings(
        val replies: Boolean = true, val bills: Boolean = true, val jobs: Boolean = true, val deliveries: Boolean = true,
        val every: Int = 30, val checkNow: LocalDateTime? = null,
        /** Senders Tom marked not important: the app hides them and the box skips them. */
        val hide: List<HideRule> = emptyList(),
        /** Senders Tom marked always important. */
        val important: List<String> = emptyList(),
    )

    fun settings(v: Vault): BoxSettings = v.read(SETTINGS)?.replace("\r", "")?.split("\n")?.let { l ->
        fun b(k: String, def: Boolean) = Md.fmGet(l, k)?.trim()?.lowercase()?.let { it == "true" } ?: def
        BoxSettings(
            b("replies", true), b("bills", true), b("jobs", true), b("deliveries", true),
            Md.fmGet(l, "every")?.trim()?.toIntOrNull()?.coerceIn(10, 240) ?: 30,
            Md.fmGet(l, "check_now")?.trim()?.let { runCatching { LocalDateTime.parse(it.take(16)) }.getOrNull() },
            InboxSort.hideRules(l), InboxSort.importantSenders(l),
        )
    } ?: BoxSettings()

    fun saveSettings(v: Vault, s: BoxSettings) {
        val body = "---\ntype: email-settings\nreplies: ${s.replies}\nbills: ${s.bills}\njobs: ${s.jobs}\ndeliveries: ${s.deliveries}\nevery: ${s.every}\n" +
            (s.checkNow?.let { "check_now: ${it.toString().take(16)}\n" } ?: "") + "---\n\nSettings › Inbox in Flight Deck. The box reads this before each check.\n" + InboxSort.listsBody(s)
        if (v.entry(SETTINGS) == null) { v.mkdirs(SETTINGS.substringBeforeLast('/')); VaultIo.write(v, v.create(SETTINGS.substringBeforeLast('/'), SETTINGS.substringAfterLast('/').removeSuffix(".md"), "md"), body) }
        else VaultIo.write(v, SETTINGS, body)
    }

    /**
     * Check now: saves [s] with check_now set (read by box/mailbox) and, when the other box script's folder is there,
     * drops the [CHECK_NOW] file it watches. Before this, the second half was missing, so Check now did nothing on that box.
     */
    fun checkNow(v: Vault, s: BoxSettings, now: LocalDateTime = LocalDateTime.now()) {
        saveSettings(v, s.copy(checkNow = now))
        val dir = CHECK_NOW.substringBeforeLast('/')
        if (v.entry(dir) != null && v.entry(CHECK_NOW) == null) VaultIo.write(v, v.create(dir, "check-now", ""), now.toString().take(16) + "\n")
    }

    /** Opens the Proton Mail app (or web) on the inbox; Bridge ids can't deep-link to one email. */
    const val PROTON_WEB = "https://mail.proton.me/u/0/inbox"
    const val PROTON_APP = "ch.protonmail.android"
}
