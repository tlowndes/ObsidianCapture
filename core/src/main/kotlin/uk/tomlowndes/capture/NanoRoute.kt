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
    private const val MAX_CHARS = 10_000
    private const val NOTE_CHARS = 1_400
    private val STOP = setOf("what", "whats", "what's", "when", "where", "which", "about", "have", "does", "with", "this", "that", "there",
        "they", "them", "then", "than", "from", "into", "your", "mine", "tell", "show", "give", "summarise", "summarize", "summary",
        "please", "could", "would", "should", "today", "tomorrow", "week", "anything", "something", "going", "happening", "need", "my")
    private const val RAMBLE_WORDS = 40
    private val DAY = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.UK)

    private val rules = listOf(
        Regex("\\b(save|write|add|put|move|rename|delete|tidy)\\b.*\\b(note|notes|vault|obsidian|inbox|project)\\b") to "Vault edit",
        Regex("\\b(research|look up|look into|search (the )?(web|internet|online)|google it|latest|news|price of|prices|compare|reviews?)\\b") to "Research job",
        Regex("\\b(code|coding|github|pull request|pr|bug|script|compile|repo|deploy|the app)\\b") to "Coding job",
    )

    /** Why [text] should skip Nano and go straight to the box, or null to try it on the phone. */
    fun needsBox(text: String): String? {
        val t = text.lowercase(Locale.UK)
        if (t.split(Regex("\\s+")).count { it.isNotBlank() } >= RAMBLE_WORDS) return "Long ramble · sorting it"
        return rules.firstOrNull { (re, _) -> re.containsMatchIn(t) }?.second
    }

    private val HM = DateTimeFormatter.ofPattern("HH:mm")
    private val SHORT = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)

    /**
     * What Tom has on, for Nano to answer "what's on tomorrow?" and the like: today's and tomorrow's calendar, tasks
     * that are late, due today or tomorrow (plus a few undated ones), and the weather card's write-up.
     */
    fun context(today: LocalDate, events: List<CalEvent>, tasks: List<TaskItem>, weather: WeatherSummary?): String = buildString {
        val tomorrow = today.plusDays(1)
        fun day(label: String, d: LocalDate) {
            val on = events.filter { it.on(d) }.sortedWith(compareBy({ !it.allDay }, { it.start }))
            append("$label (${d.format(SHORT)}): ")
            append(if (on.isEmpty()) "nothing in the calendar" else on.take(12).joinToString("; ") { e ->
                (if (e.allDay) "all day " else "${e.start.format(HM)}–${e.end.format(HM)} ") + e.title + (e.location?.takeIf { it.isNotBlank() }?.let { " at $it" } ?: "")
            })
            append("\n")
        }
        day("Today", today)
        day("Tomorrow", tomorrow)
        fun list(label: String, ts: List<TaskItem>, max: Int) {
            if (ts.isNotEmpty()) append("$label: ").append(ts.take(max).joinToString("; ") { it.text }).append(if (ts.size > max) " (+${ts.size - max} more)" else "").append("\n")
        }
        list("Overdue tasks", tasks.filter { it.due != null && it.due < today }, 6)
        list("Tasks due today", tasks.filter { it.due == today }, 10)
        list("Tasks due tomorrow", tasks.filter { it.due == tomorrow }, 10)
        list("Tasks with no date", tasks.filter { it.due == null }, 6)
        weather?.let { w ->
            append(if (w.tomorrow) "Weather tomorrow: " else "Weather today: ")
            append("${w.high}° / ${w.low}°, ${w.headline}. ${w.detail}")
            w.tip?.let { append(" ").append(it) }
            w.tonight?.let { append(" ").append(it) }
            append("\n")
        }
    }.trim()

    /** The words in [question] worth searching the vault for. */
    fun keywords(question: String): List<String> =
        question.lowercase(Locale.UK).split(Regex("[^\\p{L}\\p{N}'-]+")).map { it.trim('\'', '-') }
            .filter { it.length >= 4 && it !in STOP }.distinct()

    /**
     * The few notes that best match [question], as "## Title (path)" plus the part of each round the words, kept
     * small enough for Nano. Notes in hidden 99 folders and templates are skipped.
     */
    fun vaultContext(notes: List<NoteInfo>, question: String, max: Int = 3): String {
        val words = keywords(question)
        if (words.isEmpty()) return ""
        val scored = notes.asSequence()
            .filter { !it.path.startsWith("99 ") && !it.path.contains("/99 ") && it.text.isNotBlank() }
            .map { n ->
                val title = n.path.lowercase(Locale.UK)
                val text = n.text.lowercase(Locale.UK)
                val score = words.sumOf { w -> (if (title.contains(w)) 6 else 0) + Regex(Regex.escape(w)).findAll(text).take(5).count() }
                n to score
            }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<NoteInfo, Int>> { it.second }.thenByDescending { it.first.modified })
            .take(max).toList()
        return scored.joinToString("\n\n") { (n, _) -> "## ${n.title} (${n.path})\n" + excerpt(n.text, words) }
    }

    /** Up to [NOTE_CHARS] of [text]: from a little before the first matching line, frontmatter left out. */
    fun excerpt(text: String, words: List<String>): String {
        val lines = Md.fmBody(NoteText.lines(text)).filter { it.isNotBlank() }
        val first = lines.indexOfFirst { l -> words.any { l.lowercase(Locale.UK).contains(it) } }.coerceAtLeast(0)
        val from = (first - 3).coerceAtLeast(0)
        val out = StringBuilder()
        for (l in lines.drop(from)) {
            if (out.length + l.length > NOTE_CHARS) break
            out.append(l.trimEnd()).append('\n')
        }
        return out.toString().trim().ifEmpty { lines.joinToString("\n").take(NOTE_CHARS) }
    }

    fun prompt(turns: List<PhoneBrain.Turn>, text: String, today: LocalDate, tags: List<String>, context: String = "", notes: String = ""): String {
        val head = """
            You are Jarvis, Tom's assistant on his phone. Use UK English. Keep answers short and friendly: one to three
            sentences unless he asks for more. Answers may be read aloud, so no tables or headings. Today is ${today.format(DAY)}.
            Below is what Tom has on today and tomorrow (calendar, tasks, weather) and the notes from his Obsidian vault
            that best match his question. Use them to answer; don't make up anything that isn't there. If a good answer
            needs more than this (other notes, other dates, the web, current facts, code, or changing a note), reply
            with only the word BOX.
            When Tom asks you to add, remember or remind him of something to do, confirm it in a few words, then end with
            one line per task:
            TASK: <short task> | <YYYY-MM-DD, or blank> | <one of ${tags.joinToString(" ")}, or blank>
        """.trimIndent()
        val lines = turns.filter { it.text.isNotBlank() }.takeLast(KEEP_TURNS)
            .map { (if (it.mine) "Tom: " else "Jarvis: ") + it.text.trim() }
        // Oldest turns go first when the prompt would be too long for Nano's small window.
        var past = lines
        while (past.isNotEmpty() && (past.sumOf { it.length } + text.length + head.length + context.length + notes.length) > MAX_CHARS) past = past.drop(1)
        return buildString {
            append(head).append("\n\n")
            if (context.isNotBlank()) append("What Tom has on:\n").append(context.trim()).append("\n\n")
            if (notes.isNotBlank()) append("Matching notes from his vault:\n").append(notes.trim()).append("\n\n")
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
