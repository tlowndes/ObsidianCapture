package uk.tomlowndes.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The stores working on a plain folder, as the desktop app uses them. */
class FileVaultTest {
    @get:Rule val tmp = TemporaryFolder()

    private val prefs = object : VaultPrefs {
        override val ideasDir = "03 - Projects"
        override val imagesDir = "99 - Meta/Assets"
    }

    private fun vault() = FileVault(tmp.root)
    private fun file(path: String) = File(tmp.root, path)

    @Test fun `a taken name gets a free one`() {
        val v = vault()
        assertEquals("a/Note.md", v.create("a", "Note", "md"))
        assertEquals("a/Note (1).md", v.create("a", "Note", "md"))
        assertTrue(file("a/Note (1).md").isFile)
    }

    @Test fun `writes leave no temporary file behind`() {
        val v = vault()
        val p = v.create("", "Note", "md")
        assertEquals("hello\n", VaultIo.write(v, p, "hello\n"))
        assertEquals(listOf("Note.md"), tmp.root.list()!!.toList())
    }

    @Test fun `quick tasks are added, listed and ticked`() {
        val w = VaultWriter(vault())
        w.addTask("Book MOT", "2026-10-17")
        val t = w.listQuickTasks().single()
        assertEquals("Book MOT", t.text)
        w.completeTask(t)
        assertEquals(emptyList<TaskItem>(), w.listQuickTasks())
        assertTrue(file(VaultTasks.QUICK_TASK).readText().contains("- [x] #task Book MOT"))
    }

    @Test fun `tasks in other notes are found, skipping dot folders`() {
        file("Projects/House.md").apply { parentFile.mkdirs(); writeText("# House\n- [ ] #task Paint hall\n") }
        file(".trash/Old.md").apply { parentFile.mkdirs(); writeText("- [ ] #task Gone\n") }
        val tasks = VaultWriter(vault(), File(tmp.root, "cache.json")).listTasks()
        assertEquals(listOf("Paint hall"), tasks.map { it.text })
        assertEquals("Projects/House.md", tasks.single().path)
    }

    @Test fun `archiving moves an article and unarchiving brings it back`() {
        val v = vault()
        VaultWriter(v).addNote("Story", ReadLater.note("https://example.com", "Story", null, "2026-10-15T10:00"))
        val store = ReadStore(v)
        val a = store.list().single()
        val archived = store.archive(a.ref)
        assertTrue(archived.archived)
        assertTrue(file("01 - Inbox/ReadItLater Inbox/Archive/Story.md").isFile)
        assertFalse(file("01 - Inbox/ReadItLater Inbox/Story.md").exists())
        store.unarchive(archived)
        assertTrue(store.list().single().read)
    }

    @Test fun `ideas are created and logged`() {
        val store = IdeaStore(vault(), prefs)
        val title = store.create("Garage layout", "Pegboard wall")
        store.addEntry(title, "Measured the wall", listOf("wall.jpg"))
        val doc = store.load(title)
        assertEquals("Measured the wall", doc.entries.single().text)
        assertEquals(1, store.list().single().images)
        val (name, handle) = store.saveImage(byteArrayOf(1, 2, 3), "wall")
        assertEquals("wall.jpg", name)
        assertTrue(store.imageIndex()["wall.jpg"] == handle)
    }
}
