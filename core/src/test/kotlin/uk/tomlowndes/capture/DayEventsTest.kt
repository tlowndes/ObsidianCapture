package uk.tomlowndes.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class DayEventsTest {
    private val day = LocalDate.of(2026, 11, 12)
    private fun ev(title: String, from: String, to: String) =
        CalEvent("c", title, day.atTime(LocalTime.parse(from)), day.atTime(LocalTime.parse(to)), false)
    private val bins = CalEvent("c", "Bin day", day.atStartOfDay(), day.plusDays(1).atStartOfDay(), true)
    private val events = listOf(ev("Pilates", "19:00", "20:00"), ev("Gym", "08:30", "09:30"), bins,
        ev("Lunch", "12:30", "13:30"), ev("Standup", "10:00", "10:15"), ev("Dentist", "15:00", "15:45"))

    @Test fun foldsFinishedEvents() {
        val d = DayEvents.of(events, day.atTime(13, 5))
        assertEquals(listOf("Bin day"), d.allDay.map { it.title })
        assertEquals(listOf("Gym", "Standup"), d.earlier.map { it.title })
        assertEquals(listOf("Lunch", "Dentist", "Pilates"), d.rest.map { it.title })
        assertEquals("2 earlier · Gym, Standup", d.foldLabel)
        assertEquals(4, d.left)
    }

    @Test fun eventEndingNowCountsAsPast() {
        val d = DayEvents.of(events, day.atTime(13, 30))
        assertEquals(listOf("Gym", "Standup", "Lunch"), d.earlier.map { it.title })
    }

    @Test fun morningHasNoFold() {
        val d = DayEvents.of(events, day.atTime(7, 0))
        assertNull(d.foldLabel)
        assertEquals(6, d.left)
    }

    @Test fun otherDaysLeftOut() {
        val tomorrow = CalEvent("c", "Tomorrow", day.plusDays(1).atTime(9, 0), day.plusDays(1).atTime(10, 0), false)
        assertEquals(0, DayEvents.of(listOf(tomorrow), LocalDateTime.of(day, LocalTime.NOON)).left)
    }
}
