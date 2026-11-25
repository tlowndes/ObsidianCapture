package uk.tomlowndes.capture

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** One ticket this device printed: its [id], the note it lives in, the task's line as [TicketRail.body] reads it, and when. */
data class Printed(val id: String, val path: String, val body: String, val at: LocalDateTime)

/**
 * The app's own note of what it printed (printed-marks-v1). A task counts as printed once its `🆔` is on its line,
 * but that save can fail (a sync landing, the phone's file swap). This list lets the app put a missing `🆔` back
 * next time it looks, so a printed task never comes back as unprinted, and says when each ticket printed.
 */
object PrintLog {
    /** Entries older than this are dropped. */
    const val KEEP_DAYS = 60L
    private val HM = DateTimeFormatter.ofPattern("HH:mm")

    fun of(t: TaskItem, id: String, at: LocalDateTime) = Printed(id.lowercase(), t.path, TicketRail.body(t.raw), at)

    /** [log] with [new] added; an id printed again keeps its newest time. */
    fun add(log: List<Printed>, new: List<Printed>): List<Printed> {
        val ids = new.map { it.id }.toSet()
        return log.filter { it.id !in ids } + new
    }

    /** [log] without tickets that are cleared (their id is on a ticked line) or older than [KEEP_DAYS]. */
    fun prune(log: List<Printed>, done: List<TickedTask>, now: LocalDateTime): List<Printed> {
        val cleared = done.mapNotNull { TicketRail.railId(it.raw) }.toSet()
        val cut = now.minusDays(KEEP_DAYS)
        return log.filter { it.id !in cleared && it.at.isAfter(cut) }
    }

    /**
     * Open tasks that were printed but lost their `🆔` (or never got it): each with the id to put back. A task matches
     * on its note and its words; an id already on another open task isn't handed out twice.
     */
    fun missing(log: List<Printed>, open: List<TaskItem>): List<Pair<TaskItem, String>> {
        val inUse = open.mapNotNull { TicketRail.railId(it.raw) }.toMutableSet()
        val out = mutableListOf<Pair<TaskItem, String>>()
        val seen = mutableSetOf<String>()
        for (t in open.distinctBy { it.path to it.raw }) {
            if (TicketRail.railId(t.raw) != null) continue
            val body = TicketRail.body(t.raw)
            val p = log.lastOrNull { it.path == t.path && it.body == body && it.id !in inUse } ?: continue
            if (!seen.add(t.path + "\u0000" + t.raw)) continue
            inUse += p.id
            out += t to p.id
        }
        return out
    }

    /** [open] with [missing]'s ids on their lines, for showing straight away while the vault catches up. */
    fun marked(open: List<TaskItem>, missing: List<Pair<TaskItem, String>>): List<TaskItem> {
        if (missing.isEmpty()) return open
        val m = missing.associate { (t, id) -> (t.path to t.raw) to id }
        return open.map { t -> m[t.path to t.raw]?.let { t.copy(raw = TicketRail.withId(t.raw, it)) } ?: t }
    }

    /** When the ticket with [id] printed, or null when this device didn't print it. */
    fun at(log: List<Printed>, id: String): LocalDateTime? = log.lastOrNull { it.id == id.lowercase() }?.at

    /** "printed today 07:02", "printed Mon 21:59", "printed 12 Nov". */
    fun words(at: LocalDateTime, today: LocalDate): String {
        val d = at.toLocalDate()
        val days = java.time.temporal.ChronoUnit.DAYS.between(d, today)
        return when {
            days == 0L -> "printed today ${at.format(HM)}"
            days == 1L -> "printed yesterday ${at.format(HM)}"
            days in 2..6 -> "printed ${d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.UK)} ${at.format(HM)}"
            else -> "printed ${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.SHORT, Locale.UK)}"
        }
    }

    /** The short stamp on a rail row: "PRINTED 07:02" today, else "PRINTED MON" or "PRINTED 12 NOV". */
    fun stamp(at: LocalDateTime, today: LocalDate): String {
        val d = at.toLocalDate()
        val days = java.time.temporal.ChronoUnit.DAYS.between(d, today)
        val s = when {
            days == 0L -> at.format(HM)
            days in 1..6 -> d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.UK)
            else -> "${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.SHORT, Locale.UK)}"
        }
        return "PRINTED ${s.uppercase(Locale.UK)}"
    }

    /** The tag on a printed task's row: "🧾 PRINTED", or "🧾 FRI" for one printed ahead of its day. */
    fun tag(t: TaskItem, today: LocalDate): String =
        if (t.due != null && t.due.isAfter(today)) "🧾 ${TicketRail.dayTag(t.due, today)}" else "🧾 PRINTED"

    fun encode(log: List<Printed>): String = JSONArray().apply {
        log.forEach { put(JSONObject().put("id", it.id).put("p", it.path).put("b", it.body).put("at", it.at.toString())) }
    }.toString()

    fun decode(s: String?): List<Printed> = runCatching {
        val a = JSONArray(s ?: return emptyList())
        (0 until a.length()).mapNotNull { i ->
            val o = a.getJSONObject(i)
            runCatching { Printed(o.getString("id"), o.getString("p"), o.getString("b"), LocalDateTime.parse(o.getString("at"))) }.getOrNull()
        }
    }.getOrElse { emptyList() }
}
