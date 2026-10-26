package uk.tomlowndes.capture

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** One task Claude pulled out of a brain dump. [tag] is like "#home"; [due] may be null. */
data class SortedTask(val text: String, val tag: String?, val due: LocalDate?)

/** What "Sort it" turns a brain dump into. Nothing is written until the user taps Save. */
data class SortResult(val tasks: List<SortedTask>, val ideas: List<String>, val note: String?) {
    val isEmpty get() = tasks.isEmpty() && ideas.isEmpty() && note.isNullOrBlank()
}

/** A brain dump kept on the phone until it's saved: raw text, and the sorted result once there is one. */
data class QueuedDump(val id: Long, val text: String, val sorted: SortResult? = null)

/**
 * The Capture "Sort it" step, minus the network call: the instructions and output schema sent to Claude,
 * reading its JSON answer, and writing the result into the vault.
 */
/** Save failed. [message] is what to show the user; a retry of the same result won't add a second copy. */
class SortSaveException(message: String, cause: Throwable? = null) : Exception(message, cause)

object Sorting {
    /** Default Claude model for Sort it. The phone can override it in Settings. */
    const val MODEL = "claude-haiku-5-5"

    val SYSTEM = """
        You sort a spoken or typed brain dump for a personal Obsidian vault.
        Split it into:
        - tasks: concrete things to do. Short imperative text without the date words. Pick one tag from the
          person's tags when one fits (for example #home, #jobs, #learning, #bill, #work), otherwise null.
          Set due (YYYY-MM-DD) only when the dump gives or implies a day; work it out from today's date.
        - ideas: project ideas or things to explore later, as short titles.
        - note: anything worth keeping that is neither, as one or two plain sentences, or null.
        Keep the person's own words where you can. Don't invent tasks that weren't said.
    """.trimIndent()

    /** JSON schema for the structured output. */
    val SCHEMA: JSONObject = JSONObject(
        """
        {"type":"object","additionalProperties":false,"required":["tasks","ideas","note"],
         "properties":{
          "tasks":{"type":"array","items":{"type":"object","additionalProperties":false,
            "required":["text","tag","due"],
            "properties":{"text":{"type":"string"},
              "tag":{"type":["string","null"]},
              "due":{"type":["string","null"],"description":"YYYY-MM-DD"}}}},
          "ideas":{"type":"array","items":{"type":"string"}},
          "note":{"type":["string","null"]}}}
        """.trimIndent()
    )

    fun userMessage(dump: String, today: LocalDate, tags: List<String>): String =
        "Today is ${today.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }} $today." +
            (if (tags.isNotEmpty()) " Tags in use: ${tags.joinToString(" ")}." else "") +
            "\n\nBrain dump:\n${dump.trim()}"

    fun parse(json: String): SortResult {
        val o = JSONObject(json)
        val tasks = o.optJSONArray("tasks").objects().mapNotNull { t ->
            val text = t.optString("text").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val tag = t.optNullableString("tag")?.trim()?.removePrefix("#")?.takeIf { it.matches(Regex("[\\w/-]+")) }?.let { "#${it.lowercase()}" }
            val due = t.optNullableString("due")?.let { runCatching { LocalDate.parse(it.trim()) }.getOrNull() }
            SortedTask(text, tag, due)
        }
        val ideas = o.optJSONArray("ideas").strings().map { it.trim() }.filter { it.isNotEmpty() }
        return SortResult(tasks, ideas, o.optNullableString("note")?.trim()?.takeIf { it.isNotEmpty() })
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
        val body = r.note?.trim()?.takeIf { it.isNotEmpty() }?.plus("\n") ?: return
        val name = "Capture $today"
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

    // ---- the on-phone queue, kept as JSON ----

    fun queueToJson(q: List<QueuedDump>): String = JSONArray(q.map { d ->
        JSONObject().put("id", d.id).put("text", d.text).apply { d.sorted?.let { put("sorted", resultJson(it)) } }
    }).toString()

    fun queueFromJson(s: String?): List<QueuedDump> =
        runCatching { JSONArray(s ?: "[]") }.getOrDefault(JSONArray()).objects().map { o ->
            QueuedDump(o.optLong("id"), o.optString("text"), o.optJSONObject("sorted")?.let { parse(it.toString()) })
        }

    private fun resultJson(r: SortResult) = JSONObject()
        .put("tasks", JSONArray(r.tasks.map { JSONObject().put("text", it.text).put("tag", it.tag ?: JSONObject.NULL).put("due", it.due?.toString() ?: JSONObject.NULL) }))
        .put("ideas", JSONArray(r.ideas))
        .put("note", r.note ?: JSONObject.NULL)

    private fun JSONArray?.objects(): List<JSONObject> = (0 until (this?.length() ?: 0)).mapNotNull { this!!.optJSONObject(it) }
    private fun JSONArray?.strings(): List<String> = (0 until (this?.length() ?: 0)).mapNotNull { this!!.opt(it) as? String }
    private fun JSONObject.optNullableString(k: String): String? = if (isNull(k)) null else optString(k, "").ifEmpty { null }
}
