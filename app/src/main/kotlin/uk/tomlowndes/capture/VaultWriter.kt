package uk.tomlowndes.capture

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.time.LocalDate

/** An open task; [raw] is the exact line as it appears in the note at vault [path]. */
data class TaskItem(
    val raw: String, val text: String, val due: LocalDate?, val recurrence: String? = null,
    val path: String = VaultTasks.QUICK_TASK
)

/** Result of ticking a task: the archived line and, for recurring tasks, the follow-up line left in Active. */
data class Completion(val doneLine: String, val nextLine: String?)

/** Reads and writes the Obsidian vault through the Storage Access Framework folder the user picked. */
class VaultWriter(private val ctx: Context, private val treeUri: Uri) {
    private val root = DocumentFile.fromTreeUri(ctx, treeUri) ?: error("Vault folder unavailable")

    private fun dir(path: String): DocumentFile {
        var cur = root
        for (part in path.split("/")) {
            cur = cur.findFile(part)?.takeIf { it.isDirectory }
                ?: cur.createDirectory(part) ?: error("Cannot create $part")
        }
        return cur
    }

    private fun taskFile(create: Boolean): DocumentFile? {
        val inbox = dir(INBOX)
        return inbox.findFile(TASK_FILE)
            ?: if (create) inbox.createFile("text/markdown", TASK_FILE.removeSuffix(".md")) else null
    }

    private fun readLines(file: DocumentFile): MutableList<String> =
        VaultIo.read(ctx, file).split("\n").toMutableList()

    /** Edits a note (redone on the fresh copy if a sync lands meanwhile) and lets the home screen widget know. */
    private fun edit(file: DocumentFile, change: (MutableList<String>) -> Unit) {
        VaultIo.edit(ctx, file, change)
        Widgets.tasksChanged(ctx)
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
    fun listTasks(): List<TaskItem> = listQuickTasks() + VaultScan.tasks(ctx, treeUri)

    private fun isQuick(t: TaskItem) = t.path == VaultTasks.QUICK_TASK

    /** The note [t] lives in. */
    private fun noteOf(t: TaskItem): DocumentFile {
        if (isQuick(t)) return taskFile(false) ?: error("Task note not found")
        var cur = root
        for (part in t.path.split("/")) cur = cur.findFile(part) ?: error("Note moved in Obsidian, refreshed")
        return cur
    }

    /**
     * Ticks the task. In Quick Task it moves to the top of "## Archive"; in any other note it is ticked where it is.
     * With [recurring], a task that has a 🔁 rule also leaves its next occurrence in place, like the Tasks plugin does.
     */
    fun completeTask(t: TaskItem, recurring: Boolean = true): Completion {
        val c = TaskNote.completion(t.raw, LocalDate.now(), recurring)
        edit(noteOf(t)) { if (isQuick(t)) TaskNote.complete(it, t.raw, c) else VaultTasks.completeInPlace(it, t.raw, c) }
        return c
    }

    /** Changes (or clears, for null) a task's due date in place. Returns the task's new line. */
    fun rescheduleTask(t: TaskItem, date: LocalDate?): String {
        val updated = TaskRules.withDue(t.raw, date)
        edit(noteOf(t)) { TaskNote.replace(it, t.raw, updated) }
        return updated
    }

    /** Removes the task. Returns the line it was on, for [restoreTask]. */
    fun deleteTask(t: TaskItem): Int {
        var at = -1
        edit(noteOf(t)) { at = VaultTasks.delete(it, t.raw) }
        return at
    }

    /** Undo for [completeTask] (pass its [Completion]), [deleteTask] (its line [at]) or a reschedule ([replace]). */
    fun restoreTask(t: TaskItem, done: Completion?, replace: String? = null, at: Int? = null) {
        if (isQuick(t)) {
            val file = taskFile(true) ?: return
            edit(file) { TaskNote.restore(it, t.raw, done, replace) }
        } else {
            edit(noteOf(t)) { VaultTasks.restoreInPlace(it, t.raw, done, replace, at) }
        }
    }

    /** Every note in the vault except dot-folders, for quick search. */
    fun allNotes(): List<VaultNote> = VaultScan.notes(ctx, treeUri) { p -> p.split("/").any { it.startsWith(".") } }

    /** Adds a `#task` line (with optional [due]) under the note's "## Tasks", or [text] as a plain line at its end. */
    fun addToNote(note: VaultNote, text: String, asTask: Boolean, due: LocalDate?) {
        val line = if (asTask) TaskNote.newTask(text.replace(Regex("\\s*\\n\\s*"), " "), due?.toString()) else text.trim()
        edit(note.file(ctx)) { if (asTask) NoteSearch.addTask(it, line) else NoteSearch.addLine(it, line) }
    }

    /** Writes a note named [name] into the Read It Later inbox (the provider adds .md and de-duplicates). */
    fun addNote(name: String, content: String) {
        val file = dir("$INBOX/$RIL_DIR").createFile("text/markdown", name) ?: error("Cannot create note")
        VaultIo.write(ctx, file, content)
    }

    companion object {
        const val INBOX = "01 - Inbox"
        const val RIL_DIR = "ReadItLater Inbox"
        const val TASK_FILE = "⚡ Quick Task.md"
    }
}
