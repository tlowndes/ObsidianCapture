package uk.tomlowndes.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime

class FlightNotesTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `the starter Stuck menu reads into sections`() {
        val s = FlightNotes.stuck(FlightNotes.STUCK_STARTER)
        assertEquals(StuckItem("☕", "Make a proper coffee", 5), s.getValue(StuckLength.QUICK).first())
        assertEquals(4, s.getValue(StuckLength.QUICK).size)
        assertEquals(1, s.getValue(StuckLength.MEDIUM).size)
        assertTrue(s.getValue(StuckLength.LONG).isEmpty())
    }

    @Test fun `items are added at the end of their section`() {
        val lines = FlightNotes.STUCK_STARTER.split("\n").toMutableList()
        FlightNotes.addStuck(lines, StuckLength.MEDIUM, StuckItem("🧺", "Fold the washing", 15))
        FlightNotes.addStuck(lines, StuckLength.LONG, StuckItem("🍳", "Cook something", 40))
        val s = FlightNotes.stuck(lines.joinToString("\n"))
        assertEquals("Fold the washing", s.getValue(StuckLength.MEDIUM).last().name)
        assertEquals(listOf(StuckItem("🍳", "Cook something", 40)), s.getValue(StuckLength.LONG))
    }

    @Test fun `focus lines round-trip and old date-only lines still read`() {
        val s = FocusSession(LocalDateTime.of(2026, 10, 25, 7, 40), "Bins out", 12)
        assertEquals("- 2026-10-25 07:40 · Bins out · 12 min", FlightNotes.focusLine(s))
        val read = FlightNotes.sessions("# Focus log\n\n${FlightNotes.focusLine(s)}\n- 2026-10-24 · Old one · 25")
        assertEquals(listOf(s, FocusSession(LocalDateTime.of(2026, 10, 24, 0, 0), "Old one", 25)), read)
        assertEquals(listOf(s), FlightNotes.today(read, LocalDate.of(2026, 10, 25)))
    }

    @Test fun `notes are made on first run and sessions are logged`() {
        val v = FileVault(tmp.root)
        FlightNotes.logFocus(v, FocusSession(LocalDateTime.of(2026, 10, 25, 8, 12), "Setup WOL", 25))
        assertTrue(File(tmp.root, FlightNotes.STUCK).isFile)
        assertEquals(25, FlightNotes.loadToday(v, LocalDate.of(2026, 10, 25)).sumOf { it.minutes })
        assertEquals(4, FlightNotes.loadStuck(v).getValue(StuckLength.QUICK).size)
    }
}
