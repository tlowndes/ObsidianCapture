package uk.tomlowndes.capture

import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The on-phone brain (Gemini Nano, mockup nano-brain-v1): which asks it keeps, the prompt it gets, and reading its answer.
 * Nano can't see the vault, the web or code, so those go to Claude on the box, either up front ([needsBox]) or when
 * Nano answers just "BOX". Tasks come back as `TASK: text | date | #tag` lines at the end.
 */
object NanoRoute {
    /** [Jarvis.ChatMsg.via] for an answer that came from the phone. */
    const val VIA = "nano"
    const val KEEP_TURNS = 8
    private const val MAX_CHARS = 6000
    private const val RAMBLE_WORDS = 40
    private val DAY = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.UK)

    private val rules = listOf(
        Regex("\\b(vault|obsidian|my notes?|a note|the note|inbox|projects?|portfolio|journal|bible|read later|my tasks|to-?do list|what'?s on my|what is on my|next step)\\b") to "Needs your vault",
        Regex("\\b(research|look up|look into|search (the )?(web|internet|online)|google it|latest|news|price of|prices|compare|reviews?)\\b") to "Research job",
        Regex("\\b(code|coding|github|pull request|pr|bug|script|compile|repo|deploy|the app)\\b") to "Coding job",
    )

    /** Why [text] should skip Nano and go straight to the box, or null to try it on the phone. */
    fun needsBox(text: String): String? {
        val t = text.lowercase(Locale.UK)
        if (t.split(Regex("\\s+")).count { it.isNotBlank() } >= RAMBLE_WORDS) return "Long ramble · sorting it"
        return rules.firstOrNull { (re, _) -> re.containsMatchIn(t) }?.second
    }

    fun prompt(turns: List<PhoneBrain.Turn>, text: String, today: LocalDate, tags: List<String>): String {
        val head = """
            You are Jarvis, Tom's assistant on his phone. Use UK English. Keep answers short and friendly: one to three
            sentences unless he asks for more. Answers may be read aloud, so no tables or headings. Today is ${today.format(DAY)}.
            You can't see Tom's notes, files, calendar, tasks or the web. If a good answer needs any of those, current
            facts, or code, reply with only the word BOX.
            When Tom asks you to add, remember or remind him of something to do, confirm it in a few words, then end with
            one line per task:
            TASK: <short task> | <YYYY-MM-DD, or blank> | <one of ${tags.joinToString(" ")}, or blank>
        """.trimIndent()
        val lines = turns.filter { it.text.isNotBlank() }.takeLast(KEEP_TURNS)
            .map { (if (it.mine) "Tom: " else "Jarvis: ") + it.text.trim() }
        // Oldest turns go first when the prompt would be too long for Nano's small window.
        var past = lines
        while (past.isNotEmpty() && (past.sumOf { it.length } + text.length + head.length) > MAX_CHARS) past = past.drop(1)
        return buildString {
            append(head).append("\n\n")
            if (past.isNotEmpty()) append("Conversation so far:\n").append(past.joinToString("\n")).append("\n")
            append("Tom: ").append(text.trim()).append("\nJarvis:")
        }
    }

    data class Reply(val text: String, val tasks: List<SortedTask>, val toBox: Boolean)

    private val taskLine = Regex("(?im)^\\s*TASK:\\s*(.*)$")

    fun parse(raw: String): Reply {
        val s = raw.trim().removePrefix("Jarvis:").trim()
        if (s.isEmpty() || s.uppercase(Locale.UK).trimEnd('.', '!').let { it == "BOX" || it.startsWith("BOX\n") }) return Reply("", emptyList(), true)
        val tasks = taskLine.findAll(s).mapNotNull { m ->
            val p = m.groupValues[1].split("|").map { it.trim() }
            PhoneBrain.parseTask(JSONObject().put("text", p.getOrElse(0) { "" }).put("due", p.getOrElse(1) { "" }).put("tag", p.getOrElse(2) { "" }))
        }.toList()
        val text = s.replace(taskLine, "").trim().replace(Regex("\\n{3,}"), "\n\n")
        return Reply(text.ifEmpty { if (tasks.isEmpty()) "" else "Added." }, tasks, false)
    }

    /** What to show while Nano is still writing: no TASK lines, and nothing while it might be saying BOX. */
    fun visible(partial: String): String {
        val s = partial.trimStart().removePrefix("Jarvis:").trimStart()
        val up = s.trimEnd().trimEnd('.', '!').uppercase(Locale.UK)
        if ("BOX".startsWith(up) || up == "BOX" || up.startsWith("BOX\n")) return ""
        val cut = Regex("(?im)^\\s*TASK:").find(s)?.range?.first ?: s.length
        return s.substring(0, cut).trimEnd()
    }
}
