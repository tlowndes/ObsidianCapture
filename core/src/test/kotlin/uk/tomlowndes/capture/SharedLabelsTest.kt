package uk.tomlowndes.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class SharedLabelsTest {
    private val thu = LocalDate.of(2026, 11, 26)

    @Test fun `due words`() {
        assertEquals("today", dueLabel(thu, thu))
        assertEquals("tomorrow", dueLabel(thu.plusDays(1), thu))
        assertEquals("5 Dec", dueLabel(LocalDate.of(2026, 12, 5), thu))
        assertEquals("Today", dayChip(thu, thu))
        assertEquals("Sat 5 Dec", dayChip(LocalDate.of(2026, 12, 5), thu))
        assertEquals("5 Jan 2027", dayChip(LocalDate.of(2027, 1, 5), thu))
        assertEquals("overdue", duePhrase(thu.minusDays(1), thu))
        assertEquals("due tomorrow", duePhrase(thu.plusDays(1), thu))
        assertEquals("due Sat 5", duePhrase(LocalDate.of(2026, 12, 5), thu))
        assertEquals("yesterday", relativeDay(thu.minusDays(1), thu))
        assertEquals("5 Dec", relativeDay(LocalDate.of(2026, 12, 5), thu))
    }

    @Test fun `greeting and clock`() {
        assertEquals("good morning", greeting(LocalTime.of(9, 0)))
        assertEquals("good afternoon", greeting(LocalTime.of(12, 0)))
        assertEquals("good evening", greeting(LocalTime.of(18, 0)))
        assertEquals("1:05", mmss(65))
        assertEquals("0:00", mmss(0))
    }

    @Test fun tones() {
        assertEquals(ColourRole.GREEN, Tones.chore(0.1))
        assertEquals(ColourRole.PRIMARY, Tones.chore(0.5))
        assertEquals(ColourRole.AMBER, Tones.chore(0.8))
        assertEquals(ColourRole.WARN, Tones.chore(Chores.OVERDUE))
        assertEquals(ColourRole.AMBER, Tones.tag("#work"))
        assertEquals(ColourRole.GREEN, Tones.tag("#personal"))
        assertEquals(ColourRole.AMBER, Tones.tag("#bill"))
        assertEquals(ColourRole.PURPLE, Tones.tag("#garden"))
        assertNull(Tones.priority(Priority.NONE))
        assertEquals(0xFFFF6B6B, Tones.priority(Priority.HIGHEST)!!.first)
    }

    @Test fun `inline markdown runs`() {
        val s = InlineMd.spans("Hi **bold [both](https://b.uk)** *it* and `code` [site](https://x.uk) [[Note|alias]] ![i](a.png)")
        assertEquals("Hi bold both it and code site alias ", s.joinToString("") { it.text })
        assertEquals(true, s.first { it.text == "bold " }.bold)
        val both = s.first { it.text == "both" }
        assertEquals(true, both.bold && both.url == "https://b.uk")
        assertEquals(true, s.first { it.text == "it" }.italic)
        assertEquals(true, s.first { it.text == "code" }.code)
        assertEquals("https://x.uk", s.first { it.text == "site" }.url)
        assertEquals("Note", s.first { it.text == "alias" }.wiki)
    }

    @Test fun `tags only when asked`() {
        assertEquals(1, InlineMd.spans("Buy #garden seeds").size)
        val s = InlineMd.spans("Buy #garden seeds #task and **#work**", tags = true)
        assertEquals("#garden", s.first { it.text == "#garden" }.tag)
        assertNull(s.first { it.text == "#task" }.tag)
        val w = s.first { it.text == "#work" }
        assertEquals("#work", w.tag); assertEquals(true, w.bold)
    }
}
