package uk.tomlowndes.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate

/** Task actions and their Undo, as both apps run them. */
class TaskOpsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val today = LocalDate.of(2026, 11, 26)

    private fun writer(ticks: Boolean = true) =
        VaultWriter(FileVault(File(tmp.root, "vault")), tickFile = if (ticks) File(tmp.root, "ticks.txt") else null)
    private fun quick() = File(tmp.root, "vault/" + VaultTasks.QUICK_TASK).readText()

    @Test fun `move changes only the date and undo puts it back`() {
        val w = writer()
        w.addTask("Book MOT ⏫", "2026-11-27")
        val t = w.listQuickTasks().single()
        val c = TaskOps.move(w, t, LocalDate.of(2026, 12, 5), today)
        assertEquals("Moved to Sat 5 Dec", c.message)
        assertTrue(quick().contains("Book MOT ⏫ 📅 2026-12-05"))
        c.undo(w)
        assertTrue(quick().contains("Book MOT ⏫ 📅 2026-11-27"))
        assertEquals("Moved to tomorrow", TaskOps.move(w, w.listQuickTasks().single(), today.plusDays(1), today).message)
        assertEquals("Date cleared", TaskOps.move(w, w.listQuickTasks().single(), null, today).message)
    }

    @Test fun `bulk runs each, counts failures, and one undo takes all back`() {
        val w = writer()
        w.addTask("One", null); w.addTask("Two", null)
        val items = w.listQuickTasks()
        val stale = items[0].copy(raw = items[0].raw + " gone")
        val c = TaskOps.bulkMove(w, items + stale, today, today)
        assertEquals("2 tasks moved to today · 1 failed", c.message)
        assertEquals(2, w.listQuickTasks().count { it.due == today })
        c.undo(w)
        assertEquals(0, w.listQuickTasks().count { it.due != null })
        val ex = assertThrows(TaskOps.NoneChanged::class.java) { TaskOps.bulkRemove(w, listOf(stale)) }
        assertEquals("Couldn't change 1 task", ex.message)
    }

    @Test fun `bulk done, flag, tag and remove`() {
        val w = writer()
        w.addTask("One", null); w.addTask("Two", null)
        assertEquals("2 tasks flagged high", TaskOps.bulkPriority(w, w.listQuickTasks(), Priority.HIGH).message)
        assertEquals("#garden added to 2 tasks", TaskOps.bulkTag(w, w.listQuickTasks(), "#garden").message)
        assertTrue(w.listQuickTasks().all { "#garden" in it.raw })
        val done = TaskOps.bulkDone(w, w.listQuickTasks(), true)
        assertEquals("2 tasks done", done.message)
        assertTrue(w.listQuickTasks().isEmpty())
        done.undo(w)
        assertEquals(2, w.listQuickTasks().size)
        assertEquals("2 tasks removed", TaskOps.bulkRemove(w, w.listQuickTasks()).message)
    }

    @Test fun `un-tick of a repeat takes its next copy away, from the tick log`() {
        val w = writer()
        w.addTask("Bins 🔁 every week", "2026-11-26")
        val c = TaskOps.complete(w, w.listQuickTasks().single(), true, today)
        assertEquals("Done · next: 3 Dec", c.message)
        assertEquals(1, w.listQuickTasks().size)
        val tick = w.ticks().single()
        assertTrue(tick.nextLine!!.contains("2026-12-03"))
        val d = w.listTaskLists().done.first { it.raw == tick.doneLine }
        w.untickTask(d)
        val open = w.listQuickTasks().single()
        assertEquals(LocalDate.of(2026, 11, 26), open.due)
        assertTrue(w.ticks().isEmpty())
    }

    @Test fun `undo of a tick forgets it, and no tick file keeps nothing`() {
        val w = writer()
        w.addTask("Call mum", null)
        val c = TaskOps.complete(w, w.listQuickTasks().single(), true, today)
        assertEquals("Task completed", c.message)
        assertEquals(1, w.ticks().size)
        c.undo(w)
        assertTrue(w.ticks().isEmpty())
        val n = writer(ticks = false)
        TaskOps.complete(n, n.listQuickTasks().single(), true, today)
        assertTrue(n.ticks().isEmpty())
    }

    @Test fun `tick log keeps the newest per line and the last thirty`() {
        var log = emptyList<TickLog.Tick>()
        repeat(35) { log = TickLog.add(log, TickLog.Tick("line $it", it.toLong(), null)) }
        log = TickLog.add(log, TickLog.Tick("line 34", 99, "next"))
        assertEquals(TickLog.KEEP, log.size)
        assertEquals("next", TickLog.nextFor(log, "line 34"))
        assertEquals(log, TickLog.decode(TickLog.encode(log)))
        assertNull(TickLog.nextFor(TickLog.remove(log, "line 34"), "line 34"))
        assertFalse(TickLog.decode("junk").isNotEmpty())
    }

    @Test fun `seeding only fills an empty log`() {
        val w = writer()
        w.seedTicks(listOf(TickLog.Tick("a", 1, null)))
        w.seedTicks(listOf(TickLog.Tick("b", 2, null)))
        assertEquals(listOf("a"), w.ticks().map { it.doneLine })
    }
}
