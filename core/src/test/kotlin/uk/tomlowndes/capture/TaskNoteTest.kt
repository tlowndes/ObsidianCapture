package uk.tomlowndes.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class TaskNoteTest {
    private val today = LocalDate.parse("2026-10-12")
    private val milk = "- [ ] #task Buy milk 📅 2026-10-12"
    private val mum = "- [ ] #task Call mum"
    private val bins = "- [ ] #task Bins out 🔁 every week 📅 2026-10-12"

    /** A Quick Task note with things around the task lists that must never be touched. */
    private val note = """
        ---
        tags: [tasks]
        ---

        > Written by hand in Obsidian

        ## Active

        $milk
        $mum
        $bins

        ## Archive

        - [x] #task Old thing ✅ 2026-10-01

        ## Notes
        Free text the app doesn't own.
    """.trimIndent() + "\n"

    private fun lines(s: String = note) = s.split("\n").toMutableList()
    private fun text(ls: List<String>) = ls.joinToString("\n")

    @Test fun `lists open tasks in Active only`() {
        val tasks = TaskNote.list(lines())
        assertEquals(listOf("Buy milk", "Call mum", "Bins out"), tasks.map { it.text })
        assertEquals(today, tasks[0].due)
        assertNull(tasks[1].due)
        assertEquals("every week", tasks[2].recurrence)
        assertEquals(milk, tasks[0].raw)
    }

    @Test fun `parse ignores done tasks and plain bullets`() {
        assertNull(TaskNote.parse("- [x] #task Done ✅ 2026-10-01"))
        assertNull(TaskNote.parse("- just a bullet"))
        assertEquals("Indented", TaskNote.parse("    - [ ] Indented")?.text)
    }

    @Test fun `new task goes at the end of Active`() {
        val ls = lines()
        TaskNote.insertActive(ls, TaskNote.newTask("  Post letter ", "2026-10-13"))
        assertEquals(note.replace("$bins\n", "$bins\n- [ ] #task Post letter 📅 2026-10-13\n"), text(ls))
    }

    @Test fun `new task without a date`() {
        assertEquals("- [ ] #task Post letter", TaskNote.newTask("Post letter", null))
    }

    @Test fun `new task in a note with no headings is appended`() {
        val ls = lines("- [ ] #task One")
        TaskNote.insertActive(ls, "- [ ] #task Two")
        assertEquals("- [ ] #task One\n- [ ] #task Two", text(ls))
    }

    @Test fun `ticking moves the task to the top of Archive with a done date`() {
        val ls = lines()
        val c = TaskNote.completion(milk, today, recurring = true)
        TaskNote.complete(ls, milk, c)
        assertEquals("- [x] #task Buy milk 📅 2026-10-12 ✅ 2026-10-12", c.doneLine)
        assertNull(c.nextLine)
        assertEquals(
            note.replace("$milk\n", "")
                .replace("## Archive\n\n", "## Archive\n\n- [x] #task Buy milk 📅 2026-10-12 ✅ 2026-10-12\n"),
            text(ls)
        )
    }

    @Test fun `ticking a recurring task leaves the next one in its place`() {
        val ls = lines()
        val c = TaskNote.completion(bins, today, recurring = true)
        TaskNote.complete(ls, bins, c)
        assertEquals("- [ ] #task Bins out 🔁 every week 📅 2026-10-19", c.nextLine)
        assertEquals(c.nextLine, TaskNote.list(ls).last().raw)
        assertTrue(ls.indexOf(c.doneLine) > ls.indexOf("## Archive"))
    }

    @Test fun `recurring switched off just ticks it`() {
        val c = TaskNote.completion(bins, today, recurring = false)
        assertNull(c.nextLine)
    }

    @Test fun `a line that already has a done date keeps it`() {
        val c = TaskNote.completion("- [ ] #task Odd ✅ 2026-10-01", today, recurring = true)
        assertEquals("- [x] #task Odd ✅ 2026-10-01", c.doneLine)
    }

    @Test fun `ticking creates Archive if the note has none`() {
        val ls = lines("## Active\n\n$milk")
        TaskNote.complete(ls, milk, TaskNote.completion(milk, today, true))
        assertEquals("## Active\n\n\n## Archive\n\n- [x] #task Buy milk 📅 2026-10-12 ✅ 2026-10-12", text(ls))
    }

    @Test fun `editing a task changed in Obsidian stops without writing`() {
        val ls = lines()
        val stale = "- [ ] #task Buy milk"
        assertThrows(IllegalStateException::class.java) { TaskNote.complete(ls, stale, TaskNote.completion(stale, today, true)) }
        assertThrows(IllegalStateException::class.java) { TaskNote.replace(ls, stale, "x") }
        assertThrows(IllegalStateException::class.java) { TaskNote.delete(ls, stale) }
        assertEquals(note, text(ls))
    }

    @Test fun `undo of a recurring tick restores the note exactly`() {
        val ls = lines()
        val c = TaskNote.completion(bins, today, true)
        TaskNote.complete(ls, bins, c)
        TaskNote.restore(ls, bins, c)
        assertEquals(note, text(ls))
    }

    @Test fun `undo of a plain tick puts the task back in Active`() {
        val ls = lines()
        val c = TaskNote.completion(milk, today, true)
        TaskNote.complete(ls, milk, c)
        TaskNote.restore(ls, milk, c)
        assertFalse(ls.contains(c.doneLine))
        assertEquals(listOf("Call mum", "Bins out", "Buy milk"), TaskNote.list(ls).map { it.text })
    }

    @Test fun `reschedule and its undo`() {
        val ls = lines()
        val moved = TaskRules.withDue(milk, LocalDate.parse("2026-10-20"))
        TaskNote.replace(ls, milk, moved)
        assertEquals(note.replace(milk, moved), text(ls))
        TaskNote.restore(ls, milk, null, replace = moved)
        assertEquals(note, text(ls))
    }

    @Test fun `editing one task rewrites that line and leaves every other line alone`() {
        val ls = lines()
        TaskNote.edit(ls, milk, "Buy oat milk", LocalDate.parse("2026-10-20"), "every week on Tuesday", 15)
        val updated = "- [ ] #task Buy oat milk ⏱ 15m 🔁 every week on Tuesday 📅 2026-10-20"
        assertEquals(note.replace(milk, updated), text(ls))
        assertEquals(listOf("Buy oat milk", "Call mum", "Bins out"), TaskNote.list(ls).map { it.text })
        assertEquals("every week on Tuesday", TaskNote.list(ls)[0].recurrence)
    }

    @Test fun `a line that no longer matches is not overwritten`() {
        val ls = lines()
        val before = text(ls)
        val stale = "- [ ] #task Buy milk 📅 2026-10-13"
        val ex = assertThrows(IllegalStateException::class.java) {
            TaskNote.edit(ls, stale, "Buy oat milk", LocalDate.parse("2026-10-20"), "every month on the 1st", null)
        }
        assertTrue(ex.message!!.contains("Task changed in Obsidian"))
        assertEquals(before, text(ls))
    }

    @Test fun `edit keeps indent priority and fields on that line only`() {
        val raw = "    - [ ] #task Pay rent 🔁 every month 📅 2026-10-01 ⏫ [id:: abc123]"
        val next = "- [ ] #task Leave this line alone"
        val ls = mutableListOf(raw, next)
        TaskNote.edit(ls, raw, "Pay the rent", LocalDate.parse("2026-11-01"), "every month on the 1st", null)
        assertEquals("    - [ ] #task Pay the rent 🔁 every month on the 1st 📅 2026-11-01 ⏫ [id:: abc123]", ls[0])
        assertEquals(next, ls[1])
    }

    @Test fun `delete and its undo`() {
        val ls = lines()
        TaskNote.delete(ls, mum)
        assertEquals(note.replace("$mum\n", ""), text(ls))
        TaskNote.restore(ls, mum, null)
        assertEquals(listOf("Buy milk", "Bins out", "Call mum"), TaskNote.list(ls).map { it.text })
    }
}
