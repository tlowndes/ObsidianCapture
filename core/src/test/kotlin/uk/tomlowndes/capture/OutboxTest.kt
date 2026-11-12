package uk.tomlowndes.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class OutboxTest {
    @get:Rule val tmp = TemporaryFolder()

    private var clock = 1_000L
    private fun box() = Outbox(File(tmp.root, "outbox.json")) { clock }

    @Test fun `the same payload is queued once`() {
        val b = box()
        val first = b.enqueue(Outbox.SAVE_TASK, Outbox.taskPayload("Book MOT", "2026-11-12"))
        val second = b.enqueue(Outbox.SAVE_TASK, Outbox.taskPayload("Book MOT", "2026-11-12"))
        assertEquals(first.id, second.id)
        assertEquals(1, b.pending().size)
        assertEquals(1, b.due().size)
    }

    @Test fun `a failure waits, then a success drops it`() {
        val b = box()
        val job = b.enqueue(Outbox.JARVIS, Outbox.jarvisPayload("jarvis", "hello", "chat-1", false))
        b.fail(job.id)
        assertTrue(b.due().isEmpty())
        assertEquals(1, b.pending().size)
        clock += Outbox.backoffMs(1)
        assertEquals(job.id, b.due().single().id)
        b.succeed(job.id)
        assertTrue(b.pending().isEmpty())
    }

    @Test fun `two callers cannot run one job at the same time`() {
        assertTrue(Outbox.begin("a"))
        assertFalse(Outbox.begin("a"))
        Outbox.end("a")
        assertTrue(Outbox.begin("a"))
        Outbox.end("a")
    }

    @Test fun `a task replay does not add a second copy`() {
        val vault = FileVault(tmp.newFolder("vault"))
        val writer = VaultWriter(vault)
        writer.addTask("Book MOT", "2026-11-12")
        Outbox.saveTask(writer, Outbox.taskPayload("Book MOT", "2026-11-12"))
        assertEquals(1, writer.listQuickTasks().size)
        Outbox.saveTask(writer, Outbox.taskPayload("Ring Mum", null))
        assertEquals(listOf("Book MOT", "Ring Mum"), writer.listQuickTasks().map { it.text })
    }

    @Test fun `inbox lines already there are not appended again`() {
        val vault = FileVault(tmp.newFolder("vault2"))
        val writer = VaultWriter(vault)
        writer.appendInbox(listOf("- saw the plumber"))
        Outbox.saveLines(writer, vault, Outbox.linesPayload(listOf("- saw the plumber")))
        val text = vault.read("${VaultWriter.INBOX}/${VaultWriter.INBOX_NOTE}")!!
        assertEquals(1, text.lines().count { it == "- saw the plumber" })
        Outbox.saveLines(writer, vault, Outbox.linesPayload(listOf("- saw the plumber", "- new line")))
        val after = vault.read("${VaultWriter.INBOX}/${VaultWriter.INBOX_NOTE}")!!
        assertEquals(1, after.lines().count { it == "- saw the plumber" })
        assertTrue(after.contains("- new line"))
    }
}
