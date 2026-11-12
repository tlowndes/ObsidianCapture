package uk.tomlowndes.capture

import java.time.LocalDate

/**
 * The edits [VaultWriter] makes to the Quick Task note, as plain functions on its lines so they can be tested
 * without a phone. Open tasks live under "## Active", ticked ones under "## Archive".
 */
object TaskNote {
    private val OPEN_TASK = Regex("^\\s*- \\[ \\] (.*)$")
    private val DONE_TASK = Regex("^\\s*- \\[[xX]\\] (.*)$")
    private val DONE_DATE = Regex("✅\\s*(\\d{4}-\\d{2}-\\d{2})")

    private fun List<String>.activeStart() = indexOfFirst { it.trim() == "## Active" }
    private fun List<String>.archiveStart() = indexOfFirst { it.trim() == "## Archive" }

    fun newTask(text: String, due: String?) = "- [ ] #task ${text.trim()}" + (due?.let { " 📅 $it" } ?: "")

    /** Inserts [line] at the end of the "## Active" list. */
    fun insertActive(lines: MutableList<String>, line: String) {
        val active = lines.activeStart()
        val archive = lines.archiveStart()
        val at = if (active >= 0) {
            var i = if (archive > active) archive else lines.size
            while (i > active + 1 && lines[i - 1].isBlank()) i--
            i
        } else lines.size
        lines.add(at, line)
    }

    /** Open tasks in the order they appear in the note. */
    fun list(lines: List<String>): List<TaskItem> {
        val start = lines.activeStart() + 1
        val archive = lines.archiveStart()
        val end = if (archive > start) archive else lines.size
        return lines.subList(start, end).mapNotNull { parse(it) }
    }

    fun parse(line: String): TaskItem? {
        val body = OPEN_TASK.matchEntire(line.trimEnd())?.groupValues?.get(1) ?: return null
        val text = TaskRules.cleanText(body)
        return TaskItem(line, text.ifBlank { body.trim() }, TaskRules.due(body), TaskRules.recurrence(body))
    }

    /** A ticked `- [x]` line, or null. */
    fun parseDone(line: String): TickedTask? {
        val body = DONE_TASK.matchEntire(line.trimEnd())?.groupValues?.get(1) ?: return null
        val text = TaskRules.cleanText(body)
        val date = DONE_DATE.find(body)?.groupValues?.get(1)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        return TickedTask(line, text.ifBlank { body.trim() }, date)
    }

    /** Ticked tasks under "## Archive", newest first (the app puts each one at the top). */
    fun listDone(lines: List<String>): List<TickedTask> {
        val archive = lines.archiveStart()
        if (archive < 0) return emptyList()
        val end = (archive + 1 until lines.size).firstOrNull { lines[it].startsWith("## ") } ?: lines.size
        return lines.subList(archive + 1, end).mapNotNull { parseDone(it) }
    }

    /** [doneLine] as an open task again: `- [ ]` and no ✅ date. */
    fun reopened(doneLine: String): String =
        doneLine.replaceFirst(Regex("- \\[[xX]\\]"), "- [ ]").replace(Regex("\\s*✅\\s*\\d{4}-\\d{2}-\\d{2}"), "")

    /**
     * Un-ticks [doneLine] in Quick Task: it leaves "## Archive" and goes back into "## Active" (in place of
     * [nextLine], the follow-up a repeat left behind, when that is still there).
     */
    fun untick(lines: MutableList<String>, doneLine: String, nextLine: String?) {
        val i = lines.indexOf(doneLine)
        if (i < 0) error("Task changed in Obsidian, refreshed")
        lines.removeAt(i)
        val open = reopened(doneLine)
        val at = nextLine?.let { lines.indexOf(it) } ?: -1
        if (at >= 0) lines[at] = open else insertActive(lines, open)
    }

    /** What ticking [raw] on [today] produces; nothing in the note changes yet. */
    fun completion(raw: String, today: LocalDate, recurring: Boolean): Completion {
        val next = if (recurring) TaskRules.nextOccurrence(raw, today) else null
        val ticked = raw.replaceFirst("- [ ]", "- [x]")
        val done = if (ticked.contains("✅")) ticked else ticked.trimEnd() + " ✅ $today"
        return Completion(done, next)
    }

    private fun depth(line: String) = line.takeWhile { it == ' ' || it == '\t' }.replace("\t", "    ").length

    /**
     * The subtasks under line [i] (the lines after it that are indented further), moved up a level so they don't
     * end up under the task above once line [i] goes. Returns them as they were, for Undo.
     */
    private fun liftSteps(lines: MutableList<String>, i: Int): List<String> {
        val d = depth(lines[i])
        var end = i + 1
        while (end < lines.size && lines[end].isNotBlank() && depth(lines[end]) > d) end++
        val steps = lines.subList(i + 1, end).toList()
        if (steps.isEmpty()) return steps
        val shift = steps.minOf { depth(it) } - d
        for (k in i + 1 until end) lines[k] = outdent(lines[k], shift)
        return steps
    }

    private fun outdent(line: String, by: Int): String {
        var left = by
        var n = 0
        while (left > 0 && n < line.length && (line[n] == ' ' || line[n] == '\t')) { left -= if (line[n] == '\t') 4 else 1; n++ }
        return line.substring(n)
    }

    /**
     * Replaces [raw] with its next occurrence (if any) and puts the ticked line at the top of "## Archive".
     * Without a next occurrence, its subtasks move up a level instead of slipping under the task above.
     * Returns [c] with where the line was and its subtasks, for [restore].
     */
    fun complete(lines: MutableList<String>, raw: String, c: Completion): Completion {
        val i = lines.indexOf(raw)
        if (i < 0) error("Task changed in Obsidian, refreshed")
        val steps = if (c.nextLine == null) liftSteps(lines, i) else emptyList()
        lines.removeAt(i)
        if (c.nextLine != null) lines.add(i, c.nextLine)
        val archive = lines.archiveStart()
        if (archive >= 0) {
            var at = archive + 1
            if (at < lines.size && lines[at].isBlank()) at++
            lines.add(at, c.doneLine)
        } else {
            lines.addAll(listOf("", "## Archive", "", c.doneLine))
        }
        return c.copy(at = i, steps = steps)
    }

    fun replace(lines: MutableList<String>, raw: String, updated: String) {
        val i = lines.indexOf(raw)
        if (i < 0) error("Task changed in Obsidian, refreshed")
        lines[i] = updated
    }

    /**
     * Rewrites [raw] in place: wording, due date, recurrence and estimate. The line has to still be [raw];
     * if Obsidian (or a sync) changed it, this throws and [lines] is left untouched.
     */
    fun edit(lines: MutableList<String>, raw: String, text: String, due: LocalDate?, recurrence: String?, minutes: Int?) {
        replace(lines, raw, TaskRules.editedLine(raw, text, due, recurrence, minutes))
    }

    /** Removes [raw]; its subtasks move up a level. Returns where it was and the subtasks as they were, for [restore]. */
    fun delete(lines: MutableList<String>, raw: String): Removed {
        val i = lines.indexOf(raw)
        if (i < 0) error("Task changed in Obsidian, refreshed")
        val steps = liftSteps(lines, i)
        lines.removeAt(i)
        return Removed(i, steps)
    }

    /**
     * Undo for [complete] (pass its [Completion]), [delete] (its [Removed]) or a reschedule ([replace]).
     * The task goes back on the line it came from, with its subtasks indented under it again, not at the bottom.
     */
    fun restore(lines: MutableList<String>, raw: String, done: Completion?, replace: String? = null, removed: Removed? = null) {
        if (done != null) lines.remove(done.doneLine)
        val at = (done?.nextLine ?: replace)?.let { lines.indexOf(it) } ?: -1
        if (at >= 0) { lines[at] = raw; return }
        val was = done?.at?.takeIf { it >= 0 } ?: removed?.at ?: -1
        val steps = done?.steps?.takeIf { done.at >= 0 } ?: removed?.steps.orEmpty()
        // The lifted subtasks, where they now sit, so the task goes back just above them.
        val lifted = if (steps.isEmpty()) -1 else liftedAt(lines, steps, raw)
        when {
            lifted >= 0 -> {
                steps.forEachIndexed { k, s -> lines[lifted + k] = s }
                lines.add(lifted, raw)
            }
            was in 1..lines.size && inActive(lines, was) -> lines.add(was, raw)
            else -> insertActive(lines, raw)
        }
    }

    private fun liftedAt(lines: List<String>, steps: List<String>, raw: String): Int {
        val shift = steps.minOf { depth(it) } - depth(raw)
        val now = steps.map { outdent(it, shift) }
        return (0..lines.size - now.size).firstOrNull { s -> now.indices.all { lines[s + it] == now[it] } } ?: -1
    }

    /** True when inserting at [i] keeps the line inside "## Active" (or the note has no headings). */
    private fun inActive(lines: List<String>, i: Int): Boolean {
        val active = lines.activeStart()
        val archive = lines.archiveStart()
        return (active < 0 || i > active) && (archive < 0 || i <= archive)
    }
}
