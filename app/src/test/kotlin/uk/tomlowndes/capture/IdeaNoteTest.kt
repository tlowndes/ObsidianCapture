package uk.tomlowndes.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class IdeaNoteTest {
    private fun lines(s: String) = s.split("\n").toMutableList()
    private fun fresh() = lines(IdeaNote.newNote("Garden shed", "  Somewhere for the tools  ", LocalDate.parse("2026-10-12")))

    @Test fun `a new idea note has frontmatter, summary and an empty log`() {
        assertEquals(
            "---\ntype: idea\nstatus: idea\ncreated: 2026-10-12\ntags: []\n---\n\n# Garden shed\n\n## Summary\n\nSomewhere for the tools\n\n## Log\n\n",
            IdeaNote.newNote("Garden shed", "  Somewhere for the tools  ", LocalDate.parse("2026-10-12"))
        )
        val doc = IdeaNote.parse("Garden shed", fresh().joinToString("\n"))
        assertEquals("idea", doc.status)
        assertEquals("Somewhere for the tools", doc.summary)
        assertTrue(doc.entries.isEmpty())
    }

    @Test fun `entries are newest first with their photos`() {
        val ls = fresh()
        IdeaNote.addEntry(ls, "2026-10-12 09:00", "First thought", emptyList())
        IdeaNote.addEntry(ls, "2026-10-12 10:00", "Second\nline", listOf("shed-1.jpg", "shed-2.jpg"))
        val doc = IdeaNote.parse("Garden shed", ls.joinToString("\n"))
        assertEquals(listOf("2026-10-12 10:00", "2026-10-12 09:00"), doc.entries.map { it.heading })
        assertEquals("Second\nline", doc.entries[0].text)
        assertEquals(listOf("shed-1.jpg", "shed-2.jpg"), doc.entries[0].images)
        assertTrue(ls.contains("![[shed-1.jpg]]"))
        assertEquals("Somewhere for the tools", doc.summary)
    }

    @Test fun `photo embeds with a size still count as photos`() {
        val doc = IdeaNote.parse("X", "## Log\n\n### 2026-10-12 09:00\n\nText\n\n![[a.jpg|300]]\n")
        assertEquals(listOf("a.jpg"), doc.entries[0].images)
        assertEquals("Text", doc.entries[0].text)
    }

    @Test fun `a note missing its Log gets one`() {
        val ls = lines("# Bare\n\nNotes from Obsidian")
        IdeaNote.addEntry(ls, "2026-10-12 09:00", "Hello", emptyList())
        assertEquals("# Bare\n\nNotes from Obsidian\n\n## Log\n\n### 2026-10-12 09:00\n\nHello\n\n", ls.joinToString("\n"))
    }

    @Test fun `editing an entry keeps its photos and the others`() {
        val ls = fresh()
        IdeaNote.addEntry(ls, "2026-10-12 09:00", "First", emptyList())
        IdeaNote.addEntry(ls, "2026-10-12 10:00", "Second", listOf("p.jpg"))
        val second = IdeaNote.parse("G", ls.joinToString("\n")).entries[0]
        IdeaNote.editEntry(ls, second, "Second, edited")
        val doc = IdeaNote.parse("G", ls.joinToString("\n"))
        assertEquals(IdeaEntry("2026-10-12 10:00", "Second, edited", listOf("p.jpg")), doc.entries[0])
        assertEquals(IdeaEntry("2026-10-12 09:00", "First", emptyList()), doc.entries[1])
    }

    @Test fun `entries with the same time are told apart by their text`() {
        val ls = fresh()
        IdeaNote.addEntry(ls, "2026-10-12 09:00", "One", emptyList())
        IdeaNote.addEntry(ls, "2026-10-12 09:00", "Two", emptyList())
        IdeaNote.deleteEntry(ls, IdeaEntry("2026-10-12 09:00", "One", emptyList()))
        assertEquals(listOf("Two"), IdeaNote.parse("G", ls.joinToString("\n")).entries.map { it.text })
    }

    @Test fun `deleting removes only that entry`() {
        val ls = fresh()
        IdeaNote.addEntry(ls, "2026-10-12 09:00", "Keep", emptyList())
        val before = ls.toList()
        IdeaNote.addEntry(ls, "2026-10-12 10:00", "Drop", emptyList())
        IdeaNote.deleteEntry(ls, IdeaEntry("2026-10-12 10:00", "Drop", emptyList()))
        assertEquals(before, ls)
    }

    @Test fun `an entry changed or removed on another device is not overwritten`() {
        val ls = fresh()
        IdeaNote.addEntry(ls, "2026-10-12 09:00", "Changed on desktop", emptyList())
        val snapshot = ls.toList()
        assertThrows(IllegalStateException::class.java) {
            IdeaNote.editEntry(ls, IdeaEntry("2026-10-12 09:00", "Original", emptyList()), "Phone edit")
        }
        assertThrows(IllegalStateException::class.java) {
            IdeaNote.deleteEntry(ls, IdeaEntry("2026-10-11 09:00", "Gone", emptyList()))
        }
        assertEquals(snapshot, ls)
    }

    @Test fun `summary edits replace only the summary`() {
        val ls = fresh()
        IdeaNote.addEntry(ls, "2026-10-12 09:00", "Entry", emptyList())
        val before = ls.joinToString("\n")
        IdeaNote.setSummary(ls, "Somewhere for the tools", "A shed for tools\nand bikes")
        assertEquals(before.replace("Somewhere for the tools", "A shed for tools\nand bikes"), ls.joinToString("\n"))
    }

    @Test fun `summary changed elsewhere is not overwritten`() {
        val ls = fresh()
        assertThrows(IllegalStateException::class.java) { IdeaNote.setSummary(ls, "What the phone showed", "New") }
    }

    @Test fun `a note missing its Summary gets one above the Log`() {
        val ls = lines("# T\n\n## Log\n")
        IdeaNote.setSummary(ls, "", "Hi")
        assertEquals("# T\n\n## Summary\n\nHi\n\n## Log\n", ls.joinToString("\n"))
    }

    @Test fun `status lives in frontmatter`() {
        val ls = fresh()
        Md.fmSet(ls, "status", "doing")
        assertEquals("doing", IdeaNote.parse("G", ls.joinToString("\n")).status)
        assertEquals("idea", IdeaNote.parse("G", "# No frontmatter").status)
    }

    @Test fun `titles are made safe for file names`() {
        assertEquals("What-if- a -shed-", IdeaNote.safeName("What/if: a #shed?"))
        assertEquals("Spaces squashed", IdeaNote.safeName("  Spaces \n  squashed "))
        assertEquals(80, IdeaNote.safeName("x".repeat(200)).length)
    }
}
