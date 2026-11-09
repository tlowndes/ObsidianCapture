package uk.tomlowndes.capture

import java.time.LocalDate
import java.time.YearMonth

/** What happens to a task at monthly migration: carry it on (date cleared), give it a new date, or drop it. */
enum class MigrateChoice { CARRY, SCHEDULE, DROP }

/**
 * Bullet Journal migration (paper-hybrid-v1): once a month, every open task still dated in an earlier month is looked at
 * one by one. Carrying it keeps it open without its old date, scheduling gives it a new one, dropping marks it
 * cancelled (`- [-] … ❌`), as the weekly review does.
 */
object Migrate {
    /** Open tasks due before the start of [today]'s month, oldest first. Recurring ones are left alone. */
    fun candidates(tasks: List<TaskItem>, today: LocalDate): List<TaskItem> {
        val start = today.withDayOfMonth(1)
        return tasks.filter { it.due != null && it.due.isBefore(start) && it.recurrence == null }.sortedBy { it.due }
    }

    /** "2026-11": the month a migration is for. */
    fun monthKey(today: LocalDate): String = YearMonth.from(today).toString()

    /** Whether the Deck should offer migration: there's something to sort and this month's hasn't been done. */
    fun due(tasks: List<TaskItem>, today: LocalDate, lastDone: String?): Boolean =
        lastDone != monthKey(today) && candidates(tasks, today).isNotEmpty()

    /** "October → November". */
    fun title(today: LocalDate): String {
        fun name(m: YearMonth) = m.month.name.lowercase().replaceFirstChar { it.uppercase() }
        val now = YearMonth.from(today)
        return "${name(now.minusMonths(1))} → ${name(now)}"
    }
}
