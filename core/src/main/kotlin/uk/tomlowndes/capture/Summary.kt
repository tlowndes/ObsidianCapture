package uk.tomlowndes.capture

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Print summary (mockup print-summary-v1): Jarvis boils a note, a Jarvis chat or the day down to a few lines,
 * shown on paper first and then printed on the pocket printer.
 */
object Summary {
    /** How much Jarvis writes. Short = two lines, Bullets = three to five points, Longer = a paragraph. */
    enum class Length(val label: String) { SHORT("Short"), BULLETS("Bullets"), LONGER("Longer") }

    /** What goes on the paper. [bullets] are list points; when empty, [text] is a paragraph. */
    data class Doc(
        val title: String,
        val subtitle: String,
        val text: String,
        val bullets: List<String>,
        val ticks: Boolean = false,
    ) {
        /** The summary as plain lines, for the Edit box. */
        fun editable(): String = if (bullets.isNotEmpty()) bullets.joinToString("\n") { "- $it" } else text

        /** [s] from the Edit box back into a Doc, keeping list points as list points. */
        fun edited(s: String): Doc {
            val pts = points(s)
            return if (pts.isNotEmpty()) copy(text = "", bullets = pts) else copy(text = s.trim(), bullets = emptyList())
        }
    }

    /** Notes and chats can be long; this much is plenty for a summary and keeps the request quick. */
    const val MAX_SOURCE = 12_000

    private val STAMP = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.UK)

    fun stamp(now: LocalDateTime): String = now.format(STAMP)

    private fun ask(length: Length): String = when (length) {
        Length.SHORT -> "Reply with only two short plain sentences."
        Length.BULLETS -> "Reply with only 3 to 5 short bullet points, one per line, each starting with \"- \"."
        Length.LONGER -> "Reply with only one plain paragraph of 4 to 6 sentences."
    }

    private const val PLAIN = " UK spelling, no intro, no headings, no markdown bold. It will be printed on a till roll."

    /** What Jarvis is asked for a vault note. */
    fun notePrompt(title: String, body: String, length: Length): String =
        "Summarise this Obsidian note for me. ${ask(length)}$PLAIN\n\nNote: $title\n\n" + body.take(MAX_SOURCE)

    /** What Jarvis is asked for the last Jarvis exchange (what was said, and the answer). */
    fun chatPrompt(said: String, reply: String, length: Length): String =
        "Summarise this conversation with you as a short to-do style recap. First line: \"Title: \" and a title of up to " +
            "four words. Then ${ask(length).removePrefix("Reply with only ").replaceFirstChar { it.lowercase() }}$PLAIN\n\n" +
            "Me: ${said.take(MAX_SOURCE / 2)}\n\nYou: ${reply.take(MAX_SOURCE / 2)}"

    /** What Jarvis is asked for the Daily Slip's In brief line. */
    fun dayPrompt(s: SlipContent): String {
        val b = StringBuilder()
        b.append("Write my day in brief for the top of a printed slip: up to three short plain sentences, friendly, ")
        b.append("say what to start with. UK spelling, no intro, no markdown. Today is ${s.date.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }} ${Slip.shortDate(s.date)}.\n")
        s.weather?.let { b.append("Weather: ${it.headline}\n") }
        (s.allDay + s.events).forEach { e -> b.append("Event: ${listOfNotNull(e.start, e.title, e.place).joinToString(" ")}\n") }
        s.overdue.forEach { b.append("Overdue: ${it.text}\n") }
        s.today.forEach { b.append("Today: ${it.text}\n") }
        return b.toString()
    }

    /** "- point", "* point", "• point", "1. point" lines. */
    private val POINT = Regex("^\\s*(?:[-*•]|\\d+[.)])\\s+(?:\\[[ xX]\\]\\s+)?(.+?)\\s*$")

    fun points(s: String): List<String> = s.replace("\r", "").lines().mapNotNull { POINT.matchEntire(it)?.groupValues?.get(1) }
        .map(::clean).filter { it.isNotEmpty() }

    /** Drops markdown bold/italics, headings and backticks that the slip can't show. */
    fun clean(s: String): String = s.replace(Regex("\\*\\*(.+?)\\*\\*"), "$1").replace(Regex("__(.+?)__"), "$1")
        .replace(Regex("(?<![\\w*])\\*(?!\\s)(.+?)(?<!\\s)\\*(?![\\w*])"), "$1").replace("`", "")
        .replace(Regex("^#+\\s*"), "").trim()

    /**
     * Jarvis's reply to a [Doc]. A "Title:" first line (chat summaries) overrides [title]. List lines become
     * bullets when [length] asks for them, or when the reply is mostly a list anyway.
     */
    fun parse(reply: String, title: String, subtitle: String, length: Length, ticks: Boolean = false): Doc {
        var lines = reply.replace("\r", "").lines().map { it.trimEnd() }.dropWhile { it.isBlank() }
        var t = title
        lines.firstOrNull()?.let { first ->
            Regex("^\\s*\\**title\\**\\s*:\\s*(.+)$", RegexOption.IGNORE_CASE).matchEntire(first)?.let {
                t = clean(it.groupValues[1]).trim('"', '“', '”').ifBlank { title }
                lines = lines.drop(1)
            }
        }
        val body = lines.joinToString("\n").trim()
        val pts = points(body)
        val listy = pts.isNotEmpty() && (length == Length.BULLETS || ticks || pts.size * 2 >= body.lines().count { it.isNotBlank() })
        return if (listy) Doc(t, subtitle, "", pts, ticks)
        else Doc(t, subtitle, body.lines().map(::clean).filter { it.isNotEmpty() }.joinToString(" "), emptyList(), ticks)
    }

    /** The In brief text from Jarvis's reply: plain, at most [max] characters so it stays three-ish lines. */
    fun brief(reply: String, max: Int = 320): String? {
        val t = reply.replace("\r", "").lines().map(::clean).filter { it.isNotEmpty() }
            .joinToString(" ") { POINT.matchEntire(it)?.groupValues?.get(1) ?: it }.trim()
        if (t.isEmpty()) return null
        if (t.length <= max) return t
        val cut = t.take(max)
        val end = cut.lastIndexOfAny(charArrayOf('.', '!', '?'))
        return if (end > max / 2) cut.substring(0, end + 1) else cut.trimEnd() + "…"
    }

    /** Replies for the demo address, so the sheet can be tried without the server. */
    fun demo(length: Length, chat: Boolean): String {
        val head = if (chat) "Title: Garage plan\n" else ""
        return head + when (length) {
            Length.SHORT -> "A short summary goes here, two sentences at most. This is the demo, so set up Jarvis for the real thing."
            Length.BULLETS -> "- The first main point\n- The second point\n- One more worth keeping\n- Next: the first thing to do"
            Length.LONGER -> "This is what a longer summary looks like on the slip. It runs to a short paragraph of a few sentences. " +
                "Jarvis keeps it plain so it prints cleanly. Set up Jarvis in Settings to get a real one."
        }
    }

    fun demoBrief(d: LocalDate): String = "Steady ${d.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }}. " +
        "Start with the oldest overdue task, then clear today's list. This is the demo In brief."
}
