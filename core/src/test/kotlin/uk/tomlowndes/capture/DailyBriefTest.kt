package uk.tomlowndes.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDate
import java.time.LocalDateTime

class DailyBriefTest {
    @get:Rule val tmp = TemporaryFolder()
    private val day = LocalDate.of(2026, 11, 25)
    private val now = day.atTime(6, 50)

    private val reply = """
        ## Opening
        One interview prep slot and a quiet afternoon.

        ## Three things
        - Job hunt | Medium | Send the B12 application | CV's done. First step: open the listing.
        - Portfolio | high | Case study problem paragraph | Carried over. First step: open the note.
        - Home | low | Bins out | Collection tomorrow.

        ## Hard stop
        16:00 | Dark by four and showers from three.

        ## Audio
        ### Weather & travel
        Nine degrees, dry till three.
        ### Opening & today
        Morning Tom.
        Interview prep at ten.
        ### Hard stop
        Stop at four.

        ## Short
        Dry till three.
        Prep at ten.
    """.trimIndent()

    private fun input(): DailyBrief.Input {
        val tasks = listOf(
            TaskItem("- [ ] Send B12 #job 📅 2026-11-24", "Send B12 #job", day.minusDays(1)),
            TaskItem("- [ ] Pay Octopus £84 #bill 📅 2026-11-27", "Pay Octopus £84 #bill", day.plusDays(2)),
            TaskItem("- [ ] Clean car 📅 2026-11-28", "Clean car", day.plusDays(3)),
        )
        val events = listOf(
            CalEvent("p", "Interview prep with Sam", day.atTime(10, 0), day.atTime(10, 30), false),
            CalEvent("p", "Inspection", day.plusDays(1).atTime(13, 0), day.plusDays(1).atTime(13, 30), false),
        )
        val jobs = listOf(
            Job("a.md", "B12", "Web Designer", "Shortlisted", null, null, 4),
            Job("b.md", "Sphere", "Product Designer", "Applied", day, day.minusDays(1)),
            Job("c.md", "Old", "Designer", "Rejected", null, null),
        )
        return DailyBrief.Input(now, tasks, events, WeatherSummary(9, 4, "Dry, then showers", "Showers from 15:00.", "Take a hood."), jobs)
    }

    @Test fun `base sections come from the vault`() {
        val b = DailyBrief.base(input())
        assertEquals(listOf("8h 30m", "1", "1", "1/3"), b.stats.map { it.text })
        assertEquals("10:00–10:30", b.today.single().label)
        assertEquals("9° / 4° · Dry, then showers", b.weather!!.label)
        assertEquals("Showers from 15:00. Take a hood.", b.weather!!.text)
        assertEquals("Thu 26", b.comingUp.first().label)
        assertTrue(b.comingUp.any { it.text.contains("Clean car") })
        assertFalse(b.comingUp.any { it.text.contains("Octopus") })
        assertEquals(2, b.roles.size)
        assertEquals("follow up", b.roles.first().tag)
        assertEquals("to apply", b.roles[1].tag)
        assertTrue(b.bills.single().startsWith("Pay Octopus"))
    }

    @Test fun `free day says so`() {
        val b = DailyBrief.base(input().copy(events = emptyList()))
        assertTrue(b.today.single().text.startsWith("Free all day"))
        assertEquals("9h", b.stats.first().text)
    }

    @Test fun `claude's reply merges and round-trips through the note`() {
        val d = DailyBrief.merge(DailyBrief.base(input()), reply)
        assertEquals("One interview prep slot and a quiet afternoon.", d.opening)
        assertEquals(3, d.things.size)
        assertEquals("medium", d.things.first().energy)
        assertEquals("16:00", d.hardStop!!.label)
        // Chapters come back in reading order whatever order Claude wrote them in.
        assertEquals(listOf("opening", "weather", "stop"), d.chapters.map { it.key })
        assertEquals(2, d.chapters.first().lines.size)
        assertEquals(listOf("Dry till three.", "Prep at ten."), d.shortLines)

        val back = DailyBrief.parse(DailyBrief.render(d))!!
        assertEquals(d.copy(audio = d.chapters), back)
    }

    @Test fun `box notes with news parse`() {
        val text = """
            ---
            date: 2026-11-25
            writer: box
            made: "06:15"
            ---
            ## Opening
            Quiet one.
            ## TL;DR
            - Design | Design system foundations | https://example.com/a | Start from what you use.
            - AI | Slides ships | https://example.com/b | Decks | exports.
            ## Audio
            ### The TL;DR
            Two bits of news.
        """.trimIndent()
        val d = DailyBrief.parse(text)!!
        assertEquals(DailyBrief.WRITER_BOX, d.writer)
        assertEquals(2, d.news.size)
        assertEquals("Decks | exports.", d.news[1].line)
        assertEquals("news", d.chapters.single().key)
        assertEquals(listOf("Two bits of news."), d.shortLines)
    }

    @Test fun `saved for the day, heard and opened once`() {
        val v = FileVault(tmp.root)
        assertNull(DailyBrief.load(v, day))
        DailyBrief.save(v, DailyBrief.merge(DailyBrief.base(input()), reply))
        assertNotNull(DailyBrief.load(v, day))
        assertNull(DailyBrief.load(v, day.plusDays(1)))
        assertFalse(DailyBrief.load(v, day)!!.heard)
        DailyBrief.markHeard(v, day)
        DailyBrief.markOpened(v, day)
        val d = DailyBrief.load(v, day)!!
        assertTrue(d.heard)
        assertTrue(d.opened)
        assertEquals(3, d.things.size)
    }

    @Test fun `app waits for the box till quarter to seven`() {
        assertFalse(DailyBrief.appMayWrite(day.atTime(6, 30)))
        assertTrue(DailyBrief.appMayWrite(day.atTime(6, 45)))
    }

    @Test fun `lengths and chapter starts`() {
        val c = listOf(BriefChapter("opening", "Opening", listOf("word ".repeat(150))), BriefChapter("weather", "Weather", listOf("word ".repeat(75))))
        assertEquals(listOf(0, 60), DailyBrief.starts(c))
        assertEquals("1:30", DailyBrief.length(c.flatMap { it.lines }))
        assertEquals("1:00", DailyBrief.length(c.flatMap { it.lines }, 1.5f))
        assertEquals("6h 30m", DailyBrief.hours(390))
        assertEquals("weather", DailyBrief.chapterKey("Weather & travel"))
        assertEquals("news", DailyBrief.chapterKey("The TL;DR"))
    }

    /** Exactly what box/brief/brief.py writes (a dry run on a small vault), so the two never drift apart. */
    @Test fun `the box script's note parses`() {
        val d = DailyBrief.parse(BOX_NOTE)!!
        assertEquals(DailyBrief.WRITER_BOX, d.writer)
        assertEquals("06:15", d.made)
        assertEquals(4, d.stats.size)
        assertEquals("Interview prep with Sam · Zoom", d.today.single().text)
        assertEquals("9° / 4° · Dry, then showers", d.weather!!.label)
        assertEquals(3, d.things.size)
        assertEquals("02 - Area/Work/Job Hunt/Applications/B12.md", d.roles.single().path)
        assertEquals("16:00", d.hardStop!!.label)
        assertEquals(listOf("opening", "news"), d.chapters.map { it.key })
        assertEquals(listOf("Dry till three."), d.shortLines)
    }

    private companion object {
        val BOX_NOTE = """
            ---
            date: 2026-11-25
            writer: box
            made: "06:15"
            news: false
            heard: false
            opened: false
            ---

            ## Opening
            Quiet one.

            ## Stats
            - free time | 8h 30m
            - roles to apply | 1
            - waiting on you | 1
            - apps this wk | 0/3

            ## Today
            - 10:00–10:30 | Interview prep with Sam · Zoom

            ## Weather
            9° / 4° · Dry, then showers | Showers from three.

            ## Three things
            - Job hunt | medium | Send the B12 application | First step: open it.
            - Home | low | Clean car | First step: bin bag.
            - Admin | low | Pay Octopus | First step: log in.

            ## Coming up
            - Sat 28 | Clean car

            ## Roles
            - to apply | Web Designer · B12 | Shortlisted · fit 4/5 | 02 - Area/Work/Job Hunt/Applications/B12.md

            ## Bills & inbox
            - Pay Octopus £84 · Fri 27

            ## Hard stop
            16:00 | Dark by four.

            ## Audio
            ### Opening & today
            Morning Tom.
            ### The TL;DR
            One bit of news.

            ## Short
            Dry till three.
        """.trimIndent()
    }
}
