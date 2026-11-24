package uk.tomlowndes.capture

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One Did I? check: `- 🔥 Hob · Is the hob off?`. The question is optional. A check linked to the house (Home Did I?)
 * ends with its Home Assistant test: `- 🔥 Hob · Is the hob off? · ha sensor.hob_plug_power < 5`.
 */
data class DidCheck(val emoji: String, val name: String, val question: String = "$name done?", val link: HaLink? = null)

/** One tap in the day's log: `- 08:12 · Door`, or `- 08:39 · Door · auto` when Home Assistant answered it. */
data class DidStamp(val time: LocalTime, val name: String, val auto: Boolean = false)

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
        val whole = CHECK_LINE.matchEntire(line)?.groupValues?.get(1) ?: return@mapNotNull null
        // A Home Did I? link is the last part, starting "ha ".
        val parts = whole.split("·").map { it.trim() }
        val link = parts.drop(1).lastOrNull()?.let { HaLink.parse(it) }
        val body = (if (link != null) parts.dropLast(1) else parts).joinToString(" · ")
        val main = body.substringBefore("·").trim()
        val question = body.substringAfter("·", "").trim()
        val first = main.substringBefore(" ")
        // A line without a leading emoji ("- Plugs") is still a check; it gets a plain tick.
        val hasEmoji = first.isNotEmpty() && !first.first().isLetterOrDigit() && main.contains(" ")
        val emoji = if (hasEmoji) first else "✓"
        val name = (if (hasEmoji) main.substringAfter(" ") else main).trim()
        if (name.isEmpty()) null else if (question.isEmpty()) DidCheck(emoji, name, link = link) else DidCheck(emoji, name, question, link)
    }.distinctBy { it.name.lowercase(Locale.UK) }

    fun checkLine(c: DidCheck): String =
        (if (c.question == "${c.name} done?") "- ${c.emoji} ${c.name}" else "- ${c.emoji} ${c.name} · ${c.question}") +
            (c.link?.let { " · " + it.format() } ?: "")

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

    fun stampLine(s: DidStamp) = "- ${s.time.format(HM)} · ${s.name}" + if (s.auto) " · $AUTO" else ""

    private const val AUTO = "auto"

    fun stamps(text: String): List<DidStamp> = text.split("\n").mapNotNull { line ->
        val m = STAMP_LINE.matchEntire(line) ?: return@mapNotNull null
        val t = runCatching { LocalTime.parse(m.groupValues[1]) }.getOrNull() ?: return@mapNotNull null
        val name = m.groupValues[2]
        val auto = name.endsWith(" · $AUTO") || name.endsWith("·$AUTO")
        DidStamp(t, if (auto) name.substringBeforeLast("·").trim() else name, auto)
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

    /** Sets (or with null, clears) the Home Did I? link on the check called [name], keeping the rest of its line. */
    fun setLink(lines: MutableList<String>, name: String, link: HaLink?): Boolean {
        val i = lines.indexOfFirst { l -> CHECK_LINE.matches(l) && checks(l).firstOrNull()?.name?.equals(name, ignoreCase = true) == true }
        if (i < 0) return false
        val c = checks(lines[i]).first()
        lines[i] = checkLine(c.copy(link = link))
        return true
    }

    fun saveLink(v: Vault, name: String, link: HaLink?) { ensure(v); VaultIo.edit(v, CHECKS) { setLink(it, name, link) } }

    /** Logs [s] as it is (an auto stamp keeps its own time). Makes the day's note if it isn't there yet. */
    fun stampAt(v: Vault, d: LocalDate, s: DidStamp) {
        val path = logPath(d)
        if (v.entry(path) == null) {
            v.mkdirs(LOG_DIR)
            VaultIo.write(v, v.create(LOG_DIR, d.toString(), "md"), header(d))
        }
        VaultIo.edit(v, path) { lines -> addStamp(lines, s); sortStamps(lines) }
    }

    /** Keeps the day's stamp lines in time order (an auto stamp can land after a later tap). */
    private fun sortStamps(lines: MutableList<String>) {
        val idx = lines.indices.filter { STAMP_LINE.matches(lines[it]) }
        val sorted = idx.map { lines[it] }.sortedBy { it.trim().removePrefix("- ").take(5) }
        idx.forEachIndexed { k, i -> lines[i] = sorted[k] }
    }
}

/** The tools in Hangar. Only [ready] ones can be added; the rest show as coming next. */
enum class HangarTool(val id: String, val title: String, val icon: String, val blurb: String, val ready: Boolean) {
    DID_I("didi", "Did I?", "✓", "Stop wondering if you locked the door", true),
    LAUNCH_PAD("launch", "Launch Pad", "↗", "A checklist when you head out", true),
    HEADS_UP("heads", "Heads Up", "◔", "Buzzes before you need to switch", true),
    /** The evening version of Launch Pad (hangar-phase2). */
    NIGHT_PAD("night", "Night Pad", "☾", "Get tomorrow's bag ready tonight", true),
    /** Finding things (hangar-phase2). */
    POCKET_PASS("pass", "Pocket Pass", "⌗", "The codes you always hunt for, one tap to copy", true),
    LAST_SEEN("seen", "Last Seen", "⌖", "Snap where you put something, find it later", true),
    RETURN_RADAR("returns", "Return Radar", "↩", "Return-by dates for parcels, with buzzes", true),
    /** Day to day (hangar-phase3). */
    COOL_OFF("cool", "Cool-off", "❄", "Wait 48 hours before you buy, and watch the price", true),
    REFILL("refill", "Refill", "✚", "Reorder meds a week before they run out", true),
    WAITING("waiting", "Waiting Mode", "⧗", "Something better than scrolling in a queue", true),
    ERRAND_RUN("run", "Errand Run", "⇢", "Every errand stop in one run, nearest first", true),
    /** At the desk, not getting out: Hangar lists it in its own group (ticket-rail-v1). */
    TICKET_RAIL("rail", "Ticket Rail", "▤", "Print tasks as tickets for a rail by your desk", true),
    /** At the desk too: turns the room down through Home Assistant (sensory-dial-v1). */
    SENSORY("sensory", "Sensory Dial", "◐", "Turn the room down in one move: lights, noise and calls", true),
    /** Moved off the Deck into Hangar (hangar-dock-v2). They open the app's own screens. */
    INBOX("inbox", "Inbox", "✉", "Email that needs you, from the box", true),
    HOME("home", "Home", "🏠", "Chores by room, when they need doing", true),
    PROJECTS("projects", "Projects", "💡", "Your active project and ideas", true),
    ERRANDS("errands", "Errands", "📍", "Errands and shopping, by place", true),
    MEALS("meals", "Meals", "🍽", "This week's meals and the shop", true),
    /** The morning email as a page, read or listened to (daily-brief-v1). Opens its own screen. */
    BRIEF("brief", "Daily Brief", "☀", "Your morning brief, to read or listen to", true);

    /** Hangar's group for the tool: Getting out, Finding things, Day to day, or At the desk. */
    val group get() = when (this) {
        TICKET_RAIL, SENSORY -> "At the desk"
        POCKET_PASS, LAST_SEEN, RETURN_RADAR -> "Finding things"
        COOL_OFF, REFILL, WAITING, ERRAND_RUN -> "Day to day"
        INBOX, HOME, PROJECTS, ERRANDS, MEALS, BRIEF -> "Around home"
        else -> "Getting out"
    }

    /** Tools for getting out the door. */
    val out get() = group == "Getting out"

    companion object {
        fun byId(id: String) = values().firstOrNull { it.id == id.trim() }
    }
}

/** Hangar's layout: the tools you've added, in your order. Kept as "didi,launch" in the app's settings. */
object Hangar {
    /** Null means Hangar has never been set up (first run shows the Add list). */
    fun parse(saved: String?): List<HangarTool>? =
        saved?.split(",")?.mapNotNull { HangarTool.byId(it) }?.filter { it != HangarTool.ERRAND_RUN }?.distinct()

    /** Errand Run moved to the Errands list (fold-doors-v1). True while an old Hangar list still names it. */
    fun hadRun(saved: String?) = saved?.split(",")?.contains(HangarTool.ERRAND_RUN.id) == true

    fun format(tools: List<HangarTool>) = tools.distinct().joinToString(",") { it.id }

    fun add(tools: List<HangarTool>, t: HangarTool) = if (t in tools) tools else tools + t

    fun remove(tools: List<HangarTool>, t: HangarTool) = tools - t

    /** Moves the tool at [from] up (-1) or down (+1). */
    fun move(tools: List<HangarTool>, from: Int, by: Int): List<HangarTool> {
        val to = from + by
        if (from !in tools.indices || to !in tools.indices) return tools
        return tools.toMutableList().apply { add(to, removeAt(from)) }
    }

    /** The tools that moved off the Deck (hangar-dock-v2), and where each lands in an existing Hangar. */
    val FROM_DECK = listOf(HangarTool.INBOX to 0, HangarTool.HOME to 2, HangarTool.ERRANDS to 3, HangarTool.PROJECTS to 6, HangarTool.MEALS to 7)

    /** Adds the Deck's old tiles to a Hangar set up before they moved, spread through the top so they show in the dock. */
    fun withDeckTools(tools: List<HangarTool>?): List<HangarTool> {
        val list = tools.orEmpty().toMutableList()
        FROM_DECK.forEach { (t, at) -> if (t !in list) list.add(minOf(at, list.size), t) }
        return list
    }

    /**
     * Which order wins when the phone's list meets the vault's (hangar-windows-v1). Windows only reorders, so when
     * both hold the same tools the vault's order is taken; when the tools differ the phone's list stands (it's
     * where tools are added and hidden, and where their buzzes are set up).
     */
    fun merged(phone: List<HangarTool>, vault: List<HangarTool>?): List<HangarTool> =
        if (vault != null && vault.toSet() == phone.toSet()) vault else phone

    /** Puts the tool at [from] at [to], the rest shuffling along. */
    fun moved(tools: List<HangarTool>, from: Int, to: Int): List<HangarTool> {
        if (from == to || from !in tools.indices || to !in tools.indices) return tools
        return tools.toMutableList().apply { add(to, removeAt(from)) }
    }

    /** Tools not added yet, ready ones first. */
    fun available(tools: List<HangarTool>) = HangarTool.values().filter { it !in tools && it != HangarTool.ERRAND_RUN }.sortedBy { !it.ready }
}
