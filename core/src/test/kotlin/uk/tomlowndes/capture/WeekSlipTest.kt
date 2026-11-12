package uk.tomlowndes.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class WeekSlipTest {
    private val thu = LocalDate.of(2026, 11, 12)
    private fun task(text: String, due: LocalDate?) = TaskItem("- [ ] #task $text", text, due)
    private fun bill(text: String, due: LocalDate) = TaskItem("- [ ] $text #bill 📅 $due", "$text #bill", due)
    private fun ev(title: String, start: String, end: String) =
        CalEvent("cal", title, java.time.LocalDateTime.parse(start), java.time.LocalDateTime.parse(end), false, null)

    @Test fun thisWeekSkipsPastDaysAndSplitsBills() {
        val tasks = listOf(task("Book dentist", thu), bill("Council tax £142", thu), task("Old", thu.minusDays(2)), task("MOT", thu.plusDays(1)))
        val w = WeekSlip.build(thu, false, tasks, listOf(ev("Dentist", "2026-11-13T10:00", "2026-11-13T10:30")), WeekOptions())
        assertEquals(listOf("THU", "FRI", "SAT", "SUN"), w.days.map { it.label })
        assertTrue(w.days[0].today)
        assertEquals(listOf("Book dentist"), w.days[0].tasks.map { it.text })
        assertEquals(listOf(SlipReminder("Council tax", "£142")), w.days[0].bills)
        assertEquals("10:00", w.days[1].events.single().start)
        assertTrue(w.days[3].empty)
        assertEquals("THIS WEEK", w.title)
        assertEquals("12 – 15 NOVEMBER 2026", w.range)
        assertEquals("Week 46 · 4 days left", w.sub)
    }

    @Test fun fullWeekAndNextWeek() {
        assertEquals(7, WeekSlip.build(thu, false, emptyList(), emptyList(), WeekOptions(skipPast = false)).days.size)
        val n = WeekSlip.build(thu, true, emptyList(), emptyList(), WeekOptions())
        assertEquals(LocalDate.of(2026, 11, 16), n.days.first().date)
        assertEquals(7, n.days.size)
        assertFalse(n.days.any { it.today })
        assertEquals("NEXT WEEK", n.title)
        assertEquals("Week 47", n.sub)
        assertEquals("16–22 Nov", WeekSlip.pickLabel(n.monday))
        assertEquals("30 Nov – 6 Dec", WeekSlip.pickLabel(LocalDate.of(2026, 11, 30)))
        assertTrue(WeekSlip.startsOnNext(LocalDate.of(2026, 11, 15)))
    }
}
