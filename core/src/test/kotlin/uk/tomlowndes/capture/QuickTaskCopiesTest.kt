package uk.tomlowndes.capture

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 2026-11-24: "⚡ Quick Task (1).md" turned up beside Quick Task and every task in it showed twice. */
class QuickTaskCopiesTest {
    @get:Rule val tmp = TemporaryFolder()

    private val quick = "${VaultWriter.INBOX}/${VaultWriter.TASK_FILE}"
    private val copy = "${VaultWriter.INBOX}/⚡ Quick Task (1).md"
    private val body = "## Active\n- [ ] #task Update Minecraft server 📅 2026-11-24\n\n## Archive\n"

    private fun note(path: String, text: String) = File(tmp.root, path).apply { parentFile.mkdirs(); writeText(text) }

    @Before fun start() = VaultScan.reset()
    @After fun end() = VaultScan.reset()

    @Test fun `only numbered copies in the inbox count`() {
        assertTrue(QuickGuard.isCopy(copy))
        assertTrue(QuickGuard.isCopy("${VaultWriter.INBOX}/⚡ Quick Task (12).md"))
        assertFalse(QuickGuard.isCopy(quick))
        assertFalse(QuickGuard.isCopy("05 - Resources/⚡ Quick Task (1).md"))
        assertFalse(QuickGuard.isCopy("${VaultWriter.INBOX}/⚡ Quick Task notes.md"))
    }

    @Test fun `a copy's tasks are not listed a second time`() {
        note(quick, body)
        note(copy, body)
        note("Projects/A.md", "- [ ] #task One\n")
        val w = VaultWriter(FileVault(tmp.root))
        val lists = w.listTaskLists()
        assertEquals(listOf("Update Minecraft server", "One"), lists.open.map { it.text })
    }

    @Test fun `the banner points the copy out`() {
        note(quick, body)
        note(copy, body)
        val w = VaultWriter(FileVault(tmp.root))
        val a = w.listTaskLists().quickAlert!!
        assertEquals(listOf(copy), a.copies)
        assertTrue(a.headline().contains("⚡ Quick Task (1).md"))
        assertEquals(listOf(copy), w.quickAlert()!!.copies)
    }

    @Test fun `no copy, no banner`() {
        note(quick, body)
        val w = VaultWriter(FileVault(tmp.root))
        assertNull(w.listTaskLists().quickAlert)
        assertNull(w.quickAlert())
    }
}
