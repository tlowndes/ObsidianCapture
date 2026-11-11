package uk.tomlowndes.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Quick Task moved out of the inbox: adding a task must not start a fresh, near-empty one. */
class QuickTaskMovedTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `adding a task refuses when Quick Task was moved elsewhere`() {
        val moved = File(tmp.root, "05 - Resources/Personal/${VaultWriter.TASK_FILE}")
        moved.parentFile.mkdirs()
        moved.writeText("## Active\n\n- [ ] #task Keep me\n")
        val w = VaultWriter(FileVault(tmp.root))
        try {
            w.addTask("New one", null)
            fail("should have refused")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("moved to 05 - Resources/Personal"))
        }
        assertFalse(File(tmp.root, "${VaultWriter.INBOX}/${VaultWriter.TASK_FILE}").exists())
        assertEquals("## Active\n\n- [ ] #task Keep me\n", moved.readText())
    }

    @Test fun `with no Quick Task anywhere, adding a task still makes one`() {
        val w = VaultWriter(FileVault(tmp.root))
        w.addTask("First", null)
        assertEquals(listOf("First"), w.listQuickTasks().map { it.text })
    }
}
