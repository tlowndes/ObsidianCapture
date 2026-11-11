package uk.tomlowndes.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class NanoRouteTest {
    @Test fun quickAsksStayOnThePhone() {
        assertNull(NanoRoute.needsBox("Remind me to order printer paper Friday"))
        assertNull(NanoRoute.needsBox("What's a good name for a cat?"))
    }

    @Test fun vaultResearchCodeAndRamblesGoToTheBox() {
        assertEquals("Needs your vault", NanoRoute.needsBox("What's the next step on the portfolio project?"))
        assertEquals("Research job", NanoRoute.needsBox("Look up the best 53mm paper rolls"))
        assertEquals("Coding job", NanoRoute.needsBox("Fix the bug in the app"))
        assertEquals("Long ramble · sorting it", NanoRoute.needsBox((1..45).joinToString(" ") { "word" }))
    }

    @Test fun replyWithTasks() {
        val r = NanoRoute.parse("Added it for Friday.\nTASK: Order printer paper | 2026-11-14 | #personal\n")
        assertEquals("Added it for Friday.", r.text)
        assertEquals(listOf(SortedTask("Order printer paper", "#personal", LocalDate.of(2026, 11, 14))), r.tasks)
        assertTrue(!r.toBox)
        assertEquals(SortedTask("Ring Mum", null, null), NanoRoute.parse("TASK: Ring Mum |  | ").tasks.single())
        assertEquals("Added.", NanoRoute.parse("TASK: Ring Mum").text)
    }

    @Test fun boxAnswer() {
        assertTrue(NanoRoute.parse("BOX").toBox)
        assertTrue(NanoRoute.parse(" box. ").toBox)
        assertTrue(NanoRoute.parse("").toBox)
        assertTrue(!NanoRoute.parse("Boxing Day is the 26th.").toBox)
    }

    @Test fun visibleHidesBoxAndTasks() {
        assertEquals("", NanoRoute.visible("BO"))
        assertEquals("", NanoRoute.visible("BOX"))
        assertEquals("Added it.", NanoRoute.visible("Added it.\nTASK: Ring"))
        assertEquals("Boxing Day", NanoRoute.visible("Boxing Day"))
    }

    @Test fun promptHasDateHistoryAndTags() {
        val p = NanoRoute.prompt(listOf(PhoneBrain.Turn(true, "hi"), PhoneBrain.Turn(false, "Hello Tom")), "and you?", LocalDate.of(2026, 11, 11), listOf("#work", "#personal"))
        assertTrue(p.contains("Wednesday 11 November 2026"))
        assertTrue(p.contains("#work #personal"))
        assertTrue(p.contains("Tom: hi\nJarvis: Hello Tom\n"))
        assertTrue(p.endsWith("Tom: and you?\nJarvis:"))
        val long = NanoRoute.prompt((1..8).map { PhoneBrain.Turn(it % 2 == 1, "x".repeat(1500)) }, "q", LocalDate.of(2026, 11, 11), emptyList())
        assertTrue(long.length < 6500)
    }
}
