package uk.tomlowndes.capture

import java.time.LocalDate

/**
 * Energy reshuffle (energy-reshuffle-v1): pick how much you've got today and the tasks that don't fit move to the
 * lightest days this week. Nothing is written here; [plan] says what would move, the screens show it and save it.
 */
object Reshuffle {
    /** Default "usual day": how many actions fit on a 100% day. Set in Settings › Tasks. */
    const val USUAL = 9
    /** Days a task can move to: tomorrow and the five after it, so the moves stay inside the one-week Upcoming. */
    const val SPREAD = 6

    enum class Level(val label: String, val share: Double) {
        REST("Rest", 0.0), LOW("30%", 0.3), HALF("50%", 0.5), FULL("100%", 1.0);

        /** Actions that fit today on a [usual] day: rounded, halves down (9 at 50% is 4). */
        fun fits(usual: Int): Int = kotlin.math.ceil(usual * share - 0.5).toInt().coerceAtLeast(0)
    }

    data class Move(val task: TaskItem, val to: LocalDate)

    /**
     * What a reshuffle at [level] would do. [today] counts tasks due today and overdue ones; [stay] are the ones that
     * never move; [moves] where the rest go; [load] tasks per day for the week from today, after the moves.
     */
    data class Plan(
        val level: Level, val fits: Int, val today: Int, val stay: List<TaskItem>, val keep: List<TaskItem>,
        val moves: List<Move>, val before: Map<LocalDate, Int>, val load: Map<LocalDate, Int>,
    )

    /** High and Highest, bills, tasks with a time slot and repeating ones stay where they are. */
    fun fixed(t: TaskItem): Boolean =
        Priority.of(t).urgent || Deck.isBill(t) || DayPlan.timeOf(t) != null || t.recurrence != null

    /** Short tasks (15 minutes or less) are kept first, then by priority, then the oldest due date. */
    private val keepOrder = compareBy<TaskItem>(
        { if ((TaskRules.minutes(it.raw) ?: Int.MAX_VALUE) <= TaskViews.SHORT_MIN) 0 else 1 },
        { Priority.of(it).level },
        { it.due ?: LocalDate.MAX },
    )

    /** [pinned] are raw lines you chose to keep today: they stay like fixed ones and take up room. */
    fun plan(tasks: List<TaskItem>, today: LocalDate, level: Level, usual: Int = USUAL, pinned: Set<String> = emptySet()): Plan {
        val onToday = tasks.filter { it.due != null && !it.due.isAfter(today) }
        val (stay, movable) = onToday.partition { fixed(it) || it.raw in pinned }
        val fits = level.fits(usual)
        val room = (fits - stay.size).coerceAtLeast(0)
        val sorted = movable.sortedWith(keepOrder)
        val keep = sorted.take(room)
        val going = sorted.drop(room)

        val week = (0..SPREAD.toLong()).map { today.plusDays(it) }
        val before = week.associateWith { d -> if (d == today) onToday.size else tasks.count { it.due == d } }
        val load = before.toMutableMap()
        val full = usual.coerceAtLeast(1)
        val moves = going.map { t ->
            val later = week.drop(1)
            // The lightest day with room; if every day is full, the lightest one anyway. Ties go to the earlier day.
            val to = later.filter { load.getValue(it) < full }.minByOrNull { load.getValue(it) }
                ?: later.minBy { load.getValue(it) }
            load[to] = load.getValue(to) + 1
            load[today] = load.getValue(today) - 1
            Move(t, to)
        }
        return Plan(level, fits, onToday.size, stay, keep, moves, before, load)
    }
}
