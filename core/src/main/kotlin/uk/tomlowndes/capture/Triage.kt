package uk.tomlowndes.capture

import java.time.LocalDate

/**
 * Triage cards (build-queue O1): overdue and undated tasks one at a time. Right = today, left = later (reschedule
 * sheet), up or ✕ = drop (cancelled). Bills stay out; they have their own card.
 */
object Triage {
    /** Tasks shows a Triage button once this many are overdue or undated. */
    const val SHOW_FROM = 5

    /** Overdue first (oldest first), then undated ones in vault order. */
    fun candidates(tasks: List<TaskItem>, today: LocalDate): List<TaskItem> {
        val open = tasks.filter { !Deck.isBill(it) }
        val overdue = open.filter { it.due != null && it.due.isBefore(today) }.sortedBy { it.due }
        val undated = open.filter { it.due == null }
        return overdue + undated
    }

    fun show(tasks: List<TaskItem>, today: LocalDate) = candidates(tasks, today).size >= SHOW_FROM

    /** "Overdue · 3 weeks ago", "No date", for the card's top line. */
    fun label(t: TaskItem, today: LocalDate): String {
        val d = t.due ?: return "No date"
        val days = java.time.temporal.ChronoUnit.DAYS.between(d, today)
        return "Overdue · " + when {
            days <= 1 -> "yesterday"
            days < 7 -> "$days days"
            days < 14 -> "a week"
            days < 60 -> "${days / 7} weeks"
            else -> "${days / 30} months"
        }
    }
}
