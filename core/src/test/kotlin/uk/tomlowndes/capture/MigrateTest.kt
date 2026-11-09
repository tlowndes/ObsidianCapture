package uk.tomlowndes.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class MigrateTest {
    private val today = LocalDate.of(2026, 11, 9)
    private fun t(text: String, due: LocalDate?, rec: String? = null) = TaskItem("- [ ] #task $text", text, due, rec)

    @Test fun `only tasks dated before this month, oldest first, no repeats`() {
        val tasks = listOf(
            t("Update CV", LocalDate.of(2026, 10, 4)), t("Fix gate", LocalDate.of(2026, 10, 2)),
            t("This month", LocalDate.of(2026, 11, 3)), t("No date", null),
            t("Water plants", LocalDate.of(2026, 10, 1), "every week")
        )
        assertEquals(listOf("Fix gate", "Update CV"), Migrate.candidates(tasks, today).map { it.text })
    }

    @Test fun `offered once a month`() {
        val tasks = listOf(t("Fix gate", LocalDate.of(2026, 10, 2)))
        assertTrue(Migrate.due(tasks, today, null))
        assertTrue(Migrate.due(tasks, today, "2026-10"))
        assertFalse(Migrate.due(tasks, today, "2026-11"))
        assertFalse(Migrate.due(emptyList(), today, null))
        assertEquals("October → November", Migrate.title(today))
        assertEquals("December → January", Migrate.title(LocalDate.of(2027, 1, 3)))
    }
}
