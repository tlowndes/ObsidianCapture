package uk.tomlowndes.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test fun `the queue keeps raw text and sorted results`() {
        val q = listOf(QueuedDump(1, "raw one"), QueuedDump(2, "raw two", Sorting.parse(answer)))
        assertEquals(q, Sorting.queueFromJson(Sorting.queueToJson(q)))
        assertEquals(emptyList<QueuedDump>(), Sorting.queueFromJson("broken"))
    }

    @Test fun `the user message carries the date and tags`() {
        val m = Sorting.userMessage(" buy milk ", LocalDate.of(2026, 10, 25), listOf("#home"))
        assertTrue(m.startsWith("Today is Sunday 2026-10-25. Tags in use: #home."))
        assertTrue(m.endsWith("Brain dump:\nbuy milk"))
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
}
