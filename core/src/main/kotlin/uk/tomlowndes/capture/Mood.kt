package uk.tomlowndes.capture

import java.time.LocalDate

/**
 * One-tap mood (build-queue T5): 1–5, logged in the Habits note's Done column as `mood=4`, so it sits with that day's
 * habits. The weekly review sets it against habits and how many tasks there were.
 */
object Mood {
    const val KEY = "mood"
    val FACES = listOf("😣", "😕", "😐", "🙂", "😄")

    fun of(d: HabitDay): Int? = d.counts[KEY]?.takeIf { it in 1..5 }

    fun face(n: Int?) = n?.let { FACES.getOrNull(it - 1) }

    fun set(d: HabitDay, n: Int): HabitDay = d.copy(counts = d.counts + (KEY to n.coerceIn(1, 5)))

    /** Shown from 18:00 until today has a mood. */
    fun ask(note: HabitsNote, today: LocalDate, hour: Int) = hour >= 18 && of(note.day(today)) == null

    /**
     * One line for the weekly review: what the better days had in common. [tasksDone] is tasks ticked each day.
     * Null until there are at least three days with a mood.
     */
    fun insight(note: HabitsNote, days: List<LocalDate>, tasksDone: Map<LocalDate, Int>): String? {
        val rated = days.mapNotNull { d -> of(note.day(d))?.let { d to it } }
        if (rated.size < 3) return null
        val avg = rated.map { it.second }.average()
        val good = rated.filter { it.second >= avg }.map { it.first }.toSet()
        val bad = rated.map { it.first }.toSet() - good
        val parts = mutableListOf<String>()
        note.active.filter { !it.counter }.map { h ->
            val inGood = good.count { h.name in note.day(it).done }.toDouble() / good.size.coerceAtLeast(1)
            val inBad = bad.count { h.name in note.day(it).done }.toDouble() / bad.size.coerceAtLeast(1)
            h to inGood - inBad
        }.filter { it.second >= 0.5 }.maxByOrNull { it.second }?.let { parts += "a ${it.first.name.lowercase()}" }
        val goodTasks = good.map { tasksDone[it] ?: 0 }.average()
        val badTasks = bad.map { tasksDone[it] ?: 0 }.takeIf { it.isNotEmpty() }?.average()
        if (badTasks != null && badTasks - goodTasks >= 2) parts += "fewer tasks"
        if (badTasks != null && goodTasks - badTasks >= 2) parts += "more ticked off"
        if (parts.isEmpty()) return "Mood averaged ${"%.1f".format(java.util.Locale.UK, avg)} of 5 this week."
        return "Best days had " + parts.joinToString(" and ") + "."
    }
}
