package uk.tomlowndes.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class AppEventsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val day = LocalDate.of(2026, 11, 20)

    @Test fun `a full event writes and reads back`() {
        val e = AppEvent(day, "Dentist check-up", LocalTime.of(14, 30), 60, "Headingley Dental", "Bring the form")
        val line = AppEvents.line(e)
        assertEquals("- ○ 2026-11-20 14:30 1h Dentist check-up 📍 Headingley Dental — Bring the form", line)
        assertEquals(e, AppEvents.parse(line))
    }

    @Test fun `an all-day event has no time or length`() {
        val e = AppEvent(day, "Mum's birthday")
        assertEquals("- ○ 2026-11-20 Mum's birthday", AppEvents.line(e))
        assertEquals(e, AppEvents.parse(AppEvents.line(e)))
        val c = e.toCal()
        assertTrue(c.allDay)
        assertEquals(day.plusDays(1).atStartOfDay(), c.end)
    }

    @Test fun `length and note without a place`() {
        val e = AppEvent(day, "Call", LocalTime.of(9, 0), 90, null, "about the job")
        assertEquals(e, AppEvents.parse(AppEvents.line(e)))
        assertEquals(LocalDateTime.of(2026, 11, 20, 10, 30), e.toCal().end)
    }

    @Test fun `events are added to the meta note and listed in order`() {
        val v = FileVault(tmp.root)
        AppEvents.add(v, AppEvent(day, "Later", LocalTime.of(18, 0)))
        AppEvents.add(v, AppEvent(day, "Earlier", LocalTime.of(9, 0), 30, "Office"))
        assertEquals(listOf("Earlier", "Later"), AppEvents.load(v).map { it.title })
        assertEquals(listOf("Earlier", "Later"), AppEvents.calendar(v, day).map { it.title })
        assertEquals(0, AppEvents.calendar(v, day.plusDays(1)).size)
        AppEvents.remove(v, AppEvents.load(v).first())
        assertEquals(listOf("Later"), AppEvents.load(v).map { it.title })
    }
}

class AppEventsProtonTest {
    private val day = LocalDate.of(2026, 11, 24)

    @Test fun `an event becomes an invite with where and notes`() {
        val e = AppEvent(day, "Haircut", LocalTime.of(10, 0), 60, "Barbers", "Bring card")
        val a = AppEvents.ask(e)
        assertEquals("10:00", a.start); assertEquals("11:00", a.end)
        assertEquals("Barbers", a.location); assertEquals("Bring card", a.note)
        assertEquals(AppEvents.askId(e.toCal()), a.id)
        val back = InboxSort.parseCalendar(InboxSort.calendarLine(a)).single()
        assertEquals(a, back)
        assertEquals(null, AppEvents.ask(AppEvent(day, "Birthday")).start)
    }

    @Test fun `the app copy steps aside once Proton has it`() {
        val mine = AppEvent(day, "Haircut", LocalTime.of(10, 0), 60).toCal()
        val other = AppEvent(day, "Dentist", LocalTime.of(14, 0)).toCal()
        val proton = CalEvent("p", "haircut", mine.start, mine.end, false)
        assertEquals(listOf(other), AppEvents.withoutCopies(listOf(mine, other), listOf(proton)))
    }
}
