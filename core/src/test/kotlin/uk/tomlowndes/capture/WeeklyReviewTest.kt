package uk.tomlowndes.capture

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate

class WeeklyReviewTest {
    @get:Rule val tmp = TemporaryFolder()
    private val today = LocalDate.of(2026, 10, 25) // Sunday
    private fun task(line: String) = TaskNote.parse(line)!!

    @Test fun `done tasks this week are read with their dates`() {
        val lines = listOf(
            "- [x] #task Bins out ✅ 2026-10-21",
            "- [x] #task Old thing ✅ 2026-10-01",
            "- [x] Not a vault task ✅ 2026-10-22",
            "- [ ] #task Still open 📅 2026-10-20"
        )
        assertEquals(listOf(DoneTask("Bins out", LocalDate.of(2026, 10, 21), "a.md")), WeeklyReview.doneIn(lines, "a.md", today.minusDays(6)))
    }

    @Test fun `review splits carried, drop and next week`() {
        val open = listOf(
            task("- [ ] #task Boiler service 📅 2026-10-22"),
            task("- [ ] #task Fix the gate 📅 2026-10-02"),
            task("- [ ] #task Portfolio draft 📅 2026-10-27"),
            task("- [ ] Council tax £128 #bill #task 📅 2026-10-28"),
            task("- [ ] #task Far off 📅 2026-11-20")
        )
        val r = WeeklyReview.build(emptyList(), open, today)
        assertEquals(listOf("Boiler service"), r.carried.map { it.text })
        assertEquals("Fix the gate", r.drop?.text)
        assertEquals(listOf("Portfolio draft"), r.nextTasks.map { it.text })
        assertEquals(listOf("Council tax"), r.nextBills.map { it.name })
        assertEquals(LocalDate.of(2026, 10, 26), WeeklyReview.nextMonday(today))
    }

    @Test fun `drop marks cancelled and keeps the line`() {
        assertEquals("- [-] #task Fix the gate 📅 2026-10-02 ❌ 2026-10-25", WeeklyReview.cancelled("- [ ] #task Fix the gate 📅 2026-10-02", today))
    }

    @Test fun `done tasks are found in the vault`() {
        File(tmp.root, "Projects").mkdirs()
        File(tmp.root, "Projects/House.md").writeText("- [x] #task Paint door ✅ ${today.minusDays(1)}\n")
        assertEquals(listOf("Paint door"), WeeklyReview.done(FileVault(tmp.root), today).map { it.text })
    }
}
