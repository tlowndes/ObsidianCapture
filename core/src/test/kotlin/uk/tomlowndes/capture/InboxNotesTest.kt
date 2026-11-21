package uk.tomlowndes.capture

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class InboxNotesTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun note(path: String, text: String = "x", at: Long = 0) =
        File(tmp.root, path).apply { parentFile.mkdirs(); writeText(text); if (at > 0) setLastModified(at) }

    @Test fun `lists loose inbox notes, newest first, without the app's own`() {
        note("01 - Inbox/⚡ Quick Task.md")
        note("01 - Inbox/Inbox.md")
        note("01 - Inbox/ReadItLater Inbox/Article.md")
        note("01 - Inbox/Old idea.md", at = 1_000_000_000_000)
        note("01 - Inbox/Capture 2026-11-19.md", at = 1_700_000_000_000)
        note("01 - Inbox/picture.png")
        val v = FileVault(tmp.root)
        assertEquals(listOf("01 - Inbox/Capture 2026-11-19.md", "01 - Inbox/Old idea.md"), InboxNotes.list(v))
    }

    @Test fun `a ticked note stops showing and the config keeps other keys`() {
        note("01 - Inbox/Old idea.md")
        note("99 - Meta/capture-config.json", "{\"plannedOn\":\"2026-11-21\"}")
        val v = FileVault(tmp.root)
        InboxNotes.markSeen(v, "01 - Inbox/Old idea.md")
        assertEquals(emptyList<String>(), InboxNotes.list(v))
        assertEquals(true, File(tmp.root, "01 - Inbox/Old idea.md").exists())
        assertEquals("2026-11-21", org.json.JSONObject(v.read(DeckConfig.PATH)!!).getString("plannedOn"))
    }

    @Test fun `filing moves the note into the project folder`() {
        note("01 - Inbox/Interview ideas.md", "hello")
        note("03 - Projects/Job hunt/Job hunt.md")
        val v = FileVault(tmp.root)
        val to = InboxNotes.file(v, "01 - Inbox/Interview ideas.md", "03 - Projects/Job hunt")
        assertEquals("hello", v.read(to))
        assertEquals(false, File(tmp.root, "01 - Inbox/Interview ideas.md").exists())
        assertEquals("Interview ideas", InboxNotes.title(to))
    }
}
