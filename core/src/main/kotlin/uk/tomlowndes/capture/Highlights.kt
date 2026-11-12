package uk.tomlowndes.capture

import java.time.LocalDate

/** A passage from saved reading, with where it came from. */
data class Passage(val text: String, val title: String, val ref: ReadRef)

/**
 * Daily highlights (build-queue T4): three passages a day from Read later articles you've read. Your own highlights
 * come first; otherwise strong sentences from the article. ♡ Keep adds one to Highlights.md; Next skips it, and
 * skipped passages don't come back.
 */
object Highlights {
    const val PATH = "${FlightNotes.DIR}/Highlights.md"
    const val PER_DAY = 3
    private val MARK = Regex("==([^=]{20,400})==")

    /** Sentences worth showing from an article [body], most quotable first. */
    fun passages(doc: ArticleDoc, max: Int = 4): List<String> {
        val own = doc.highlights + doc.body.flatMap { l -> MARK.findAll(l).map { it.groupValues[1].trim() }.toList() }
        if (own.isNotEmpty()) return own.distinct().take(max)
        val text = doc.body.asSequence().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("!") && !it.startsWith("|") && !it.startsWith("```") && !it.startsWith(">") }
            .map { ReadNote.plainText(it) }.joinToString(" ")
        return Regex("(?<=[.!?])\\s+(?=[A-Z\"“])").split(text).map { it.trim() }
            .filter { it.length in 70..260 && !it.contains("http") && it.count { c -> c == ' ' } >= 10 }
            .sortedByDescending { score(it) }.take(max)
    }

    private fun score(s: String): Int {
        var n = 100 - kotlin.math.abs(s.length - 150) / 3
        if (Regex("\\b(is|are|means|always|never|best|most|less|more)\\b", RegexOption.IGNORE_CASE).containsMatchIn(s)) n += 20
        if (s.contains("I ") || s.contains("we ")) n -= 5
        if (s.any { it.isDigit() }) n -= 10
        return n
    }

    /** A short id for a passage, kept in capture-config.json so the phone and PC skip the same ones. */
    fun key(p: Passage) = keyOf(p.text.take(80))

    /** Older builds kept the first 80 characters in phone prefs; this turns those into the short id. */
    fun keyOf(start: String) = if (start.length == 8 && start.all { it in "0123456789abcdef" }) start else "%08x".format(start.hashCode())

    /** Today's [PER_DAY] passages: a different mix each day, never one that was skipped or kept. */
    fun pick(all: List<Passage>, today: LocalDate, seen: Set<String>): List<Passage> {
        val fresh = all.filter { key(it) !in seen }.distinctBy { key(it) }
        if (fresh.isEmpty()) return emptyList()
        val rnd = java.util.Random(today.toEpochDay())
        // One per article where possible.
        val byArticle = fresh.groupBy { it.ref }.values.shuffled(rnd)
        val out = byArticle.map { it.first() }.take(PER_DAY).toMutableList()
        if (out.size < PER_DAY) out += fresh.filter { it !in out }.shuffled(rnd).take(PER_DAY - out.size)
        return out
    }

    fun keepLines(p: Passage, today: LocalDate) = listOf("> ${p.text}", "— [[${p.ref.file.removeSuffix(".md")}|${p.title}]] · $today", "")

    fun keep(v: Vault, p: Passage, today: LocalDate) {
        if (v.entry(PATH) == null) {
            v.mkdirs(FlightNotes.DIR)
            VaultIo.write(v, v.create(FlightNotes.DIR, "Highlights", "md"), "# Highlights\n\n")
        }
        VaultIo.edit(v, PATH) { lines ->
            while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.lastIndex)
            lines.add("")
            lines.addAll(keepLines(p, today))
        }
    }
}
