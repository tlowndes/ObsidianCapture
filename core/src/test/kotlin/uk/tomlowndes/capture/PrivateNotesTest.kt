package uk.tomlowndes.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PrivateNotesTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun put(path: String) = File(tmp.root, path).apply { parentFile.mkdirs(); writeText("# x\n") }

    @Test fun `private notes and Did I logs never reach search`() {
        listOf(PocketPass.NOTE, Refill.NOTE, LastSeen.NOTE, "${DidI.LOG_DIR}/2026-11-21.md", DidI.CHECKS, "03 - Projects/Refit.md").forEach(::put)
        val found = NoteIndex.paths(FileVault(tmp.root)).map { it.path }.toSet()
        assertEquals(setOf(DidI.CHECKS, "03 - Projects/Refit.md"), found)
    }

    @Test fun `a search for a private note says where it is`() {
        assertNotNull(NoteIndex.privateHint("refi"))
        assertNotNull(NoteIndex.privateHint("pocket"))
        assertNull(NoteIndex.privateHint("re"))
        assertNull(NoteIndex.privateHint("kitchen"))
    }
}
