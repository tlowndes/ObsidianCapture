package uk.tomlowndes.capture

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * A recurrence the repeat picker can show. [monthDay] is 1–31, or -1 for the last day of the month.
 * [rule] is the Tasks-plugin wording (`every week on Tuesday`, `every month on the 1st`).
 */
data class RepeatSpec(
    val every: Int = 1,
    val unit: String = "week",
    val weekday: DayOfWeek? = null,
    val monthDay: Int? = null,
    val weekdays: Boolean = false,
    val whenDone: Boolean = false,
) {
    fun rule(): String = TaskRules.repeatRule(every, unit, weekday, weekdays, whenDone, monthDay)
}

/** Parsing and date maths for Obsidian Tasks-plugin lines (📅 due, ⏳ scheduled, 🛫 start, 🔁 recurrence). */
object TaskRules {
    val DUE = Regex("📅\\s*(\\d{4}-\\d{2}-\\d{2})")
    private val SCHEDULED = Regex("⏳\\s*(\\d{4}-\\d{2}-\\d{2})")
    private val START = Regex("🛫\\s*(\\d{4}-\\d{2}-\\d{2})")
    private val RECUR = Regex("🔁\\s*(.*?)(?=\\s*(?:📅|⏳|🛫|✅|➕|❌|⏱|🔺|⏫|🔼|🔽|⏬|\\[|$))")
    private val NOISE = Regex(
        "(?:📅|⏳|🛫|➕|✅|❌)\\s*\\d{4}-\\d{2}-\\d{2}|🔁\\s*.*?(?=\\s*(?:📅|⏳|🛫|✅|➕|❌|⏱|🔺|⏫|🔼|🔽|⏬|\\[|$))|⏱\\s*\\d+\\s*m(?:in)?\\b|[🔺⏫🔼🔽⏬]|\\[\\w+:: [^\\]]*\\]|#task(?![\\w/])"
    )
    private val MINUTES = Regex("⏱\\s*(\\d+)\\s*m(?:in)?\\b")
    private val CHECKBOX = Regex("^(\\s*- \\[[^\\]]*\\])\\s*")
    /** Markers on a task line, in the order they should be told apart. Recurrence stops at the next marker. */
    private val PIECE = Regex(
        "📅\\s*\\d{4}-\\d{2}-\\d{2}" +
            "|🔁\\s*\\S.*?(?=\\s*(?:📅|⏳|🛫|✅|➕|❌|⏱|🔺|⏫|🔼|🔽|⏬|\\[|#task(?![\\w/])|$))" +
            "|⏱\\s*\\d+\\s*m(?:in)?\\b" +
            "|(?:⏳|🛫|➕|✅|❌)\\s*\\d{4}-\\d{2}-\\d{2}" +
            "|[🔺⏫🔼🔽⏬]" +
            "|\\[\\w+:: [^\\]]*\\]" +
            "|#task(?![\\w/])"
    )

    fun due(line: String): LocalDate? =
        DUE.find(line)?.groupValues?.get(1)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    fun recurrence(line: String): String? = RECUR.find(line)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    /** Task description with all plugin markers and fields stripped out. */
    fun cleanText(body: String): String = body.replace(NOISE, "").replace(Regex("\\s+"), " ").trim()

    /** Minutes from `⏱ 15m` / `⏱ 30min`, or null. */
    fun minutes(line: String): Int? = MINUTES.find(line)?.groupValues?.get(1)?.toIntOrNull()

    /** "1st", "2nd", "3rd", "11th". */
    fun ordinal(n: Int): String {
        val suffix = when {
            n % 100 in 11..13 -> "th"
            n % 10 == 1 -> "st"
            n % 10 == 2 -> "nd"
            n % 10 == 3 -> "rd"
            else -> "th"
        }
        return "$n$suffix"
    }

    /** Human label for a recurrence rule, e.g. "every week". */
    fun recurrenceLabel(rule: String) = rule.trim()

    /**
     * A rule [nextDate] understands, in Tasks-plugin wording.
     * A weekly [day] is `every week on Tuesday` (or `every 2 weeks on Tuesday`).
     * A [monthDay] (1–31, or -1 for the last day) is `every month on the 1st`.
     * `when done` counts from the tick rather than the due date.
     * Older lines written as `every tuesday` still parse; this writes the Tasks form.
     */
    fun repeatRule(
        every: Int, unit: String, day: DayOfWeek? = null, weekdays: Boolean = false,
        whenDone: Boolean = false, monthDay: Int? = null,
    ): String {
        val n = every.coerceAtLeast(1)
        val base = when {
            weekdays -> "every weekday"
            unit == "week" && day != null -> weekPhrase(n, day)
            unit == "month" && monthDay != null && monthDay != 0 -> monthPhrase(n, monthDay)
            day != null && unit != "month" -> weekPhrase(n, day)
            n <= 1 -> "every $unit"
            else -> "every $n ${unit}s"
        }
        return if (whenDone) "$base when done" else base
    }

    /** The repeat picker state for [rule], or null when it isn't one the picker can show. */
    fun parseRepeat(rule: String): RepeatSpec? {
        val (body, whenDone) = splitWhenDone(rule) ?: return null
        if (body == "every weekday") return RepeatSpec(weekdays = true, whenDone = whenDone)
        namedDay(body)?.let { return RepeatSpec(unit = "week", weekday = it, whenDone = whenDone) }
        parseWeekOn(body)?.let { (n, dow) -> return RepeatSpec(n, "week", weekday = dow, whenDone = whenDone) }
        parseMonthOn(body)?.let { (n, day) -> return RepeatSpec(n, "month", monthDay = day, whenDone = whenDone) }
        val m = SIMPLE.matchEntire(body) ?: return null
        val n = m.groupValues[1].trim().ifEmpty { "1" }.toIntOrNull() ?: return null
        if (n < 1) return null
        return RepeatSpec(n, m.groupValues[2], whenDone = whenDone)
    }

    /** Next date for [rule], or null if the rule isn't one we understand. */
    fun nextDate(rule: String, due: LocalDate?, completed: LocalDate): LocalDate? {
        val (body, whenDone) = splitWhenDone(rule) ?: return null
        val base = if (whenDone) completed else (due ?: completed)

        if (body == "every weekday") {
            var d = base.plusDays(1)
            while (d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY) d = d.plusDays(1)
            return d
        }
        namedDay(body)?.let { return nextWeekday(base, it, 1) }
        parseWeekOn(body)?.let { (n, dow) -> return nextWeekday(base, dow, n.toLong()) }
        parseMonthOn(body)?.let { (n, day) -> return nextMonthDay(base, day, n.toLong()) }
        val m = SIMPLE.matchEntire(body) ?: return null
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

    /** Time chips offered when adding or editing a task. */
    val ESTIMATE_CHOICES = listOf(5, 15, 30, 60, 120)

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

    /**
     * [raw] with a new description, due date, recurrence and estimate.
     * The checkbox (so a done or cancelled task stays that way), the indent, and any marker this editor
     * doesn't change — priority, scheduled, start, done, created, cancelled, `[field:: ]` — stay where they are.
     * A null due, recurrence or estimate removes that marker. Passing the line's current values back returns [raw].
     */
    fun editedLine(raw: String, text: String, due: LocalDate?, recurrence: String?, minutes: Int?): String {
        val words = text.trim().replace(Regex("\\s+"), " ")
        val rule = recurrence?.trim()?.ifBlank { null }
        if (words == descriptionOf(raw) && due == this.due(raw) && rule == this.recurrence(raw) && minutes == this.minutes(raw)) {
            return raw
        }
        val cr = raw.endsWith("\r")
        val line = if (cr) raw.dropLast(1) else raw
        val box = CHECKBOX.find(line) ?: return raw
        val pieces = piecesOf(line.substring(box.range.last + 1))
        val out = mutableListOf<String>()
        var textWritten = false
        var seenDue = false
        var seenRecur = false
        var seenMin = false
        var textAt = -1
        for (p in pieces) {
            when (p.slot) {
                Slot.TEXT -> if (!textWritten) {
                    textAt = out.size
                    if (words.isNotEmpty()) out += words
                    textWritten = true
                }
                Slot.DUE -> {
                    seenDue = true
                    if (due != null) out += "📅 $due"
                }
                Slot.RECUR -> {
                    seenRecur = true
                    if (rule != null) out += "🔁 $rule"
                }
                Slot.MINUTES -> {
                    seenMin = true
                    if (minutes != null) out += "⏱ ${minutes}m"
                }
                Slot.KEEP -> out += p.value
            }
        }
        if (!textWritten) {
            textAt = out.indexOfLast { it.startsWith("#task") }.let { if (it < 0) 0 else it + 1 }
            if (words.isNotEmpty()) out.add(textAt, words)
        }
        val extra = buildList {
            if (minutes != null && !seenMin) add("⏱ ${minutes}m")
            if (rule != null && !seenRecur) add("🔁 $rule")
            if (due != null && !seenDue) add("📅 $due")
        }
        if (extra.isNotEmpty()) {
            val at = if (words.isNotEmpty() && textAt >= 0) textAt + 1 else textAt.coerceAtLeast(0)
            out.addAll(at.coerceIn(0, out.size), extra)
        }
        val body = out.joinToString(" ")
        return box.groupValues[1] + (if (body.isEmpty()) "" else " $body") + if (cr) "\r" else ""
    }

    private enum class Slot { TEXT, DUE, RECUR, MINUTES, KEEP }
    private data class Piece(val slot: Slot, val value: String)

    private fun piecesOf(body: String): List<Piece> {
        val out = mutableListOf<Piece>()
        var i = 0
        for (m in PIECE.findAll(body)) {
            if (m.range.first > i) addWords(out, body.substring(i, m.range.first))
            val v = m.value.trim()
            val slot = when {
                v.startsWith("📅") -> Slot.DUE
                v.startsWith("🔁") -> Slot.RECUR
                v.startsWith("⏱") -> Slot.MINUTES
                else -> Slot.KEEP
            }
            out += Piece(slot, v)
            i = m.range.last + 1
        }
        if (i < body.length) addWords(out, body.substring(i))
        return out
    }

    private fun addWords(out: MutableList<Piece>, gap: String) {
        val words = gap.trim()
        if (words.isNotEmpty()) out += Piece(Slot.TEXT, words)
    }

    private fun descriptionOf(raw: String): String {
        val line = raw.removeSuffix("\r")
        val box = CHECKBOX.find(line) ?: return cleanText(line)
        return cleanText(line.substring(box.range.last + 1))
    }

    private val SIMPLE = Regex("^every (\\d+ )?(day|week|month|year)s?$")
    private val WEEK_ON = Regex("^every (?:(\\d+) )?weeks? on ([a-z]+)$")
    private val MONTH_ON = Regex("^every (?:(\\d+) )?months? on the (\\d{1,2})(?:st|nd|rd|th)?$")
    private val MONTH_LAST = Regex("^every (?:(\\d+) )?months? on the last$")

    /** The rule without a trailing "when done", or null when there's nothing left. */
    private fun splitWhenDone(rule: String): Pair<String, Boolean>? {
        var r = rule.trim().lowercase().replace(Regex("\\s+"), " ")
        if (r.isEmpty()) return null
        val whenDone = r.endsWith("when done")
        if (whenDone) r = r.removeSuffix("when done").trim()
        if (r.isEmpty()) return null
        return r to whenDone
    }

    private fun namedDay(body: String): DayOfWeek? =
        DayOfWeek.entries.firstOrNull { body == "every ${it.name.lowercase()}" }

    private fun parseWeekOn(body: String): Pair<Int, DayOfWeek>? {
        val m = WEEK_ON.matchEntire(body) ?: return null
        val n = m.groupValues[1].toIntOrNull() ?: 1
        if (n < 1) return null
        val dow = DayOfWeek.entries.firstOrNull { it.name.equals(m.groupValues[2], true) } ?: return null
        return n to dow
    }

    /** Day of month 1–31, or -1 for the last day. */
    private fun parseMonthOn(body: String): Pair<Int, Int>? {
        MONTH_LAST.matchEntire(body)?.let {
            val n = it.groupValues[1].toIntOrNull() ?: 1
            if (n < 1) return null
            return n to -1
        }
        val m = MONTH_ON.matchEntire(body) ?: return null
        val n = m.groupValues[1].toIntOrNull() ?: 1
        val day = m.groupValues[2].toIntOrNull() ?: return null
        if (n < 1 || day !in 1..31) return null
        return n to day
    }

    private fun weekPhrase(every: Int, day: DayOfWeek): String {
        val name = day.getDisplayName(TextStyle.FULL, Locale.UK)
        return if (every <= 1) "every week on $name" else "every $every weeks on $name"
    }

    private fun monthPhrase(every: Int, monthDay: Int): String {
        val on = if (monthDay < 0) "the last" else "the ${ordinal(monthDay.coerceIn(1, 31))}"
        return if (every <= 1) "every month on $on" else "every $every months on $on"
    }

    /** The next [dow], [interval] weeks apart, counting the week that contains [base] (Monday-start, as rrule does). */
    private fun nextWeekday(base: LocalDate, dow: DayOfWeek, interval: Long): LocalDate {
        if (interval <= 1) {
            var d = base.plusDays(1)
            while (d.dayOfWeek != dow) d = d.plusDays(1)
            return d
        }
        val anchor = base.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        var d = base.plusDays(1)
        while (ChronoUnit.DAYS.between(base, d) < 366L * 5) {
            if (d.dayOfWeek == dow) {
                val weeks = ChronoUnit.WEEKS.between(anchor, d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)))
                if (weeks % interval == 0L) return d
            }
            d = d.plusDays(1)
        }
        return d
    }

    /**
     * The next month-day strictly after [base]. [day] is 1–31, or -1 for the last day.
     * Months are counted from [base]'s month. A requested day that the month doesn't have is skipped,
     * which is what the Tasks plugin does for `every month on the 31st`.
     */
    private fun nextMonthDay(base: LocalDate, day: Int, interval: Long): LocalDate? {
        if (interval < 1) return null
        val anchor = base.withDayOfMonth(1)
        var month = anchor
        while (ChronoUnit.MONTHS.between(anchor, month) < 12L * 40) {
            val between = ChronoUnit.MONTHS.between(anchor, month)
            if (between % interval == 0L) {
                val candidate = when {
                    day < 0 -> month.withDayOfMonth(month.lengthOfMonth())
                    day in 1..month.lengthOfMonth() -> month.withDayOfMonth(day)
                    else -> null
                }
                if (candidate != null && candidate.isAfter(base)) return candidate
            }
            month = month.plusMonths(1)
        }
        return null
    }
}
