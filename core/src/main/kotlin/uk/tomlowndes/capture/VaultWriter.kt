package uk.tomlowndes.capture

import java.io.File
import java.time.LocalDate

/** An open task; [raw] is the exact line as it appears in the note at vault [path]. */
data class TaskItem(
    val raw: String, val text: String, val due: LocalDate?, val recurrence: String? = null,
    val path: String = VaultTasks.QUICK_TASK
)

/** A ticked task; [raw] is its `- [x]` line in the note at [path], [done] its ✅ date. */
data class TickedTask(val raw: String, val text: String, val done: LocalDate?, val path: String = VaultTasks.QUICK_TASK)

/** Open tasks plus ticked ones (Quick Task's Archive first, newest first, then the rest of the vault). */
data class TaskLists(val open: List<TaskItem>, val done: List<TickedTask>)

/**
 * Result of ticking a task: the archived line and, for recurring tasks, the follow-up line left in Active.
 * In Quick Task, [at] is the line it was on and [steps] its subtasks as they were, so Undo can put both back.
 */
data class Completion(val doneLine: String, val nextLine: String?, val at: Int = -1, val steps: List<String> = emptyList())

/** Where a removed task was ([at]) and, in Quick Task, its subtasks as they were, for Undo. */
data class Removed(val at: Int, val steps: List<String> = emptyList())

/**
 * Tasks and quick notes in the vault. [cacheFile] keeps the vault-wide task scan between runs; [onTasksChanged]
 * runs after every task edit (Android refreshes its home screen widget there).
 */
class VaultWriter(
    private val vault: Vault, private val cacheFile: File? = null, private val onTasksChanged: () -> Unit = {},
    /** How long the vault's note list may be reused before walking every folder again (0: walk every time). */
    private val walkTtlMs: Long = 0,
) {
    private val taskPath = "$INBOX/$TASK_FILE"

    private fun taskFile(create: Boolean): String? = when {
        vault.entry(taskPath) != null -> taskPath
        create -> { refuseIfMoved(); vault.create(INBOX, TASK_FILE.removeSuffix(".md"), "md") }
        else -> null
    }

    /**
     * Quick Task missing from the inbox usually means something moved it (2026-11-11: it turned up in
     * 05 - Resources/Personal and a fresh, near-empty one was made in its place). Stop rather than start a second one.
     */
    private fun refuseIfMoved() {
        val moved = runCatching { vault.notes { p -> p.split("/").any { it.startsWith(".") } } }.getOrDefault(emptyList())
            .firstOrNull { it.path != taskPath && it.path.substringAfterLast('/') == TASK_FILE }
        if (moved != null) error("$TASK_FILE was moved to ${moved.path.substringBeforeLast('/')}. Move it back to $INBOX, then try again")
    }

    private fun readLines(path: String): MutableList<String> = VaultIo.read(vault, path).split("\n").toMutableList()

    /** Edits a note (redone on the fresh copy if a sync lands meanwhile) and lets listeners know. */
    private fun edit(path: String, change: (MutableList<String>) -> Unit) {
        VaultIo.edit(vault, path, change = change)
        onTasksChanged()
    }

    fun addTask(text: String, due: String?) {
        val file = taskFile(true) ?: error("Cannot create task note")
        edit(file) { TaskNote.insertActive(it, TaskNote.newTask(text, due)) }
    }

    /** Open tasks in Quick Task's "## Active" section, in note order. */
    fun listQuickTasks(): List<TaskItem> {
        val file = taskFile(false) ?: return emptyList()
        return TaskNote.list(readLines(file))
    }

    /** Quick Task's open tasks first, then `#task` tasks from every other note in the vault. */
    fun listTasks(): List<TaskItem> = listQuickTasks() + VaultScan.tasks(vault, cacheFile, walkTtlMs)

    /** [listTasks] plus ticked tasks, from the same vault scan. */
    fun listTaskLists(): TaskLists {
        val quick = taskFile(false)?.let { readLines(it) }
        val (open, done) = VaultScan.scan(vault, cacheFile, walkTtlMs)
        return TaskLists(
            (quick?.let { TaskNote.list(it) } ?: emptyList()) + open,
            (quick?.let { TaskNote.listDone(it) } ?: emptyList()) + done
        )
    }

    /** Un-ticks [d]; [nextLine] is the follow-up a repeat left when it was ticked, removed again if still there. */
    fun untickTask(d: TickedTask, nextLine: String? = null) {
        if (d.path == VaultTasks.QUICK_TASK) {
            val file = taskFile(false) ?: error("Task note not found")
            edit(file) { TaskNote.untick(it, d.raw, nextLine) }
        } else {
            val path = d.path.takeIf { vault.entry(it)?.isDir == false } ?: error("Note moved in Obsidian, refreshed")
            edit(path) { VaultTasks.untickInPlace(it, d.raw, nextLine) }
        }
    }

    private fun isQuick(t: TaskItem) = t.path == VaultTasks.QUICK_TASK

    /** The note [t] lives in. */
    private fun noteOf(t: TaskItem): String {
        if (isQuick(t)) return taskFile(false) ?: error("Task note not found")
        return t.path.takeIf { vault.entry(it)?.isDir == false } ?: error("Note moved in Obsidian, refreshed")
    }

    /**
     * Ticks the task. In Quick Task it moves to the top of "## Archive"; in any other note it is ticked where it is.
     * With [recurring], a task that has a 🔁 rule also leaves its next occurrence in place, like the Tasks plugin does.
     */
    fun completeTask(t: TaskItem, recurring: Boolean = true): Completion {
        val c = TaskNote.completion(t.raw, LocalDate.now(), recurring)
        var out = c
        edit(noteOf(t)) { if (isQuick(t)) out = TaskNote.complete(it, t.raw, c) else VaultTasks.completeInPlace(it, t.raw, c) }
        return out
    }

    /**
     * Rewrites this task's wording, due date, recurrence and estimate on its current line.
     * Anything else on the line (priority, scheduled date, fields the app doesn't edit) stays.
     * If that exact line is no longer in the note, throws and writes nothing.
     * Returns the line as it now stands.
     */
    fun editTask(t: TaskItem, text: String, due: LocalDate?, recurrence: String?, minutes: Int?, priority: Priority? = null): String {
        val edited = TaskRules.editedLine(t.raw, text, due, recurrence, minutes)
        // Priority (priority-flags-v1): null leaves the line's mark alone.
        val updated = if (priority == null || priority == Priority.of(edited)) edited else Priority.with(edited, priority)
        edit(noteOf(t)) { TaskNote.replace(it, t.raw, updated) }
        return updated
    }

    /** Changes (or clears, for null) a task's due date in place. Returns the task's new line. */
    fun rescheduleTask(t: TaskItem, date: LocalDate?): String {
        val updated = TaskRules.withDue(t.raw, date)
        edit(noteOf(t)) { TaskNote.replace(it, t.raw, updated) }
        return updated
    }

    /** Marks the task cancelled (`- [-] … ❌ date`) where it is; the line is kept. */
    fun cancelTask(t: TaskItem, today: LocalDate = LocalDate.now()) {
        edit(noteOf(t)) { TaskNote.replace(it, t.raw, WeeklyReview.cancelled(t.raw, today)) }
    }

    /** Gives the task a time today (Visual day), or clears it for null. Returns the new line. */
    fun setTime(t: TaskItem, start: java.time.LocalTime?, minutes: Int): String {
        val updated = DayPlan.withTime(t.raw, start, minutes)
        edit(noteOf(t)) { TaskNote.replace(it, t.raw, updated) }
        return updated
    }

    /** Drag to a free gap (windows-catchup-v1): due [date] and a [start] time for its estimate. Returns the new line. */
    fun planAt(t: TaskItem, date: LocalDate, start: java.time.LocalTime): String {
        val updated = DayPlan.withTime(TaskRules.withDue(t.raw, date), start, DayPlan.minutes(t))
        edit(noteOf(t)) { TaskNote.replace(it, t.raw, updated) }
        return updated
    }

    /** Break it down: adds [steps] as indented subtasks under the task. */
    fun addSubtasks(t: TaskItem, steps: List<Step>) {
        if (steps.isEmpty()) return
        edit(noteOf(t)) { Breakdown.insert(it, t.raw, steps) }
    }

    /** Sets (or clears) the task's `⏱` estimate in place. Returns the new line. */
    fun setEstimate(t: TaskItem, minutes: Int?): String {
        val updated = TaskRules.withMinutes(t.raw, minutes)
        edit(noteOf(t)) { TaskNote.replace(it, t.raw, updated) }
        return updated
    }

    /** Removes the task. Returns where it was, for [restoreTask]. In Quick Task its subtasks stay, moved up a level. */
    fun deleteTask(t: TaskItem): Removed {
        var out = Removed(-1)
        edit(noteOf(t)) { out = if (isQuick(t)) TaskNote.delete(it, t.raw) else Removed(VaultTasks.delete(it, t.raw)) }
        return out
    }

    /** Undo for [completeTask] (pass its [Completion]), [deleteTask] (its line [at]) or a reschedule ([replace]). */
    fun restoreTask(t: TaskItem, done: Completion?, replace: String? = null, at: Removed? = null) {
        if (isQuick(t)) {
            val file = taskFile(true) ?: return
            edit(file) { TaskNote.restore(it, t.raw, done, replace, at) }
        } else {
            edit(noteOf(t)) { VaultTasks.restoreInPlace(it, t.raw, done, replace, at?.at) }
        }
    }

    /** Every note in the vault except dot-folders, for quick search. */
    fun allNotes(): List<VaultNote> = vault.notes { p -> p.split("/").any { it.startsWith(".") } }

    /** Adds a `#task` line (with optional [due]) under the note's "## Tasks", or [text] as a plain line at its end. */
    fun addToNote(note: VaultNote, text: String, asTask: Boolean, due: LocalDate?) {
        val line = if (asTask) TaskNote.newTask(text.replace(Regex("\\s*\\n\\s*"), " "), due?.toString()) else text.trim()
        edit(note.path) { if (asTask) NoteSearch.addTask(it, line) else NoteSearch.addLine(it, line) }
    }

    /** Writes a note named [name] (a free name is picked if it's taken) into the Read It Later inbox. */
    fun addNote(name: String, content: String) {
        VaultIo.write(vault, vault.create("$INBOX/$RIL_DIR", name, "md"), content)
    }

    /** Writes a note named [name] (a free name is picked if it's taken) into the inbox. */
    fun addInboxNote(name: String, content: String) {
        VaultIo.write(vault, vault.create(INBOX, name, "md"), content)
    }

    /** True when an inbox note named [name] (or "[name] (1)") already holds exactly [content]. */
    fun hasInboxNote(name: String, content: String): Boolean =
        inboxNamed(name).any { VaultIo.read(vault, "$INBOX/${it.name}") == content }

    /**
     * Writes [content] into the inbox under [name]. If that exact note is already there, or an empty
     * file was left by a failed write of it, that file is reused so a retry doesn't add a second copy.
     * Returns the note's path.
     */
    fun putInboxNote(name: String, content: String): String {
        val named = inboxNamed(name)
        named.firstOrNull { VaultIo.read(vault, "$INBOX/${it.name}") == content }?.let { return "$INBOX/${it.name}" }
        val hole = named.firstOrNull { VaultIo.read(vault, "$INBOX/${it.name}").isEmpty() }
        if (hole != null) {
            val path = "$INBOX/${hole.name}"
            VaultIo.write(vault, path, content)
            return path
        }
        val path = vault.create(INBOX, name, "md")
        try {
            VaultIo.write(vault, path, content)
        } catch (e: Exception) {
            runCatching { vault.delete(path) }
            throw e
        }
        return path
    }

    /** Adds [lines] at the end of the inbox's running note (`01 - Inbox/Inbox.md`), making it if needed. Rapid-log notes and events go here. */
    fun appendInbox(lines: List<String>) {
        if (lines.isEmpty()) return
        val path = "$INBOX/$INBOX_NOTE"
        if (vault.entry(path) == null) VaultIo.write(vault, vault.create(INBOX, INBOX_NOTE.removeSuffix(".md"), "md"), "")
        VaultIo.edit(vault, path) { ls ->
            while (ls.isNotEmpty() && ls.last().isBlank()) ls.removeAt(ls.size - 1)
            ls.addAll(lines)
            ls.add("")
        }
    }

    /** Removes the note at [path]. Used to undo a capture note when the rest of the save fails. */
    fun deleteNote(path: String) { vault.delete(path); VaultScan.touched(path) }

    private fun inboxNamed(name: String): List<Vault.Entry> {
        val re = Regex("^${Regex.escape(name)}( \\(\\d+\\))?\\.md$")
        return vault.list(INBOX).filter { !it.isDir && re.matches(it.name) }
    }

    companion object {
        const val INBOX = "01 - Inbox"
        const val RIL_DIR = "ReadItLater Inbox"
        const val TASK_FILE = "⚡ Quick Task.md"
        const val INBOX_NOTE = "Inbox.md"
    }
}
