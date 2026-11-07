package uk.tomlowndes.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class CompletedTasksTest {
    private val note = """
        ## Active

        - [ ] #task Bins out 🔁 every week 📅 2026-10-19

        ## Archive

        - [x] #task Bins out 🔁 every week 📅 2026-10-12 ✅ 2026-10-12
        - [x] #task Old thing ✅ 2026-10-01

        ## Notes
        - [x] not in archive ✅ 2026-10-11
    """.trimIndent()

    private fun lines() = note.split("\n").toMutableList()

    @Test fun `parses done lines with their date`() {
        val d = TaskNote.parseDone("- [x] #task Pay rent ⏱ 5m ✅ 2026-10-05")!!
        assertEquals("Pay rent", d.text)
        assertEquals(LocalDate.parse("2026-10-05"), d.done)
        assertNull(TaskNote.parseDone("- [ ] #task Open"))
        assertNull(TaskNote.parseDone("- [x] #task No date")?.done)
    }

    @Test fun `lists Archive only, top first`() {
        assertEquals(listOf("Bins out", "Old thing"), TaskNote.listDone(lines()).map { it.text })
    }

    @Test fun `untick puts a repeat back in place of its follow-up`() {
        val ls = lines()
        TaskNote.untick(ls, "- [x] #task Bins out 🔁 every week 📅 2026-10-12 ✅ 2026-10-12", "- [ ] #task Bins out 🔁 every week 📅 2026-10-19")
        assertEquals(1, ls.count { it.contains("Bins out") })
        assertEquals("- [ ] #task Bins out 🔁 every week 📅 2026-10-12", ls[2])
    }

    @Test fun `untick of a plain task goes back to the end of Active`() {
        val ls = lines()
        TaskNote.untick(ls, "- [x] #task Old thing ✅ 2026-10-01", null)
        assertEquals(listOf("Bins out", "Old thing"), TaskNote.list(ls).map { it.text })
        assertEquals(listOf("Bins out"), TaskNote.listDone(ls).map { it.text })
    }

    @Test fun `untick in place reopens and drops the follow-up`() {
        val ls = mutableListOf("# Note", "- [ ] #task Water 🔁 every day 📅 2026-10-13", "- [x] #task Water 🔁 every day 📅 2026-10-12 ✅ 2026-10-12")
        VaultTasks.untickInPlace(ls, ls[2], ls[1])
        assertEquals(listOf("# Note", "- [ ] #task Water 🔁 every day 📅 2026-10-12"), ls)
    }

    @Test fun `task lines keep open and done, skip code and plain bullets`() {
        val ls = listOf("- [ ] #task a", "- [x] #task b ✅ 2026-10-01", "- [x] c ✅ 2026-10-01", "```", "- [ ] #task q", "```")
        assertEquals(listOf("- [ ] #task a", "- [x] #task b ✅ 2026-10-01"), VaultTasks.taskLines(ls))
    }

    @Test fun `recent done prefers app stamps then date`() {
        val a = TickedTask("- [x] a ✅ 2026-10-10", "a", LocalDate.parse("2026-10-10"))
        val b = TickedTask("- [x] b ✅ 2026-10-12", "b", LocalDate.parse("2026-10-12"))
        val c = TickedTask("- [x] c ✅ 2026-10-12", "c", LocalDate.parse("2026-10-12"))
        val stamped = LocalDate.parse("2026-10-12").atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() + 3_600_000
        assertEquals(listOf("c", "b", "a"), TaskViews.recentDone(listOf(a, b, c), mapOf(c.raw to stamped)).map { it.text })
        assertEquals(listOf("b", "c"), TaskViews.recentDone(listOf(a, b, c), emptyMap(), 2).map { it.text })
    }
}
