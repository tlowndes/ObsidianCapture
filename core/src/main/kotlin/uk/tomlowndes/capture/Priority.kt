package uk.tomlowndes.capture

/**
 * Task priority, as the Obsidian Tasks plugin writes it (🔺 ⏫ 🔼 🔽 ⏬). [level] sorts highest first; NONE sits
 * between Medium and Low, like the plugin. [flag] is the small mark the Tasks list shows (mockup priority-flags-v1).
 */
enum class Priority(val level: Int, val mark: String, val flag: String, val title: String) {
    HIGHEST(0, "🔺", "!!!", "Highest"),
    HIGH(1, "⏫", "!!", "High"),
    MEDIUM(2, "🔼", "!", "Medium"),
    NONE(3, "", "", "No priority"),
    LOW(4, "🔽", "↓", "Low"),
    LOWEST(5, "⏬", "↓↓", "Lowest");

    /** Highest or High: what the High+ filter keeps. */
    val urgent get() = level <= HIGH.level

    companion object {
        private val MARKS = Regex("\\s*[🔺⏫🔼🔽⏬]\\uFE0F?")
        /** Where the plugin puts priority: after the description, before recurrence, estimate and dates. */
        private val AFTER = Regex("\\s(🔁|⏱|➕|🛫|⏳|📅|✅|❌|\\[\\w+::)")

        /** The order the Edit task row offers them. */
        val CHOICES = listOf(NONE, HIGHEST, HIGH, MEDIUM, LOW, LOWEST)

        fun of(line: String): Priority = values().firstOrNull { it != NONE && it.mark in line } ?: NONE
        fun of(t: TaskItem): Priority = of(t.raw)

        /** [line] with its priority set to [p]: any old mark is removed, NONE leaves none. */
        fun with(line: String, p: Priority): String {
            val cr = line.endsWith("\r")
            val bare = (if (cr) line.dropLast(1) else line).replace(MARKS, "")
            val out = if (p == NONE) bare else {
                val at = AFTER.find(bare)?.range?.first ?: bare.trimEnd().length
                bare.substring(0, at).trimEnd() + " ${p.mark}" + bare.substring(at)
            }
            return out + if (cr) "\r" else ""
        }
    }
}
