package uk.tomlowndes.capture

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** One thing to do when stuck: `- ☕ Make a proper coffee · 5 min`. */
data class StuckItem(val emoji: String, val name: String, val minutes: Int)

/** Stuck? Pick one. sections, shortest first. */
enum class StuckLength(val title: String) { QUICK("Quick"), MEDIUM("Medium"), LONG("Long") }

/** One finished Focus session from the Focus log. */
data class FocusSession(val at: LocalDateTime, val task: String, val minutes: Int)

/**
 * The plain notes Flight Deck keeps in the vault (editable in Obsidian), made with starter items on first run
 * if they're missing.
 */
object FlightNotes {
    const val DIR = "99 - Meta"
    const val STUCK = "$DIR/Stuck menu.md"
    const val FOCUS_LOG = "$DIR/Focus log.md"

    val STUCK_STARTER = """
        # Stuck menu

        ## Quick
        - ☕ Make a proper coffee · 5 min
        - 🎧 One song, then sit down · 4 min
        - 🧽 Clear one surface · 8 min
        - 🚶 Walk to the end of the road · 10 min

        ## Medium
        - 📞 Text one friend · 5 min

        ## Long
    """.trimIndent() + "\n"

    val FOCUS_STARTER = "# Focus log\n\n"

    /** Creates any missing note with its starter text. */
    fun ensure(v: Vault) {
        listOf(STUCK to STUCK_STARTER, FOCUS_LOG to FOCUS_STARTER).forEach { (path, text) ->
            if (v.entry(path) == null) {
                v.mkdirs(DIR)
                VaultIo.write(v, v.create(DIR, nameOf(path).removeSuffix(".md"), "md"), text)
            }
        }
    }

    // ---- Stuck menu ----

    private val STUCK_LINE = Regex("^\\s*[-*]\\s+(\\S+)\\s+(.+?)\\s*·\\s*(\\d+)\\s*min\\b.*$")

    fun stuck(text: String): Map<StuckLength, List<StuckItem>> {
        val out = StuckLength.values().associateWith { mutableListOf<StuckItem>() }
        var section: StuckLength? = null
        text.split("\n").forEach { line ->
            if (line.startsWith("## ")) {
                section = StuckLength.values().firstOrNull { it.title.equals(line.removePrefix("## ").trim(), ignoreCase = true) }
                return@forEach
            }
            val s = section ?: return@forEach
            STUCK_LINE.matchEntire(line)?.let { m ->
                out.getValue(s).add(StuckItem(m.groupValues[1], m.groupValues[2].trim(), m.groupValues[3].toInt()))
            }
        }
        return out
    }

    fun stuckLine(i: StuckItem) = "- ${i.emoji} ${i.name} · ${i.minutes} min"

    /** Adds [item] at the end of its section, making the section if it isn't there. */
    fun addStuck(lines: MutableList<String>, length: StuckLength, item: StuckItem) {
        val start = lines.indexOfFirst { it.trim().equals("## ${length.title}", ignoreCase = true) }
        if (start < 0) {
            while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.lastIndex)
            lines.addAll(listOf("", "## ${length.title}", stuckLine(item), ""))
            return
        }
        var at = (start + 1 until lines.size).firstOrNull { lines[it].startsWith("## ") } ?: lines.size
        while (at > start + 1 && lines[at - 1].isBlank()) at--
        lines.add(at, stuckLine(item))
    }

    // ---- Focus log ----

    private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private val FOCUS_LINE = Regex("^\\s*-\\s+(\\d{4}-\\d{2}-\\d{2})(?:\\s+(\\d{2}:\\d{2}))?\\s*·\\s*(.+?)\\s*·\\s*(\\d+)\\s*(?:min|m)?\\s*$")

    fun focusLine(s: FocusSession) = "- ${s.at.format(STAMP)} · ${s.task} · ${s.minutes} min"

    fun sessions(text: String): List<FocusSession> = text.split("\n").mapNotNull { line ->
        val m = FOCUS_LINE.matchEntire(line) ?: return@mapNotNull null
        val date = runCatching { LocalDate.parse(m.groupValues[1]) }.getOrNull() ?: return@mapNotNull null
        val time = m.groupValues[2].takeIf { it.isNotEmpty() }?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: LocalTime.MIDNIGHT
        FocusSession(LocalDateTime.of(date, time), m.groupValues[3], m.groupValues[4].toInt())
    }

    fun today(sessions: List<FocusSession>, today: LocalDate) = sessions.filter { it.at.toLocalDate() == today }

    fun logFocus(v: Vault, s: FocusSession) {
        ensure(v)
        VaultIo.edit(v, FOCUS_LOG) { lines ->
            while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.lastIndex)
            lines.add(focusLine(s))
        }
    }

    fun loadStuck(v: Vault): Map<StuckLength, List<StuckItem>> { ensure(v); return stuck(VaultIo.read(v, STUCK)) }

    fun loadToday(v: Vault, today: LocalDate = LocalDate.now()): List<FocusSession> =
        today(sessions(v.read(FOCUS_LOG).orEmpty()), today)
}
