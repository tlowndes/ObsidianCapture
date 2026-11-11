package uk.tomlowndes.capture

import java.time.LocalDate
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class SummaryTest {
    @Test
    fun bulletsBecomePoints() {
        val d = Summary.parse("Here you go:\n- **Three** pieces\n* Each: problem, process\n1. Next: write it", "Plan", "Summary", Summary.Length.BULLETS)
        assertEquals(listOf("Three pieces", "Each: problem, process", "Next: write it"), d.bullets)
        assertEquals("Plan", d.title)
    }

    @Test
    fun paragraphStaysText() {
        val d = Summary.parse("A short line.\nAnother one.", "Plan", "Summary", Summary.Length.SHORT)
        assertTrue(d.bullets.isEmpty())
        assertEquals("A short line. Another one.", d.text)
    }

    @Test
    fun chatTitleLineIsTaken() {
        val d = Summary.parse("Title: \"Garage plan\"\n- Shelves first\n- Bike rack", "Jarvis chat", "x", Summary.Length.BULLETS, ticks = true)
        assertEquals("Garage plan", d.title)
        assertEquals(2, d.bullets.size)
        assertTrue(d.ticks)
    }

    @Test
    fun editRoundTrips() {
        val d = Summary.Doc("T", "s", "", listOf("One", "Two"))
        assertEquals("- One\n- Two", d.editable())
        assertEquals(listOf("One", "Two", "Three"), d.edited("- One\n- Two\n- Three").bullets)
        assertEquals("Just text", d.edited("Just text").text)
    }

    @Test
    fun briefIsTrimmedToSentences() {
        assertNull(Summary.brief("  \n "))
        assertEquals("Busy morning. Start with the invoice.", Summary.brief("**Busy** morning.\nStart with the invoice."))
        val long = "One sentence here. ".repeat(40)
        val b = Summary.brief(long)!!
        assertTrue(b.length <= 320 && b.endsWith("."))
    }

    @Test
    fun dayPromptCarriesTheDay() {
        val s = SlipContent(
            LocalDate.of(2026, 11, 11), 46, 315, 365, null, emptyList(),
            listOf(SlipEvent("Dentist", "09:30", "10:00", null)),
            listOf(SlipTask("Send invoice", emptyList(), LocalDate.of(2026, 11, 9))), listOf(SlipTask("Book MOT", emptyList())),
            emptyList(), null,
        )
        val p = Summary.dayPrompt(s)
        assertTrue("09:30 Dentist" in p && "Overdue: Send invoice" in p && "Today: Book MOT" in p)
    }

    @Test
    fun promptsCapLongNotes() {
        val p = Summary.notePrompt("N", "x".repeat(50_000), Summary.Length.LONGER)
        assertTrue(p.length < Summary.MAX_SOURCE + 400)
    }
}
