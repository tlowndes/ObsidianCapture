package uk.tomlowndes.capture

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class DateWordsTest {
    private val wed = LocalDate.of(2026, 10, 15)
    private fun split(s: String) = DateWords.split(s, wed)

    @Test fun `weekday at the end is the next one, or today`() {
        assertEquals("Book MOT" to LocalDate.of(2026, 10, 17), split("Book MOT fri"))
        assertEquals("Call Sam" to wed, split("Call Sam wednesday"))
        assertEquals("Call Sam" to LocalDate.of(2026, 10, 22), split("Call Sam next wed"))
    }

    @Test fun `today, tomorrow and next week`() {
        assertEquals("Bins" to wed, split("Bins today"))
        assertEquals("Bins" to wed.plusDays(1), split("Bins tomorrow"))
        assertEquals("Bins" to wed.plusDays(7), split("Bins next week"))
    }

    @Test fun `day and month roll into next year once gone`() {
        assertEquals("Renew domain" to LocalDate.of(2026, 10, 25), split("Renew domain 25 oct"))
        assertEquals("Renew domain" to LocalDate.of(2027, 3, 2), split("Renew domain march 2"))
        assertEquals("Pay" to LocalDate.of(2027, 1, 5), split("Pay 5/1"))
        assertEquals("Pay" to LocalDate.of(2026, 12, 1), split("Pay 2026-12-01"))
    }

    @Test fun `words that only look like dates are kept`() {
        assertEquals("Watch the movie" to null, split("Watch the movie"))
        assertEquals("Buy 3 eggs" to null, split("Buy 3 eggs"))
        assertEquals("Read about may" to LocalDate.of(2027, 5, 1), DateWords.split("Read about may 1", wed))
        assertEquals("tomorrow" to null, split("tomorrow"))
        assertEquals("Fix the mon key" to null, split("Fix the mon key"))
    }
}
