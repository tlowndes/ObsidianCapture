package uk.tomlowndes.capture

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * A home job that "decays" (home-life-v1): no due date, just how often it wants doing. [done] is when it was last done
 * (null: never, so it shows as full).
 */
data class Chore(val name: String, val emoji: String = "", val every: Int = 7, val done: LocalDate? = null) {
    val label get() = if (emoji.isEmpty()) name else "$emoji $name"

    /** Days since it was last done (null when never). */
    fun age(today: LocalDate): Long? = done?.let { ChronoUnit.DAYS.between(it, today).coerceAtLeast(0) }

    /** How full its bar is: 0 just done, 1 due, more than 1 overdue. Never done counts as due. */
    fun fill(today: LocalDate): Double = age(today)?.let { it.toDouble() / every.coerceAtLeast(1) } ?: 1.0

    /** "12 days · every 7", "today · every 4", "not yet · every 7". */
    fun ageLabel(today: LocalDate): String {
        val a = age(today)
        val since = when (a) { null -> "not yet"; 0L -> "today"; 1L -> "1 day"; else -> "$a days" }
        return "$since · every $every"
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
        parts.drop(1).forEach { p ->
            EVERY.matchEntire(p)?.let { every = it.groupValues[1].toInt().coerceIn(1, 365) }
            DONE.matchEntire(p)?.let { done = runCatching { LocalDate.parse(it.groupValues[1]) }.getOrNull() }
        }
        val (emoji, name) = splitEmoji(parts[0])
        if (name.isBlank()) return null
        return Chore(name, emoji, every, done)
    }

    fun line(c: Chore): String = "- " + c.label + " · every ${c.every}" + (c.done?.let { " · done $it" } ?: "")

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
    fun markDone(v: Vault, c: Chore, day: LocalDate = LocalDate.now()): Chore { change(v, c.name, c.copy(done = day)); return c }

    fun put(v: Vault, old: String?, c: Chore) = change(v, old, c)
    fun remove(v: Vault, c: Chore) = change(v, c.name, null)
}
