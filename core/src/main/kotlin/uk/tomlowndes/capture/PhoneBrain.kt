package uk.tomlowndes.capture

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.helpers.MessageAccumulator
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolResultBlockParam
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Jarvis's phone brain: the app talks to Claude itself and streams the answer, so everyday chat skips the Oracle box.
 * Two tools: [ADD_TASK] puts a to-do on the Check and save card, and [HAND_OFF] passes a job that needs the box
 * (web research, code, vault edits) to one of the box's workers. The key lives in the app's secure storage.
 */
object PhoneBrain {
    /** "Fast" in Settings. */
    const val FAST = "claude-haiku-5-5"
    /** "Clever" in Settings. */
    const val CLEVER = "claude-sonnet-5-5"

    fun model(clever: Boolean) = if (clever) CLEVER else FAST

    const val ADD_TASK = "add_task"
    const val HAND_OFF = "hand_off"

    /** Box workers a job can be handed to (ids as the server knows them). */
    val BOX_WORKERS = listOf("research", "coding", "obsidian", "oddjobs")

    /** Messages of the chat sent as context; older ones drop off. */
    const val HISTORY = 30

    /** Rounds of tool use in one answer before it stops. */
    private const val MAX_ROUNDS = 4

    data class Turn(val mine: Boolean, val text: String)

    data class Handoff(val worker: String, val request: String)

    data class Answer(
        val text: String,
        val tasks: List<SortedTask>,
        val handoffs: List<Handoff>,
        val inputTokens: Long,
        val outputTokens: Long,
        val model: String,
    )

    /** Claude declined to answer (a safety refusal). */
    class Refused : Exception("Claude couldn't help with that one.")

    private val DAY = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.UK)

    fun system(today: LocalDate, tags: List<String>): String = """
        You are Jarvis, Tom's assistant in his Flight Deck app, which sits on top of his Obsidian vault. Use UK English.
        Keep replies short: one to three sentences unless he asks for more. Replies may be read aloud, so no tables,
        no headings and no long lists. Today is ${today.format(DAY)}.

        Use $ADD_TASK for anything Tom wants to do or be reminded of. It goes on a Check and save card that he confirms,
        so say it's ready to save rather than saved. Give a due date (YYYY-MM-DD) only when he says when.
        ${if (tags.isEmpty()) "" else "Tags he uses: ${tags.joinToString(" ")}. Pick one only when it clearly fits."}

        Use $HAND_OFF when the request needs something you can't do here: research or anything to look up on the web
        (research), code, repos or builds (coding), reading or changing notes in his vault beyond adding a task
        (obsidian), or any other job that needs a computer (oddjobs). Pass the request in his words with any detail
        from the chat. Afterwards say in a few words who it went to. Never make up what's in his vault, calendar or the news.
    """.trimIndent()

    /**
     * The chat as the API wants it: starts with Tom, roles alternate (back-to-back messages from one side are joined),
     * and it ends with Tom. At most [keep] messages.
     */
    fun history(turns: List<Turn>, keep: Int = HISTORY): List<Turn> {
        val out = ArrayList<Turn>()
        for (t in turns.takeLast(keep)) {
            if (t.text.isBlank()) continue
            if (out.isEmpty() && !t.mine) continue
            val last = out.lastOrNull()
            if (last != null && last.mine == t.mine) out[out.size - 1] = Turn(t.mine, last.text + "\n\n" + t.text)
            else out.add(t)
        }
        while (out.isNotEmpty() && !out.last().mine) out.removeAt(out.size - 1)
        return out
    }

    /** One [ADD_TASK] call's input, or null when there's no usable text. */
    fun parseTask(input: JSONObject): SortedTask? {
        val text = input.optString("text").trim().ifEmpty { return null }
        val tag = input.optString("tag").trim().removePrefix("#").takeIf { it.matches(Regex("[\\w/-]+")) }?.let { "#${it.lowercase()}" }
        val due = input.optString("due").trim().takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        return SortedTask(text, tag, due)
    }

    /** One [HAND_OFF] call's input, or null when the worker is unknown or there's no request. */
    fun parseHandoff(input: JSONObject): Handoff? {
        val w = input.optString("worker").trim().lowercase().replace("-", "").replace(" ", "")
        val req = input.optString("request").trim()
        return if (w in BOX_WORKERS && req.isNotEmpty()) Handoff(w, req) else null
    }

    /** What the box's worker is called on screen. */
    fun workerLabel(id: String) = Jarvis.worker(id).label

    // ---- Spend ------------------------------------------------------------------------------------------------

    /** US dollars per million tokens, in and out. */
    private val PRICE = mapOf(FAST to (0.10 to 0.50), CLEVER to (2.00 to 10.00))

    /** Rough pounds per dollar, for the Settings line. */
    const val GBP_PER_USD = 0.79

    fun costUsd(model: String, input: Long, output: Long): Double {
        val (i, o) = PRICE[model] ?: PRICE.getValue(CLEVER)
        return input / 1e6 * i + output / 1e6 * o
    }

    /** Spend kept on the phone: the month it's for, micro-dollars, and how many answers. */
    data class Spend(val month: String, val microUsd: Long, val answers: Int) {
        val pounds get() = microUsd / 1e6 * GBP_PER_USD

        fun add(usd: Double, now: YearMonth = YearMonth.now()): Spend {
            val m = now.toString()
            val base = if (m == month) this else Spend(m, 0, 0)
            return base.copy(microUsd = base.microUsd + Math.round(usd * 1e6), answers = base.answers + 1)
        }

        /** "£0.38 · 21 answers", or "Nothing yet" (a new month starts from zero). */
        fun label(now: YearMonth = YearMonth.now()): String {
            if (month != now.toString() || answers == 0) return "Nothing yet"
            val p = pounds
            val money = if (p < 0.01) "under 1p" else if (p < 1) "${Math.round(p * 100)}p" else "£%.2f".format(Locale.UK, p)
            return "$money · $answers " + if (answers == 1) "answer" else "answers"
        }
    }

    // ---- Calls ------------------------------------------------------------------------------------------------

    private fun client(key: String): AnthropicClient = AnthropicOkHttpClient.builder().apiKey(key.trim()).maxRetries(2).build()

    private fun prop(vararg kv: Pair<String, Any>) = JsonValue.from(mapOf(*kv))

    private fun tools(): List<Tool> = listOf(
        Tool.builder().name(ADD_TASK).strict(true)
            .description("Put a to-do on Tom's Check and save card. He confirms it before it goes in his vault.")
            .inputSchema(
                Tool.InputSchema.builder()
                    .properties(
                        Tool.InputSchema.Properties.builder()
                            .putAdditionalProperty("text", prop("type" to "string", "description" to "The task, short, starting with a verb"))
                            .putAdditionalProperty("tag", prop("type" to "string", "description" to "One tag like work or personal, or empty"))
                            .putAdditionalProperty("due", prop("type" to "string", "description" to "YYYY-MM-DD, or empty when he didn't say"))
                            .build()
                    )
                    .required(listOf("text", "tag", "due"))
                    .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                    .build()
            ).build(),
        Tool.builder().name(HAND_OFF).strict(true)
            .description("Send a job that needs a computer to one of the workers on Tom's server. It answers in the chat, or on the Done line when it's a long job.")
            .inputSchema(
                Tool.InputSchema.builder()
                    .properties(
                        Tool.InputSchema.Properties.builder()
                            .putAdditionalProperty("worker", prop("type" to "string", "enum" to BOX_WORKERS))
                            .putAdditionalProperty("request", prop("type" to "string", "description" to "What to do, with any detail it needs"))
                            .build()
                    )
                    .required(listOf("worker", "request"))
                    .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                    .build()
            ).build(),
    )

    private fun base(model: String, system: String): MessageCreateParams.Builder = MessageCreateParams.builder()
        .model(model)
        .maxTokens(4000L)
        .system(system)
        .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
        .apply {
            // Sonnet: a refusal is retried on a fallback model by the server instead of coming back empty.
            if (model == CLEVER) {
                putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
            }
        }

    /**
     * Streams one answer to [turns] (see [history]); [onText] gets the answer so far each time more arrives.
     * Blocking: run it off the main thread. Throws the SDK's exceptions when Claude can't be reached.
     */
    fun chat(
        key: String, model: String, turns: List<Turn>, today: LocalDate, tags: List<String>, onText: (String) -> Unit,
    ): Answer {
        val params = base(model, system(today, tags)).apply {
            tools().forEach { addTool(it) }
            history(turns).forEach { if (it.mine) addUserMessage(it.text) else addAssistantMessage(it.text) }
        }
        val tasks = ArrayList<SortedTask>()
        val handoffs = ArrayList<Handoff>()
        val text = StringBuilder()
        var inTok = 0L
        var outTok = 0L
        val c = client(key)
        try {
            repeat(MAX_ROUNDS) {
                val acc = MessageAccumulator.create()
                c.messages().createStreaming(params.build()).use { s ->
                    s.stream().forEach { ev ->
                        acc.accumulate(ev)
                        ev.contentBlockDelta().flatMap { it.delta().text() }.ifPresent { d ->
                            text.append(d.text()); onText(text.toString())
                        }
                    }
                }
                val msg = acc.message()
                inTok += msg.usage().inputTokens(); outTok += msg.usage().outputTokens()
                val stop = msg.stopReason().orElse(null)
                if (stop == StopReason.REFUSAL) throw Refused()
                val uses = msg.content().mapNotNull { it.toolUse().orElse(null) }
                if (stop != StopReason.TOOL_USE || uses.isEmpty()) return Answer(text.toString().trim(), tasks, handoffs, inTok, outTok, model)
                if (text.isNotEmpty() && !text.endsWith(" ") && !text.endsWith("\n")) text.append(" ")
                val results = uses.map { u ->
                    val input = runCatching { JSONObject(u._input().convert(Map::class.java) as Map<*, *>) }.getOrDefault(JSONObject())
                    val (ok, reply) = when (u.name()) {
                        ADD_TASK -> parseTask(input)?.let { tasks.add(it); true to "Added to the Check and save card." }
                            ?: (false to "No task text.")
                        HAND_OFF -> parseHandoff(input)?.let { handoffs.add(it); true to "Sent to ${workerLabel(it.worker)} on the server." }
                            ?: (false to "Unknown worker or empty request.")
                        else -> false to "No such tool."
                    }
                    ContentBlockParam.ofToolResult(
                        ToolResultBlockParam.builder().toolUseId(u.id()).content(reply).isError(!ok).build()
                    )
                }
                params.addMessage(msg).addUserMessageOfContentBlockParams(results)
            }
        } finally {
            c.close()
        }
        return Answer(text.toString().trim(), tasks, handoffs, inTok, outTok, model)
    }

    /** What a printed summary slip shows. */
    data class Summary(val title: String, val points: List<String>, val todos: List<String>)

    private val SUMMARY_SCHEMA = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "title" to mapOf("type" to "string"),
            "points" to mapOf("type" to "array", "items" to mapOf("type" to "string")),
            "todos" to mapOf("type" to "array", "items" to mapOf("type" to "string")),
        ),
        "required" to listOf("title", "points", "todos"),
        "additionalProperties" to false,
    )

    const val SUMMARY_SYSTEM = """
You boil a chat or a note down to a till-roll slip for a 48 mm pocket printer. UK English. Title: up to four words.
Points: the 2 to 5 things worth remembering, each under 60 characters. To-dos: actions that are still open, each
under 50 characters, starting with a verb; empty when there are none. No emoji, no Markdown."""

    fun parseSummary(json: String): Summary {
        val o = JSONObject(json)
        fun list(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it).trim() }.filter { it.isNotEmpty() } }.orEmpty()
        return Summary(o.optString("title").trim().ifEmpty { "Summary" }, list("points"), list("todos"))
    }

    /** Claude's summary of [text] for a slip. Blocking. */
    fun summarise(key: String, model: String, kind: String, text: String): Summary {
        val params = MessageCreateParams.builder()
            .model(model).maxTokens(2000L).system(SUMMARY_SYSTEM.trim())
            .outputConfig(
                OutputConfig.builder().effort(OutputConfig.Effort.LOW)
                    .format(JsonOutputFormat.builder().schema(
                        JsonOutputFormat.Schema.builder().apply { SUMMARY_SCHEMA.forEach { (k, v) -> putAdditionalProperty(k, JsonValue.from(v)) } }.build()
                    ).build())
                    .build()
            )
            .addUserMessage("Summarise this $kind:\n\n$text")
            .build()
        val c = client(key)
        val msg = try { c.messages().create(params) } finally { c.close() }
        if (msg.stopReason().orElse(null) == StopReason.REFUSAL) throw Refused()
        return parseSummary(msg.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString(""))
    }

    /** A chat as plain text for [summarise]. */
    fun transcript(turns: List<Turn>): String = turns.joinToString("\n") { (if (it.mine) "Tom: " else "Jarvis: ") + it.text.trim() }

    /**
     * A note's slip without asking Claude: the first heading (or [name]) as the title, open "- [ ]" lines as to-dos,
     * other bullets as points. Used when there's no key, and for short notes.
     */
    fun fromNote(name: String, markdown: String): Summary {
        val body = markdown.replace(Regex("^---\\n[\\s\\S]*?\\n---\\n"), "")
        val lines = body.lines().map { it.trim() }
        val title = lines.firstOrNull { it.startsWith("# ") }?.removePrefix("# ")?.trim() ?: name
        val todos = lines.mapNotNull { Regex("^[-*] \\[ ] (.+)").find(it)?.groupValues?.get(1) }.map(::plainMd)
        val points = lines.filter { Regex("^[-*+] (?!\\[[ xX>-]]).+").matches(it) }.map { plainMd(it.substring(2)) }
        val paras = if (points.isEmpty()) lines.filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("- [") && !it.startsWith(">") } else emptyList()
        return Summary(title, (points.ifEmpty { paras }).take(12), todos.take(12))
    }

    /** Links, bold and the like stripped for paper. */
    fun plainMd(s: String): String = s
        .replace(Regex("\\[\\[([^]|]+)\\|([^]]+)]]"), "$2").replace(Regex("\\[\\[([^]]+)]]"), "$1")
        .replace(Regex("\\[([^]]+)]\\([^)]*\\)"), "$1")
        .replace(Regex("[*_`]{1,3}"), "").trim()

    fun summaryToJson(s: Summary): String =
        JSONObject().put("title", s.title).put("points", JSONArray(s.points)).put("todos", JSONArray(s.todos)).toString()
}
