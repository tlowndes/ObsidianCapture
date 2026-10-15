package uk.tomlowndes.capture

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** The due-date groups the Tasks tab shows, in order. */
enum class Bucket(val title: String) {
    OVERDUE("Overdue"), TODAY("Today"), TOMORROW("Tomorrow"), WEEK("This week"), LATER("Later"), NONE("No date")
}

object TaskGroups {
    fun bucketOf(d: LocalDate?, today: LocalDate): Bucket {
        if (d == null) return Bucket.NONE
        val endOfWeek = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
        return when {
            d.isBefore(today) -> Bucket.OVERDUE
            d == today -> Bucket.TODAY
            d == today.plusDays(1) -> Bucket.TOMORROW
            !d.isAfter(endOfWeek) -> Bucket.WEEK
            else -> Bucket.LATER
        }
    }

    /** Non-empty groups in order, each sorted by due date. */
    fun groups(tasks: List<TaskItem>, today: LocalDate): List<Pair<Bucket, List<TaskItem>>> =
        Bucket.values().mapNotNull { b ->
            tasks.filter { bucketOf(it.due, today) == b }.sortedBy { it.due ?: LocalDate.MAX }.takeIf { it.isNotEmpty() }?.let { b to it }
        }
}
