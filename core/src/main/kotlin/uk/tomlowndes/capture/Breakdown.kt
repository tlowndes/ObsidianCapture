package uk.tomlowndes.capture

/** One small step from Break it down. */
data class Step(val text: String, val minutes: Int?)

/**
 * Break it down (build-queue T3): Jarvis splits a task into small steps, and they're saved as indented subtasks under
 * the original line so Obsidian shows them nested. [level] is the chillies: 1 = a few steps, 4 = tiny ones.
 */
object Breakdown {
    const val LEVELS = 4
    private val STEP = Regex("^\\s*(?:[-*•]|\\d+[.)])\\s+(?:\\[[ xX]\\]\\s+)?(.+?)\\s*$")
    private val MIN = Regex("(?:·|-|–|—|\\(|,)?\\s*(?:⏱\\s*)?(\\d{1,3})\\s*(?:m|min|mins|minutes)\\)?\\s*$", RegexOption.IGNORE_CASE)

    fun count(level: Int) = when (level.coerceIn(1, LEVELS)) { 1 -> 3; 2 -> 5; 3 -> 7; else -> 10 }

    /** What Jarvis is asked. The reply is read by [parse]. */
    fun prompt(task: String, level: Int): String =
        "Break this task into about ${count(level)} small, concrete first steps, each a few minutes long, in order. " +
            "Reply with only a list, one step per line, like \"- Open Figma, sort by last edited · 2m\". No intro.\n\nTask: $task"

    /** List lines from a reply, with a minutes estimate when the line ends with one. */
    fun parse(reply: String): List<Step> = reply.replace("\r", "").split("\n").mapNotNull { line ->
        val body = STEP.matchEntire(line)?.groupValues?.get(1) ?: return@mapNotNull null
        val m = MIN.find(body)
        val text = (if (m != null) body.substring(0, m.range.first) else body).trim().trim('·', '-', '–', ',').trim()
            .replace(Regex("^\\*\\*(.+)\\*\\*$"), "$1")
        if (text.isEmpty()) null else Step(text, m?.groupValues?.get(1)?.toIntOrNull())
    }.take(12)

    /** Steps for the demo address, so the sheet can be tried without the server. */
    fun demo(task: String, level: Int): List<Step> = listOf(
        Step("Open what you need for \"${task.take(40)}\"", 2),
        Step("Write down what done looks like", 2),
        Step("Do the smallest first bit", 5),
        Step("Check what's left and pick the next bit", 2),
        Step("Do the next bit", 10),
        Step("Tidy up and note where you got to", 5),
        Step("Tick it off", 1),
        Step("Have a short break", 5),
        Step("Look over it once more", 5),
        Step("Close everything", 1),
    ).take(count(level))

    /** The subtask line for [s], indented under a parent with [indent]. Tagged #task when the parent is. */
    fun line(s: Step, indent: String, tagTask: Boolean): String =
        "$indent    - [ ] ${s.text}" + (if (tagTask) " #task" else "") + (s.minutes?.let { " ⏱ ${it}m" } ?: "")

    /**
     * Puts [steps] under the line [raw] in [lines], after any subtasks it already has. Throws when the line has gone.
     */
    fun insert(lines: MutableList<String>, raw: String, steps: List<Step>) {
        val at = lines.indexOfFirst { it.trimEnd() == raw.trimEnd() }
        require(at >= 0) { "Task moved in Obsidian, refreshed" }
        val indent = raw.takeWhile { it == ' ' || it == '\t' }
        val depth = indent.replace("\t", "    ").length
        var end = at + 1
        while (end < lines.size && lines[end].isNotBlank() &&
            lines[end].takeWhile { it == ' ' || it == '\t' }.replace("\t", "    ").length > depth) end++
        val tag = Regex("(^|\\s)#task(\\s|$)").containsMatchIn(raw)
        lines.addAll(end, steps.map { line(it, indent, tag) })
    }
}
