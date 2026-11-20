package uk.tomlowndes.capture

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One Did I? check: `- 🔥 Hob · Is the hob off?`. The question is optional. */
data class DidCheck(val emoji: String, val name: String, val question: String = "$name done?")

/** One tap in the day's log: `- 08:12 · Door`. */
data class DidStamp(val time: LocalTime, val name: String)

/** A check with the time it was last stamped today, or null when it hasn't been yet. */
data class DidStatus(val check: DidCheck, val at: LocalTime?)

/**
 * Did I? (Hangar, phase 1): one tap logs "door locked", "hob off" or "took meds" with the time, so the
 * "did I actually do it?" worry has an answer later. Checks live in one note; each day gets its own log note.
 * Everything stays plain markdown, editable in Obsidian.
 */
object DidI {
    const val CHECKS = "${FlightNotes.DIR}/Did I checks.md"
    const val LOG_DIR = "${FlightNotes.DIR}/Did I"

    val STARTER = """
        # Did I?

        One check per line: emoji, name, then an optional question after a ·.
        Flight Deck logs each tap with the time in the Did I folder.

        - 💊 Meds · Taken your meds?
        - 🔒 Door · Is the door locked?
        - 🔥 Hob · Is the hob off?
    """.trimIndent() + "\n"

    private val HM = DateTimeFormatter.ofPattern("HH:mm")
    private val DAY = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.UK)
    private val CHECK_LINE = Regex("^\\s*[-*]\\s+(?!\\[)(.+?)\\s*$")
    private val STAMP_LINE = Regex("^\\s*-\\s+(\\d{2}:\\d{2})\\s*·\\s*(.+?)\\s*$")

    fun logPath(d: LocalDate) = "$LOG_DIR/$d.md"

    // ---- checks ----

    fun checks(text: String): List<DidCheck> = text.split("\n").mapNotNull { line ->
        val body = CHECK_LINE.matchEntire(line)?.groupValues?.get(1) ?: return@mapNotNull null
        val main = body.substringBefore("·").trim()
        val question = body.substringAfter("·", "").trim()
        val first = main.substringBefore(" ")
        // A line without a leading emoji ("- Plugs") is still a check; it gets a plain tick.
        val hasEmoji = first.isNotEmpty() && !first.first().isLetterOrDigit() && main.contains(" ")
        val emoji = if (hasEmoji) first else "✓"
        val name = (if (hasEmoji) main.substringAfter(" ") else main).trim()
        if (name.isEmpty()) null else if (question.isEmpty()) DidCheck(emoji, name) else DidCheck(emoji, name, question)
    }.distinctBy { it.name.lowercase(Locale.UK) }

    fun checkLine(c: DidCheck): String =
        if (c.question == "${c.name} done?") "- ${c.emoji} ${c.name}" else "- ${c.emoji} ${c.name} · ${c.question}"

    /** Adds [c] after the last check line, or at the end. Does nothing if a check with that name is there. */
    fun addCheck(lines: MutableList<String>, c: DidCheck) {
        if (checks(lines.joinToString("\n")).any { it.name.equals(c.name, ignoreCase = true) }) return
        val last = lines.indexOfLast { CHECK_LINE.matches(it) }
        if (last >= 0) { lines.add(last + 1, checkLine(c)); return }
        while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.lastIndex)
        lines.addAll(listOf("", checkLine(c)))
    }

    // ---- the day's log ----

    fun header(d: LocalDate) = "# Did I? · ${d.format(DAY)}\n\n"

    fun stampLine(s: DidStamp) = "- ${s.time.format(HM)} · ${s.name}"

    fun stamps(text: String): List<DidStamp> = text.split("\n").mapNotNull { line ->
        val m = STAMP_LINE.matchEntire(line) ?: return@mapNotNull null
        val t = runCatching { LocalTime.parse(m.groupValues[1]) }.getOrNull() ?: return@mapNotNull null
        DidStamp(t, m.groupValues[2])
    }

    fun addStamp(lines: MutableList<String>, s: DidStamp) {
        while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.lastIndex)
        lines.add(stampLine(s))
    }

    /** Removes the newest line for [s] (Undo). Returns false if it wasn't there. */
    fun removeStamp(lines: MutableList<String>, s: DidStamp): Boolean {
        val i = lines.indexOfLast { it.trim() == stampLine(s) }
        if (i < 0) return false
        lines.removeAt(i)
        return true
    }

    /** Each check with its latest stamp today, in the order of the checks note. */
    fun status(checks: List<DidCheck>, stamps: List<DidStamp>): List<DidStatus> = checks.map { c ->
        DidStatus(c, stamps.filter { it.name.equals(c.name, ignoreCase = true) }.maxOfOrNull { it.time })
    }

    /** The first check not done yet: the "Right now" card in Hangar and the next question in the flow. */
    fun nextOpen(status: List<DidStatus>): DidStatus? = status.firstOrNull { it.at == null }

    /** "2 of 3", or "All done" when every check is stamped. */
    fun progress(status: List<DidStatus>): String {
        val done = status.count { it.at != null }
        return if (status.isNotEmpty() && done == status.size) "All done" else "$done of ${status.size}"
    }

    // ---- vault ----

    fun ensure(v: Vault) {
        if (v.entry(CHECKS) != null) return
        v.mkdirs(FlightNotes.DIR)
        VaultIo.write(v, v.create(FlightNotes.DIR, nameOf(CHECKS).removeSuffix(".md"), "md"), STARTER)
    }

    fun loadChecks(v: Vault): List<DidCheck> { ensure(v); return checks(VaultIo.read(v, CHECKS)) }

    fun loadStamps(v: Vault, d: LocalDate): List<DidStamp> = stamps(v.read(logPath(d)).orEmpty())

    fun today(v: Vault, d: LocalDate = LocalDate.now()): List<DidStatus> = status(loadChecks(v), loadStamps(v, d))

    /** Logs [name] at [at]. Makes the day's note if it isn't there yet. */
    fun stamp(v: Vault, name: String, at: LocalDateTime = LocalDateTime.now()): DidStamp {
        val s = DidStamp(at.toLocalTime().withSecond(0).withNano(0), name)
        val path = logPath(at.toLocalDate())
        if (v.entry(path) == null) {
            v.mkdirs(LOG_DIR)
            VaultIo.write(v, v.create(LOG_DIR, at.toLocalDate().toString(), "md"), header(at.toLocalDate()))
        }
        VaultIo.edit(v, path) { addStamp(it, s) }
        return s
    }

    fun undo(v: Vault, d: LocalDate, s: DidStamp) {
        if (v.entry(logPath(d)) == null) return
        VaultIo.edit(v, logPath(d)) { removeStamp(it, s) }
    }

    fun saveCheck(v: Vault, c: DidCheck) { ensure(v); VaultIo.edit(v, CHECKS) { addCheck(it, c) } }
}

/** The tools in Hangar. Only [ready] ones can be added; the rest show as coming next. */
enum class HangarTool(val id: String, val title: String, val icon: String, val blurb: String, val ready: Boolean) {
    DID_I("didi", "Did I?", "✓", "Stop wondering if you locked the door", true),
    LAUNCH_PAD("launch", "Launch Pad", "↗", "A checklist when you head out", true),
    HEADS_UP("heads", "Heads Up", "◔", "Buzzes before you need to switch", true),
    /** At the desk, not getting out: Hangar lists it in its own group (ticket-rail-v1). */
    TICKET_RAIL("rail", "Ticket Rail", "▤", "Print tasks as tickets for a rail by your desk", true);

    /** Tools for getting out the door; the rest sit "At the desk". */
    val out get() = this != TICKET_RAIL

    companion object {
        fun byId(id: String) = values().firstOrNull { it.id == id.trim() }
    }
}

/** Hangar's layout: the tools you've added, in your order. Kept as "didi,launch" in the app's settings. */
object Hangar {
    /** Null means Hangar has never been set up (first run shows the Add list). */
    fun parse(saved: String?): List<HangarTool>? =
        saved?.split(",")?.mapNotNull { HangarTool.byId(it) }?.distinct()

    fun format(tools: List<HangarTool>) = tools.distinct().joinToString(",") { it.id }

    fun add(tools: List<HangarTool>, t: HangarTool) = if (t in tools) tools else tools + t

    fun remove(tools: List<HangarTool>, t: HangarTool) = tools - t

    /** Moves the tool at [from] up (-1) or down (+1). */
    fun move(tools: List<HangarTool>, from: Int, by: Int): List<HangarTool> {
        val to = from + by
        if (from !in tools.indices || to !in tools.indices) return tools
        return tools.toMutableList().apply { add(to, removeAt(from)) }
    }

    /** Tools not added yet, ready ones first. */
    fun available(tools: List<HangarTool>) = HangarTool.values().filter { it !in tools }.sortedBy { !it.ready }
}
