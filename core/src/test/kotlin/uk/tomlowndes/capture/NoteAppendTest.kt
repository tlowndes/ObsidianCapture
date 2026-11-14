package uk.tomlowndes.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate

class NoteAppendTest {
    @get:Rule val tmp = TemporaryFolder()
    private val day = LocalDate.of(2026, 11, 14)

    private fun note(vararg l: String) = l.toMutableList()

    @Test fun `the Log heading gets the newest line first`() {
        val n = note("---", "type: project", "---", "## Summary", "Garage.", "", "## Log", "- 2026-11-09 Cleared the back wall", "", "## Links")
        val added = NoteAppend.insert(n, "## Log", "Ordered the shelving", day)
        assertEquals(listOf("- 2026-11-14 Ordered the shelving"), added)
        assertEquals(listOf("## Log", "- 2026-11-14 Ordered the shelving", "- 2026-11-09 Cleared the back wall"), n.subList(6, 9))
    }

    @Test fun `another heading gets the line at the end of its section`() {
        val n = note("## Next step", "- [ ] Buy filler", "", "## Links", "- a")
        NoteAppend.insert(n, "Next step", "Check the quote", day)
        assertEquals(listOf("## Next step", "- [ ] Buy filler", "- 2026-11-14 Check the quote", "", "## Links", "- a"), n)
    }

    @Test fun `no heading puts the line at the end and keeps one trailing newline`() {
        val n = note("Some text", "", "")
        NoteAppend.insert(n, null, "More", day)
        assertEquals(listOf("Some text", "- 2026-11-14 More", ""), n)
    }

    @Test fun `a heading that is missing is added at the end`() {
        val n = note("# Garage", "text", "")
        NoteAppend.insert(n, "## Log", "First", day)
        assertEquals(listOf("# Garage", "text", "", "## Log", "- 2026-11-14 First", ""), n)
    }

    @Test fun `headings in code and frontmatter are ignored`() {
        val n = note("---", "# not a heading", "---", "## Real", "```", "## Fake", "```")
        assertEquals(listOf("## Real"), NoteAppend.headings(n))
    }

    @Test fun `a retry does not add the same line twice and undo takes it out`() {
        val n = note("## Log", "- old")
        val added = NoteAppend.insert(n, "Log", "Same", day)!!
        assertNull(NoteAppend.insert(n, "Log", "Same", day))
        assertEquals(3, n.size)
        assertTrue(NoteAppend.remove(n, added))
        assertEquals(listOf("## Log", "- old"), n)
        assertFalse(NoteAppend.remove(n, added))
    }

    @Test fun `several lines are indented under the bullet`() {
        assertEquals(listOf("- 2026-11-14 First", "  second", "  - third"), NoteAppend.entry("First\nsecond\n- third\n\n", day))
    }

    @Test fun `appending to a real note writes only that line`() {
        File(tmp.root, "03 - Projects/Garage").mkdirs()
        val f = File(tmp.root, "03 - Projects/Garage/Garage.md")
        f.writeText("---\ntype: project\n---\n## Log\n- 2026-11-09 Cleared the back wall\n")
        val w = VaultWriter(FileVault(tmp.root))
        val added = w.appendToNote("03 - Projects/Garage/Garage.md", "## Log", "Ordered shelving", day)!!
        assertEquals("---\ntype: project\n---\n## Log\n- 2026-11-14 Ordered shelving\n- 2026-11-09 Cleared the back wall\n", f.readText())
        w.removeFromNote("03 - Projects/Garage/Garage.md", added)
        assertEquals("---\ntype: project\n---\n## Log\n- 2026-11-09 Cleared the back wall\n", f.readText())
    }

    @Test fun `a missing note is refused`() {
        val w = VaultWriter(FileVault(tmp.root))
        try { w.appendToNote("nope.md", null, "x", day); throw AssertionError("should refuse") } catch (e: IllegalStateException) { }
    }

    @Test fun `saving a result with addTo adds to the note and keeps the JSON round trip`() {
        File(tmp.root, "A").mkdirs()
        val f = File(tmp.root, "A/Garage.md"); f.writeText("## Log\n")
        val prefs = object : VaultPrefs { override val ideasDir = "03 - Projects"; override val imagesDir = "99 - Meta/Assets" }
        val v = FileVault(tmp.root)
        val r = SortResult(emptyList(), emptyList(), "Shelves ordered", addTo = NoteTarget("Garage", "A/Garage.md", "Log"))
        Sorting.save(r, VaultWriter(v), IdeaStore(v, prefs), day)
        assertEquals("## Log\n- 2026-11-14 Shelves ordered\n", f.readText())
        assertEquals(r, Sorting.fromJson(Sorting.toJson(r)))
        val unresolved = r.copy(addTo = NoteTarget("Garage"))
        try { Sorting.save(unresolved, VaultWriter(v), IdeaStore(v, prefs), day); throw AssertionError("should ask") } catch (e: SortSaveException) { }
    }

    @Test fun `a new note carries its tags and source tower and titles come from the first sentence`() {
        val r = SortResult(emptyList(), emptyList(), "Body", "T", noteTags = listOf("personal", "garage"))
        assertEquals("---\ncreated: 2026-11-14\nsource: tower\ntags:\n  - personal\n  - garage\n---\n\nBody\n", Sorting.noteBody(r, day))
        assertEquals("Clean and degrease it, fill cracks", Sorting.titleFrom("Clean and degrease it, fill cracks with epoxy filler, then roll. Do it above 10°C."))
        assertEquals("Note 2026-11-14", Sorting.titleFrom("  ", day))
        assertEquals(r, Sorting.fromJson(Sorting.toJson(r)))
    }

    @Test fun `a note name is matched to a title, projects first`() {
        val all = listOf(
            NoteInfo("05 - Resources/Home/Garage door quotes.md", 1), NoteInfo("03 - Projects/Garage/Garage.md", 2),
            NoteInfo("05 - Resources/Garage.md", 3), NoteInfo("99 - Meta/Garage notes.md", 4), NoteInfo("03 - Projects/Garden/Garden plan.md", 5),
        )
        assertEquals("03 - Projects/Garage/Garage.md", NoteAppend.find(all, "garage")?.path)
        assertEquals("05 - Resources/Home/Garage door quotes.md", NoteAppend.find(all, "garage door")?.path)
        assertEquals("03 - Projects/Garden/Garden plan.md", NoteAppend.find(all, "[[Garden plan]]")?.path)
        assertEquals("03 - Projects/Garden/Garden plan.md", NoteAppend.find(all, "plan garden")?.path)
        assertNull(NoteAppend.find(all, "kitchen"))
        assertNull(NoteAppend.find(all, " "))
    }
}
