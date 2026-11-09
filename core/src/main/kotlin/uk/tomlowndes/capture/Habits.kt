package uk.tomlowndes.capture

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

enum class HabitKind { TICK, COUNTER }

/**
 * One habit from the `## Habits` list. [days] empty means every day. A counter counts up by [step] towards [goal]
 * ([max] flips it: staying at or under the goal is the win). [pausedOn] hides it; [rest] is the last pause, which
 * streaks step over so resuming keeps them.
 */
data class Habit(
    val name: String,
    val emoji: String = "",
    val kind: HabitKind = HabitKind.TICK,
    val days: Set<DayOfWeek> = emptySet(),
    val goal: Int = 1,
    val step: Int = 1,
    val unit: String = "",
    val max: Boolean = false,
    val pausedOn: LocalDate? = null,
    val rest: Pair<LocalDate, LocalDate>? = null,
) {
    val counter get() = kind == HabitKind.COUNTER
    val paused get() = pausedOn != null
    /** Water keeps its own column in the log, so older notes still read. */
    val water get() = counter && name.equals(Habits.WATER, ignoreCase = true)
    val label get() = if (emoji.isEmpty()) name else "$emoji $name"

    fun due(d: LocalDate) = days.isEmpty() || d.dayOfWeek in days
    fun resting(d: LocalDate) = pausedOn?.let { !d.isBefore(it) } == true || rest?.let { !d.isBefore(it.first) && !d.isAfter(it.second) } == true
}

/** One day in the Habits log: water drunk, which habits were ticked and other counters' totals. */
data class HabitDay(val date: LocalDate, val waterMl: Int, val done: Set<String>, val counts: Map<String, Int> = emptyMap()) {
    fun value(h: Habit) = when {
        h.water -> waterMl
        h.counter -> counts[h.name] ?: 0
        else -> if (h.name in done) 1 else 0
    }

    /** Adds [by] to [h] (a counter), never going below zero. */
    fun add(h: Habit, by: Int): HabitDay {
        val v = (value(h) + by).coerceAtLeast(0)
        return if (h.water) copy(waterMl = v) else copy(counts = if (v == 0) counts - h.name else counts + (h.name to v))
    }

    fun toggle(h: Habit) = copy(done = if (h.name in done) done - h.name else done + h.name)
}

data class HabitsNote(val all: List<Habit>, val days: Map<LocalDate, HabitDay>) {
    fun day(d: LocalDate) = days[d] ?: HabitDay(d, 0, emptySet())

    val active get() = all.filter { !it.paused }
    val paused get() = all.filter { it.paused }
    val water get() = all.firstOrNull { it.water }
    val waterGoalMl get() = water?.goal ?: Habits.WATER_GOAL

    /** Habits that show on [d]: not paused and due that day, in list order. */
    fun on(d: LocalDate) = active.filter { it.due(d) }

    fun met(h: Habit, d: HabitDay) = when {
        !h.counter -> h.name in d.done
        h.max -> d.date in days && d.value(h) <= h.goal
        else -> d.value(h) >= h.goal
    }

    /** Done and total for [d], for the home pill and card. */
    fun progress(d: LocalDate): Pair<Int, Int> {
        val list = on(d)
        return list.count { met(it, day(d)) } to list.size
    }

    /**
     * Due days in a row the habit was met, up to today (or yesterday if today isn't met yet). Days it isn't due or
     * was paused are stepped over. No penalty otherwise.
     */
    fun streak(h: Habit, today: LocalDate): Int {
        val first = days.keys.minOrNull() ?: return 0
        var d = if (met(h, day(today))) today else today.minusDays(1)
        var n = 0
        while (!d.isBefore(first)) {
            if (h.due(d) && !h.resting(d) && !frozen(h, d, today)) { if (met(h, day(d))) n++ else break }
            d = d.minusDays(1)
        }
        return n
    }

    private fun missed(h: Habit, d: LocalDate, today: LocalDate) =
        d.isBefore(today) && h.due(d) && !h.resting(d) && !met(h, day(d)) && days.keys.minOrNull()?.let { !d.isBefore(it) } == true

    /**
     * Streak freeze (build-queue O2): one a week, used on the first missed day of that week, so one bad day doesn't
     * end a streak. A frozen day doesn't add to the streak either. Worked out from the log, nothing extra is written.
     */
    fun frozen(h: Habit, d: LocalDate, today: LocalDate): Boolean {
        if (!missed(h, d, today)) return false
        val mon = d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        var e = mon
        while (e.isBefore(d)) { if (missed(h, e, today)) return false; e = e.plusDays(1) }
        return true
    }

    /** Freezes left this week for [h]: 1, or 0 once one has been used. */
    fun freezesLeft(h: Habit, today: LocalDate): Int = if (week(today).any { frozen(h, it, today) }) 0 else 1

    /** The habit (and its streak) a freeze saved most recently this week, for the Habits screen note. */
    fun lastFreeze(today: LocalDate): Triple<Habit, LocalDate, Int>? =
        active.filter { !it.counter }.mapNotNull { h ->
            week(today).lastOrNull { frozen(h, it, today) }?.let { Triple(h, it, streak(h, today)) }
        }.filter { it.third > 0 }.maxByOrNull { it.second }

    /** Monday to Sunday of the week holding [today]. */
    fun week(today: LocalDate): List<LocalDate> {
        val mon = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return (0L..6L).map { mon.plusDays(it) }
    }
}

/**
 * `99 - Meta/Habits.md`: the habit list under `## Habits`, the water goal under `## Water`, and a dated table
 * under `## Log` (`| Date | Water ml | Done |`). Each list line is `- 🧘 Stretch · Mon Wed Fri` or
 * `- ☕ Coffee · counter 2 cups +1 max`, so it can be edited in Obsidian or from Manage habits.
 * Other counters log as `Name=N` in the Done column.
 */
object Habits {
    const val PATH = "${FlightNotes.DIR}/Habits.md"
    const val WATER = "Water"
    const val WATER_GOAL = 2000
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

    /** Starter icons offered when adding a habit. */
    val ICONS = listOf("🧘", "🚶", "📖", "💧", "💊", "🛏️", "🍎", "✍️", "🎸", "☕", "🏋️", "🧹")

    private val ROW = Regex("^\\|\\s*(\\d{4}-\\d{2}-\\d{2})\\s*\\|\\s*(\\d*)\\s*\\|(.*)\\|\\s*$")
    private val COUNT = Regex("^(.+?)=(\\d+)$")
    private val COUNTER = Regex("^counter (\\d+)(?: ([^+\\s]+))? \\+(\\d+)( max)?$", RegexOption.IGNORE_CASE)
    private val PAUSED = Regex("^paused (\\d{4}-\\d{2}-\\d{2})$", RegexOption.IGNORE_CASE)
    private val REST = Regex("^rest (\\d{4}-\\d{2}-\\d{2})\\.\\.(\\d{4}-\\d{2}-\\d{2})$", RegexOption.IGNORE_CASE)
    private val SHORT = DayOfWeek.entries.associateBy { it.name.take(3).lowercase() }
    private val WEEKDAYS = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
    private val WEEKENDS = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)

    fun defaultWater(goal: Int = WATER_GOAL) = Habit(WATER, "💧", HabitKind.COUNTER, goal = goal, step = GLASS_ML, unit = "ml")

    /** Names go in a markdown list and a table cell, so drop the characters either would read as structure. */
    fun cleanName(s: String) = s.replace(Regex("[|,=\\n]"), " ").replace(" · ", " ").replace(Regex("\\s+"), " ").trim()

    fun daysText(days: Set<DayOfWeek>) = when (days) {
        emptySet<DayOfWeek>(), DayOfWeek.entries.toSet() -> "every day"
        WEEKDAYS -> "weekdays"
        WEEKENDS -> "weekends"
        else -> days.sorted().joinToString(" ") { it.name.take(1) + it.name.substring(1, 3).lowercase() }
    }

    /** The list line for [h], without the leading `- `. */
    fun line(h: Habit): String = listOfNotNull(
        h.label,
        h.days.takeIf { it.isNotEmpty() && it.size < 7 }?.let { daysText(it) },
        if (h.counter) "counter ${h.goal}${if (h.unit.isNotEmpty()) " ${h.unit}" else ""} +${h.step}${if (h.max) " max" else ""}" else null,
        h.pausedOn?.let { "paused $it" },
        h.rest?.let { "rest ${it.first}..${it.second}" },
    ).joinToString(" · ")

    fun parseLine(s: String): Habit {
        val parts = s.split(" · ").map { it.trim() }
        var h = Habit(parts[0])
        val head = parts[0]
        if (head.contains(' ') && head.codePointAt(0).let { !Character.isLetterOrDigit(it) && it >= 0x2000 }) {
            h = h.copy(emoji = head.substringBefore(' '), name = head.substringAfter(' ').trim())
        }
        parts.drop(1).forEach { a ->
            val words = a.lowercase().split(" ")
            val counter = COUNTER.matchEntire(a)
            val paused = PAUSED.matchEntire(a)
            val rest = REST.matchEntire(a)
            h = when {
                a.equals("every day", true) -> h.copy(days = emptySet())
                a.equals("weekdays", true) -> h.copy(days = WEEKDAYS)
                a.equals("weekends", true) -> h.copy(days = WEEKENDS)
                words.all { it in SHORT } -> h.copy(days = words.map { SHORT.getValue(it) }.toSet())
                counter != null -> h.copy(
                    kind = HabitKind.COUNTER, goal = counter.groupValues[1].toInt(), unit = counter.groupValues[2],
                    step = counter.groupValues[3].toInt().coerceAtLeast(1), max = counter.groupValues[4].isNotEmpty()
                )
                paused != null -> runCatching { h.copy(pausedOn = LocalDate.parse(paused.groupValues[1])) }.getOrDefault(h)
                rest != null -> runCatching { h.copy(rest = LocalDate.parse(rest.groupValues[1]) to LocalDate.parse(rest.groupValues[2])) }.getOrDefault(h)
                else -> h.copy(name = "${h.name} · $a")
            }
        }
        return h
    }

    fun parse(text: String): HabitsNote {
        val lines = text.split("\n")
        val goal = section(lines, "Water").firstNotNullOfOrNull { Regex("goal:\\s*(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: WATER_GOAL
        val listed = section(lines, "Habits").mapNotNull { Regex("^\\s*[-*]\\s+(.+)$").matchEntire(it)?.groupValues?.get(1)?.trim() }.map(::parseLine)
        // Older notes don't list Water; it's always there, first.
        val habits = if (listed.any { it.water }) listed else listOf(defaultWater(goal)) + listed
        val days = section(lines, "Log").mapNotNull { l ->
            val m = ROW.matchEntire(l.trim()) ?: return@mapNotNull null
            val date = runCatching { LocalDate.parse(m.groupValues[1]) }.getOrNull() ?: return@mapNotNull null
            val cells = m.groupValues[3].split(",").map { it.trim() }.filter { it.isNotEmpty() }
            val counts = cells.mapNotNull { c -> COUNT.matchEntire(c)?.let { it.groupValues[1].trim() to it.groupValues[2].toInt() } }.toMap()
            HabitDay(date, m.groupValues[2].toIntOrNull() ?: 0, cells.filter { !COUNT.matches(it) }.toSet(), counts)
        }.associateBy { it.date }
        return HabitsNote(habits, days)
    }

    private fun bounds(lines: List<String>, name: String): IntRange? {
        val start = lines.indexOfFirst { it.trim().equals("## $name", ignoreCase = true) }
        if (start < 0) return null
        val end = (start + 1 until lines.size).firstOrNull { lines[it].startsWith("## ") } ?: lines.size
        return start until end
    }

    private fun section(lines: List<String>, name: String): List<String> =
        bounds(lines, name)?.let { lines.subList(it.first + 1, it.last + 1) } ?: emptyList()

    fun row(d: HabitDay, order: List<String>): String {
        val ticks = order.filter { it in d.done } + d.done.filter { it !in order }.sorted()
        val counts = (order.filter { it in d.counts } + d.counts.keys.filter { it !in order }.sorted())
            .filter { (d.counts[it] ?: 0) > 0 }.map { "$it=${d.counts[it]}" }
        return "| ${d.date} | ${d.waterMl} | ${(ticks + counts).joinToString(", ")} |"
    }

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

    /** Water first, every day, 250 ml a press: what older notes mean by leaving it off the list. */
    private fun plainWater(h: Habit, i: Int) =
        i == 0 && h == defaultWater(h.goal)

    /**
     * Writes [list] (in order) as the `## Habits` section, keeps the Water goal in step, and moves log entries for
     * any habit in [renames] (old name to new) so streaks follow a rename.
     */
    fun setList(lines: MutableList<String>, list: List<Habit>, renames: Map<String, String> = emptyMap()) {
        val body = list.filterIndexed { i, h -> !(h.water && plainWater(h, i)) }.map { "- ${line(it)}" } + ""
        val habits = bounds(lines, "Habits")
        if (habits != null) {
            repeat(habits.last - habits.first) { lines.removeAt(habits.first + 1) }
            lines.addAll(habits.first + 1, body)
        } else {
            val at = lines.indexOfFirst { it.startsWith("## ") }.takeIf { it >= 0 } ?: lines.size
            lines.addAll(at, listOf("## Habits") + body)
        }
        list.firstOrNull { it.water }?.let { w ->
            val water = bounds(lines, "Water")
            if (water == null) {
                val at = bounds(lines, "Log")?.first ?: lines.size
                lines.addAll(at, listOf("## Water", "goal: ${w.goal} ml", ""))
            } else {
                val g = (water.first + 1..water.last).firstOrNull { lines[it].trim().startsWith("goal:") }
                if (g != null) lines[g] = "goal: ${w.goal} ml" else lines.add(water.first + 1, "goal: ${w.goal} ml")
            }
        }
        if (renames.isNotEmpty()) {
            val order = list.map { it.name }
            val log = bounds(lines, "Log") ?: return
            for (i in log) {
                val m = ROW.matchEntire(lines[i].trim()) ?: continue
                val day = parse("## Log\n${lines[i]}").days.values.firstOrNull() ?: continue
                if (day.done.none { it in renames } && day.counts.keys.none { it in renames }) continue
                val moved = day.copy(
                    done = day.done.map { renames[it] ?: it }.toSet(),
                    counts = day.counts.mapKeys { renames[it.key] ?: it.key }
                )
                if (m.groupValues[1] == moved.date.toString()) lines[i] = row(moved, order)
            }
        }
    }

    /** The whole note as text, which [parse] reads back. Used for the phone's offline cache. */
    fun text(n: HabitsNote, lastDays: Int = 14): String {
        val lines = "# Habits\n\n## Water\ngoal: ${n.waterGoalMl} ml\n\n## Log\n| Date | Water ml | Done |\n|---|---|---|\n".split("\n").toMutableList()
        setList(lines, n.all)
        val order = n.all.map { it.name }
        n.days.values.sortedBy { it.date }.takeLast(lastDays).forEach { setDay(lines, it, order) }
        return lines.joinToString("\n")
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
            setDay(lines, change(note.day(date)), note.all.map { it.name })
        }
    }

    /** Saves the habit list from Manage habits. */
    fun save(v: Vault, list: List<Habit>, renames: Map<String, String> = emptyMap()) {
        ensure(v)
        VaultIo.edit(v, PATH) { lines -> setList(lines, list, renames) }
    }

    fun litres(ml: Int) = if (ml % 1000 == 0) "${ml / 1000}" else "%.2f".format(java.util.Locale.UK, ml / 1000.0).trimEnd('0').trimEnd('.')

    private fun unit(h: Habit) = if (h.unit.isEmpty()) "" else " ${h.unit}"

    /** "1 / 2 L" or "1 / 2 cups", for the home card. */
    fun short(h: Habit, v: Int) = if (h.water) "${litres(v)} / ${litres(h.goal)} L" else "$v / ${h.goal}${unit(h)}"

    /** "1.5 L of 2 L · 6 glasses" or "1 of 2 cups max", for the Habits screen. */
    fun long(h: Habit, v: Int) =
        if (h.water) "${litres(v)} L of ${litres(h.goal)} L · ${v / h.step.coerceAtLeast(1)} glasses"
        else "$v of ${h.goal}${unit(h)}${if (h.max) " max" else ""}"

    /** "+250" or "+1", the press button. */
    fun press(h: Habit) = "+${h.step}"

    /** The grey line under a habit in Manage habits: "Tick · every day", "Counter · 2 L · +250 ml". */
    fun summary(h: Habit): String {
        val days = daysText(h.days)
        if (!h.counter) return "Tick · $days"
        val goal = if (h.water) "${litres(h.goal)} L" else "${h.goal}${unit(h)}"
        return listOfNotNull("Counter", goal, "+${h.step}${unit(h)}", if (h.max) "max" else null, days.takeIf { it != "every day" })
            .joinToString(" · ")
    }

    /** Pauses [h] from [today]. */
    fun pause(h: Habit, today: LocalDate) = h.copy(pausedOn = today)

    /** Resumes [h]; the paused days become its rest, so the streak carries on. */
    fun resume(h: Habit, today: LocalDate) =
        h.copy(pausedOn = null, rest = h.pausedOn?.takeIf { it.isBefore(today) }?.let { it to today.minusDays(1) } ?: h.rest)
}
