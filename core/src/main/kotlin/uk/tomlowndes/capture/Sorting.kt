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
object Sorting {
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

    /** Writes [r] into the vault: tasks to Quick Task, ideas as projects tagged `type: idea`, the note to the inbox. */
    fun save(r: SortResult, writer: VaultWriter, ideas: IdeaStore, today: LocalDate = LocalDate.now()) {
        r.tasks.forEach { writer.addTask(taskText(it), it.due?.toString()) }
        r.ideas.forEach { title ->
            val made = runCatching { ideas.create(title, "") }.getOrNull()
            if (made != null) ideas.setType(made, "idea")
        }
        r.note?.let { writer.addInboxNote("Capture $today", it + "\n") }
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
