package uk.tomlowndes.capture

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** One day in the Habits log: water drunk and which habits were ticked. */
data class HabitDay(val date: LocalDate, val waterMl: Int, val done: Set<String>)

data class HabitsNote(val habits: List<String>, val waterGoalMl: Int, val days: Map<LocalDate, HabitDay>) {
    fun day(d: LocalDate) = days[d] ?: HabitDay(d, 0, emptySet())

    /** Days in a row the habit was ticked, up to today (or yesterday if today isn't ticked yet). No penalty otherwise. */
    fun streak(habit: String, today: LocalDate): Int {
        var d = if (habit in day(today).done) today else today.minusDays(1)
        var n = 0
        while (habit in day(d).done) { n++; d = d.minusDays(1) }
        return n
    }

    /** Monday to Sunday of the week holding [today]. */
    fun week(today: LocalDate): List<LocalDate> {
        val mon = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return (0L..6L).map { mon.plusDays(it) }
    }
}

/**
 * `99 - Meta/Habits.md`: the habit list under `## Habits`, the water goal under `## Water`, and a dated table
 * under `## Log` (`| Date | Water ml | Done |`). Add or remove habits by editing the list in Obsidian.
 */
object Habits {
    const val PATH = "${FlightNotes.DIR}/Habits.md"
    const val GLASS_ML = 250

    val STARTER = """
        # Habits

        ## Habits
        - Walk 20 min
        - Read 10 pages
        - Stretch

        ## Water
        goal: 2000 ml

        ## Log
        | Date | Water ml | Done |
        |---|---|---|
    """.trimIndent() + "\n"

    private val ROW = Regex("^\\|\\s*(\\d{4}-\\d{2}-\\d{2})\\s*\\|\\s*(\\d*)\\s*\\|(.*)\\|\\s*$")

    fun parse(text: String): HabitsNote {
        val lines = text.split("\n")
        val habits = section(lines, "Habits").mapNotNull { Regex("^\\s*[-*]\\s+(.+)$").matchEntire(it)?.groupValues?.get(1)?.trim() }
        val goal = section(lines, "Water").firstNotNullOfOrNull { Regex("goal:\\s*(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 2000
        val days = section(lines, "Log").mapNotNull { l ->
            val m = ROW.matchEntire(l.trim()) ?: return@mapNotNull null
            val date = runCatching { LocalDate.parse(m.groupValues[1]) }.getOrNull() ?: return@mapNotNull null
            HabitDay(date, m.groupValues[2].toIntOrNull() ?: 0, m.groupValues[3].split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet())
        }.associateBy { it.date }
        return HabitsNote(habits, goal, days)
    }

    private fun section(lines: List<String>, name: String): List<String> {
        val start = lines.indexOfFirst { it.trim().equals("## $name", ignoreCase = true) }
        if (start < 0) return emptyList()
        val end = (start + 1 until lines.size).firstOrNull { lines[it].startsWith("## ") } ?: lines.size
        return lines.subList(start + 1, end)
    }

    fun row(d: HabitDay, order: List<String>) =
        "| ${d.date} | ${d.waterMl} | ${order.filter { it in d.done }.plus(d.done.filter { it !in order }.sorted()).joinToString(", ")} |"

    /** Replaces or adds [d]'s row in the Log table, keeping rows in date order. */
    fun setDay(lines: MutableList<String>, d: HabitDay, order: List<String>) {
        var start = lines.indexOfFirst { it.trim().equals("## Log", ignoreCase = true) }
        if (start < 0) {
            while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.lastIndex)
            lines.addAll(listOf("", "## Log", "| Date | Water ml | Done |", "|---|---|---|"))
            start = lines.size - 3
        }
        val end = (start + 1 until lines.size).firstOrNull { lines[it].startsWith("## ") } ?: lines.size
        val rows = (start + 1 until end).filter { ROW.matches(lines[it].trim()) }
        rows.firstOrNull { ROW.matchEntire(lines[it].trim())!!.groupValues[1] == d.date.toString() }?.let { lines[it] = row(d, order); return }
        val after = rows.lastOrNull { ROW.matchEntire(lines[it].trim())!!.groupValues[1] < d.date.toString() }
        val header = (start + 1 until end).lastOrNull { lines[it].trim().startsWith("|---") || lines[it].trim().startsWith("| Date") }
        val at = (after ?: header ?: start) + 1
        lines.add(at, row(d, order))
    }

    fun ensure(v: Vault) {
        if (v.entry(PATH) == null) {
            v.mkdirs(FlightNotes.DIR)
            VaultIo.write(v, v.create(FlightNotes.DIR, "Habits", "md"), STARTER)
        }
    }

    fun load(v: Vault): HabitsNote { ensure(v); return parse(VaultIo.read(v, PATH)) }

    /** Re-reads the note, applies [change] to [date]'s entry and writes it back. */
    fun update(v: Vault, date: LocalDate, change: (HabitDay) -> HabitDay) {
        ensure(v)
        VaultIo.edit(v, PATH) { lines ->
            val note = parse(lines.joinToString("\n"))
            setDay(lines, change(note.day(date)), note.habits)
        }
    }
}
