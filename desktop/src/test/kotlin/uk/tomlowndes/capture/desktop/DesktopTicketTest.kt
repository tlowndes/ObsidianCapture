package uk.tomlowndes.capture.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.tomlowndes.capture.Ticket
import java.time.LocalTime

/** Print now's ticket, drawn on the PC (next-step-print-v1). */
class DesktopTicketTest {
    @Test fun `long words and long tasks wrap inside the ticket`() {
        val width = { s: String -> s.length * 10f }
        assertEquals(listOf("Reply yes", "or no to", "Sam"), DesktopTicket.wrap("Reply yes or no to Sam", 90f, width))
        assertEquals(listOf("abcdefghi", "jk"), DesktopTicket.wrap("abcdefghijk", 90f, width))
        assertEquals(emptyList<String>(), DesktopTicket.wrap("  ", 90f, width))
    }

    @Test fun `a ticket is the phone's size, with ink on it`() {
        val t = Ticket("mai004", "MAI 004", "TODAY", "SAT 21 NOV", "MAIL", "Reply yes or no to Sam", "Inbox / Next steps", LocalTime.of(9, 41), 0)
        val img = DesktopTicket.draw(t)
        assertEquals(DesktopSlip.WIDTH, img.width)
        assertEquals(DesktopTicket.HEIGHT, img.height)
        var dark = 0
        for (y in 0 until img.height step 4) for (x in 0 until img.width step 4) if (img.getRGB(x, y) and 0xFF < 128) dark++
        assertTrue("expected ink on the ticket, got $dark", dark > 500)
        // The rail grips the top: nothing printed there.
        for (y in 0 until 80) for (x in 0 until img.width) assertEquals(0xFFFFFF, img.getRGB(x, y) and 0xFFFFFF)
    }
}
