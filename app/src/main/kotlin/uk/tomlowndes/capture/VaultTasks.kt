package uk.tomlowndes.capture

/**
 * Tasks kept in any note of the vault, not just Quick Task, as plain functions on a note's lines so they can be
 * tested without a phone. A task is an open `- [ ]` line tagged `#task`, like the Tasks plugin's global filter.
 * Done tasks are ticked where they are; only Quick Task moves them to its Archive.
 */
object VaultTasks {
    const val QUICK_TASK = "${VaultWriter.INBOX}/${VaultWriter.TASK_FILE}"

    /** Folders never scanned for tasks (besides dot-folders and the vault's templates folder). */
    val SKIP = listOf("99 - Meta", "${VaultWriter.INBOX}/${VaultWriter.RIL_DIR}")

    /** Open `#task` lines in [lines], skipping frontmatter and code blocks (where `tasks` queries live). */
    fun parseNote(lines: List<String>, path: String): List<TaskItem> {
        val out = mutableListOf<TaskItem>()
        var fence = false
        var i = 0
        if (lines.firstOrNull()?.trim() == "---") {
            val end = (1 until lines.size).firstOrNull { lines[it].trim() == "---" }
            if (end != null) i = end + 1
        }
        while (i < lines.size) {
            val line = lines[i++]
            if (line.trimStart().startsWith("```") || line.trimStart().startsWith("~~~")) { fence = !fence; continue }
            if (fence || !TAG.containsMatchIn(line)) continue
            TaskNote.parse(line)?.let { out.add(it.copy(path = path)) }
        }
        return out
    }

    /** Whether [path] (a vault-relative folder or note path) is left out of the scan. */
    fun skipped(path: String, templates: List<String>): Boolean {
        if (path.split("/").any { it.startsWith(".") }) return true
        return (SKIP + templates).filter { it.isNotBlank() }.any { path == it || path.startsWith("$it/") }
    }

    /** Ticks [raw] where it is; a recurring task's next occurrence goes just above it, as the Tasks plugin does. */
    fun completeInPlace(lines: MutableList<String>, raw: String, c: Completion) {
        val i = lines.indexOf(raw)
        if (i < 0) error("Task changed in Obsidian, refreshed")
        lines[i] = c.doneLine
        if (c.nextLine != null) lines.add(i, c.nextLine)
    }

    /** Removes [raw] and returns the line it was on, for [restoreInPlace]. */
    fun delete(lines: MutableList<String>, raw: String): Int {
        val i = lines.indexOf(raw)
        if (i < 0) error("Task changed in Obsidian, refreshed")
        lines.removeAt(i)
        return i
    }

    /** Undo for [completeInPlace] (pass its [Completion]), [delete] (its line [at]) or a reschedule ([replace]). */
    fun restoreInPlace(lines: MutableList<String>, raw: String, done: Completion?, replace: String? = null, at: Int? = null) {
        if (done != null) {
            val i = lines.indexOf(done.doneLine)
            if (i < 0) error("Task changed in Obsidian")
            lines[i] = raw
            done.nextLine?.let { next -> lines.indexOf(next).takeIf { it >= 0 }?.let { lines.removeAt(it) } }
            return
        }
        if (replace != null) {
            val i = lines.indexOf(replace)
            if (i < 0) error("Task changed in Obsidian")
            lines[i] = raw
            return
        }
        lines.add((at ?: lines.size).coerceIn(0, lines.size), raw)
    }

    /** "Projects/House move.md" -> "House move". */
    fun noteName(path: String) = path.substringAfterLast("/").removeSuffix(".md")

    private val TAG = Regex("(^|\\s)#task(/|\\s|$)")
}
