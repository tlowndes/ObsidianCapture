package uk.tomlowndes.capture

import java.time.LocalDate

/** The shareable weekly recap (build-queue O4). [perDay] is tasks ticked each of the 7 days ending [to]. */
data class Recap(
    val from: LocalDate, val to: LocalDate, val tasksDone: Int, val focusMin: Int, val bestStreak: Int,
    val jobsApplied: Int, val habitsHit: Int, val perDay: List<Pair<LocalDate, Int>>, val mood: String?,
) {
    val best get() = perDay.maxByOrNull { it.second }?.takeIf { it.second > 0 }
}

object Recaps {
    fun build(
        done: List<DoneTask>, focus: List<FocusSession>, habits: HabitsNote?, jobs: List<Job>, today: LocalDate,
    ): Recap {
        val from = today.minusDays(6)
        val days = (0L..6L).map { from.plusDays(it) }
        val perDay = days.map { d -> d to done.count { it.done == d } }
        val focusMin = focus.filter { !it.at.toLocalDate().isBefore(from) && !it.at.toLocalDate().isAfter(today) }.sumOf { it.minutes }
        val hit = habits?.let { n ->
            val pairs = days.filter { !it.isAfter(today) }.map { n.progress(it) }
            val total = pairs.sumOf { it.second }
            if (total == 0) 0 else pairs.sumOf { it.first } * 100 / total
        } ?: 0
        val streak = habits?.let { n -> n.active.maxOfOrNull { n.streak(it, today) } } ?: 0
        val applied = jobs.count { j -> j.applied?.let { !it.isBefore(from) && !it.isAfter(today) } == true }
        val mood = habits?.let { Mood.insight(it, days, perDay.toMap()) }
        return Recap(from, today, done.size, focusMin, streak, applied, hit, perDay, mood)
    }

    /** "4h 10m", "45m". */
    fun hours(min: Int) = if (min < 60) "${min}m" else if (min % 60 == 0) "${min / 60}h" else "${min / 60}h ${min % 60}m"

    /** "Best day: Thursday, 7 tasks." */
    fun bestLine(r: Recap): String? = r.best?.let { (d, n) ->
        "Best day: ${d.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.UK)}, $n ${if (n == 1) "task" else "tasks"}."
    }

    /** The lines Save to vault writes into the week's recap note. */
    fun note(r: Recap): String = buildString {
        val week = r.to.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR)
        append("# Week $week recap\n\n")
        append("${r.from} to ${r.to}\n\n")
        append("- Tasks done: ${r.tasksDone}\n")
        append("- Focus: ${hours(r.focusMin)}\n")
        append("- Best streak: ${r.bestStreak} days\n")
        append("- Jobs applied: ${r.jobsApplied}\n")
        append("- Habits hit: ${r.habitsHit}%\n")
        bestLine(r)?.let { append("\n$it\n") }
        r.mood?.let { append("\n$it\n") }
        append("\n| Day | Tasks |\n|---|---|\n")
        r.perDay.forEach { (d, n) -> append("| ${d.dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.uppercase() }} ${d.dayOfMonth} | $n |\n") }
    }

    fun noteName(r: Recap) = "Week ${r.to.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR)} recap"
}
