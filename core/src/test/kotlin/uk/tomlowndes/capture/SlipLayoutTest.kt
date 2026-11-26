package uk.tomlowndes.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class SlipLayoutTest {
    /** Records what was drawn; every text line is 30 dots tall. */
    private class Fake : SlipCanvas {
        val text = mutableListOf<String>()
        var boxes = 0
        override fun block(s: String, f: SlipFont, x: Float, top: Float, w: Float, center: Boolean, spacing: Float): Float { text += s; return 30f }
        override fun say(s: String, f: SlipFont, x: Float, base: Float, align: SlipCanvas.Align, white: Boolean) { text += s }
        override fun width(s: String, f: SlipFont) = s.length * f.size * 0.6f
        override fun fill(x1: Float, y1: Float, x2: Float, y2: Float) {}
        override fun box(x1: Float, y1: Float, x2: Float, y2: Float, w: Float) { boxes++ }
        override fun rule(x1: Float, y1: Float, x2: Float, y2: Float, w: Float, dash: FloatArray?, cap: SlipCap) {}
        override fun dot(cx: Float, cy: Float, r: Float) {}
        override fun icon(name: String, x: Float, y: Float, s: Float) {}
    }

    private val now = LocalDateTime.of(2026, 11, 26, 9, 30)
    private val look = SlipLayout.Look(dayTitle = 104f)

    @Test fun `list slip draws its items with tick boxes and the printed line`() {
        val c = Fake()
        val doc = ListDoc("SHOPPING", "3 items", listOf(ListGroup("Fridge", listOf("Milk", "Eggs")), ListGroup(null, listOf("Bread"))))
        val h = SlipLayout.list(c, look, false, doc, now)
        assertTrue(c.text.containsAll(listOf("Milk", "Eggs", "Bread", "Fridge")))
        assertTrue(c.text.last().endsWith("· Flight Deck"))
        assertEquals(3, c.boxes)
        assertTrue(h in 200..SlipLayout.MAX_HEIGHT)
    }

    @Test fun `large text makes a taller slip`() {
        val doc = ListDoc("SHOPPING", "", listOf(ListGroup(null, List(10) { "Item $it" })))
        val small = SlipLayout.list(Fake(), look, false, doc, now)
        val big = SlipLayout.list(Fake(), look, true, doc, now)
        assertTrue(big > small)
    }

    @Test fun `height never passes the page`() {
        val doc = ListDoc("LONG", "", listOf(ListGroup(null, List(2000) { "Item $it" })))
        assertEquals(SlipLayout.MAX_HEIGHT, SlipLayout.list(Fake(), look, false, doc, now))
    }
}
