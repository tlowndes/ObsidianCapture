package uk.tomlowndes.capture

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** Reads a due date typed at the end of a task: "Book MOT fri", "Call Sam tomorrow", "Renew domain 25 oct". */
object DateWords {
    private val DAYS = DayOfWeek.values().associateBy { it.name.lowercase().take(3) }

    private fun month(s: String): Int? {
        val w = s.lowercase()
        if (w.length < 3) return null
        return (1..12).firstOrNull { java.time.Month.of(it).name.lowercase().startsWith(w) } ?: if (w == "sept") 9 else null
    }

    private fun day(s: String): DayOfWeek? {
        val w = s.lowercase()
        if (w.length < 3) return null
        val d = DAYS[w.take(3)] ?: return null
        val full = d.name.lowercase()
        return d.takeIf { full.startsWith(w) || w == "weds" }
    }

    /** A day and month this year, or next year if it has already gone. */
    private fun upcoming(dayOfMonth: Int, m: Int, today: LocalDate): LocalDate? = runCatching {
        val d = LocalDate.of(today.year, m, dayOfMonth)
        if (d.isBefore(today)) d.plusYears(1) else d
    }.getOrNull()

    /** The date the words mean, or null if they aren't a date. */
    fun date(words: List<String>, today: LocalDate): LocalDate? {
        val w = words.map { it.lowercase().trim(',', '.') }
        when (w.size) {
            1 -> {
                val s = w[0]
                when (s) {
                    "today", "tod" -> return today
                    "tomorrow", "tom", "tmr", "tmrw" -> return today.plusDays(1)
                }
                day(s)?.let { return today.with(TemporalAdjusters.nextOrSame(it)) }
                Regex("^(\\d{1,2})/(\\d{1,2})(?:/(\\d{2}|\\d{4}))?$").matchEntire(s)?.let { m ->
                    val (dd, mm, yy) = m.destructured
                    if (yy.isEmpty()) return upcoming(dd.toInt(), mm.toInt(), today)
                    val year = if (yy.length == 2) 2000 + yy.toInt() else yy.toInt()
                    return runCatching { LocalDate.of(year, mm.toInt(), dd.toInt()) }.getOrNull()
                }
                runCatching { LocalDate.parse(s) }.getOrNull()?.let { return it }
            }
            2 -> {
                if (w[0] == "next" && w[1] == "week") return today.plusDays(7)
                if (w[0] == "next") day(w[1])?.let { return today.with(TemporalAdjusters.next(it)) }
                val n0 = w[0].removeSuffix("st").removeSuffix("nd").removeSuffix("rd").removeSuffix("th").toIntOrNull()
                val n1 = w[1].removeSuffix("st").removeSuffix("nd").removeSuffix("rd").removeSuffix("th").toIntOrNull()
                if (n0 != null) month(w[1])?.let { return upcoming(n0, it, today) }
                if (n1 != null) month(w[0])?.let { return upcoming(n1, it, today) }
            }
        }
        return null
    }

    /**
     * Splits a due date off the end of [text]: ("Book MOT", Fri 17 Oct) for "Book MOT fri".
     * Text with no date at the end comes back whole with null. A date alone is not split off.
     */
    fun split(text: String, today: LocalDate): Pair<String, LocalDate?> {
        val words = text.trim().split(Regex("\\s+"))
        for (n in listOf(2, 1)) {
            if (words.size <= n) continue
            val d = date(words.takeLast(n), today) ?: continue
            return words.dropLast(n).joinToString(" ") to d
        }
        return text.trim() to null
    }
}
