package uk.tomlowndes.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MdTest {
    private fun lines(s: String) = s.split("\n").toMutableList()

    private val note = """
        ---
        status: unread
        tags: [a, b]
        ---

        # Title

        ## 🎯 Do this first

        - thing

        ## Wind down
        - **Went well:** yes
    """.trimIndent()

    @Test fun `reads frontmatter values`() {
        assertEquals("unread", Md.fmGet(lines(note), "status"))
        assertEquals("[a, b]", Md.fmGet(lines(note), "tags"))
        assertNull(Md.fmGet(lines(note), "missing"))
        assertNull(Md.fmGet(lines("# No frontmatter\nstatus: read"), "status"))
    }

    @Test fun `changing a key leaves everything else alone`() {
        val ls = lines(note)
        Md.fmSet(ls, "status", "read")
        assertEquals(note.replace("status: unread", "status: read"), ls.joinToString("\n"))
    }

    @Test fun `a missing key is added at the end of the frontmatter`() {
        val ls = lines(note)
        Md.fmSet(ls, "saved", "2026-10-12T09:30")
        assertEquals(note.replace("tags: [a, b]\n", "tags: [a, b]\nsaved: 2026-10-12T09:30\n"), ls.joinToString("\n"))
    }

    @Test fun `a note without frontmatter gets a block`() {
        val ls = lines("# Hello\n\nBody")
        Md.fmSet(ls, "status", "read")
        assertEquals("---\nstatus: read\n---\n\n# Hello\n\nBody", ls.joinToString("\n"))
    }

    @Test fun `similar keys are not confused`() {
        val ls = lines("---\nstatus_old: x\nstatus: unread\n---")
        Md.fmSet(ls, "status", "read")
        assertEquals("---\nstatus_old: x\nstatus: read\n---", ls.joinToString("\n"))
    }

    @Test fun `body skips the frontmatter`() {
        assertEquals("", Md.fmBody(lines(note)).first())
        assertEquals("# Title", Md.fmBody(lines(note))[1])
        assertEquals(lines("# Plain"), Md.fmBody(lines("# Plain")))
    }

    @Test fun `headings match without emoji or case`() {
        val ls = lines(note)
        assertEquals(7, Md.headingIndex(ls, "Do this first"))
        assertEquals(7, Md.headingIndex(ls, "do THIS first"))
        assertEquals(-1, Md.headingIndex(ls, "Top 3"))
    }

    @Test fun `section runs to the next level-two heading`() {
        val ls = lines(note)
        assertEquals(8..10, Md.sectionRange(ls, "Do this first"))
        assertEquals(12..12, Md.sectionRange(ls, "Wind down"))
        assertNull(Md.sectionRange(ls, "Nope"))
    }
}
