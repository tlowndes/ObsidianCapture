package uk.tomlowndes.capture

import org.junit.Assert.assertEquals
import org.junit.Test

/** Quick search: finding a note, then adding a task or a line to it. */
class NoteSearchTest {
    private data class N(val path: String, val modified: Long)

    private val notes = listOf(
        N("03 - Projects/House move/House move.md", 30),
        N("03 - Projects/House move/Removal quotes.md", 20),
        N("Areas/Home/Garden.md", 50),
        N("Daily/2026-10-12.md", 40),
        N("Movies to watch.md", 10)
    )

    private fun find(q: String) = NoteSearch.match(notes, q, 50, { it.path }, { it.modified }).map { it.path }

    @Test fun `every word must appear, name matches come first`() {
        assertEquals(
            listOf("03 - Projects/House move/House move.md", "03 - Projects/House move/Removal quotes.md"),
            find("house MOVE")
        )
        assertEquals(listOf("03 - Projects/House move/Removal quotes.md"), find("move quotes"))
        assertEquals(emptyList<String>(), find("house garden"))
    }

    @Test fun `names starting with the query beat names containing it`() {
        assertEquals("Movies to watch.md", find("mov").first())
    }

    @Test fun `blank query lists recently changed notes`() {
        assertEquals(listOf("Areas/Home/Garden.md", "Daily/2026-10-12.md"), NoteSearch.match(notes, " ", 2, { it.path }, { it.modified }).map { it.path })
    }

    @Test fun `folder of a note`() {
        assertEquals("Areas/Home", NoteSearch.folder("Areas/Home/Garden.md"))
        assertEquals("", NoteSearch.folder("Movies to watch.md"))
    }

    private fun lines(s: String) = s.split("\n").toMutableList()
    private fun text(ls: List<String>) = ls.joinToString("\n")
    private val task = "- [ ] #task Water tomatoes 📅 2026-10-13"

    @Test fun `task goes at the end of the Tasks list`() {
        val ls = lines("# Garden\n\n## Tasks\n\n- [ ] #task Mow lawn\n\n## Notes\nSoil is clay.\n")
        NoteSearch.addTask(ls, task)
        assertEquals("# Garden\n\n## Tasks\n\n- [ ] #task Mow lawn\n$task\n\n## Notes\nSoil is clay.\n", text(ls))
    }

    @Test fun `an empty Tasks section keeps a blank line under the heading`() {
        val ls = lines("## ✅ Tasks\n## Notes\n")
        NoteSearch.addTask(ls, task)
        assertEquals("## ✅ Tasks\n\n$task\n## Notes\n", text(ls))
    }

    @Test fun `without a Tasks section the task goes at the end`() {
        val ls = lines("---\ntags: [garden]\n---\nSoil is clay.\n\n\n")
        NoteSearch.addTask(ls, task)
        assertEquals("---\ntags: [garden]\n---\nSoil is clay.\n$task\n", text(ls))
    }

    @Test fun `a task after a list joins the list`() {
        val ls = lines("- [ ] #task Mow lawn\n")
        NoteSearch.addTask(ls, task)
        assertEquals("- [ ] #task Mow lawn\n$task\n", text(ls))
    }

    @Test fun `a plain line after a list or heading gets a gap`() {
        val ls = lines("- bulbs\n- seeds")
        NoteSearch.addLine(ls, "Order compost.")
        assertEquals("- bulbs\n- seeds\n\nOrder compost.\n", text(ls))
        val h = lines("## Notes\n")
        NoteSearch.addLine(h, "First note.")
        assertEquals("## Notes\n\nFirst note.\n", text(h))
    }

    @Test fun `a line in an empty note`() {
        val ls = lines("")
        NoteSearch.addLine(ls, "Hello")
        assertEquals("Hello\n", text(ls))
    }
}
