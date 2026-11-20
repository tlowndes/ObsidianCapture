package uk.tomlowndes.capture

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.MonthDay
import java.time.YearMonth

class CalendarGridTest {
    private val tue = LocalDate.of(2026, 11, 17)
    private fun ev(title: String, d: LocalDate, h: Int?, mins: Long = 60) =
        if (h == null) CalEvent("p", title, d.atStartOfDay(), d.plusDays(1).atStartOfDay(), true)
        else CalEvent("p", title, d.atTime(h, 0), d.atTime(h, 0).plusMinutes(mins), false)
    private fun task(text: String, due: LocalDate?) = TaskItem("- [ ] #task $text", text, due)

    @Test fun `month grid runs monday to sunday`() {
        val days = CalendarGrid.monthDays(YearMonth.of(2026, 11))
        assertEquals(LocalDate.of(2026, 10, 26), days.first())
        assertEquals(LocalDate.of(2026, 12, 6), days.last())
        assertEquals(0, days.size % 7)
        assertEquals(LocalDate.of(2026, 11, 16), CalendarGrid.weekStart(tue))
    }

    @Test fun `dots and the day list`() {
        val d = tue.plusDays(7)
        val tasks = listOf(task("Send invoice", d), TaskItem("- [ ] #task Rent £500 #bill", "Rent £500 #bill", d))
        val events = listOf(ev("Haircut", d, 10))
        assertEquals(listOf(Dot.BIRTHDAY, Dot.EVENT, Dot.BILL), CalendarGrid.dots(d, tasks, events, mapOf(d to listOf("Rosie"))))
        val items = CalendarGrid.day(d, tasks, events)
        assertEquals(listOf("Haircut"), items.timed.map { it.title })
        assertEquals(3, items.size)
    }

    @Test fun `week blocks, all day and untimed counts`() {
        val mon = CalendarGrid.weekStart(tue)
        val events = listOf(ev("Lunch", tue.plusDays(1), 11), ev("Bin day", tue, null))
        val tasks = listOf(TaskItem("- [ ] 09:30 - 10:30 #task Job call", "09:30 - 10:30 Job call", tue), task("Email", tue))
        val blocks = CalendarGrid.weekBlocks(mon, tasks, events)
        assertEquals(listOf(1 to "Job call", 2 to "Lunch"), blocks.sortedBy { it.day }.map { it.day to it.title })
        assertEquals(LocalTime.of(9, 30), blocks.first { it.task != null }.start)
        assertEquals(listOf("Bin day"), CalendarGrid.weekAllDay(mon, events)[1].map { it.title })
        assertEquals(listOf(0, 1, 0, 0, 0, 0, 0), CalendarGrid.weekUntimed(mon, tasks))
    }

    @Test fun `birthdays in range`() {
        val p = Person("People/Rosie.md", "Rosie", MonthDay.of(11, 20))
        assertEquals(mapOf(LocalDate.of(2026, 11, 20) to listOf("Rosie")), CalendarGrid.birthdays(listOf(p), tue, tue.plusDays(30)))
    }
}
