package uk.tomlowndes.capture

import java.time.LocalDate

/** Bullet Journal kinds: • task, ○ event, – note, ★ priority task. */
enum class LogKind(val mark: String, val label: String) {
    TASK("•", "task"), EVENT("○", "event"), NOTE("–", "note"), PRIORITY("★", "priority");
    val isTask get() = this == TASK || this == PRIORITY
}

/** One rapid-log line, read: its kind, the words left once the date and estimate are taken off, and those. */
data class LogEntry(val kind: LogKind, val text: String, val due: LocalDate? = null, val minutes: Int? = null)

/**
 * Bullet Journal rapid logging (paper-hybrid-v1). A line's first character picks its kind, and plain-English bits at the
 * end fill in the rest: "Call Sarah fri 15m #work" is a task due Friday, 15 minutes, tagged #work.
 * Also reads a whole page of text (a scanned notebook page) into tasks and inbox lines.
 */
object RapidLog {
    // Signifiers as typed, and as text recognition tends to read handwritten ones.
    private val TASK_MARKS = listOf("[ ]", "[]", "☐", "□", "•", "·", "*", "+")
    private val EVENT_MARKS = listOf("○", "◦", "°", "o ", "O ")
    private val NOTE_MARKS = listOf("–", "—", "-", "=", "~")
    private val PRIORITY_MARKS = listOf("★", "☆", "!", "*!")
    private val DONE_MARKS = listOf("×", "✗", "✓", "✔", "[x]", "[X]", "x ", "X ")
    /** Short words that are more often a name or a noun than a date at the end of a task ("Email Tom", "Watch the sun"). */
    private val NOT_DATES = setOf("tom", "tod", "sun")
    private val ESTIMATE = Regex("^(?:(\\d+)h)?(?:(\\d+)m(?:in)?)?$", RegexOption.IGNORE_CASE)

    /** The kind a line's leading mark gives (null when it has none), and the line without it. Done lines (× ✓) give null kind and done = true. */
    fun mark(line: String): Triple<LogKind?, String, Boolean> {
        val s = line.trimStart()
        fun rest(m: String) = s.removePrefix(m).trimStart()
        PRIORITY_MARKS.firstOrNull { s.startsWith(it) }?.let { return Triple(LogKind.PRIORITY, rest(it), false) }
        DONE_MARKS.firstOrNull { s.startsWith(it) }?.let { return Triple(null, rest(it), true) }
        TASK_MARKS.firstOrNull { s.startsWith(it) }?.let { return Triple(LogKind.TASK, rest(it), false) }
        EVENT_MARKS.firstOrNull { s.startsWith(it) }?.let { return Triple(LogKind.EVENT, rest(it), false) }
        NOTE_MARKS.firstOrNull { s.startsWith(it) }?.let { return Triple(LogKind.NOTE, rest(it), false) }
        return Triple(null, s, false)
    }

    /** "15m" → 15, "1h" → 60, "1h30" or "1h30m" → 90; anything else null. */
    fun minutes(word: String): Int? {
        val w = word.lowercase().let { if (Regex("^\\d+h\\d+$").matches(it)) it + "m" else it }
        val m = ESTIMATE.matchEntire(w) ?: return null
        val (h, mm) = m.destructured
        if (h.isEmpty() && mm.isEmpty()) return null
        return (h.toIntOrNull() ?: 0) * 60 + (mm.toIntOrNull() ?: 0)
    }

    /**
     * Reads one line. [kind] is used when the line has no mark of its own. Trailing words that are an estimate or a date
     * (in either order, tags kept in place) come off the text; a date is only read for tasks.
     */
    fun parse(line: String, today: LocalDate, kind: LogKind = LogKind.TASK): LogEntry {
        val (k, body, _) = mark(line)
        val use = k ?: kind
        val words = body.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.toMutableList()
        val tags = mutableListOf<String>()
        var due: LocalDate? = null
        var mins: Int? = null
        var changed = true
        while (changed && words.isNotEmpty()) {
            changed = false
            val last = words.last()
            if (last.startsWith("#") && last.length > 1) { tags.add(0, words.removeAt(words.size - 1)); changed = true; continue }
            if (mins == null) minutes(last)?.let { mins = it; words.removeAt(words.size - 1); changed = true; continue }
            if (due == null && use.isTask) {
                if (words.size >= 2) DateWords.date(words.takeLast(2), today)?.let {
                    due = it; repeat(2) { words.removeAt(words.size - 1) }; changed = true
                }
                if (!changed && last.lowercase() !in NOT_DATES) DateWords.date(listOf(last), today)?.let { due = it; words.removeAt(words.size - 1); changed = true }
            }
        }
        // Keep a lone date-like word if it was the whole line ("Friday" alone is the text, not the date).
        if (words.isEmpty() && due != null && tags.isEmpty()) return LogEntry(use, body.trim(), null, mins)
        return LogEntry(use, (words + tags).joinToString(" "), due, mins)
    }

    /** The task text as written to Quick Task: estimate and ⏫ for a priority. */
    fun taskText(e: LogEntry): String =
        e.text + (e.minutes?.let { " ⏱ ${it}m" } ?: "") + (if (e.kind == LogKind.PRIORITY) " ⏫" else "")

    /** The line an event or note gets in the inbox note. */
    fun inboxLine(e: LogEntry): String = "- " + (if (e.kind == LogKind.EVENT) "○ " else "") + e.text

    /**
     * A page of text (from a scan) as rapid-log entries. Each marked line starts an entry; an unmarked line carries on the
     * one before (handwriting wraps), or is a note when nothing came before. Done lines (× ✓) are skipped, as are blanks.
     */
    fun page(text: String, today: LocalDate): List<LogEntry> {
        val out = mutableListOf<Pair<LogKind, String>>()
        var skipping = false
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val (k, body, done) = mark(line)
            when {
                done -> skipping = true
                k != null -> { out += k to body; skipping = false }
                skipping -> {}
                out.isNotEmpty() -> out[out.size - 1] = out.last().first to (out.last().second + " " + body)
                else -> out += LogKind.NOTE to body
            }
        }
        return out.filter { it.second.isNotBlank() }.map { (k, body) -> parse(body, today, k) }
    }
}
