package uk.tomlowndes.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class UpcomingTest {
    private val now = LocalDateTime.of(2026, 11, 9, 9, 20)
    private val today = now.toLocalDate()
    private fun ev(title: String, day: Int, h: Int, m: Int, mins: Long = 60, allDay: Boolean = false): CalEvent {
        val s = LocalDateTime.of(2026, 11, day, h, m)
        return CalEvent("p", title, s, if (allDay) s.plusDays(1) else s.plusMinutes(mins), allDay)
    }
    private fun task(text: String, due: LocalDate?, extra: String = "") = TaskItem("- [ ] #task $text$extra", text, due)

    @Test fun `seven days with tasks first then events, overdue on today`() {
        val days = Upcoming.days(
            listOf(task("Late", today.minusDays(5)), task("Dentist", today), task("Car tax", today.plusDays(3)), task("Someday", null)),
            listOf(ev("Call", 9, 10, 0, 30), ev("Dinner", 10, 19, 0), ev("Birthday", 14, 0, 0, allDay = true)),
            now
        )
        assertEquals(7, days.size)
        val t = days[0].items
        assertTrue((t[0] as AgendaItem.Task).late)
        assertEquals("Dentist", (t[1] as AgendaItem.Task).task.text)
        assertTrue(t.any { it is AgendaItem.Event && it.event.title == "Call" })
        assertEquals(listOf("Dinner"), days[1].items.map { (it as AgendaItem.Event).event.title })
        assertTrue(days[2].clear)
        assertEquals(1, days[3].tasks)
        assertEquals("Birthday", days[5].allDay.single().title)
        assertTrue(days.none { d -> d.items.any { it is AgendaItem.Task && it.task.text == "Someday" } })
    }

    @Test fun `gaps of an hour or more between events, from now, inside 9 to 6`() {
        val items = Upcoming.withGaps(listOf(ev("Call", 9, 10, 0, 30), ev("Review", 9, 13, 0), ev("Gym", 9, 17, 30)), now)
        val gaps = items.filterIsInstance<AgendaItem.Gap>()
        // 9:20–10:00 is too short; 10:30–13:00 and 14:00–17:30 count; nothing after the gym (window ends at 18:00).
        assertEquals(listOf(150, 210), gaps.map { it.minutes })
        assertEquals(LocalDateTime.of(2026, 11, 9, 10, 30), gaps[0].start)
        assertTrue(items[1] is AgendaItem.Gap)
    }

    @Test fun `an empty day is one big gap from now`() {
        val g = Upcoming.withGaps(emptyList(), now).single() as AgendaItem.Gap
        assertEquals(now, g.start)
        assertEquals("8h 40m free", Upcoming.gapLabel(g.minutes))
        assertTrue(Upcoming.withGaps(emptyList(), now.withHour(17).withMinute(30)).isEmpty())
    }

    @Test fun `next up and countdown`() {
        val events = listOf(ev("Early", 9, 8, 0, 30), ev("Call", 9, 10, 0, 30), ev("Gym", 9, 17, 30), ev("All", 9, 0, 0, allDay = true))
        val next = Upcoming.nextUp(events, now)
        assertEquals("Call", next?.title)
        assertEquals("40m", Upcoming.countdown(next!!, now))
        assertEquals("1h 10m", Upcoming.countdown(next, now.minusMinutes(30)))
        assertEquals("now", Upcoming.countdown(next, now.withHour(10).withMinute(5)))
        assertNull(Upcoming.nextUp(events, now.withHour(19)))
    }

    @Test fun `fit a task picks estimates that fit, longest first`() {
        val tasks = listOf(task("Short", null, " ⏱ 5m"), task("Long", today.plusDays(2), " ⏱ 45m"), task("Too long", null, " ⏱ 3h"),
            task("No estimate", null), task("Already today", today, " ⏱ 10m"))
        assertEquals(listOf("Long", "Short"), Upcoming.fits(tasks, 60, today).map { it.text })
    }

    @Test fun `reschedule presets use the real dates`() {
        assertEquals(
            listOf("Today" to today, "Tomorrow" to today.plusDays(1), "This weekend" to LocalDate.of(2026, 11, 14), "Next week" to LocalDate.of(2026, 11, 16)),
            Upcoming.presets(today)
        )
        // On a Saturday "This weekend" is next Saturday's, as tomorrow already covers Sunday.
        assertEquals(LocalDate.of(2026, 11, 21), Upcoming.presets(LocalDate.of(2026, 11, 14))[2].second)
    }
}
