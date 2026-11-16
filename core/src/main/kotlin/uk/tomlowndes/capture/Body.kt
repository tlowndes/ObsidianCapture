package uk.tomlowndes.capture

import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

/**
 * Sleep and steps (body-people-errands-v1): read from Health Connect on the phone and saved as numbers in the day's
 * Habits row (sleep=minutes, steps=count, bed=hhmm), next to mood, so What helps you can use them. Nothing else kept.
 */
object Body {
    const val SLEEP = "sleep"
    const val STEPS = "steps"
    const val BED = "bed"
    const val SHORT_MIN = 390
    const val EASY_TASK_MIN = 15

    fun sleep(d: HabitDay): Int? = d.counts[SLEEP]?.takeIf { it > 0 }
    fun steps(d: HabitDay): Int? = d.counts[STEPS]?.takeIf { it > 0 }
    fun bed(d: HabitDay): LocalTime? = d.counts[BED]?.let { runCatching { LocalTime.of(it / 100 % 24, it % 100) }.getOrNull() }

    fun with(d: HabitDay, sleepMin: Int?, steps: Int?, bed: LocalTime?): HabitDay = d.copy(counts = d.counts
        .let { c -> sleepMin?.takeIf { it > 0 }?.let { c + (SLEEP to it) } ?: c }
        .let { c -> steps?.takeIf { it > 0 }?.let { c + (STEPS to it) } ?: c }
        .let { c -> bed?.let { c + (BED to (it.hour * 100 + it.minute).coerceAtLeast(1)) } ?: c })

    /** The usual night: the median of the last 14 nights before [day] that have sleep. */
    fun usual(note: HabitsNote, day: LocalDate): Int? = (1..14L).mapNotNull { sleep(note.day(day.minusDays(it))) }.sorted()
        .takeIf { it.size >= 3 }?.let { it[it.size / 2] }

    fun hm(min: Int) = "${min / 60}h ${"%02d".format(min % 60)}m"

    /** "😴 6h 40m · bed 23:50 · 30m under your usual" */
    fun sleepLine(note: HabitsNote, day: LocalDate): String? {
        val d = note.day(day)
        val s = sleep(d) ?: return null
        val diff = usual(note, day)?.let { s - it }
        return listOfNotNull("😴 ${hm(s)}", bed(d)?.let { "bed %02d:%02d".format(it.hour, it.minute) },
            diff?.takeIf { kotlin.math.abs(it) >= 15 }?.let { if (it < 0) "${-it}m under your usual" else "${it}m over your usual" }).joinToString(" · ")
    }

    /** "👟 4,210 yesterday · goal 7k" */
    fun stepsLine(note: HabitsNote, day: LocalDate, goal: Int): String? {
        val s = steps(note.day(day.minusDays(1))) ?: return null
        return "👟 ${"%,d".format(Locale.UK, s)} yesterday · goal ${if (goal % 1000 == 0) "${goal / 1000}k" else "%,d".format(Locale.UK, goal)}"
    }

    fun shortNight(note: HabitsNote, day: LocalDate, under: Int = SHORT_MIN) = sleep(note.day(day))?.let { it < under } == true
}
