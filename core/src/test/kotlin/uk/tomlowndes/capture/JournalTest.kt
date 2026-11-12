package uk.tomlowndes.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDate

class JournalTest {
    @get:Rule val tmp = TemporaryFolder()
    private val prompts = Journal.prompts(Journal.PROMPTS_STARTER)
    private val thu = LocalDate.of(2026, 11, 12)
    private val entry = JournalEntry(
        mapOf("Slept" to "OK", "Energy" to "Low", "Mood" to "4", "Main thing" to "y", "Moved" to "y", "Win" to "Sent the case study draft"),
        "Long day."
    )

    @Test fun starterHasTwelveItems() {
        assertEquals(12, prompts.size)
        assertEquals(JournalPrompt(JournalKind.SCALE, "😴", "Slept", "Slept", listOf("Badly", "OK", "Well")), prompts[0])
        assertEquals(JournalKind.MOOD, prompts[2].kind)
        assertEquals("Main thing", prompts[3].short)
        assertEquals("Did the main thing", prompts[3].label)
        assertEquals(7, prompts.count { it.kind == JournalKind.TICK })
    }

    @Test fun rendersOneMetaLineTicksAndLines() {
        val out = Journal.render(prompts, entry, JournalFacts(tasks = 4, focusMin = 50, events = 3))
        assertEquals("😴 OK · ⚡ Low · 🙂 · ✓ 4 tasks · ⏱ 50 min · 📅 3", out[0])
        assertEquals("✅ Main thing · ⬜ Job hunt · ✅ Moved · ⬜ Ate · ⬜ Talked · ⬜ Screens · ⬜ Meds", out[1])
        assertEquals("**Win:** Sent the case study draft", out[2])
        assertEquals(listOf("", "Long day."), out.drop(3))
    }

    @Test fun parseRoundTrips() {
        val back = Journal.parse(prompts, Journal.render(prompts, entry, JournalFacts(4, 50, 3)))
        assertEquals(entry, back)
        assertEquals(6, back.answered(prompts))
    }

    @Test fun savesToMonthNoteInDateOrderAndReplacesSameDay() {
        val v = FileVault(tmp.root)
        Journal.save(v, thu, prompts, entry, JournalFacts())
        Journal.save(v, thu.minusDays(1), prompts, JournalEntry(mapOf("Slept" to "Well")), JournalFacts())
        Journal.save(v, thu, prompts, entry.copy(note = ""), JournalFacts(tasks = 2))
        val text = v.read("02 - Area/Journal/2026-11.md")!!
        assertTrue(text.startsWith("---\ntype: journal\nmonth: 2026-11\nAutoNoteMover: disable\n---\n# Journal · November 2026\n"))
        assertTrue(text.indexOf("## Wed 11 Nov") < text.indexOf("## Thu 12 Nov"))
        assertEquals(1, Regex("## Thu 12 Nov").findAll(text).count())
        assertFalse(text.contains("Long day."))
        assertTrue(text.contains("✓ 2 tasks"))
        assertTrue(Journal.written(v, thu))
        assertFalse(Journal.written(v, thu.plusDays(1)))
        assertEquals("y", Journal.load(v, thu, prompts)!!.answers["Main thing"])
        assertNull(Journal.load(v, thu.plusDays(1), prompts))
    }

    @Test fun customPromptsAndBadLinesSkipped() {
        val p = Journal.prompts("- tick 🧹 Tidied up\n- scale ⚡ Energy\n- line 🙏 Grateful for | Thanks\nnot a prompt")
        assertEquals(listOf("Tidied up", "Thanks"), p.map { it.short })
    }
}
