package uk.tomlowndes.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class PiDeckTest {
    private val today = LocalDate.of(2026, 11, 11)
    private fun task(line: String) = TaskNote.parse(line)!!

    @Test fun `deck summary has the next step, due count and the next follow-up`() {
        val tasks = listOf(
            task("- [ ] #task Tailor CV #jobs ⏱ 25m 📅 2026-11-11"),
            task("- [ ] Rent £900 #bill #task 📅 2026-11-01"),
            task("- [ ] #task Someday"),
        )
        val jobs = listOf(
            Job("a.md", "Monzo", "Product designer", "Applied", LocalDate.of(2026, 11, 14), null),
            Job("b.md", "Gone", "Designer", "Rejected", LocalDate.of(2026, 11, 12), null),
        )
        val out = PiDeck.deck(tasks, emptySet(), null, today, 50, null, jobs, "MED", LocalDateTime.of(2026, 11, 11, 9, 30, 5, 123))
        assertEquals(
            """{"v":1,"sent":"2026-11-11T09:30:05","energy":"MED","next":{"title":"Tailor CV","due":"2026-11-11","mins":25},""" +
                """"due":2,"focus_min":50,"habits":{"done":0,"total":0},"water":null,""" +
                """"jobs":{"open":1,"follow_up":{"company":"Monzo","role":"Product designer","date":"2026-11-14"}}}""",
            out
        )
    }

    @Test fun `focus body, and an empty one when it ends`() {
        assertEquals("""{"focus":{"task":"Write \"case\" study","running":true,"ends":1700000100,"remaining_s":100,"total_s":1500}}""",
            PiDeck.focus("Write \"case\" study", true, 1_700_000_100_000, 100_000, 1_500_000))
        assertEquals("""{"focus":null}""", PiDeck.focus(null, false, null, 0, 0))
    }

    @Test fun `addresses with or without a scheme or port`() {
        assertEquals("piholegotchi.local" to 80, PiDeck.hostPort("piholegotchi.local"))
        assertEquals("192.168.1.42" to 8080, PiDeck.hostPort("http://192.168.1.42:8080/"))
        assertEquals(null, PiDeck.hostPort("  "))
        assertTrue(PiDeck.str("a\nb").contains("\\n"))
    }
}
