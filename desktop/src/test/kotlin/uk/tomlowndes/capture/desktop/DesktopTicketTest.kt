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

    @Test fun `a short ticket is the minimum length, with ink on it`() {
        val t = Ticket("mai004", "MAI 004", "TODAY", "SAT 21 NOV", "MAIL", "Reply yes or no to Sam", "Inbox / Next steps", LocalTime.of(9, 41), 0)
        val img = DesktopTicket.draw(t)
        assertEquals(DesktopSlip.WIDTH, img.width)
        assertEquals(DesktopTicket.heightOf(t), img.height)
        assertTrue("a short job should be well under the old 110 mm: ${img.height}", img.height in DesktopTicket.MIN_HEIGHT until 1000)
        var dark = 0
        for (y in 0 until img.height step 4) for (x in 0 until img.width step 4) if (img.getRGB(x, y) and 0xFF < 128) dark++
        assertTrue("expected ink on the ticket, got $dark", dark > 500)
        // The rail grips the top: nothing printed there.
        for (y in 0 until 80) for (x in 0 until img.width) assertEquals(0xFFFFFF, img.getRGB(x, y) and 0xFFFFFF)
    }

    @Test fun `a long job makes a longer ticket, and every step counts`() {
        val short = Ticket("hom001", "HOM 001", "TODAY", "", "HOME", "Bins out", "Inbox / Quick Task", LocalTime.of(7, 2), 0)
        val long = short.copy(text = "Rewrite the portfolio case study for the banking app redesign, add the before and after screens, tighten the intro and send it to the recruiter before Friday")
        val steps = long.copy(steps = (1..12).map { "Step number $it" })
        val a = DesktopTicket.heightOf(short)
        val b = DesktopTicket.heightOf(long)
        val c = DesktopTicket.heightOf(steps)
        assertTrue("short job near the minimum: $a", a in DesktopTicket.MIN_HEIGHT until 900)
        assertTrue("long title should be taller: $b", b > a)
        assertTrue("12 steps should be taller still: $c", c > b + 12 * 40)
        assertEquals(c, DesktopTicket.draw(steps).height)
    }
}
