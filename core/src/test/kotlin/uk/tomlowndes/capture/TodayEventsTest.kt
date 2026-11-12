package uk.tomlowndes.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class TodayEventsTest {
    private val d = LocalDate.of(2026, 11, 12)
    private fun ev(t: String, h: Int, m: Int, mins: Long = 60) =
        CalEvent("c", t, d.atTime(h, m), d.atTime(h, m).plusMinutes(mins), false)
    private val allDay = CalEvent("c", "Bins", d.atStartOfDay(), d.plusDays(1).atStartOfDay(), true)
    private val now = LocalDateTime.of(d, java.time.LocalTime.of(12, 42))
    private val events = listOf(ev("Lunch", 12, 30), ev("Gym", 7, 30), ev("Dentist", 9, 15), ev("Call", 11, 0), ev("Review", 15, 0))

    @Test fun oneFinishedStaysInPlaceFaded() {
        val rows = TodayEvents.rows(listOf(ev("Gym", 7, 30), ev("Lunch", 12, 30)), now, false)
        assertEquals(listOf(TodayRow.Event(ev("Gym", 7, 30), true), TodayRow.Event(ev("Lunch", 12, 30), false)), rows)
    }

    @Test fun twoOrMoreFoldClosed() {
        val rows = TodayEvents.rows(events + allDay, now, false)
        assertEquals(TodayRow.Event(allDay, false), rows[0])
        assertEquals(TodayRow.Earlier(3, false, false), rows[1])
        assertEquals(listOf("Lunch", "Review"), rows.drop(2).map { (it as TodayRow.Event).event.title })
        assertEquals("✓ 3 earlier", TodayEvents.earlierLabel(rows[1] as TodayRow.Earlier))
    }

    @Test fun openShowsFinishedThenHideRow() {
        val rows = TodayEvents.rows(events, now, true)
        assertEquals(listOf("Gym", "Dentist", "Call"), rows.take(3).map { (it as TodayRow.Event).also { e -> assertTrue(e.past) }.event.title })
        assertEquals("hide earlier", TodayEvents.earlierLabel(rows[3] as TodayRow.Earlier))
    }

    @Test fun allDoneLabel() {
        val late = now.withHour(20)
        val rows = TodayEvents.rows(events + allDay, late, false)
        assertEquals(TodayRow.Earlier(5, false, true), rows[1])
        assertEquals("✓ All done for today · 5 earlier", TodayEvents.earlierLabel(rows[1] as TodayRow.Earlier))
    }

    @Test fun endingExactlyNowIsPast() {
        assertTrue(ev("Call", 11, 42).isPast(now))
    }

    @Test fun seenListMergesAndKeepsOtherKeys() {
        val json = """{"cards":["TODAY"],"highlightsSeen":["aaaaaaaa"]}"""
        val out = HighlightsSeen.with(json, listOf("bbbbbbbb", "aaaaaaaa"))
        assertEquals(setOf("aaaaaaaa", "bbbbbbbb"), HighlightsSeen.read(out))
        assertTrue(out.contains("TODAY"))
    }

    @Test fun oldPrefsKeysTurnIntoShortIds() {
        val p = Passage("x".repeat(120), "T", ReadRef("a.md", false))
        assertEquals(Highlights.key(p), Highlights.keyOf(p.text.take(80)))
        assertEquals(Highlights.key(p), Highlights.keyOf(Highlights.key(p)))
    }
}
