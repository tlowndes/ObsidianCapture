package uk.tomlowndes.capture

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Parsing and date maths for Obsidian Tasks-plugin lines (📅 due, ⏳ scheduled, 🛫 start, 🔁 recurrence). */
object TaskRules {
    val DUE = Regex("📅\\s*(\\d{4}-\\d{2}-\\d{2})")
    private val SCHEDULED = Regex("⏳\\s*(\\d{4}-\\d{2}-\\d{2})")
    private val START = Regex("🛫\\s*(\\d{4}-\\d{2}-\\d{2})")
    private val RECUR = Regex("🔁\\s*(.*?)(?=\\s*(?:📅|⏳|🛫|✅|➕|❌|⏱|🔺|⏫|🔼|🔽|⏬|\\[|$))")
    private val NOISE = Regex(
        "(?:📅|⏳|🛫|➕|✅|❌)\\s*\\d{4}-\\d{2}-\\d{2}|🔁\\s*.*?(?=\\s*(?:📅|⏳|🛫|✅|➕|❌|⏱|🔺|⏫|🔼|🔽|⏬|\\[|$))|⏱\\s*\\d+\\s*m(?:in)?\\b|[🔺⏫🔼🔽⏬]|\\[\\w+:: [^\\]]*\\]|#task"
    )

    fun due(line: String): LocalDate? =
        DUE.find(line)?.groupValues?.get(1)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    fun recurrence(line: String): String? = RECUR.find(line)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    /** Task description with all plugin markers and fields stripped out. */
    fun cleanText(body: String): String = body.replace(NOISE, "").replace(Regex("\\s+"), " ").trim()

    /** Human label for a recurrence rule, e.g. "every week". */
    fun recurrenceLabel(rule: String) = rule.trim()

    /** Next date for [rule], or null if the rule isn't one we understand. */
    fun nextDate(rule: String, due: LocalDate?, completed: LocalDate): LocalDate? {
        var r = rule.trim().lowercase()
        val whenDone = r.endsWith("when done")
        if (whenDone) r = r.removeSuffix("when done").trim()
        val base = if (whenDone) completed else (due ?: completed)

        if (r == "every weekday") {
            var d = base.plusDays(1)
            while (d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY) d = d.plusDays(1)
            return d
        }
        DayOfWeek.values().firstOrNull { r == "every ${it.name.lowercase()}" }?.let { dow ->
            var d = base.plusDays(1)
            while (d.dayOfWeek != dow) d = d.plusDays(1)
            return d
        }
        val m = Regex("^every (\\d+ )?(day|week|month|year)s?$").matchEntire(r) ?: return null
        val n = m.groupValues[1].trim().ifEmpty { "1" }.toLong()
        return when (m.groupValues[2]) {
            "day" -> base.plusDays(n)
            "week" -> base.plusWeeks(n)
            "month" -> base.plusMonths(n)
            else -> base.plusYears(n)
        }
    }

    /** The follow-up line for a recurring task that was just completed, or null if it doesn't recur. */
    fun nextOccurrence(line: String, completed: LocalDate): String? {
        val rule = recurrence(line) ?: return null
        val oldDue = due(line)
        val next = nextDate(rule, oldDue, completed) ?: return null
        val shift = if (oldDue != null) ChronoUnit.DAYS.between(oldDue, next) else 0L

        var out = line
        out = if (oldDue != null) out.replace(DUE, "📅 $next") else out.trimEnd() + " 📅 $next"
        if (shift != 0L) {
            out = out.replace(SCHEDULED) { "⏳ ${LocalDate.parse(it.groupValues[1]).plusDays(shift)}" }
            out = out.replace(START) { "🛫 ${LocalDate.parse(it.groupValues[1]).plusDays(shift)}" }
        }
        return out
    }

    private val ESTIMATE = Regex("\\s*⏱\\s*\\d+\\s*m(?:in)?\\b")

    /** [line] with its time estimate set to [minutes] (`⏱ 30m`, added before any date) or removed for null. */
    fun withMinutes(line: String, minutes: Int?): String {
        val bare = line.replace(ESTIMATE, "")
        if (minutes == null) return bare
        val at = Regex("\\s(📅|⏳|🛫|🔁)").find(bare)?.range?.first ?: bare.trimEnd().length
        return bare.substring(0, at).trimEnd() + " ⏱ ${minutes}m" + bare.substring(at)
    }

    /** "15m", "1h", "1h 30m". */
    fun minutesLabel(m: Int) = when {
        m < 60 -> "${m}m"
        m % 60 == 0 -> "${m / 60}h"
        else -> "${m / 60}h ${m % 60}m"
    }

    /** [line] with its due date set to [date] (added, changed or, for null, removed). */
    fun withDue(line: String, date: LocalDate?): String = when {
        date == null -> line.replace(Regex("\\s*📅\\s*\\d{4}-\\d{2}-\\d{2}"), "").trimEnd()
        DUE.containsMatchIn(line) -> line.replace(DUE, "📅 $date")
        else -> line.trimEnd() + " 📅 $date"
    }
}
