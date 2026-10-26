package uk.tomlowndes.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class IdeaNoteTest {
    private fun lines(s: String) = s.split("\n").toMutableList()
    private fun fresh() = lines(IdeaNote.newNote("Garden shed", "  Somewhere for the tools  ", LocalDate.parse("2026-10-12")))
    private fun log() = lines(IdeaNote.newLog("Garden shed"))
    private fun legacy() = lines("---\nstatus: idea\n---\n\n# Garden shed\n\n## Summary\n\nTools\n\n## Log\n\n" +
        "### 2026-10-12 10:00\n\nSecond\n\n![[p.jpg]]\n\n### 2026-10-12 09:00\n\nFirst\n\n## Links\n\nKeep me\n")

    @Test fun `a new idea note has frontmatter, summary and a link to its log file`() {
        assertEquals(
            "---\ntype: idea\nstatus: idea\ncreated: 2026-10-12\ntags: []\n---\n\n# Garden shed\n\n## Summary\n\nSomewhere for the tools\n\n## Log\n\n[[Garden shed - Log]]\n",
            IdeaNote.newNote("Garden shed", "  Somewhere for the tools  ", LocalDate.parse("2026-10-12"))
        )
        val doc = IdeaNote.parse("Garden shed", fresh().joinToString("\n"))
        assertEquals("idea", doc.status)
        assertEquals("Somewhere for the tools", doc.summary)
        assertTrue(doc.entries.isEmpty())
    }

    @Test fun `entries are newest first with their photos`() {
        val ls = log()
        IdeaNote.addEntry(ls, "2026-10-12 09:00", "First thought", emptyList())
        IdeaNote.addEntry(ls, "2026-10-12 10:00", "Second\nline", listOf("shed-1.jpg", "shed-2.jpg"))
        val doc = IdeaNote.parse("Garden shed", ls.joinToString("\n"))
        assertEquals(listOf("2026-10-12 10:00", "2026-10-12 09:00"), doc.entries.map { it.heading })
        assertEquals("Second\nline", doc.entries[0].text)
        assertEquals(listOf("shed-1.jpg", "shed-2.jpg"), doc.entries[0].images)
        assertTrue(ls.contains("![[shed-1.jpg]]"))
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
        val ls = log()
        IdeaNote.addEntry(ls, "2026-10-12 09:00", "First", emptyList())
        IdeaNote.addEntry(ls, "2026-10-12 10:00", "Second", listOf("p.jpg"))
        val second = IdeaNote.parse("G", ls.joinToString("\n")).entries[0]
        IdeaNote.editEntry(ls, second, "Second, edited")
        val doc = IdeaNote.parse("G", ls.joinToString("\n"))
        assertEquals(IdeaEntry("2026-10-12 10:00", "Second, edited", listOf("p.jpg")), doc.entries[0])
        assertEquals(IdeaEntry("2026-10-12 09:00", "First", emptyList()), doc.entries[1])
    }

    @Test fun `entries with the same time are told apart by their text`() {
        val ls = log()
        IdeaNote.addEntry(ls, "2026-10-12 09:00", "One", emptyList())
        IdeaNote.addEntry(ls, "2026-10-12 09:00", "Two", emptyList())
        IdeaNote.deleteEntry(ls, IdeaEntry("2026-10-12 09:00", "One", emptyList()))
        assertEquals(listOf("Two"), IdeaNote.parse("G", ls.joinToString("\n")).entries.map { it.text })
    }

    @Test fun `deleting removes only that entry`() {
        val ls = log()
        IdeaNote.addEntry(ls, "2026-10-12 09:00", "Keep", emptyList())
        val before = ls.toList()
        IdeaNote.addEntry(ls, "2026-10-12 10:00", "Drop", emptyList())
        IdeaNote.deleteEntry(ls, IdeaEntry("2026-10-12 10:00", "Drop", emptyList()))
        assertEquals(before, ls)
    }

    @Test fun `an entry changed or removed on another device is not overwritten`() {
        val ls = log()
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

    @Test fun `a new log file holds entries newest first`() {
        assertEquals("---\ntype: idea-log\nidea: \"[[Garden shed]]\"\n---\n\n# Garden shed - Log\n\n## Log\n\n", IdeaNote.newLog("Garden shed"))
        val ls = lines(IdeaNote.newLog("Garden shed"))
        IdeaNote.addEntry(ls, "2026-10-12 09:00", "First", emptyList())
        IdeaNote.addEntry(ls, "2026-10-12 10:00", "Second", listOf("p.jpg"))
        val doc = IdeaNote.parse("Garden shed", ls.joinToString("\n"))
        assertEquals(listOf("2026-10-12 10:00", "2026-10-12 09:00"), doc.entries.map { it.heading })
        assertEquals(listOf("p.jpg"), doc.entries[0].images)
    }

    @Test fun `a new idea note has no entries of its own`() {
        assertTrue(IdeaNote.logEntries(fresh()).isEmpty())
        assertTrue(IdeaNote.parse("Garden shed", fresh().joinToString("\n")).entries.isEmpty())
    }

    @Test fun `an older idea's entries move to the log file and leave a link`() {
        val note = legacy()
        val moved = IdeaNote.logEntries(note)
        val log = lines(IdeaNote.newLog("Garden shed", moved))
        val before = IdeaNote.parse("Garden shed", note.joinToString("\n")).entries
        IdeaNote.moveLogOut(note, "Garden shed", moved)
        assertEquals(before, IdeaNote.parse("Garden shed", log.joinToString("\n")).entries)
        assertEquals(
            "---\nstatus: idea\n---\n\n# Garden shed\n\n## Summary\n\nTools\n\n## Log\n\n[[Garden shed - Log]]\n\n## Links\n\nKeep me\n",
            note.joinToString("\n")
        )
        // New entries then go on top of the moved ones.
        IdeaNote.addEntry(log, "2026-10-13 08:00", "Third", emptyList())
        assertEquals(listOf("Third", "Second", "First"), IdeaNote.parse("G", log.joinToString("\n")).entries.map { it.text })
    }

    @Test fun `an idea with an empty log just gets the link`() {
        val ls = lines("# T\n\n## Summary\n\nHi\n\n## Log\n\n")
        IdeaNote.moveLogOut(ls, "T", emptyList())
        assertEquals("# T\n\n## Summary\n\nHi\n\n## Log\n\n[[T - Log]]\n\n", ls.joinToString("\n"))
        val bare = lines("# T\n")
        IdeaNote.moveLogOut(bare, "T", emptyList())
        assertEquals("# T\n\n\n## Log\n\n[[T - Log]]\n", bare.joinToString("\n"))
        val linked = fresh()
        IdeaNote.moveLogOut(linked, "Garden shed", emptyList())
        assertEquals(fresh(), linked)
    }

    @Test fun `entries changed while moving are not lost`() {
        val note = legacy()
        val moved = IdeaNote.logEntries(note)
        IdeaNote.addEntry(note, "2026-10-12 11:00", "Added on desktop", emptyList())
        val snapshot = note.toList()
        assertThrows(IllegalStateException::class.java) { IdeaNote.moveLogOut(note, "Garden shed", moved) }
        assertEquals(snapshot, note)
    }

    @Test fun `ticking the next step marks the first open checkbox done in place`() {
        val ls = lines("---\nstatus: active\n---\n\n## Next step\n\n- [x] Old ✅ 2026-10-01\n- [ ] Test the build\n- [ ] Later thing\n")
        IdeaNote.tickNext(ls, LocalDate.parse("2026-10-26"))
        assertEquals("- [x] Test the build ✅ 2026-10-26", ls[7])
        assertEquals("- [ ] Later thing", ls[8])
        assertEquals("Later thing", IdeaNote.parse("X", ls.joinToString("\n")).next)
    }

    @Test fun `ticking with no next step says so`() {
        assertThrows(IllegalStateException::class.java) { IdeaNote.tickNext(lines("## Summary\n\nNothing\n"), LocalDate.parse("2026-10-26")) }
    }

    @Test fun `links come from the Links section in order`() {
        val note = "## Summary\n\nSee https://ignored.example\n\n## Links\n\n- [Repo](https://github.com/tlowndes/ObsidianCapture)\n" +
            "- [[Capture app changes]]\n- [[Docs/Vault summary|Summary]]\n- https://tomlowndes.co.uk/work\n\n## Files\n"
        val links = IdeaNote.links(note)
        assertEquals(listOf("Repo", "Capture app changes", "Summary", "tomlowndes.co.uk/work"), links.map { it.label })
        assertEquals(listOf(true, false, false, true), links.map { it.web })
        assertEquals("Docs/Vault summary", links[2].target)
        assertTrue(IdeaNote.links("## Summary\n\nNo links\n").isEmpty())
    }
}
