package uk.tomlowndes.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class ReminderPlanTest {
    // Wednesday 12 November 2026.
    private val day = LocalDate.of(2026, 11, 12)
    private fun at(h: Int, m: Int) = day.atTime(h, m)
    private val s = ReminderSettings(dueOn = true, dueMinute = 9 * 60, morningOn = true, morningMinute = 8 * 60, eveningOn = true, eveningMinute = 18 * 60 + 30)

    @Test fun `nothing before the first time`() {
        assertNull(ReminderPlan.slotDue(s, at(7, 59), emptySet()))
    }

    @Test fun `shows a slot once its time passes, then not again`() {
        val (slot, keys) = ReminderPlan.slotDue(s, at(8, 0), emptySet())!!
        assertEquals(ReminderSlot.MORNING, slot)
        assertNull(ReminderPlan.slotDue(s, at(8, 30), keys.toSet()))
    }

    @Test fun `after the PC was off only the latest shows and the rest are settled`() {
        val (slot, keys) = ReminderPlan.slotDue(s, at(10, 0), emptySet())!!
        assertEquals(ReminderSlot.DUE, slot)
        assertEquals(2, keys.size)
        assertNull(ReminderPlan.slotDue(s, at(12, 0), keys.toSet()))
    }

    @Test fun `weekly only on Sunday and off slots never show`() {
        val w = ReminderSettings(beforeOn = false, dueOn = false, weeklyOn = true, weeklyMinute = 17 * 60)
        assertNull(ReminderPlan.slotDue(w, at(18, 0), emptySet()))
        assertEquals(ReminderSlot.WEEKLY, ReminderPlan.slotDue(w, LocalDate.of(2026, 11, 15).atTime(17, 5), emptySet())!!.first)
    }

    @Test fun `timed task heads-up from lead time until it starts`() {
        val t = TaskItem("- [ ] 14:00 - 15:00 Case study ⏳ 2026-11-12", "14:00 - 15:00 Case study", day)
        val other = TaskItem("- [ ] 14:00 Tomorrow thing", "14:00 Tomorrow thing", day.plusDays(1))
        val tasks = listOf(t, other)
        assertTrue(ReminderPlan.timedDue(tasks, s, at(13, 49), emptySet()).isEmpty())
        val due = ReminderPlan.timedDue(tasks, s, at(13, 50), emptySet())
        assertEquals(1, due.size)
        val key = ReminderPlan.taskKey(due[0], day)
        assertTrue(ReminderPlan.timedDue(tasks, s, at(13, 55), setOf(key)).isEmpty())
        assertTrue(ReminderPlan.timedDue(tasks, s, at(14, 0), emptySet()).isEmpty())
        assertTrue(ReminderPlan.timedDue(tasks, s.copy(beforeOn = false), at(13, 55), emptySet()).isEmpty())
    }

    @Test fun `prune keeps today's keys only`() {
        val keys = setOf("DUE:2026-11-11", "DUE:2026-11-12", "TASK:2026-11-12:14:00:1")
        assertEquals(setOf("DUE:2026-11-12", "TASK:2026-11-12:14:00:1"), ReminderPlan.prune(keys, day))
    }

    @Test fun `due today lists overdue first`() {
        val list = ReminderPlan.dueToday(listOf(
            TaskItem("- [ ] a", "a", day), TaskItem("- [ ] b", "b", day.minusDays(2)),
            TaskItem("- [ ] c", "c", day.plusDays(1)), TaskItem("- [ ] d", "d", null)
        ), day)
        assertEquals(listOf("b", "a"), list.map { it.text })
    }
}
