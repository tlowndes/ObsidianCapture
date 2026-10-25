package uk.tomlowndes.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class TravelTest {
    private val now = LocalDateTime.of(2026, 10, 25, 8, 12)
    private fun ev(title: String, h: Int, m: Int, place: String?, allDay: Boolean = false) =
        CalEvent("p", title, now.withHour(h).withMinute(m), now.withHour(h + 1).withMinute(m), allDay, place)

    @Test fun `the next event today with a place is picked`() {
        val events = listOf(
            ev("Call with Sam", 9, 0, null),
            ev("Rhea @ Bearhugs", 9, 45, "Bearhugs, Gloucester Rd"),
            ev("Dentist", 14, 0, "High St"),
            ev("Earlier", 7, 0, "Gym"),
            ev("Holiday", 0, 0, "Spain", allDay = true)
        )
        assertEquals("Rhea @ Bearhugs", Travel.nextTrip(events, now)?.title)
        assertNull(Travel.nextTrip(events.take(1), now))
    }

    @Test fun `leave by allows the drive plus five minutes`() {
        val l = Travel.line(ev("Rhea", 9, 45, "x"), 18)
        assertEquals(LocalDateTime.of(2026, 10, 25, 9, 22), l.leaveBy)
    }

    @Test fun `geocode and route answers are read`() {
        assertEquals(51.47 to -2.59, Travel.parseGeocode("""[{"lat":"51.47","lon":"-2.59","display_name":"x"}]"""))
        assertNull(Travel.parseGeocode("[]"))
        assertEquals(18, Travel.parseRoute("""{"code":"Ok","routes":[{"duration":1021.4,"distance":5000}]}"""))
        assertNull(Travel.parseRoute("""{"code":"NoRoute","routes":[]}"""))
    }

    @Test fun `urls put longitude first for OSRM`() {
        val u = Travel.routeUrl(51.4546 to -2.5879, 51.47 to -2.59)
        assertTrue(u, u.contains("/driving/-2.58790,51.45460;-2.59000,51.47000"))
        assertTrue(Travel.geocodeUrl("Bearhugs, Gloucester Rd").endsWith("q=Bearhugs%2C+Gloucester+Rd"))
    }

    @Test fun `ics locations are read`() {
        val ics = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nDTSTART:20261025T094500Z\r\nDTEND:20261025T104500Z\r\nSUMMARY:Rhea\r\nLOCATION:Bearhugs\\, Gloucester Rd\r\nEND:VEVENT\r\nEND:VCALENDAR"
        assertEquals("Bearhugs, Gloucester Rd", Ics.events(ics, "p", now.toLocalDate(), now.toLocalDate()).single().location)
    }
}
