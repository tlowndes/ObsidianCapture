package uk.tomlowndes.capture

/** One step of a routine: `- 💧 Glass of water · 1m`. */
data class RoutineStep(val emoji: String, val name: String, val minutes: Int) {
    val label get() = if (emoji.isEmpty()) name else "$emoji $name"
}

data class Routine(val name: String, val steps: List<RoutineStep>) {
    val minutes get() = steps.sumOf { it.minutes }
}

/**
 * Routines (build-queue T6) live in `99 - Meta/Routines.md`: a `## Name` heading per routine and a list of steps with
 * minutes, so they're easy to edit in Obsidian. A step named like a habit ticks that habit when it's done.
 */
object Routines {
    const val PATH = "${FlightNotes.DIR}/Routines.md"
    private val STEP = Regex("^\\s*[-*]\\s+(.+?)\\s*(?:·|-|–)\\s*(\\d{1,3})\\s*m(?:in)?\\s*$")

    val STARTER = """
        # Routines

        ## Morning
        - 💧 Glass of water · 1m
        - 🛏️ Make bed · 3m
        - 🧘 Stretch · 10m
        - 🚿 Shower · 8m
        - 📋 Plan today · 5m

        ## Wind down
        - 📵 Phone on charge · 1m
        - 📖 Read 10 pages · 15m
    """.trimIndent() + "\n"

    fun parse(text: String): List<Routine> {
        val out = mutableListOf<Routine>()
        var name: String? = null
        val steps = mutableListOf<RoutineStep>()
        fun flush() { name?.let { if (steps.isNotEmpty()) out += Routine(it, steps.toList()) }; steps.clear() }
        text.replace("\r", "").split("\n").forEach { l ->
            if (l.startsWith("## ")) { flush(); name = l.removePrefix("## ").trim(); return@forEach }
            val m = STEP.matchEntire(l) ?: return@forEach
            val head = m.groupValues[1].trim()
            val first = head.codePointAt(0)
            val (emoji, label) = if (head.contains(' ') && !Character.isLetterOrDigit(first) && first >= 0x2000)
                head.substringBefore(' ') to head.substringAfter(' ').trim() else "" to head
            steps += RoutineStep(emoji, label, m.groupValues[2].toInt().coerceAtLeast(1))
        }
        flush()
        return out
    }

    fun load(v: Vault): List<Routine> {
        if (v.entry(PATH) == null) {
            v.mkdirs(FlightNotes.DIR)
            VaultIo.write(v, v.create(FlightNotes.DIR, "Routines", "md"), STARTER)
        }
        return parse(VaultIo.read(v, PATH))
    }

    /** The habit a step ticks, matched by name (case and emoji ignored). */
    fun habitFor(s: RoutineStep, habits: List<Habit>): Habit? =
        habits.firstOrNull { !it.counter && it.name.equals(s.name, ignoreCase = true) }

    /** "done by 07:40" when started at [start]. */
    fun doneBy(r: Routine, fromStep: Int, start: java.time.LocalTime): java.time.LocalTime =
        start.plusMinutes(r.steps.drop(fromStep).sumOf { it.minutes }.toLong())
}
