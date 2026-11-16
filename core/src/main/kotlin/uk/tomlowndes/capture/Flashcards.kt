package uk.tomlowndes.capture

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** One card: [q] :: [a] in a note's `## Cards` section, with its review state in an Obsidian spaced-repetition comment. */
data class Card(val path: String, val q: String, val a: String, val due: LocalDate? = null, val interval: Int = 0, val ease: Int = 250) {
    fun isDue(today: LocalDate) = due == null || !due.isAfter(today)
}

data class CardDeck(val path: String, val title: String, val cards: List<Card>) {
    fun due(today: LocalDate) = cards.count { it.isDue(today) }
}

enum class Grade(val label: String) { AGAIN("Again"), HARD("Hard"), GOT_IT("Got it") }

/**
 * Flashcards from notes (growth-v1): tag a note #learn and Claude writes 5 question-and-answer cards into a `## Cards`
 * section (`Q :: A <!--SR:!due,interval,ease-->`, the Spaced Repetition plugin's style, so Tom can edit them).
 * Missed days don't pile up: at most [CAP] are due a day.
 */
object Flashcards {
    const val CAP = 10
    const val SECTION = "Cards"
    const val TAG = "#learn"
    private val LINE = Regex("^\\s*(?:[-*]\\s+)?(.+?)\\s*::\\s*(.+?)\\s*(?:<!--SR:!(\\d{4}-\\d{2}-\\d{2}),(\\d+),(\\d+)-->)?\\s*$")

    fun isLearn(text: String): Boolean = Regex("(^|\\s)#learn(\\s|$)").containsMatchIn(text) ||
        Md.fmGet(text.split("\n"), "tags")?.contains("learn") == true

    fun parse(path: String, text: String): CardDeck {
        val lines = text.split("\n")
        val cards = Md.sectionRange(lines, SECTION)?.let { r -> lines.subList(r.first, r.last + 1) }.orEmpty().mapNotNull { l ->
            LINE.matchEntire(l)?.let { g ->
                Card(path, g.groupValues[1], g.groupValues[2], g.groupValues[3].ifEmpty { null }?.let { LocalDate.parse(it) },
                    g.groupValues[4].toIntOrNull() ?: 0, g.groupValues[5].toIntOrNull() ?: 250)
            }
        }
        return CardDeck(path, VaultTasks.noteName(path), cards)
    }

    fun line(c: Card) = "- ${c.q} :: ${c.a}" + (c.due?.let { " <!--SR:!$it,${c.interval},${c.ease}-->" } ?: "")

    /** Today's cards across decks, most overdue first, capped. */
    fun dueToday(decks: List<CardDeck>, today: LocalDate): List<Card> =
        decks.flatMap { d -> d.cards.filter { it.isDue(today) } }.sortedBy { it.due ?: LocalDate.MIN }.take(CAP)

    /** A simple SM-2: Again comes back tomorrow, Hard grows a little, Got it grows by the ease. */
    fun review(c: Card, g: Grade, today: LocalDate): Card = when (g) {
        Grade.AGAIN -> c.copy(due = today.plusDays(1), interval = 1, ease = (c.ease - 20).coerceAtLeast(130))
        Grade.HARD -> (c.interval * 1.2).toInt().coerceAtLeast(1).let { c.copy(due = today.plusDays(it.toLong()), interval = it, ease = (c.ease - 15).coerceAtLeast(130)) }
        Grade.GOT_IT -> (if (c.interval == 0) 3 else c.interval * c.ease / 100).coerceAtLeast(c.interval + 1)
            .let { c.copy(due = today.plusDays(it.toLong()), interval = it) }
    }

    /** Writes [c]'s new state back over its line (found by the question). */
    fun save(v: Vault, c: Card) = VaultIo.edit(v, c.path) { lines ->
        val at = lines.indexOfFirst { l -> LINE.matchEntire(l)?.groupValues?.get(1) == c.q }
        if (at >= 0) lines[at] = line(c)
    }

    const val SYSTEM = """You make flashcards from Tom's notes for quick review. Write exactly 5 cards that test the most useful ideas.
One per line as "Question :: Answer". Short questions, answers under 20 words, UK English, no numbering or intro."""

    fun cardsFrom(reply: String, path: String): List<Card> = reply.replace("\r", "").split("\n")
        .mapNotNull { LINE.matchEntire(it.trim())?.let { g -> Card(path, g.groupValues[1].trim('*', ' '), g.groupValues[2].trim()) } }.take(5)

    /** Adds the cards in a `## Cards` section at the end of the note. */
    fun addCards(v: Vault, path: String, cards: List<Card>) = VaultIo.edit(v, path) { lines ->
        while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.lastIndex)
        if (Md.headingIndex(lines, SECTION) < 0) lines.addAll(listOf("", "## $SECTION", ""))
        lines.addAll(cards.map(::line)); lines.add("")
    }

    /** Every #learn note, with its cards (none yet for a new one). Blocking. */
    fun decks(v: Vault, cacheFile: java.io.File?): List<CardDeck> = NoteIndex.build(v, cacheFile)
        .filter { n -> n.tags.any { it.removePrefix("#").equals("learn", true) } }
        .mapNotNull { n -> v.read(n.path)?.let { parse(n.path, it) } }

    fun minutes(n: Int) = ((n * 12 + 59) / 60).coerceAtLeast(1)
    fun overdueDays(c: Card, today: LocalDate) = c.due?.let { ChronoUnit.DAYS.between(it, today) } ?: 0
}
