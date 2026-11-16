package uk.tomlowndes.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

/** Stage 3: sleep and steps, what helps you, flashcards. */
class StageThreeTest {
    @get:Rule val tmp = TemporaryFolder()
    private val today = LocalDate.of(2026, 11, 16)

    @Test fun `sleep and steps live in the habits row`() {
        val v = FileVault(tmp.root)
        (1..5L).forEach { n -> Habits.update(v, today.minusDays(n)) { Body.with(it, 430, 6000, null) } }
        Habits.update(v, today) { Body.with(it, 400, null, LocalTime.of(23, 50)) }
        val note = Habits.load(v)
        assertEquals(400, Body.sleep(note.day(today)))
        assertEquals(LocalTime.of(23, 50), Body.bed(note.day(today)))
        assertEquals("😴 6h 40m · bed 23:50 · 30m under your usual", Body.sleepLine(note, today))
        assertEquals("👟 6,000 yesterday · goal 7k", Body.stepsLine(note, today, 7000))
        assertTrue(!Body.shortNight(note, today) && Body.shortNight(note, today, under = 420))
    }

    @Test fun `patterns round trip through the month note`() {
        val m = YearMonth.of(2026, 11)
        val p = Patterns.parse("""{"summary":"28 mood logs","patterns":[{"emoji":"🚶","title":"Walks before 10","detail":"mood +1.4 · 9 of 12 days","helps":true,"action":"habit"},{"emoji":"☕","title":"Coffee after 2pm","detail":"energy −1.1","helps":false,"action":null}]}""", m)!!
        val v = FileVault(tmp.root)
        Patterns.save(v, p, today)
        val back = Patterns.load(v, m)!!
        assertEquals("28 mood logs", back.summary)
        assertEquals(listOf("Walks before 10"), back.helps.map { it.title })
        assertEquals("habit", back.helps.first().action)
        assertEquals(listOf("Coffee after 2pm"), back.drags.map { it.title })
        assertTrue(Patterns.facts(m, null, emptyList(), emptyList()).contains("2026-11-01 SUN"))
    }

    @Test fun `cards parse, review and save`() {
        val v = FileVault(tmp.root)
        File(tmp.root, "Claude API.md").writeText("# Claude API #learn\n\nNotes.\n")
        val cards = Flashcards.cardsFrom("What does caching save? :: Cost and time, for 5 minutes\n**Batch?** :: Half price", "Claude API.md")
        assertEquals(2, cards.size)
        assertEquals("Batch?", cards[1].q)
        Flashcards.addCards(v, "Claude API.md", cards)
        val deck = Flashcards.parse("Claude API.md", File(tmp.root, "Claude API.md").readText())
        assertEquals(2, deck.due(today))
        val r = Flashcards.review(deck.cards[0], Grade.GOT_IT, today)
        assertEquals(today.plusDays(3), r.due)
        Flashcards.save(v, r)
        val again = Flashcards.parse("Claude API.md", File(tmp.root, "Claude API.md").readText())
        assertEquals(1, again.due(today))
        assertEquals(today.plusDays(1), Flashcards.review(r, Grade.AGAIN, today).due)
        assertEquals(1, Flashcards.dueToday(listOf(again), today).size)
        assertNotNull(Flashcards.decks(v, null).firstOrNull())
        assertNull(Flashcards.parse("x", "no cards").cards.firstOrNull())
    }
}
