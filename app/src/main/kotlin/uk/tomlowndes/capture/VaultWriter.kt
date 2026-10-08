package uk.tomlowndes.capture

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale

/** An open task from the "## Active" section; [raw] is the exact line as it appears in the note. */
data class TaskItem(val raw: String, val text: String, val due: LocalDate?)

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
        ctx.contentResolver.openInputStream(file.uri)?.bufferedReader()?.readText().orEmpty()
            .split("\n").toMutableList()

    private fun write(file: DocumentFile, content: String) {
        ctx.contentResolver.openOutputStream(file.uri, "wt")?.use { it.write(content.toByteArray()) }
            ?: error("Cannot write ${file.name}")
    }

    private fun save(file: DocumentFile, lines: List<String>) {
        val out = lines.joinToString("\n")
        write(file, if (out.endsWith("\n")) out else out + "\n")
    }

    private fun MutableList<String>.activeStart() = indexOfFirst { it.trim() == "## Active" }
    private fun MutableList<String>.archiveStart() = indexOfFirst { it.trim() == "## Archive" }

    /** Inserts [line] at the end of the "## Active" list. */
    private fun MutableList<String>.insertActive(line: String) {
        val active = activeStart()
        val archive = archiveStart()
        val at = if (active >= 0) {
            var i = if (archive > active) archive else size
            while (i > active + 1 && this[i - 1].isBlank()) i--
            i
        } else size
        add(at, line)
    }

    fun addTask(text: String, due: String?) {
        val file = taskFile(true) ?: error("Cannot create task note")
        val lines = readLines(file)
        lines.insertActive("- [ ] #task ${text.trim()}" + (due?.let { " 📅 $it" } ?: ""))
        save(file, lines)
    }

    /** Open tasks in the order they appear in the note. */
    fun listTasks(): List<TaskItem> {
        val file = taskFile(false) ?: return emptyList()
        val lines = readLines(file)
        val start = lines.activeStart() + 1
        val archive = lines.archiveStart()
        val end = if (archive > start) archive else lines.size
        return lines.subList(start, end).mapNotNull { parse(it) }
    }

    private fun parse(line: String): TaskItem? {
        val body = OPEN_TASK.matchEntire(line.trimEnd())?.groupValues?.get(1) ?: return null
        val due = DUE.find(body)?.groupValues?.get(1)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val text = body.replace("#task", "").replace(DUE, "").replace(INLINE_FIELD, "")
            .replace(Regex("\\s+"), " ").trim()
        return TaskItem(line, text.ifBlank { body.trim() }, due)
    }

    /** Ticks the task and moves it to the top of "## Archive". Returns the completed line (for undo). */
    fun completeTask(raw: String): String {
        val file = taskFile(false) ?: error("Task note not found")
        val lines = readLines(file)
        val i = lines.indexOf(raw)
        if (i < 0) error("Task changed in Obsidian, refreshed")
        lines.removeAt(i)
        val ticked = raw.replaceFirst("- [ ]", "- [x]")
        val done = if (ticked.contains("✅")) ticked else ticked.trimEnd() + " ✅ ${LocalDate.now()}"
        val archive = lines.archiveStart()
        if (archive >= 0) {
            var at = archive + 1
            if (at < lines.size && lines[at].isBlank()) at++
            lines.add(at, done)
        } else {
            lines.addAll(listOf("", "## Archive", "", done))
        }
        save(file, lines)
        return done
    }

    fun deleteTask(raw: String) {
        val file = taskFile(false) ?: error("Task note not found")
        val lines = readLines(file)
        if (!lines.remove(raw)) error("Task changed in Obsidian, refreshed")
        save(file, lines)
    }

    /** Undo for [completeTask] (pass its [doneLine]) or [deleteTask] (pass null). */
    fun restoreTask(raw: String, doneLine: String?) {
        val file = taskFile(true) ?: return
        val lines = readLines(file)
        if (doneLine != null) lines.remove(doneLine)
        lines.insertActive(raw)
        save(file, lines)
    }

    /** Writes a note named [name] into the Read It Later inbox (the provider adds .md and de-duplicates). */
    fun addNote(name: String, content: String) {
        val file = dir("$INBOX/$RIL_DIR").createFile("text/markdown", name) ?: error("Cannot create note")
        write(file, content)
    }

    companion object {
        const val INBOX = "01 - Inbox"
        const val RIL_DIR = "ReadItLater Inbox"
        const val TASK_FILE = "⚡ Quick Task.md"
        private val OPEN_TASK = Regex("^\\s*- \\[ \\] (.*)$")
        private val DUE = Regex("📅\\s*(\\d{4}-\\d{2}-\\d{2})")
        private val INLINE_FIELD = Regex("\\[\\w+:: [^\\]]*\\]")
    }
}
