package uk.tomlowndes.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class TaskViewsTest {
    private val today = LocalDate.of(2026, 10, 25) // a Sunday
    private fun task(line: String) = TaskNote.parse(line)!!

    @Test fun `tags leave out #task and are lower case`() {
        assertEquals(listOf("#home", "#learning"), TaskViews.tags(task("- [ ] #task Fix shed #Home #learning")))
    }

    @Test fun `work tags put a task on the work side`() {
        assertTrue(TaskViews.isWork(task("- [ ] #task Tailor CV #jobs")))
        assertFalse(TaskViews.isWork(task("- [ ] #task Bins out #home")))
    }

    @Test fun `filters combine tag, side, length and search`() {
        val t = task("- [ ] #task Reply to Sam #home ⏱ 5m")
        assertTrue(TaskViews.matches(t, "#home", TaskFilter.SHORT, "sam"))
        assertFalse(TaskViews.matches(t, "#jobs", null))
        assertFalse(TaskViews.matches(t, null, TaskFilter.WORK))
        assertFalse(TaskViews.matches(task("- [ ] #task Long one ⏱ 40m"), null, TaskFilter.SHORT))
    }

    @Test fun `tomorrow goes in This week and undated in Later`() {
        assertEquals(TaskGroup.WEEK, TaskViews.groupOf(today.plusDays(1), today))
        assertEquals(TaskGroup.LATER, TaskViews.groupOf(null, today))
        assertEquals(TaskGroup.LATER, TaskViews.groupOf(today.plusDays(2), today)) // next week
        assertEquals(TaskGroup.OVERDUE, TaskViews.groupOf(today.minusDays(3), today))
    }

    @Test fun `top tags are the most used`() {
        val ts = listOf(task("- [ ] #task a #home"), task("- [ ] #task b #home #jobs"), task("- [ ] #task c #bill"))
        assertEquals(listOf("#home", "#bill"), TaskViews.topTags(ts, 2))
    }

    @Test fun `a bill reads its name and amount`() {
        val b = TaskViews.bill(task("- [ ] Council tax £128 #bill #task 📅 2026-10-26"))
        assertEquals("Council tax", b.name)
        assertEquals(BigDecimal("128"), b.amount)
        assertNull(TaskViews.bill(task("- [ ] Gift for Mum #bill #task")).amount)
    }

    @Test fun `bills due are the next 7 days, overdue first, with a total`() {
        val ts = listOf(
            task("- [ ] Phone £35 #bill #task 📅 2026-10-29"),
            task("- [ ] Council tax £128 #bill #task 📅 2026-10-23"),
            task("- [ ] Water £20.50 #bill #task 📅 2026-10-31"),
            task("- [ ] Car tax £190 #bill #task 📅 2026-11-02"),
            task("- [ ] Not a bill £5 #task 📅 2026-10-26")
        )
        val due = TaskViews.billsDue(ts, today)
        assertEquals(listOf("Council tax", "Phone", "Water"), due.map { it.name })
        assertEquals("£183.50", TaskViews.money(TaskViews.total(due)))
        assertEquals("£163", TaskViews.money(BigDecimal("163")))
    }
}
