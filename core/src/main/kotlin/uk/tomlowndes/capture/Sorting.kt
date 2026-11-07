package uk.tomlowndes.capture

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** One task Claude pulled out of a brain dump. [tag] is like "#home"; [due] may be null. */
data class SortedTask(val text: String, val tag: String?, val due: LocalDate?)

/**
 * What Jarvis sorted out of a ramble. Nothing is written until the user taps Save.
 * [note] is a line or two for the inbox, or, when [noteTitle] is set, a whole tidied Markdown note that is saved
 * as its own file with the raw [transcript] folded at the bottom.
 */
data class SortResult(
    val tasks: List<SortedTask>,
    val ideas: List<String>,
    val note: String?,
    val noteTitle: String? = null,
    val transcript: String? = null,
) {
    val isEmpty get() = tasks.isEmpty() && ideas.isEmpty() && note.isNullOrBlank()
}

/** Save failed. [message] is what to show the user; a retry of the same result won't add a second copy. */
class SortSaveException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Check and save for what the Jarvis server sorted: reading its `sorted` JSON and writing it into the vault.
 *
 *   {"tasks": [{"text", "tag", "due": "YYYY-MM-DD"}], "ideas": ["..."],
 *    "note": "a line or two" | {"title": "...", "markdown": "..."} | null}
 */
object Sorting {
    fun parse(json: String): SortResult {
        val o = JSONObject(json)
        val tasks = o.optJSONArray("tasks").objects().mapNotNull { t ->
            val text = t.optString("text").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val tag = t.optNullableString("tag")?.trim()?.removePrefix("#")?.takeIf { it.matches(Regex("[\\w/-]+")) }?.let { "#${it.lowercase()}" }
            val due = t.optNullableString("due")?.let { runCatching { LocalDate.parse(it.trim()) }.getOrNull() }
            SortedTask(text, tag, due)
        }
        val ideas = o.optJSONArray("ideas").strings().map { it.trim() }.filter { it.isNotEmpty() }
        val noteObj = o.optJSONObject("note")
        val note = (if (noteObj != null) noteObj.optNullableString("markdown") else o.optNullableString("note"))?.trim()?.takeIf { it.isNotEmpty() }
        val title = noteObj?.optNullableString("title")?.let(::safeTitle)?.takeIf { note != null }
        return SortResult(tasks, ideas, note, title, o.optNullableString("transcript")?.trim()?.takeIf { it.isNotEmpty() })
    }

    /** A note title that is safe as a file name in every vault (no slashes, colons, # or brackets). */
    fun safeTitle(raw: String): String? =
        raw.replace(Regex("[\\\\/:*?\"<>|#^\\[\\]]"), " ").replace(Regex("\\s+"), " ").trim().trimEnd('.').take(80).trim()
            .takeIf { it.isNotEmpty() }

    /** The file a tidied note is saved as: the note, then the raw transcript folded in an Obsidian callout. */
    fun noteBody(r: SortResult, today: LocalDate): String {
        val md = r.note?.trim().orEmpty()
        val head = "---\ncreated: $today\nsource: jarvis\n---\n\n"
        val raw = r.transcript?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { t -> "\n\n> [!quote]- Transcript\n" + t.lines().joinToString("\n") { "> $it".trimEnd() } }
            .orEmpty()
        return head + md + raw + "\n"
    }

    /** The Quick Task text for a sorted task: its words plus its tag (the due date is added by [VaultWriter.addTask]). */
    fun taskText(t: SortedTask) = listOfNotNull(t.text, t.tag).joinToString(" ")

    /**
     * Writes [r] into the vault: tasks to Quick Task, ideas as projects tagged `type: idea`, the note to the inbox.
     * The three are written one after another. If one fails, this attempt's writes are removed, so the vault is
     * unchanged. If that cleanup itself fails, what's left is kept and a second Save matches it and doesn't add
     * it again. Either way this throws [SortSaveException] with a message for the user.
     */
    fun save(r: SortResult, writer: VaultWriter, ideas: IdeaStore, today: LocalDate = LocalDate.now()) {
        val undo = ArrayDeque<() -> Unit>()
        try {
            saveTasks(r, writer, undo)
            saveIdeas(r, ideas, undo)
            saveNote(r, writer, today, undo)
        } catch (e: SortSaveException) {
            throw e
        } catch (e: Exception) {
            val cleaned = rollback(undo)
            val why = e.message?.takeIf { it.isNotBlank() } ?: "something went wrong"
            val hint = if (cleaned) "Nothing was written."
            else "Some of it is already in the vault. Tap Save again and it won't be added twice."
            throw SortSaveException("Couldn't save this brain dump ($why). $hint", e)
        }
    }

    /** Tasks in [r] that aren't already open in Quick Task, matched one-for-one on wording and due date. */
    private fun saveTasks(r: SortResult, writer: VaultWriter, undo: ArrayDeque<() -> Unit>) {
        val pending = r.tasks.toMutableList()
        for (have in writer.listQuickTasks()) {
            val i = pending.indexOfFirst { taskText(it) == have.text && it.due == have.due }
            if (i >= 0) pending.removeAt(i)
        }
        pending.forEach { t ->
            val text = taskText(t)
            val due = t.due?.toString()
            writer.addTask(text, due)
            val raw = TaskNote.newTask(text, due)
            undo.addFirst { writer.deleteTask(TaskItem(raw, text, t.due)) }
        }
    }

    private fun saveIdeas(r: SortResult, ideas: IdeaStore, undo: ArrayDeque<() -> Unit>) {
        r.ideas.forEach { raw ->
            val made = ideas.ensureIdea(raw)
            if (!made.created) return@forEach
            undo.addFirst { ideas.discard(made.title) }
            ideas.setType(made.title, "idea")
        }
    }

    private fun saveNote(r: SortResult, writer: VaultWriter, today: LocalDate, undo: ArrayDeque<() -> Unit>) {
        val short = r.note?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val body = if (r.noteTitle != null) noteBody(r, today) else short + "\n"
        val name = r.noteTitle ?: "Capture $today"
        val already = writer.hasInboxNote(name, body)
        val path = writer.putInboxNote(name, body)
        if (!already) undo.addFirst { writer.deleteNote(path) }
    }

    /** Runs the undos newest first. Returns false if any of them failed, so a retry must not duplicate. */
    private fun rollback(undo: ArrayDeque<() -> Unit>): Boolean {
        var cleaned = true
        while (undo.isNotEmpty()) {
            val step = undo.removeFirst()
            if (runCatching { step() }.isFailure) cleaned = false
        }
        return cleaned
    }

    /** [r] as the same JSON [parse] reads, so a result waiting for Check and save survives a restart. */
    fun toJson(r: SortResult): String = JSONObject()
        .put("tasks", JSONArray(r.tasks.map { JSONObject().put("text", it.text).put("tag", it.tag ?: JSONObject.NULL).put("due", it.due?.toString() ?: JSONObject.NULL) }))
        .put("ideas", JSONArray(r.ideas))
        .put("note", if (r.noteTitle != null) JSONObject().put("title", r.noteTitle).put("markdown", r.note) else (r.note ?: JSONObject.NULL))
        .put("transcript", r.transcript ?: JSONObject.NULL)
        .toString()

    fun fromJson(s: String?): SortResult? = s?.let { runCatching { parse(it) }.getOrNull() }?.takeIf { !it.isEmpty }

    private fun JSONArray?.objects(): List<JSONObject> = (0 until (this?.length() ?: 0)).mapNotNull { this!!.optJSONObject(it) }
    private fun JSONArray?.strings(): List<String> = (0 until (this?.length() ?: 0)).mapNotNull { this!!.opt(it) as? String }
    private fun JSONObject.optNullableString(k: String): String? = if (isNull(k)) null else optString(k, "").ifEmpty { null }
}
