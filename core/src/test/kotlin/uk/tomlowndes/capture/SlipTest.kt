package uk.tomlowndes.capture

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SlipTest {
    private val day = LocalDate.of(2026, 11, 10)
    private fun task(text: String, due: LocalDate?) = TaskItem("- [ ] #task $text", text, due)

    @Test fun sortsTasksIntoOverdueTodayAndTomorrow() {
        val tasks = listOf(
            task("Book dentist #admin", day.minusDays(2)),
            task("Send portfolio link #jobs", day),
            task("Order paper rolls", day.plusDays(1)),
            task("Someday thing", null),
            task("Next week", day.plusDays(7)),
        )
        val s = Slip.build(day, tasks, emptyList(), null, SlipOptions())
        assertEquals(listOf("Book dentist"), s.overdue.map { it.text })
        assertEquals(listOf("#admin"), s.overdue[0].tags)
        assertEquals(day.minusDays(2), s.overdue[0].late)
        assertEquals(listOf("Send portfolio link", "Someday thing"), s.today.map { it.text })
        assertEquals(listOf("Order paper rolls"), s.tomorrow.map { it.text })
        assertEquals(46, s.week)
        assertEquals(314, s.dayOfYear)
    }

    @Test fun calendarSplitsAllDayFromTimed() {
        val ev = listOf(
            CalEvent("p", "Lunch with Mum", day.atTime(12, 30), day.atTime(13, 30), false, "Dishoom"),
            CalEvent("p", "Bin day", day.atStartOfDay(), day.plusDays(1).atStartOfDay(), true),
            CalEvent("p", "Portfolio review", day.atTime(9, 30), day.atTime(10, 0), false),
            CalEvent("p", "Tomorrow's thing", day.plusDays(1).atTime(9, 0), day.plusDays(1).atTime(10, 0), false),
        )
        val s = Slip.build(day, emptyList(), ev, null, SlipOptions())
        assertEquals(listOf("Bin day"), s.allDay.map { it.title })
        assertEquals(listOf("09:30", "12:30"), s.events.map { it.start })
        assertEquals("Dishoom", s.events[1].place)
    }

    @Test fun switchedOffSectionsAreEmpty() {
        val s = Slip.build(day, listOf(task("A", day)), emptyList(), null, SlipOptions(todo = false, calendar = false, sudoku = false))
        assertTrue(s.today.isEmpty())
        assertNull(s.sudoku)
    }

    @Test fun everySudokuHasOneAnswer() {
        Sudoku.PUZZLES.forEach { p ->
            assertEquals(81, p.length)
            assertEquals(p, 1, solutions(p.map { it - '0' }.toIntArray()))
        }
    }

    @Test fun rasterJobWrapsRowsInBands() {
        val grey = IntArray(16 * 130) { if (it % 16 == 0) 0 else 255 }
        val bits = EscPos.bits(grey, 16, 130)
        assertEquals(2 * 130, bits.size)
        assertEquals(0x80.toByte(), bits[0])
        assertEquals(0.toByte(), bits[1])
        val job = EscPos.job(bits, 2, 130)
        assertArrayEquals(byteArrayOf(0x1b, 0x40, 0x1b, 0x61, 0x00, 0x1d, 0x76, 0x30, 0x00, 2, 0, -128, 0), job.copyOfRange(0, 13))
        // Second band: the 2 rows left over.
        val second = 13 + 128 * 2
        assertArrayEquals(byteArrayOf(0x1d, 0x76, 0x30, 0x00, 2, 0, 2, 0), job.copyOfRange(second, second + 8))
        assertEquals(second + 8 + 4 + 3, job.size)
    }

    private fun solutions(g: IntArray, limit: Int = 2): Int {
        val i = g.indexOf(0)
        if (i < 0) return 1
        val r = i / 9; val c = i % 9
        var n = 0
        for (v in 1..9) {
            val ok = (0 until 9).none { g[r * 9 + it] == v || g[it * 9 + c] == v } &&
                (0 until 9).none { g[(r / 3 * 3 + it / 3) * 9 + c / 3 * 3 + it % 3] == v }
            if (!ok) continue
            g[i] = v; n += solutions(g, limit); g[i] = 0
            if (n >= limit) return n
        }
        return n
    }
}
