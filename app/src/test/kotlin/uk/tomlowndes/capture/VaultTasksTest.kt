package uk.tomlowndes.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Tasks kept in notes other than Quick Task: found, ticked and undone where they are. */
class VaultTasksTest {
    private val today = LocalDate.parse("2026-10-12")
    private val path = "03 - Projects/House move/House move.md"
    private val boxes = "- [ ] #task Order boxes 📅 2026-10-14"
    private val meter = "    - [ ] #task Read the meter 🔁 every month 📅 2026-10-12"

    private val note = """
        ---
        tags: [project]
        todo: "- [ ] #task not a task, it's frontmatter"
        ---

        ## Summary
        Moving at the end of the month.

        - [ ] A checklist item without the tag
        $boxes
        - [x] #task Book van ✅ 2026-10-01
        $meter
        - [ ] #tasks a different tag

        ```tasks
        - [ ] #task inside a code block
        ```
    """.trimIndent() + "\n"

    private fun lines(s: String = note) = s.split("\n").toMutableList()
    private fun text(ls: List<String>) = ls.joinToString("\n")

    @Test fun `finds open #task lines and remembers their note`() {
        val tasks = VaultTasks.parseNote(lines(), path)
        assertEquals(listOf("Order boxes", "Read the meter"), tasks.map { it.text })
        assertEquals(listOf(boxes, meter), tasks.map { it.raw })
        assertTrue(tasks.all { it.path == path })
        assertEquals("every month", tasks[1].recurrence)
    }

    @Test fun `subtags count and a note without frontmatter works`() {
        val tasks = VaultTasks.parseNote(listOf("- [ ] #task/work Send invoice", "- [ ] Buy #taskforce badge"), "a.md")
        assertEquals(listOf("#task/work Send invoice"), tasks.map { it.raw.removePrefix("- [ ] ") })
    }

    @Test fun `ticking ticks in place and undo puts it back`() {
        val ls = lines()
        val c = TaskNote.completion(boxes, today, recurring = true)
        VaultTasks.completeInPlace(ls, boxes, c)
        assertEquals(note.replace(boxes, "- [x] #task Order boxes 📅 2026-10-14 ✅ 2026-10-12"), text(ls))
        VaultTasks.restoreInPlace(ls, boxes, c)
        assertEquals(note, text(ls))
    }

    @Test fun `a recurring task's next occurrence goes above the ticked one, keeping its indent`() {
        val ls = lines()
        val c = TaskNote.completion(meter, today, recurring = true)
        VaultTasks.completeInPlace(ls, meter, c)
        assertEquals(
            note.replace(meter, "    - [ ] #task Read the meter 🔁 every month 📅 2026-11-12\n" +
                "    - [x] #task Read the meter 🔁 every month 📅 2026-10-12 ✅ 2026-10-12"),
            text(ls)
        )
        VaultTasks.restoreInPlace(ls, meter, c)
        assertEquals(note, text(ls))
    }

    @Test fun `remove and undo returns the task to the same line`() {
        val ls = lines()
        val at = VaultTasks.delete(ls, boxes)
        assertFalse(text(ls).contains(boxes))
        VaultTasks.restoreInPlace(ls, boxes, null, at = at)
        assertEquals(note, text(ls))
    }

    @Test fun `reschedule undo swaps the line back`() {
        val ls = lines()
        val moved = TaskRules.withDue(boxes, today)
        TaskNote.replace(ls, boxes, moved)
        VaultTasks.restoreInPlace(ls, boxes, null, replace = moved)
        assertEquals(note, text(ls))
    }

    @Test fun `a task edited in Obsidian meanwhile is not guessed at`() {
        val ls = lines()
        val c = TaskNote.completion("- [ ] #task Gone", today, recurring = false)
        assertThrows(IllegalStateException::class.java) { VaultTasks.completeInPlace(ls, "- [ ] #task Gone", c) }
        assertEquals(note, text(ls))
    }

    @Test fun `skips meta, templates, read-later and dot folders`() {
        val templates = listOf("Templates")
        assertTrue(VaultTasks.skipped("99 - Meta", templates))
        assertTrue(VaultTasks.skipped("99 - Meta/Assets", templates))
        assertTrue(VaultTasks.skipped("Templates/Daily.md", templates))
        assertTrue(VaultTasks.skipped("01 - Inbox/ReadItLater Inbox", templates))
        assertTrue(VaultTasks.skipped(".obsidian", templates))
        assertTrue(VaultTasks.skipped("Projects/.trash", templates))
        assertFalse(VaultTasks.skipped("01 - Inbox", templates))
        assertFalse(VaultTasks.skipped("Templates and tools.md", templates))
        assertFalse(VaultTasks.skipped("Daily/2026-10-12.md", templates))
    }

    @Test fun `note name for the label`() {
        assertEquals("House move", VaultTasks.noteName(path))
    }
}
