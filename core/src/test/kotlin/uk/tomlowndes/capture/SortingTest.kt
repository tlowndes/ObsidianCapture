package uk.tomlowndes.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate

class SortingTest {
    @get:Rule val tmp = TemporaryFolder()

    private val answer = """
        {"tasks":[{"text":"Call bins company","tag":"home","due":"2026-10-25"},
                  {"text":"Book boiler service","tag":"#Home","due":"2026-10-29"},
                  {"text":"  ","tag":null,"due":null},
                  {"text":"Look at notes","tag":null,"due":"Thursday"}],
         "ideas":["Look again at the Monzo role"],
         "note":"Boiler and bins sorted, Monzo role is a maybe."}
    """.trimIndent()

    @Test fun `the answer is read into tasks, ideas and a note`() {
        val r = Sorting.parse(answer)
        assertEquals(3, r.tasks.size)
        assertEquals(SortedTask("Call bins company", "#home", LocalDate.of(2026, 10, 25)), r.tasks[0])
        assertEquals("#home", r.tasks[1].tag)
        assertNull(r.tasks[2].due) // a date that isn't a date is dropped
        assertEquals(listOf("Look again at the Monzo role"), r.ideas)
        assertEquals("Boiler and bins sorted, Monzo role is a maybe.", r.note)
    }

    @Test fun `an empty answer is empty`() {
        assertTrue(Sorting.parse("""{"tasks":[],"ideas":[],"note":null}""").isEmpty)
    }

    @Test fun `a result survives the round trip through JSON`() {
        val r = Sorting.parse(answer)
        assertEquals(r, Sorting.fromJson(Sorting.toJson(r)))
        val n = r.copy(noteTitle = "Portfolio rethink", note = "## Case studies\n- Shorter", transcript = "so the case studies")
        assertEquals(n, Sorting.fromJson(Sorting.toJson(n)))
        assertNull(Sorting.fromJson("broken"))
        assertNull(Sorting.fromJson("""{"tasks":[],"ideas":[],"note":null}"""))
    }

    @Test fun `a tidied note is read with a safe title`() {
        val r = Sorting.parse("""{"tasks":[],"ideas":[],"note":{"title":"Plans: 2027 / [draft]?","markdown":"Lead with the outcome."},"transcript":"um lead with"}""")
        assertEquals("Plans 2027 draft", r.noteTitle)
        assertEquals("Lead with the outcome.", r.note)
        assertEquals("um lead with", r.transcript)
        assertNull(Sorting.parse("""{"tasks":[],"ideas":[],"note":{"title":"Empty","markdown":" "}}""").noteTitle)
    }

    @Test fun `a tidied note is saved as its own file with the transcript folded`() {
        val (w, ideas) = stores()
        val r = SortResult(emptyList(), emptyList(), "Short summary.\n\n## Case studies\n- Too long", "Portfolio rethink", "right so\nthe case studies")
        Sorting.save(r, w, ideas, LocalDate.of(2026, 11, 7))
        Sorting.save(r, w, ideas, LocalDate.of(2026, 11, 7))
        val f = File(tmp.root, "01 - Inbox/Portfolio rethink.md")
        assertEquals(
            "---\ncreated: 2026-11-07\nsource: tower\n---\n\nShort summary.\n\n## Case studies\n- Too long\n\n> [!quote]- Transcript\n> right so\n> the case studies\n",
            f.readText()
        )
        assertFalse(File(tmp.root, "01 - Inbox/Portfolio rethink (1).md").exists())
    }

    @Test fun `saving writes tasks, an idea project and an inbox note`() {
        val v = FileVault(tmp.root)
        val prefs = object : VaultPrefs {
            override val ideasDir = "03 - Projects"
            override val imagesDir = "99 - Meta/Assets"
        }
        val w = VaultWriter(v)
        val ideas = IdeaStore(v, prefs)
        Sorting.save(Sorting.parse(answer), w, ideas, LocalDate.of(2026, 10, 25))

        val tasks = w.listQuickTasks()
        assertEquals(listOf("Call bins company #home", "Book boiler service #home", "Look at notes"), tasks.map { it.text })
        assertEquals(LocalDate.of(2026, 10, 29), tasks[1].due)
        val idea = ideas.list().single()
        assertEquals("Look again at the Monzo role", idea.title)
        assertEquals("idea", idea.type)
        assertTrue(File(tmp.root, "01 - Inbox/Capture 2026-10-25.md").readText().contains("Monzo role is a maybe"))
    }

    @Test fun `saving the same result again does not add a second copy`() {
        val (w, ideas) = stores()
        val r = Sorting.parse(answer)
        Sorting.save(r, w, ideas, LocalDate.of(2026, 10, 25))
        Sorting.save(r, w, ideas, LocalDate.of(2026, 10, 25))
        assertEquals(3, w.listQuickTasks().size)
        assertEquals(1, ideas.list().size)
        val notes = File(tmp.root, "01 - Inbox").list()!!.filter { it.startsWith("Capture") }
        assertEquals(listOf("Capture 2026-10-25.md"), notes)
    }

    @Test fun `an open task with the same wording is left as it is`() {
        val (w, ideas) = stores()
        w.addTask("Buy milk", null)
        val r = SortResult(listOf(SortedTask("Buy milk", null, null), SortedTask("Buy milk", null, null)), emptyList(), null)
        Sorting.save(r, w, ideas, LocalDate.of(2026, 10, 25))
        assertEquals(listOf("Buy milk", "Buy milk"), w.listQuickTasks().map { it.text })
    }

    @Test fun `a project that already exists is not created again or retagged`() {
        val (w, ideas) = stores()
        val title = ideas.create("Look again at the Monzo role", "already mine")
        ideas.setType(title, "concept")
        Sorting.save(Sorting.parse(answer), w, ideas, LocalDate.of(2026, 10, 25))
        assertEquals(listOf(title), ideas.list().map { it.title })
        assertEquals("concept", ideas.list().single().type)
        assertEquals("already mine", ideas.load(title).summary)
    }

    @Test fun `two identical tasks in one dump are both kept, and a retry still leaves two`() {
        val (w, ideas) = stores()
        val r = SortResult(listOf(SortedTask("Buy milk", null, null), SortedTask("Buy milk", null, null)), emptyList(), null)
        Sorting.save(r, w, ideas, LocalDate.of(2026, 10, 25))
        Sorting.save(r, w, ideas, LocalDate.of(2026, 10, 25))
        assertEquals(listOf("Buy milk", "Buy milk"), w.listQuickTasks().map { it.text })
    }

    @Test fun `a failure while saving the note rolls the tasks and the idea back`() {
        val vault = FlakyVault(FileVault(tmp.root)).apply { failNote = true }
        val (w, ideas) = stores(vault)
        try {
            Sorting.save(Sorting.parse(answer), w, ideas, LocalDate.of(2026, 10, 25))
            fail("save should have failed")
        } catch (e: SortSaveException) {
            assertTrue(e.message!!.contains("Nothing was written"))
            assertTrue(e.message!!.contains("disk full"))
        }
        assertEquals(emptyList<TaskItem>(), w.listQuickTasks())
        assertEquals(emptyList<IdeaSummary>(), ideas.list())
        assertFalse(File(tmp.root, "01 - Inbox/Capture 2026-10-25.md").exists())
    }

    @Test fun `when the rollback can't finish, a retry doesn't add the tasks or the idea again`() {
        val vault = FlakyVault(FileVault(tmp.root)).apply { failNote = true; stickRollback = true }
        val (w, ideas) = stores(vault)
        try {
            Sorting.save(Sorting.parse(answer), w, ideas, LocalDate.of(2026, 10, 25))
            fail("save should have failed")
        } catch (e: SortSaveException) {
            assertTrue(e.message!!.contains("won't be added twice"))
        }
        val tasks = w.listQuickTasks().map { it.text }
        assertEquals(listOf("Call bins company #home", "Book boiler service #home", "Look at notes"), tasks)
        assertEquals(1, ideas.list().size)

        vault.failNote = false
        Sorting.save(Sorting.parse(answer), w, ideas, LocalDate.of(2026, 10, 25))
        assertEquals(tasks, w.listQuickTasks().map { it.text })
        assertEquals(1, ideas.list().size)
        val notes = File(tmp.root, "01 - Inbox").list()!!.filter { it.startsWith("Capture") }
        assertEquals(listOf("Capture 2026-10-25.md"), notes)
    }

    @Test fun `a log write that fails leaves nothing, and the retry writes one copy`() {
        val vault = FlakyVault(FileVault(tmp.root)).apply { failIdeaLog = true }
        val (w, ideas) = stores(vault)
        try {
            Sorting.save(Sorting.parse(answer), w, ideas, LocalDate.of(2026, 10, 25))
            fail("save should have failed")
        } catch (e: SortSaveException) {
            assertTrue(e.message!!.contains("Nothing was written"))
        }
        assertEquals(emptyList<TaskItem>(), w.listQuickTasks())
        assertFalse(File(tmp.root, "03 - Projects/Look again at the Monzo role").exists())

        vault.failIdeaLog = false
        Sorting.save(Sorting.parse(answer), w, ideas, LocalDate.of(2026, 10, 25))
        assertEquals(3, w.listQuickTasks().size)
        assertEquals(1, ideas.list().size)
    }

    @Test fun `an idea left half-written is finished on retry instead of creating another`() {
        val (w, ideas) = stores()
        val title = "Look again at the Monzo role"
        val folder = File(tmp.root, "03 - Projects/$title")
        folder.mkdirs()
        File(folder, "$title.md").writeText(IdeaNote.newNote(title, "", LocalDate.of(2026, 10, 25)))
        File(folder, "$title - Log.md").writeText("")
        Sorting.save(Sorting.parse(answer), w, ideas, LocalDate.of(2026, 10, 25))
        assertTrue(File(folder, "$title - Log.md").readText().contains("idea-log"))
        assertEquals(1, ideas.list().size)
        assertEquals("idea", ideas.list().single().type)
        assertEquals(3, w.listQuickTasks().size)
    }

    private fun stores(vault: Vault = FileVault(tmp.root)): Pair<VaultWriter, IdeaStore> {
        val prefs = object : VaultPrefs {
            override val ideasDir = "03 - Projects"
            override val imagesDir = "99 - Meta/Assets"
        }
        return VaultWriter(vault) to IdeaStore(vault, prefs)
    }

    /**
     * Fails chosen writes. Once the inbox note fails, later task rewrites and deletes fail too,
     * which is the cleanup-couldn't-finish case.
     */
    private class FlakyVault(private val inner: FileVault) : Vault by inner {
        var failNote = false
        var failIdeaLog = false
        /** When the note write fails, later cleanup writes and deletes fail too. */
        var stickRollback = false
        private var blockRollback = false

        override fun writeOnce(path: String, text: String) {
            if (failNote && path.contains("Capture")) {
                if (stickRollback) blockRollback = true
                error("disk full")
            }
            if (blockRollback && path.contains("Quick Task")) error("cannot rewrite")
            if (failIdeaLog && path.contains(" - Log.md")) error("disk full")
            inner.writeOnce(path, text)
        }

        override fun delete(path: String) {
            if (blockRollback) error("cannot delete")
            inner.delete(path)
        }
    }
}
