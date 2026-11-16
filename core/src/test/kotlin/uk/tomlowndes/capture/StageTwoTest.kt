package uk.tomlowndes.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate

/** Stage 2: talk me through it, job co-pilot, case study and briefing. */
class StageTwoTest {
    @get:Rule val tmp = TemporaryFolder()
    private val today = LocalDate.of(2026, 11, 15)

    @Test fun `the longest waiting task is offered unless snoozed`() {
        val cv = TaskItem("- [ ] #task Update CV 📅 2026-11-09", "Update CV", LocalDate.of(2026, 11, 9))
        val new = TaskItem("- [ ] #task Bins 📅 2026-11-14", "Bins", LocalDate.of(2026, 11, 14))
        val made = TaskItem("- [ ] #task Paint ➕ 2026-11-01", "Paint", null)
        assertEquals(made, TalkThrough.stuck(listOf(cv, new, made), today, emptyMap()))
        val s = TalkThrough.snooze(null, made, today)
        assertEquals(cv, TalkThrough.stuck(listOf(cv, new, made), today, TalkThrough.readSnoozed(s)))
        assertEquals(made, TalkThrough.stuck(listOf(cv, new, made), today.plusDays(3), TalkThrough.readSnoozed(s)))
        assertNull(TalkThrough.stuck(listOf(new), today, emptyMap()))
    }

    @Test fun `steps come after the mark`() {
        val r = "Sound, let's do that.\nSTEPS:\n- Change the top line · 5m\n- Add Flight Deck · 20m"
        assertEquals("Sound, let's do that.", TalkThrough.visible(r))
        assertEquals(listOf(Step("Change the top line", 5), Step("Add Flight Deck", 20)), TalkThrough.steps(r))
        assertNull(TalkThrough.steps("What's hard about it?"))
    }

    @Test fun `job ad is read and saved with fit and ad`() {
        assertTrue(JobCopilot.looksLikeJob("https://www.linkedin.com/jobs/view/123"))
        assertFalse(JobCopilot.looksLikeJob("https://www.bbc.co.uk/news"))
        val ad = JobCopilot.parse("""{"role":"Product Designer, Mobile","company":"Monzo","where":"Remote (UK)","salary":"£55–65k","closes":"2026-11-27","fit":78,"good":["Mobile app design"],"gaps":["Fintech"]}""", "u")!!
        assertEquals(78, ad.fit)
        val v = FileVault(tmp.root)
        val path = JobCopilot.save(v, ad, "We're hiring.", today)
        val job = Jobs.list(v).single()
        assertEquals(78, job.fit)
        assertEquals(LocalDate.of(2026, 11, 25), job.next)
        val text = File(tmp.root, path).readText()
        assertTrue(text.contains("- △ Fintech") && text.contains("## Ad"))
        val lp = JobCopilot.saveLetter(v, path, "Hi Monzo team,\n\nThanks, Tom", 1, today)
        JobCopilot.saveLetter(v, path, "Hi again", 2, today)
        assertEquals("Hi again", JobCopilot.letter(v, path))
        assertEquals(2, JobCopilot.draftNumber(v, path))
        assertTrue(File(tmp.root, lp).exists())
        assertEquals(1, Regex("Cover letter]]").findAll(File(tmp.root, path).readText()).count())
    }

    @Test fun `case study saves beside the cv note`() {
        val v = FileVault(tmp.root)
        File(tmp.root, "02 - Area/Work").mkdirs()
        File(tmp.root, "02 - Area/Work/Portfolio & CV 2026.md").writeText("# CV")
        assertEquals("02 - Area/Work/Portfolio & CV 2026.md", JobCopilot.cvPath(v))
        val p = CaseStudy.save(v, "Flight Deck", "## The problem\nTuesdays.", today)
        assertEquals("02 - Area/Work/Flight Deck - Case study.md", p)
        CaseStudy.save(v, "Flight Deck", "## The problem\nShorter.", today)
        assertTrue(File(tmp.root, p).readText().contains("Shorter."))
    }

    @Test fun `briefing is cached for the day`() {
        val v = FileVault(tmp.root)
        assertNull(Briefing.cached(v, today))
        Briefing.save(v, today, listOf("Dry till three.", "Dentist at nine forty."))
        assertEquals(2, Briefing.cached(v, today)!!.size)
        assertNull(Briefing.cached(v, today.plusDays(1)))
        assertEquals(listOf("One", "Two"), Briefing.lines("- One\n\n• Two"))
        assertNotNull(Briefing.length(listOf("word ".repeat(300))))
    }
}
