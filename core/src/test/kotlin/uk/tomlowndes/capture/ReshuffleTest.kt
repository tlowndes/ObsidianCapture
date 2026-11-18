package uk.tomlowndes.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class ReshuffleTest {
    private val today = LocalDate.of(2026, 11, 18)
    private fun task(text: String, due: LocalDate?, extra: String = "", rec: String? = null) =
        TaskItem("- [ ] #task $text$extra${due?.let { " 📅 $it" } ?: ""}", text, due, rec)

    @Test fun `levels round halves down`() {
        assertEquals(0, Reshuffle.Level.REST.fits(9))
        assertEquals(3, Reshuffle.Level.LOW.fits(9))
        assertEquals(4, Reshuffle.Level.HALF.fits(9))
        assertEquals(9, Reshuffle.Level.FULL.fits(9))
    }

    @Test fun `fixed tasks stay and the rest move to the lightest days`() {
        val tasks = listOf(
            task("Tailor CV", today, " ⏫"),
            task("Pay council tax #bill", today),
            TaskItem("- [ ] 09:00 - 10:00 Gym 📅 $today", "Gym", today),
            task("Water plants", today, rec = "every day"),
            task("Reply to Sam", today, " ⏱ 5m"),
            task("Case study", today, " ⏱ 90m"),
            task("Dribbble shots", today),
            task("Old one", today.minusDays(3)),
            task("Thu thing", today.plusDays(1)),
            task("Thu thing 2", today.plusDays(1)),
            task("Fri thing", today.plusDays(2)),
        )
        val p = Reshuffle.plan(tasks, today, Reshuffle.Level.HALF, usual = 9)
        assertEquals(8, p.today)
        assertEquals(4, p.stay.size)
        assertTrue(p.keep.isEmpty())
        assertEquals(setOf("Reply to Sam", "Case study", "Dribbble shots", "Old one"), p.moves.map { it.task.text }.toSet())
        // Sat (+3) is empty, so it fills first; nothing lands today or past the week.
        assertTrue(p.moves.all { it.to.isAfter(today) && !it.to.isAfter(today.plusDays(6)) })
        assertEquals(today.plusDays(3), p.moves.first().to)
        assertEquals(4, p.load[today])
        assertEquals(p.before.values.sum(), p.load.values.sum())
    }

    @Test fun `short tasks are kept first`() {
        val tasks = listOf(task("Long", today, " ⏱ 90m"), task("Quick", today, " ⏱ 5m"), task("Plain", today))
        val p = Reshuffle.plan(tasks, today, Reshuffle.Level.LOW, usual = 3)
        assertEquals(listOf("Quick"), p.keep.map { it.text })
        assertEquals(2, p.moves.size)
    }

    @Test fun `pinned tasks stay and take up room`() {
        val tasks = listOf(task("A", today), task("B", today), task("C", today))
        val p = Reshuffle.plan(tasks, today, Reshuffle.Level.LOW, usual = 6, pinned = setOf(tasks[2].raw))
        assertEquals(listOf("C"), p.stay.map { it.text })
        assertEquals(1, p.keep.size)
        assertEquals(1, p.moves.size)
    }

    @Test fun `full days are skipped`() {
        val busy = (1..6).flatMap { d -> List(if (d == 5) 0 else 2) { task("b$d-$it", today.plusDays(d.toLong())) } }
        val p = Reshuffle.plan(busy + task("Go", today), today, Reshuffle.Level.REST, usual = 2)
        assertEquals(today.plusDays(5), p.moves.single().to)
    }

    @Test fun `later counts the second week`() {
        val now = LocalDateTime.of(2026, 11, 18, 9, 0)
        val ev = CalEvent("p", "Next Tue", now.plusDays(7), now.plusDays(7).plusHours(1), false)
        val soon = CalEvent("p", "Fri", now.plusDays(2), now.plusDays(2).plusHours(1), false)
        val (e, t) = Upcoming.later(listOf(task("a", today.plusDays(8)), task("b", today.plusDays(2))), listOf(ev, soon), today)
        assertEquals(1, e); assertEquals(1, t)
        assertEquals(7, Upcoming.days(emptyList(), emptyList(), now, Upcoming.LIST_DAYS).size)
    }
}
