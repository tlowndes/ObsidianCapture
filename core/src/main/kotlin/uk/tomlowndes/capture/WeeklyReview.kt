package uk.tomlowndes.capture

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** A ticked task: its text and the ✅ date. */
data class DoneTask(val text: String, val done: LocalDate, val path: String)

/** What the Sunday review shows. [drop] is the one task suggested for dropping, if any. */
data class Review(
    val done: List<DoneTask>,
    val carried: List<TaskItem>,
    val drop: TaskItem?,
    val nextTasks: List<TaskItem>,
    val nextBills: List<Bill>
)

/** The weekly review: what got done, what carried over, one thing to drop, and next week at a glance. */
object WeeklyReview {
    private val DONE_LINE = Regex("^\\s*- \\[[xX]\\] (.*)$")
    private val DONE_DATE = Regex("✅\\s*(\\d{4}-\\d{2}-\\d{2})")

    /** Ticked `#task` lines in [lines] with a ✅ date on or after [since]. */
    fun doneIn(lines: List<String>, path: String, since: LocalDate): List<DoneTask> = lines.mapNotNull { l ->
        val body = DONE_LINE.matchEntire(l.trimEnd())?.groupValues?.get(1) ?: return@mapNotNull null
        if (!Regex("(^|\\s)#task(/|\\s|$)").containsMatchIn(body)) return@mapNotNull null
        val d = DONE_DATE.find(body)?.groupValues?.get(1)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return@mapNotNull null
        if (d.isBefore(since)) null else DoneTask(TaskRules.cleanText(body).ifBlank { body.trim() }, d, path)
    }

    /** Done tasks from the last 7 days, reading only notes changed in that time. */
    fun done(v: Vault, today: LocalDate): List<DoneTask> {
        val since = today.minusDays(6)
        val sinceMs = since.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val templates = VaultScan.templateFolders(v)
        return v.notes { VaultTasks.skipped(it, templates) }
            .filter { it.modified == 0L || it.modified >= sinceMs }
            .filter { it.path == VaultTasks.QUICK_TASK || !VaultTasks.skipped(it.path, templates) }
            .flatMap { n -> runCatching { doneIn(VaultIo.read(v, n.path).split("\n"), n.path, since) }.getOrDefault(emptyList()) }
            .sortedByDescending { it.done }
    }

    /** Next Monday (tomorrow on a Sunday): where Move puts a carried-over task. */
    fun nextMonday(today: LocalDate): LocalDate = today.with(TemporalAdjusters.next(DayOfWeek.MONDAY))

    fun build(done: List<DoneTask>, open: List<TaskItem>, today: LocalDate): Review {
        val weekAgo = today.minusDays(7)
        val overdue = open.filter { it.due != null && it.due.isBefore(today) && !Deck.isBill(it) }.sortedBy { it.due }
        val carried = overdue.filter { !it.due!!.isBefore(weekAgo) }
        // The one to drop: the task that's been overdue longest, if it's over a week.
        val drop = overdue.firstOrNull { it.due!!.isBefore(weekAgo) }
        val mon = nextMonday(today)
        val sun = mon.plusDays(6)
        val inNextWeek = { d: LocalDate? -> d != null && !d.isBefore(mon) && !d.isAfter(sun) }
        return Review(
            done = done,
            carried = carried,
            drop = drop,
            nextTasks = open.filter { inNextWeek(it.due) && !Deck.isBill(it) }.sortedBy { it.due },
            nextBills = open.filter { inNextWeek(it.due) && Deck.isBill(it) }.sortedBy { it.due }.map { TaskViews.bill(it) }
        )
    }

    /** The task line marked cancelled, as the Tasks plugin does: `- [-] … ❌ date`. */
    fun cancelled(raw: String, today: LocalDate): String =
        raw.replaceFirst("- [ ]", "- [-]").let { if (it.contains("❌")) it else it.trimEnd() + " ❌ $today" }
}
