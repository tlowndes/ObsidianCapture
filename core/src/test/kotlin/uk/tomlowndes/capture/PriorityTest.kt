package uk.tomlowndes.capture

import java.time.LocalDate
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class PriorityTest {
    private val today = LocalDate.of(2026, 11, 10)
    private fun t(raw: String) = TaskItem(raw, TaskRules.cleanText(raw.removePrefix("- [ ] ")), TaskRules.due(raw))

    @Test fun readsTasksPluginMarks() {
        assertEquals(Priority.HIGHEST, Priority.of("- [ ] a 🔺 📅 2026-11-10"))
        assertEquals(Priority.HIGH, Priority.of("- [ ] a ⏫"))
        assertEquals(Priority.MEDIUM, Priority.of("- [ ] a 🔼"))
        assertEquals(Priority.LOW, Priority.of("- [ ] a 🔽"))
        assertEquals(Priority.LOWEST, Priority.of("- [ ] a ⏬"))
        assertEquals(Priority.NONE, Priority.of("- [ ] a #work"))
    }

    @Test fun writesBeforeDatesAndReplacesOld() {
        assertEquals("- [ ] Apply #work ⏫ 📅 2026-11-13", Priority.with("- [ ] Apply #work 📅 2026-11-13", Priority.HIGH))
        assertEquals("- [ ] Apply 🔺 🔁 every week 📅 2026-11-13", Priority.with("- [ ] Apply 🔽 🔁 every week 📅 2026-11-13", Priority.HIGHEST))
        assertEquals("- [ ] Apply ⏬", Priority.with("- [ ] Apply", Priority.LOWEST))
        assertEquals("- [ ] Apply 📅 2026-11-13", Priority.with("- [ ] Apply ⏫ 📅 2026-11-13", Priority.NONE))
        assertEquals("- [ ] Apply 🔼\r", Priority.with("- [ ] Apply\r", Priority.MEDIUM))
    }

    @Test fun flagMarkIsHiddenFromText() {
        assertEquals("Apply", TaskRules.cleanText("Apply ⏫ 📅 2026-11-13"))
    }

    @Test fun flaggedFirstInEachDay() {
        val tasks = listOf(
            t("- [ ] dentist 📅 2026-11-10"), t("- [ ] figma 🔽 📅 2026-11-10"),
            t("- [ ] case 🔺 📅 2026-11-10"), t("- [ ] tax 🔼 📅 2026-11-10"),
        )
        val day = TaskViews.groups(tasks, today).single { it.first == TaskGroup.TODAY }.second.map { it.text }
        assertEquals(listOf("case", "tax", "dentist", "figma"), day)
    }

    @Test fun priorityGroupsInOrderByDue() {
        val tasks = listOf(
            t("- [ ] b ⏫ 📅 2026-11-13"), t("- [ ] c"), t("- [ ] a ⏫ 📅 2026-11-05"), t("- [ ] d ⏬"),
        )
        val g = TaskViews.priorityGroups(tasks)
        assertEquals(listOf(Priority.HIGH, Priority.NONE, Priority.LOWEST), g.map { it.first })
        assertEquals(listOf("a", "b"), g[0].second.map { it.text })
    }

    @Test fun urgentFilterKeepsHighAndHighest() {
        assertTrue(TaskViews.matches(t("- [ ] a 🔺"), null, TaskFilter.URGENT))
        assertTrue(TaskViews.matches(t("- [ ] a ⏫"), null, TaskFilter.URGENT))
        assertTrue(!TaskViews.matches(t("- [ ] a 🔼"), null, TaskFilter.URGENT))
        assertTrue(!TaskViews.matches(t("- [ ] a"), null, TaskFilter.URGENT))
    }
}
