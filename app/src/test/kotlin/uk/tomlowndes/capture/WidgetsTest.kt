package uk.tomlowndes.capture

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/** What the Today, Read and combined widgets show. */
class WidgetsTest {
    private val today = LocalDate.of(2026, 10, 12)
    private fun task(text: String, due: LocalDate?) = TaskItem("- [ ] $text", text, due)

    @Test fun `only overdue and today's tasks are shown, oldest first`() {
        val all = listOf(
            task("Tomorrow", today.plusDays(1)),
            task("Today", today),
            task("No date", null),
            task("Last week", today.minusDays(7)),
        )
        assertEquals(listOf("Last week", "Today"), Widgets.dueBy(all, today).map { it.text })
    }

    @Test fun `nothing due gives an empty list`() {
        assertEquals(emptyList<TaskItem>(), Widgets.dueBy(listOf(task("Later", today.plusDays(3))), today))
    }

    @Test fun `tasks title shows the count only when there is one`() {
        assertEquals("Today", Widgets.tasksTitle(0))
        assertEquals("Today · 3", Widgets.tasksTitle(3))
    }

    @Test fun `read title counts unread articles`() {
        assertEquals("📖 Read", Widgets.readTitle(0))
        assertEquals("📖 1 unread", Widgets.readTitle(1))
        assertEquals("📖 4 unread", Widgets.readTitle(4))
    }
}
