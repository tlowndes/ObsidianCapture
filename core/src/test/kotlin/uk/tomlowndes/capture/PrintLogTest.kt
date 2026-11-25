package uk.tomlowndes.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class PrintLogTest {
    private val today = LocalDate.of(2026, 11, 25) // a Wednesday
    private val now = LocalDateTime.of(2026, 11, 25, 7, 2)
    private fun task(line: String, path: String = VaultTasks.QUICK_TASK) = TaskNote.parse(line)!!.copy(path = path)

    @Test fun `a printed task that lost its id gets it back`() {
        val t = task("- [ ] #task Bins out #home 📅 2026-11-25")
        val log = listOf(PrintLog.of(t, "hom041", now))
        val m = PrintLog.missing(log, listOf(t))
        assertEquals(listOf(t to "hom041"), m)
        assertEquals("hom041", TicketRail.railId(PrintLog.marked(listOf(t), m).single().raw))
    }

    @Test fun `a task that kept its id needs nothing`() {
        val t = task("- [ ] #task Bins out #home 📅 2026-11-25 🆔 hom041")
        val log = listOf(PrintLog.of(t, "hom041", now))
        assertTrue(PrintLog.missing(log, listOf(t)).isEmpty())
    }

    @Test fun `an id already on another task isn't used twice`() {
        val a = task("- [ ] #task Bins out #home 🆔 hom041")
        val b = task("- [ ] #task Bins out #home")
        val log = listOf(PrintLog.of(b, "hom041", now))
        assertTrue(PrintLog.missing(log, listOf(a, b)).isEmpty())
    }

    @Test fun `a task in another note or with other words doesn't match`() {
        val t = task("- [ ] #task Bins out #home")
        val log = listOf(PrintLog.of(t, "hom041", now))
        assertTrue(PrintLog.missing(log, listOf(task("- [ ] #task Bins out #home", "03 - Projects/House.md"))).isEmpty())
        assertTrue(PrintLog.missing(log, listOf(task("- [ ] #task Recycling out #home"))).isEmpty())
    }

    @Test fun `cleared and old tickets drop off`() {
        val t = task("- [ ] #task Bins out #home")
        val log = listOf(PrintLog.of(t, "hom041", now), PrintLog.of(t, "hom002", now.minusDays(90)), PrintLog.of(t, "hom003", now))
        val done = listOf(TickedTask("- [x] #task Bins out #home 🆔 hom041 ✅ 2026-11-25", "Bins out", today))
        assertEquals(listOf("hom003"), PrintLog.prune(log, done, now).map { it.id })
    }

    @Test fun `printing again keeps the newest time`() {
        val t = task("- [ ] #task Bins out #home")
        val log = PrintLog.add(listOf(PrintLog.of(t, "hom041", now.minusDays(1))), listOf(PrintLog.of(t, "hom041", now)))
        assertEquals(listOf(now), log.map { it.at })
    }

    @Test fun `it survives a round trip`() {
        val t = task("- [ ] #task Bins out #home")
        val log = listOf(PrintLog.of(t, "hom041", now))
        assertEquals(log, PrintLog.decode(PrintLog.encode(log)))
        assertTrue(PrintLog.decode("not json").isEmpty())
        assertTrue(PrintLog.decode(null).isEmpty())
    }

    @Test fun `words and stamps`() {
        assertEquals("printed today 07:02", PrintLog.words(now, today))
        assertEquals("printed Mon 21:59", PrintLog.words(LocalDateTime.of(2026, 11, 23, 21, 59), today))
        assertEquals("printed 12 Nov", PrintLog.words(LocalDateTime.of(2026, 11, 12, 9, 0), today))
        assertEquals("PRINTED 07:02", PrintLog.stamp(now, today))
        assertEquals("PRINTED MON", PrintLog.stamp(LocalDateTime.of(2026, 11, 23, 21, 59), today))
        assertEquals("🧾 PRINTED", PrintLog.tag(task("- [ ] #task Bins out 📅 2026-11-25"), today))
        assertEquals("🧾 FRI", PrintLog.tag(task("- [ ] #task Parcel 📅 2026-11-27"), today))
    }
}
