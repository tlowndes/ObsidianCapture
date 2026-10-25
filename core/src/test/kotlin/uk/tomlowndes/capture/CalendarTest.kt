package uk.tomlowndes.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class CalendarTest {
    private val london = ZoneId.of("Europe/London")
    private val day = LocalDate.of(2026, 10, 25)

    private val ics = """
        BEGIN:VCALENDAR
        BEGIN:VEVENT
        DTSTART;TZID=Europe/London:20261025T094500
        DTEND;TZID=Europe/London:20261025T104500
        SUMMARY:Rhea @ Bearhugs
        END:VEVENT
        BEGIN:VEVENT
        DTSTART:20261027T090000Z
        DTEND:20261027T100000Z
        SUMMARY:Nat meeting with Nigel\, catch-up
        END:VEVENT
        BEGIN:VEVENT
        DTSTART;VALUE=DATE:20261026
        DTEND;VALUE=DATE:20261027
        SUMMARY:Bank holiday
        END:VEVENT
        BEGIN:VEVENT
        DTSTART;TZID=Europe/London:20261005T183000
        DURATION:PT1H
        RRULE:FREQ=WEEKLY;BYDAY=MO,TH
        EXDATE;TZID=Europe/London:20261029T183000
        SUMMARY:Climbing
        END:VEVENT
        BEGIN:VEVENT
        DTSTART:20261025T120000Z
        DTEND:20261025T130000Z
        STATUS:CANCELLED
        SUMMARY:Cancelled lunch
        END:VEVENT
        BEGIN:VEVENT
        DTSTART:20261020T080000Z
        DTEND:20261020T083000Z
        RRULE:FREQ=DAILY;COUNT=7
        SUMMARY:Stand-up
        END:VEVENT
        END:VCALENDAR
    """.trimIndent().replace("\n", "\r\n")

    @Test fun `events in the next seven days, all-day first then by time`() {
        val ev = Ics.events(ics, "p", day, day.plusDays(6), london)
        val titles = ev.map { "${it.start.toLocalDate()} ${it.title}" }
        assertEquals(
            listOf(
                "2026-10-26 Bank holiday",
                "2026-10-25 Stand-up", "2026-10-25 Rhea @ Bearhugs", "2026-10-26 Stand-up",
                "2026-10-26 Climbing", "2026-10-27 Nat meeting with Nigel, catch-up"
            ),
            titles
        )
    }

    @Test fun `UTC times move to local time across the clock change`() {
        val ev = Ics.events(ics, "p", day.plusDays(2), day.plusDays(2), london).single()
        assertEquals(LocalDateTime.of(2026, 10, 27, 9, 0), ev.start) // GMT from 25 Oct
    }

    @Test fun `weekly rules skip excluded dates`() {
        val climbing = Ics.events(ics, "p", day, day.plusDays(13), london).filter { it.title == "Climbing" }.map { it.start.toLocalDate() }
        assertEquals(listOf(LocalDate.of(2026, 10, 26), LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 5)), climbing)
    }

    @Test fun `the current event is marked and day overlap works`() {
        val rhea = Ics.events(ics, "p", day, day, london).first { it.title.startsWith("Rhea") }
        assertTrue(rhea.isNow(LocalDateTime.of(2026, 10, 25, 10, 0)))
        assertTrue(rhea.on(day))
    }

    @Test fun `calendars round-trip in the config and webcal becomes https`() {
        val list = listOf(CalendarSource("a", "Personal", "webcal://calendar.proton.me/x.ics", "#7aa2ff"))
        val json = CalendarConfig.withSources("{\"cards\":[\"JOBS\"]}", list)
        assertEquals(list, CalendarConfig.sources(json))
        assertEquals(HomeCard.JOBS, DeckConfig.layout(json).order.first())
        assertEquals("https://calendar.proton.me/x.ics", CalendarConfig.fetchUrl(list[0].url))
    }
}
