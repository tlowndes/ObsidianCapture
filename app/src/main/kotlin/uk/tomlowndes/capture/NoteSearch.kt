package uk.tomlowndes.capture

/**
 * Quick search over note names and folders, and adding a task or a line to the note you pick, as plain functions
 * so they can be tested without a phone.
 */
object NoteSearch {
    const val TASKS = "Tasks"
    private val LIST_ITEM = Regex("^\\s*([-*+]|\\d+\\.)\\s")

    /**
     * Notes whose path contains every word of [query] (any case). Name matches come before folder-only matches,
     * then names that start with the query, then shorter names. A blank query lists the most recently changed.
     */
    fun match(notes: List<VaultNote>, query: String, limit: Int = 50): List<VaultNote> =
        match(notes, query, limit, { it.path }, { it.modified })

    /** [match] over anything with a vault path and a modified time. */
    fun <T> match(notes: List<T>, query: String, limit: Int, pathOf: (T) -> String, modifiedOf: (T) -> Long): List<T> {
        val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return notes.sortedByDescending(modifiedOf).take(limit)
        val q = query.trim().lowercase()
        return notes.asSequence()
            .filter { n -> val p = pathOf(n).lowercase(); words.all { p.contains(it) } }
            .sortedWith(compareBy<T>(
                { n -> val name = VaultTasks.noteName(pathOf(n)).lowercase(); if (words.all { name.contains(it) }) 0 else 1 },
                { n -> if (VaultTasks.noteName(pathOf(n)).lowercase().startsWith(q)) 0 else 1 },
                { n -> VaultTasks.noteName(pathOf(n)).length }
            ))
            .take(limit).toList()
    }

    /** "Projects/House move/House move.md" -> "Projects/House move", "" at the vault root. */
    fun folder(path: String) = path.substringBeforeLast("/", "")

    /** Adds [task] at the end of the note's "## Tasks" list if it has one, otherwise at the end of the note. */
    fun addTask(lines: MutableList<String>, task: String) {
        val r = Md.sectionRange(lines, TASKS)
        if (r == null) { append(lines, task); return }
        var at = r.last + 1
        while (at > r.first && lines[at - 1].isBlank()) at--
        // Straight after the heading: keep one blank line under it, as Obsidian does.
        if (at == r.first) { lines.add(at, ""); at++ }
        lines.add(at, task)
    }

    /** Adds [line] at the end of the note. */
    fun addLine(lines: MutableList<String>, line: String) = append(lines, line)

    private fun append(lines: MutableList<String>, line: String) {
        while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.size - 1)
        val last = lines.lastOrNull()
        // A plain line straight after a list item would be read as part of that item, so leave a gap.
        val gap = last != null && !(LIST_ITEM.containsMatchIn(last) && LIST_ITEM.containsMatchIn(line)) &&
            (LIST_ITEM.containsMatchIn(last) || last.startsWith("#") || last.trim() == "---")
        if (gap) lines.add("")
        lines.add(line)
        lines.add("")
    }
}
