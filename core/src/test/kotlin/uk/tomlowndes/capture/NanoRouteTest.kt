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
        assertNull(NanoRoute.needsBox("What's the next step on the portfolio project?"))
        assertNull(NanoRoute.needsBox("What's happening tomorrow?"))
        assertEquals("Vault edit", NanoRoute.needsBox("Add this to my portfolio note"))
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
        assertTrue(p.contains("Tom: hi\nTower: Hello Tom\n"))
        assertTrue(p.endsWith("Tom: and you?\nTower:"))
        val long = NanoRoute.prompt((1..8).map { PhoneBrain.Turn(it % 2 == 1, "x".repeat(1500)) }, "q", LocalDate.of(2026, 11, 11), emptyList())
        assertTrue(long.length < 10_500)
    }

    @Test fun contextHasTomorrowTasksAndWeather() {
        val d = LocalDate.of(2026, 11, 11)
        val ev = listOf(
            CalEvent("c", "Dentist", d.plusDays(1).atTime(9, 0), d.plusDays(1).atTime(9, 30), false, "High St"),
            CalEvent("c", "Bin day", d.atStartOfDay(), d.plusDays(1).atStartOfDay(), true),
        )
        val tasks = listOf(TaskItem("", "Order stickers", d.plusDays(1)), TaskItem("", "Ring Mum", d.minusDays(2)), TaskItem("", "Read book", null))
        val c = NanoRoute.context(d, ev, tasks, WeatherSummary(12, 5, "Dry and bright", "Sunny spells.", tip = "Washing out."))
        assertTrue(c, c.contains("Today (Wed 11 Nov): all day Bin day"))
        assertTrue(c, c.contains("Tomorrow (Thu 12 Nov): 09:00–09:30 Dentist at High St"))
        assertTrue(c, c.contains("Tasks due tomorrow: Order stickers"))
        assertTrue(c, c.contains("Overdue tasks: Ring Mum"))
        assertTrue(c, c.contains("Weather today: 12° / 5°, Dry and bright. Sunny spells. Washing out."))
    }

    @Test fun vaultContextPicksMatchingNotes() {
        val notes = listOf(
            NoteInfo("03 - Projects/Portfolio/Portfolio.md", 2, text = "---\ntype: project\n---\n# Portfolio\n## Next step\nWrite the case study intro."),
            NoteInfo("01 - Inbox/Shopping.md", 3, text = "Milk, eggs"),
            NoteInfo("99 - Meta/Templates/Portfolio.md", 4, text = "template portfolio"),
        )
        val c = NanoRoute.vaultContext(notes, "What's the next step on my portfolio?")
        assertTrue(c, c.startsWith("## Portfolio (03 - Projects/Portfolio/Portfolio.md)"))
        assertTrue(c, c.contains("Write the case study intro."))
        assertTrue(!c.contains("type: project"))
        assertTrue(!c.contains("Templates"))
        assertEquals("", NanoRoute.vaultContext(notes, "what's on tomorrow"))
        val p = NanoRoute.prompt(emptyList(), "q", LocalDate.of(2026, 11, 11), emptyList(), context = "Today: x", notes = c)
        assertTrue(p.contains("What Tom has on:\nToday: x") && p.contains("Matching notes from his vault:\n## Portfolio"))
    }
}
