package uk.tomlowndes.capture

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.time.LocalDate

/** An open task from the "## Active" section; [raw] is the exact line as it appears in the note. */
data class TaskItem(val raw: String, val text: String, val due: LocalDate?, val recurrence: String? = null)

/** Result of ticking a task: the archived line and, for recurring tasks, the follow-up line left in Active. */
data class Completion(val doneLine: String, val nextLine: String?)

/** Reads and writes the Obsidian vault through the Storage Access Framework folder the user picked. */
class VaultWriter(private val ctx: Context, treeUri: Uri) {
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

    /** Edits the task note (redone on the fresh copy if a sync lands meanwhile) and lets the home screen widget know. */
    private fun edit(file: DocumentFile, change: (MutableList<String>) -> Unit) {
        VaultIo.edit(ctx, file, change)
        Widgets.tasksChanged(ctx)
    }

    fun addTask(text: String, due: String?) {
        val file = taskFile(true) ?: error("Cannot create task note")
        edit(file) { TaskNote.insertActive(it, TaskNote.newTask(text, due)) }
    }

    /** Open tasks in the order they appear in the note. */
    fun listTasks(): List<TaskItem> {
        val file = taskFile(false) ?: return emptyList()
        return TaskNote.list(readLines(file))
    }

    /**
     * Ticks the task and moves it to the top of "## Archive". With [recurring], a task that has a 🔁 rule
     * also leaves its next occurrence in place, like the Tasks plugin does.
     */
    fun completeTask(raw: String, recurring: Boolean = true): Completion {
        val file = taskFile(false) ?: error("Task note not found")
        val c = TaskNote.completion(raw, LocalDate.now(), recurring)
        edit(file) { TaskNote.complete(it, raw, c) }
        return c
    }

    /** Changes (or clears, for null) a task's due date in place. Returns the task's new line. */
    fun rescheduleTask(raw: String, date: LocalDate?): String {
        val file = taskFile(false) ?: error("Task note not found")
        val updated = TaskRules.withDue(raw, date)
        edit(file) { TaskNote.replace(it, raw, updated) }
        return updated
    }

    fun deleteTask(raw: String) {
        val file = taskFile(false) ?: error("Task note not found")
        edit(file) { TaskNote.delete(it, raw) }
    }

    /** Undo for [completeTask] (pass its [Completion]), [deleteTask] (null) or a reschedule ([replace]). */
    fun restoreTask(raw: String, done: Completion?, replace: String? = null) {
        val file = taskFile(true) ?: return
        edit(file) { TaskNote.restore(it, raw, done, replace) }
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
