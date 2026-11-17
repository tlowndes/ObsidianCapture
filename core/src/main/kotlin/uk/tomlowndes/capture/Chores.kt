package uk.tomlowndes.capture

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * A home job that "decays" (home-life-v1): no due date, just how often it wants doing. [done] is when it was last done
 * (null: never, so it shows as full).
 */
data class Chore(
    val name: String, val emoji: String = "", val every: Int = 7, val done: LocalDate? = null, val by: String? = null,
    /** Ticked off in its room since the room's last Reset (chores-rooms-v1), written as ` · ✓`. */
    val ticked: Boolean = false,
) {
    val label get() = if (emoji.isEmpty()) name else "$emoji $name"

    /** Days since it was last done (null when never). */
    fun age(today: LocalDate): Long? = done?.let { ChronoUnit.DAYS.between(it, today).coerceAtLeast(0) }

    /** How full its bar is: 0 just done, 1 due, more than 1 overdue. Never done counts as due. */
    fun fill(today: LocalDate): Double = age(today)?.let { it.toDouble() / every.coerceAtLeast(1) } ?: 1.0

    /** "12 days · every 7", "today · every 4", "not yet · every 7". */
    fun ageLabel(today: LocalDate): String {
        val a = age(today)
        val since = when (a) { null -> "not yet"; 0L -> "today"; 1L -> "1 day"; else -> "$a days" }
        return "$since · every $every" + (by?.let { " · $it did it last" } ?: "")
    }
}

/**
 * Chores that decay (home-life-v1), kept in `99 - Meta/Chores.md`, one per line, editable in Obsidian:
 * `- 🛁 Clean bathroom · every 7 · done 2026-11-03`. The bar fills the longer a chore is left; the fullest goes first.
 */
object Chores {
    const val PATH = "${FlightNotes.DIR}/Chores.md"

    /** A chore at or past this fill counts as "needs doing" (red at [OVERDUE]). */
    const val DUE = 1.0
    const val OVERDUE = 1.25

    private val LINE = Regex("^\\s*[-*]\\s+(.+?)\\s*$")
    private val EVERY = Regex("^every\\s+(\\d+)(?:\\s*days?)?$", RegexOption.IGNORE_CASE)
    private val DONE = Regex("^done\\s+(\\d{4}-\\d{2}-\\d{2})$", RegexOption.IGNORE_CASE)
    private val BY = Regex("^by\\s+(\\S.*)$", RegexOption.IGNORE_CASE)

    val STARTER = listOf(
        Chore("Clean bathroom", "🛁", 7), Chore("Change bedding", "🧺", 14), Chore("Hoover downstairs", "🧹", 5),
        Chore("Water plants", "🌱", 4), Chore("Clean car out", "🚗", 30),
    )

    private fun header() = listOf(
        "# Chores", "",
        "Flight Deck fills a bar the longer a chore is left. Tap it in the app when it's done.",
        "Edit freely: `- emoji Name · every N · done YYYY-MM-DD`.", "",
        "## Chores",
    )

    /** One line, or null when it isn't a chore. A line without `every` is weekly. */
    fun parseLine(line: String): Chore? {
        val body = LINE.matchEntire(line)?.groupValues?.get(1) ?: return null
        if (body.startsWith("[")) return null // a checkbox, not a chore
        val parts = body.split("·").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        var every = 7
        var done: LocalDate? = null
        var by: String? = null
        parts.drop(1).forEach { p ->
            BY.matchEntire(p)?.let { by = it.groupValues[1].trim() }
            EVERY.matchEntire(p)?.let { every = it.groupValues[1].toInt().coerceIn(1, 365) }
            DONE.matchEntire(p)?.let { done = runCatching { LocalDate.parse(it.groupValues[1]) }.getOrNull() }
        }
        val ticked = parts.drop(1).any { it == TICK }
        val (emoji, name) = splitEmoji(parts[0])
        if (name.isBlank()) return null
        return Chore(name, emoji, every, done, by, ticked)
    }

    fun line(c: Chore): String = "- " + c.label + " · every ${c.every}" + (c.done?.let { " · done $it" } ?: "") + (c.by?.let { " · by $it" } ?: "") +
        (if (c.ticked) " · $TICK" else "")

    // ---- rooms (chores-rooms-v1) ----

    const val TICK = "✓"
    /** Chores above any room heading, or under the old "## Chores" heading. */
    const val HOUSE = "Around the house"
    private val HEAD = Regex("^##\\s+(.+?)\\s*$")
    private val RESET = Regex("^reset\\s+(\\d{4}-\\d{2}-\\d{2})$", RegexOption.IGNORE_CASE)

    /** A `## 🛁 Bathroom · reset 2026-11-15` heading, or null when the line isn't one. */
    fun parseRoom(line: String): ChoreRoom? {
        val body = HEAD.matchEntire(line.trim())?.groupValues?.get(1) ?: return null
        val parts = body.split("·").map { it.trim() }.filter { it.isNotEmpty() }
        val (emoji, name) = splitEmoji(parts.firstOrNull() ?: return null)
        val reset = parts.drop(1).firstNotNullOfOrNull { p -> RESET.matchEntire(p)?.let { runCatching { LocalDate.parse(it.groupValues[1]) }.getOrNull() } }
        return ChoreRoom(if (name.equals("Chores", true)) HOUSE else name, emoji, reset)
    }

    fun roomLine(r: ChoreRoom) = "## " + r.label + (r.reset?.let { " · reset $it" } ?: "")

    /** The note as rooms, in order. Chores outside any room go in [HOUSE]; empty rooms are kept. */
    fun rooms(text: String): List<ChoreRoom> {
        val out = mutableListOf<ChoreRoom>()
        var cur: ChoreRoom? = null
        for (l in text.split("\n")) {
            val r = parseRoom(l)
            if (r != null) { cur?.let { out += it }; cur = r; continue }
            val c = parseLine(l) ?: continue
            cur = (cur ?: ChoreRoom(HOUSE)).let { it.copy(chores = it.chores + c) }
        }
        cur?.let { out += it }
        return out.groupBy { it.name.lowercase() }.values.map { same -> same.first().copy(chores = same.flatMap { it.chores }) }
    }

    /** The line range of [room]'s chores (heading excluded); null heading index when the room has none (loose chores). */
    private fun span(lines: List<String>, room: String): Pair<Int, Int>? {
        var start = -1
        for (i in lines.indices) {
            val r = parseRoom(lines[i])
            if (start >= 0 && r != null) return start to i
            if (r != null && r.name.equals(room, true)) start = i + 1
        }
        if (start >= 0) return start to lines.size
        if (room.equals(HOUSE, true)) {
            val end = lines.indexOfFirst { parseRoom(it) != null }.let { if (it < 0) lines.size else it }
            return 0 to end
        }
        return null
    }

    /** [text] with chore [old] in [room] replaced by [new] (null removes; [old] null adds it to the room's end). */
    fun editedIn(text: String, room: String, old: String?, new: Chore?): String {
        val lines = text.removeSuffix("\n").split("\n").toMutableList()
        val (from, to) = span(lines, room) ?: run {
            if (new == null) return text
            while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.lastIndex)
            lines += listOf("", roomLine(ChoreRoom(room)))
            lines.size to lines.size
        }
        val at = old?.let { o -> (from until to).firstOrNull { parseLine(lines[it])?.name.equals(o, true) } }
        when {
            at != null && new != null -> lines[at] = line(new)
            at != null -> lines.removeAt(at)
            new != null -> {
                var end = to
                while (end > from && lines[end - 1].isBlank()) end--
                lines.add(end, line(new))
            }
        }
        return lines.joinToString("\n") + "\n"
    }

    /** Unticks every chore in [room] and stamps the heading with [day]. */
    fun resetIn(text: String, room: String, day: LocalDate): String {
        val lines = text.removeSuffix("\n").split("\n").toMutableList()
        val (from, to) = span(lines, room) ?: return text
        for (i in from until to) parseLine(lines[i])?.takeIf { it.ticked }?.let { lines[i] = line(it.copy(ticked = false)) }
        val head = from - 1
        val r = if (head >= 0) parseRoom(lines[head]) else null
        if (r != null && r.name.equals(room, true)) lines[head] = roomLine(r.copy(reset = day))
        return lines.joinToString("\n") + "\n"
    }

    /** Adds a room with its chores at the end of the note (or replaces one of the same name, keeping done dates). */
    fun withRoom(text: String, room: ChoreRoom, oldName: String? = null): String {
        val lines = text.removeSuffix("\n").split("\n").toMutableList()
        val old = oldName?.let { o -> rooms(text).firstOrNull { it.name.equals(o, true) } }
        val kept = room.chores.map { c -> old?.chores?.firstOrNull { it.name.equals(c.name, true) }?.let { o -> o.copy(every = c.every, emoji = c.emoji.ifEmpty { o.emoji }) } ?: c }
        val block = listOf(roomLine(room.copy(reset = old?.reset ?: room.reset))) + kept.map { line(it) }
        val sp = oldName?.let { span(lines, it) }
        if (sp != null) {
            val head = sp.first - 1
            var end = sp.second
            while (end > sp.first && lines[end - 1].isBlank()) end--
            val hasHead = head >= 0 && parseRoom(lines[head]) != null
            val start = if (hasHead) head else sp.first
            repeat(end - start) { lines.removeAt(start) }
            lines.addAll(start, block)
        } else {
            while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.lastIndex)
            lines += listOf("") + block
        }
        return lines.joinToString("\n") + "\n"
    }

    /** Takes [room] and its chores out of the note. */
    fun withoutRoom(text: String, room: String): String {
        val lines = text.removeSuffix("\n").split("\n").toMutableList()
        val (from, to) = span(lines, room) ?: return text
        val start = if (from > 0 && parseRoom(lines[from - 1]) != null) from - 1 else from
        repeat(to - start) { lines.removeAt(start) }
        return lines.joinToString("\n") + "\n"
    }

    fun text(v: Vault): String { if (v.entry(PATH) == null) load(v); return VaultIo.read(v, PATH) }
    fun write(v: Vault, text: String) = VaultIo.write(v, PATH, text)

    /** Leading emoji (anything that isn't a letter or digit) split from the name. */
    fun splitEmoji(s: String): Pair<String, String> {
        val t = s.trim()
        val sp = t.indexOf(' ')
        if (sp <= 0) return "" to t
        val first = t.substring(0, sp)
        return if (first.none { it.isLetterOrDigit() }) first to t.substring(sp + 1).trim() else "" to t
    }

    fun parse(text: String): List<Chore> = text.split("\n").mapNotNull(::parseLine)

    /** Fullest first; ties keep the note's order. */
    fun sorted(all: List<Chore>, today: LocalDate): List<Chore> = all.sortedByDescending { it.fill(today) }

    /** Chores at or past [DUE], fullest first. */
    fun due(all: List<Chore>, today: LocalDate): List<Chore> = sorted(all, today).filter { it.fill(today) >= DUE }

    /** The note's text after replacing [old] (matched by name) with [new]; [new] null removes it, [old] null adds. */
    fun edited(text: String, old: String?, new: Chore?): String {
        val lines = text.split("\n").toMutableList()
        val at = old?.let { o -> lines.indexOfFirst { l -> parseLine(l)?.name.equals(o, ignoreCase = true) } } ?: -1
        when {
            at >= 0 && new != null -> lines[at] = line(new)
            at >= 0 -> lines.removeAt(at)
            new != null -> {
                val last = lines.indexOfLast { parseLine(it) != null }
                if (last >= 0) lines.add(last + 1, line(new))
                else {
                    while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.lastIndex)
                    if (lines.none { it.trim().equals("## Chores", true) }) { if (lines.isNotEmpty()) lines.add(""); lines.add("## Chores") }
                    lines.add(line(new))
                }
            }
        }
        return lines.joinToString("\n").let { if (it.endsWith("\n")) it else "$it\n" }
    }

    // ---- vault ----

    /** The chores, making the note with starter chores (all done today, so nothing starts red) the first time. */
    fun load(v: Vault, today: LocalDate = LocalDate.now()): List<Chore> {
        if (v.entry(PATH) == null) {
            v.mkdirs(FlightNotes.DIR)
            val text = (header() + STARTER.map { line(it.copy(done = today)) }).joinToString("\n") + "\n"
            VaultIo.write(v, v.create(FlightNotes.DIR, "Chores", "md"), text)
        }
        return parse(VaultIo.read(v, PATH))
    }

    private fun change(v: Vault, old: String?, new: Chore?) {
        if (v.entry(PATH) == null) load(v)
        VaultIo.edit(v, PATH) { lines ->
            val out = edited(lines.joinToString("\n"), old, new).removeSuffix("\n").split("\n")
            lines.clear(); lines.addAll(out)
        }
    }

    /** Marks [c] done on [day]. Returns the chore as it was, for Undo. */
    fun markDone(v: Vault, c: Chore, day: LocalDate = LocalDate.now()): Chore { change(v, c.name, c.copy(done = day, by = null)); return c }

    fun put(v: Vault, old: String?, c: Chore) = change(v, old, c)
    fun remove(v: Vault, c: Chore) = change(v, c.name, null)
}

/** A room of chores (chores-rooms-v1): `## 🛁 Bathroom · reset 2026-11-15` and the chores under it. */
data class ChoreRoom(val name: String, val emoji: String = "", val reset: LocalDate? = null, val chores: List<Chore> = emptyList()) {
    val label get() = if (emoji.isEmpty()) name else "$emoji $name"
    val ticked get() = chores.count { it.ticked }
}
