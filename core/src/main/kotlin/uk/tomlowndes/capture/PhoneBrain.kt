package uk.tomlowndes.capture

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.core.Timeout
import com.anthropic.helpers.MessageAccumulator
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolResultBlockParam
import com.anthropic.models.messages.WebSearchTool20250305
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Jarvis's phone brain: the app talks to Claude itself and streams the answer, so everyday chat skips the Oracle box.
 * Two tools: [ADD_TASK] puts a to-do on the Check and save card, and [HAND_OFF] passes code and other computer
 * jobs to the box. Rambles and research are answered here, on Haiku, when the phone key is switched on.
 * The key lives in the app's secure storage. Nothing is written to the vault until Check and save.
 */
object PhoneBrain {
    /** "Fast" in Settings. Everyday chat, ramble sorting and research use this unless chat is set to Clever. */
    const val FAST = "claude-haiku-5-5"
    /** How long a chat or a sort may take before it is kept and tried again later. */
    const val CHAT_TIMEOUT_S = 45L
    /** Research may look a few things up, so it is allowed a little longer. */
    const val RESEARCH_TIMEOUT_S = 60L
    /** Same length the Oracle box uses before it treats a message as a ramble. */
    const val RAMBLE_WORDS = 40
    /** "Clever" in Settings. */
    const val CLEVER = "claude-sonnet-5-5"

    fun model(clever: Boolean) = if (clever) CLEVER else FAST

    const val ADD_TASK = "add_task"
    const val HAND_OFF = "hand_off"
    const val SAVE_NOTE = "save_note"
    const val ADD_TO_NOTE = "add_to_note"

    /** Box workers a job can be handed to (ids as the server knows them). */
    val BOX_WORKERS = listOf("research", "coding", "obsidian", "oddjobs")

    /** Messages of the chat sent as context; older ones drop off. */
    const val HISTORY = 30

    /** Rounds of tool use in one answer before it stops. */
    private const val MAX_ROUNDS = 4

    data class Turn(val mine: Boolean, val text: String)

    data class Handoff(val worker: String, val request: String)

    /** A note Tom asked for: [into] names an existing note to add [text] to; otherwise it is a new note called [title]. */
    data class NoteDraft(val title: String?, val text: String, val into: String? = null, val heading: String? = null)

    data class Answer(
        val text: String,
        val tasks: List<SortedTask>,
        val handoffs: List<Handoff>,
        val inputTokens: Long,
        val outputTokens: Long,
        val model: String,
        val notes: List<NoteDraft> = emptyList(),
    )

    /** One [SAVE_NOTE] call's input, or null when there's no text. */
    fun parseSaveNote(input: JSONObject): NoteDraft? {
        val text = input.optString("markdown").trim().ifEmpty { return null }
        return NoteDraft(input.optString("title").trim().ifEmpty { null }, text)
    }

    /** One [ADD_TO_NOTE] call's input, or null when the note or the text is missing. */
    fun parseAddToNote(input: JSONObject): NoteDraft? {
        val text = input.optString("text").trim().ifEmpty { return null }
        val into = input.optString("note").trim().ifEmpty { return null }
        return NoteDraft(null, text, into, input.optString("heading").trim().ifEmpty { null })
    }

    /** The Check and save card for an answer: its tasks, and its first note (a new one, or an addition to an existing one). */
    fun sortedOf(tasks: List<SortedTask>, notes: List<NoteDraft>): SortResult? {
        val n = notes.firstOrNull()
        val r = when {
            n == null -> SortResult(tasks, emptyList(), null)
            n.into != null -> SortResult(tasks, emptyList(), n.text, addTo = NoteTarget(n.into, null, n.heading))
            else -> SortResult(tasks, emptyList(), n.text, noteTitle = n.title?.let(Sorting::safeTitle) ?: Sorting.titleFrom(n.text))
        }
        return r.takeIf { !it.isEmpty }
    }

    /** Claude declined to answer (a safety refusal). */
    class Refused : Exception("Claude couldn't help with that one.")

    private val DAY = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.UK)

    fun system(today: LocalDate, tags: List<String>, name: String = AssistantName.DEFAULT): String = """
        You are ${AssistantName.clean(name)}, Tom's assistant in his Flight Deck app, which sits on top of his Obsidian vault. Use UK English.
        Keep replies short: one to three sentences unless he asks for more. Replies may be read aloud, so no tables,
        no headings and no long lists. Today is ${today.format(DAY)}.

        Use $SAVE_NOTE when he says to note something down ("note this", "make a note", "jot down"): put the note on the
        card and don't answer it as a question. Use $ADD_TO_NOTE when he wants something added to a note he names
        ("add this to my Garage note"): give the note's name as he said it. Both go on the Check and save card like tasks.
        Use $ADD_TASK for anything Tom wants to do or be reminded of. It goes on a Check and save card that he confirms,
        so say it's ready to save rather than saved. Give a due date (YYYY-MM-DD) only when he says when.
        ${if (tags.isEmpty()) "" else "Tags he uses: ${tags.joinToString(" ")}. Pick one only when it clearly fits."}

        You can look things up on the web when the answer depends on current facts. Use that only then.
        Never make up what's in his vault, calendar or the news. You do not edit his notes yourself.
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

    private fun client(key: String, seconds: Long): AnthropicClient {
        val d = Duration.ofSeconds(seconds)
        val timeout = Timeout.builder().connect(Duration.ofSeconds(10)).read(d).write(Duration.ofSeconds(30)).request(d).build()
        // No automatic retry: a retry is the phone queue's job, later, and only once at a time.
        return AnthropicOkHttpClient.builder().apiKey(key.trim()).timeout(timeout).maxRetries(0).build()
    }

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
        Tool.builder().name(SAVE_NOTE).strict(true)
            .description("Put a new note on Tom's Check and save card. It goes in his inbox once he confirms.")
            .inputSchema(
                Tool.InputSchema.builder()
                    .properties(
                        Tool.InputSchema.Properties.builder()
                            .putAdditionalProperty("title", prop("type" to "string", "description" to "A short title, a few words"))
                            .putAdditionalProperty("markdown", prop("type" to "string", "description" to "The note, tidy, in his words. No top-level # heading"))
                            .build()
                    )
                    .required(listOf("title", "markdown"))
                    .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                    .build()
            ).build(),
        Tool.builder().name(ADD_TO_NOTE).strict(true)
            .description("Add text to a note that already exists in Tom's vault. The app finds the note and he confirms it first.")
            .inputSchema(
                Tool.InputSchema.builder()
                    .properties(
                        Tool.InputSchema.Properties.builder()
                            .putAdditionalProperty("note", prop("type" to "string", "description" to "The note's name as Tom said it, e.g. Garage"))
                            .putAdditionalProperty("text", prop("type" to "string", "description" to "What to add, short, one line where possible"))
                            .putAdditionalProperty("heading", prop("type" to "string", "description" to "Section to put it under, e.g. Log, or empty"))
                            .build()
                    )
                    .required(listOf("note", "text", "heading"))
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
        key: String, model: String, turns: List<Turn>, today: LocalDate, tags: List<String>,
        name: String = AssistantName.DEFAULT, onText: (String) -> Unit,
    ): Answer {
        val params = base(model, system(today, tags, name)).apply {
            tools().forEach { addTool(it) }
            addTool(WebSearchTool20250305.builder().maxUses(3).build())
            history(turns).forEach { if (it.mine) addUserMessage(it.text) else addAssistantMessage(it.text) }
        }
        val tasks = ArrayList<SortedTask>()
        val handoffs = ArrayList<Handoff>()
        val notes = ArrayList<NoteDraft>()
        val text = StringBuilder()
        var inTok = 0L
        var outTok = 0L
        val c = client(key, CHAT_TIMEOUT_S)
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
                if (stop != StopReason.TOOL_USE || uses.isEmpty()) return Answer(text.toString().trim(), tasks, handoffs, inTok, outTok, model, notes)
                if (text.isNotEmpty() && !text.endsWith(" ") && !text.endsWith("\n")) text.append(" ")
                val results = uses.map { u ->
                    val input = runCatching { JSONObject(u._input().convert(Map::class.java) as Map<*, *>) }.getOrDefault(JSONObject())
                    val (ok, reply) = when (u.name()) {
                        ADD_TASK -> parseTask(input)?.let { tasks.add(it); true to "Added to the Check and save card." }
                            ?: (false to "No task text.")
                        SAVE_NOTE -> parseSaveNote(input)?.let { notes.add(it); true to "The note is on the Check and save card." }
                            ?: (false to "No note text.")
                        ADD_TO_NOTE -> parseAddToNote(input)?.let { notes.add(it); true to "It's on the Check and save card for ${it.into}." }
                            ?: (false to "Needs a note name and some text.")
                        HAND_OFF -> parseHandoff(input)?.let { handoffs.add(it); true to "Passed to ${workerLabel(it.worker)}." }
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
        return Answer(text.toString().trim(), tasks, handoffs, inTok, outTok, model, notes)
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
        val c = client(key, CHAT_TIMEOUT_S)
        val msg = try { c.messages().create(params) } finally { c.close() }
        if (msg.stopReason().orElse(null) == StopReason.REFUSAL) throw Refused()
        return parseSummary(msg.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString(""))
    }

    /** A chat as plain text for [summarise]. */
    fun transcript(turns: List<Turn>, name: String = AssistantName.DEFAULT): String =
        turns.joinToString("\n") { (if (it.mine) "Tom: " else "${AssistantName.clean(name)}: ") + it.text.trim() }

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

    // ---- one call at a time, sorting, research ---------------------------------------------------------------

    private val inflight = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** The same message must not be sent twice while the first call is still running. */
    fun flightKey(worker: String, text: String) = worker + "\u0000" + text.trim()

    fun beginFlight(key: String) = inflight.add(key)

    fun endFlight(key: String) { inflight.remove(key) }

    fun worthSorting(text: String) = text.trim().split(Regex("\\s+")).count { it.isNotBlank() } >= RAMBLE_WORDS

    /** A direct answer: [sorted] is set when Tom should confirm a save. [reply] is what to show either way. */
    data class Direct(val reply: String, val sorted: SortResult?, val inTok: Long = 0, val outTok: Long = 0)

    /**
     * Reads the sorter JSON. A question comes back as a reply with no save. A ramble comes back with tasks,
     * ideas or a note. Null when the text is not that JSON.
     */
    fun parseDirect(raw: String): Direct? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val o = runCatching { JSONObject(raw.substring(start, end + 1)) }.getOrNull() ?: return null
        if (!o.has("ramble")) return null
        val reply = o.optString("reply").trim()
        if (!o.optBoolean("ramble")) return Direct(reply, null)
        val sorted = runCatching { Sorting.parse(o.toString()) }.getOrNull()?.takeIf { !it.isEmpty }
        if (sorted == null && reply.isBlank()) return null
        return Direct(reply.ifBlank { "Got it. Check it before I save." }, sorted)
    }

    private const val SORT_SYSTEM = """
You are %s, Tom's assistant. You answer him, or you sort something he wants kept in his Obsidian vault. Today is %s. UK English.
If this is a question or a request that is not about keeping a task, an idea or a note, answer only this JSON:
{"ramble": false, "reply": "one to three short sentences"}
If he wants something kept, answer only this JSON:
{"ramble": true, "reply": "one short sentence saying what you found",
 "tasks": [{"text": "short imperative", "tag": "#home, #work, #jobs, #learning, #bill or null", "due": "YYYY-MM-DD or null"}],
 "ideas": ["short titles"],
 "note": null, or one or two plain sentences, or {"title": "a short title", "markdown": "a tidy note"},
 "addTo": null, or {"note": "name of an existing note he asked to add this to, as he said it", "heading": "Log, or null"}}
Set addTo only when he names a note to add to ("add this to my Garage note"). Then "note" is just what to add.
For a note: a one or two sentence summary, then ## headings with short bullets. Keep his points and wording.
Do not invent anything. No top-level # heading. Leave tasks out of the note when they are already in tasks.
Never say you have saved anything. He confirms it first."""

    /** One Haiku call. A long message is either answered or turned into Check and save, not both. Blocking. */
    fun sortOrAnswer(key: String, text: String, today: LocalDate, name: String = AssistantName.DEFAULT): Direct {
        val params = MessageCreateParams.builder()
            .model(FAST).maxTokens(2000L).system(SORT_SYSTEM.format(AssistantName.clean(name), today.format(DAY)).trim())
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
            .addUserMessage(text.trim())
            .build()
        val c = client(key, CHAT_TIMEOUT_S)
        val msg = try { c.messages().create(params) } finally { c.close() }
        if (msg.stopReason().orElse(null) == StopReason.REFUSAL) throw Refused()
        val raw = msg.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("")
        val inTok = msg.usage().inputTokens()
        val outTok = msg.usage().outputTokens()
        return (parseDirect(raw) ?: Direct(raw.trim(), null)).copy(inTok = inTok, outTok = outTok)
    }

    private const val RESEARCH_SYSTEM = """
You are %s, looking something up for Tom. UK English. Today is %s.
Use web search when the answer depends on current facts. Write a few short sentences, then up to five bullets.
Say plainly when you are not sure. Do not invent a source. Do not edit his notes.
If he also asked to keep a task or a note, add nothing about saving in the prose, and finish with one JSON object
on its own line: {"ramble": true, "reply": "one short sentence", "tasks": [], "ideas": [], "note": null}
using the same task and note shape as a ramble sort. Otherwise do not add JSON."""

    /**
     * Research on Haiku, with a few web searches. Blocking. Gives up after [RESEARCH_TIMEOUT_S] seconds.
     * A trailing sorter JSON becomes [Direct.sorted] so Check and save can keep it.
     */
    fun research(key: String, text: String, today: LocalDate, name: String = AssistantName.DEFAULT): Direct {
        val params = MessageCreateParams.builder()
            .model(FAST).maxTokens(2000L).system(RESEARCH_SYSTEM.format(AssistantName.clean(name), today.format(DAY)).trim())
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
            .addTool(WebSearchTool20250305.builder().maxUses(3).build())
            .addUserMessage(text.trim())
            .build()
        val c = client(key, RESEARCH_TIMEOUT_S)
        val msg = try { c.messages().create(params) } finally { c.close() }
        if (msg.stopReason().orElse(null) == StopReason.REFUSAL) throw Refused()
        val raw = msg.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("\n").trim()
        val parsed = parseDirect(raw)
        val prose = if (parsed?.sorted != null) raw.substringBeforeLast("{").trim().ifBlank { parsed.reply } else raw
        return Direct(prose.ifBlank { "I couldn't find a clear answer." }, parsed?.sorted, msg.usage().inputTokens(), msg.usage().outputTokens())
    }

    /** A short line for a call that ran out of time. */
    fun timedOut(e: Throwable, seconds: Long, name: String = AssistantName.DEFAULT): String? {
        val blob = (e.javaClass.simpleName + " " + e.message.orEmpty())
        return if (blob.contains("timeout", ignoreCase = true))
            "${AssistantName.clean(name)} didn't answer within $seconds seconds. It's kept on the phone and will be tried again later."
        else null
    }

    /** One plain answer, for Break it down, a print summary, or the day's brief. Blocking. */
    fun complete(key: String, user: String, system: String, seconds: Long = CHAT_TIMEOUT_S, model: String = FAST): Direct {
        val params = MessageCreateParams.builder()
            .model(model).maxTokens(2000L).system(system)
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
            .addUserMessage(user)
            .build()
        val c = client(key, seconds)
        val msg = try { c.messages().create(params) } finally { c.close() }
        if (msg.stopReason().orElse(null) == StopReason.REFUSAL) throw Refused()
        val text = msg.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("").trim()
        return Direct(text, null, msg.usage().inputTokens(), msg.usage().outputTokens())
    }

    /**
     * What to show when a call fails. [viaServer] is the Oracle box: only then is Tailscale the thing to check.
     */
    fun failure(name: String, e: Throwable, viaServer: Boolean, seconds: Long = CHAT_TIMEOUT_S): String {
        val who = AssistantName.clean(name)
        if (!viaServer) {
            timedOut(e, seconds, who)?.let { return it }
            return when (e) {
                is Refused -> e.message ?: "$who couldn't help with that one."
                is com.anthropic.errors.UnauthorizedException, is com.anthropic.errors.PermissionDeniedException ->
                    "$who refused the key. Check it in Settings."
                is com.anthropic.errors.RateLimitException -> "$who is busy right now. Try again in a moment."
                is com.anthropic.errors.AnthropicIoException -> "Can't reach $who. Are you online?"
                else -> "Can't reach $who. Are you online?"
            }
        }
        return when {
            e is Jarvis.HttpError && (e.code == 401 || e.code == 403) -> "$who refused the token. Check it in Settings."
            e is Jarvis.HttpError -> e.message ?: "$who returned an error."
            else -> "Can't reach $who. Is Tailscale on?"
        }
    }
}
