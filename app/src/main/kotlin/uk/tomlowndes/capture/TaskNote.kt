package uk.tomlowndes.capture

import java.time.LocalDate

/**
 * The edits [VaultWriter] makes to the Quick Task note, as plain functions on its lines so they can be tested
 * without a phone. Open tasks live under "## Active", ticked ones under "## Archive".
 */
object TaskNote {
    private val OPEN_TASK = Regex("^\\s*- \\[ \\] (.*)$")

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

    /** What ticking [raw] on [today] produces; nothing in the note changes yet. */
    fun completion(raw: String, today: LocalDate, recurring: Boolean): Completion {
        val next = if (recurring) TaskRules.nextOccurrence(raw, today) else null
        val ticked = raw.replaceFirst("- [ ]", "- [x]")
        val done = if (ticked.contains("✅")) ticked else ticked.trimEnd() + " ✅ $today"
        return Completion(done, next)
    }

    /** Replaces [raw] with its next occurrence (if any) and puts the ticked line at the top of "## Archive". */
    fun complete(lines: MutableList<String>, raw: String, c: Completion) {
        val i = lines.indexOf(raw)
        if (i < 0) error("Task changed in Obsidian, refreshed")
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
    }

    fun replace(lines: MutableList<String>, raw: String, updated: String) {
        val i = lines.indexOf(raw)
        if (i < 0) error("Task changed in Obsidian, refreshed")
        lines[i] = updated
    }

    fun delete(lines: MutableList<String>, raw: String) {
        if (!lines.remove(raw)) error("Task changed in Obsidian, refreshed")
    }

    /** Undo for [complete] (pass its [Completion]), [delete] (null) or a reschedule ([replace]). */
    fun restore(lines: MutableList<String>, raw: String, done: Completion?, replace: String? = null) {
        if (done != null) lines.remove(done.doneLine)
        val at = (done?.nextLine ?: replace)?.let { lines.indexOf(it) } ?: -1
        if (at >= 0) lines[at] = raw else insertActive(lines, raw)
    }
}
