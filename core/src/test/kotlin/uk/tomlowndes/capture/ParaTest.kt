package uk.tomlowndes.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ParaTest {
    @Test fun `folders are found by name`() {
        val f = Para.folders(listOf("00 - MOC", "01 - Inbox", "02 - Areas", "03 - Projects", "99 - Meta", "05 - Archive"))
        assertEquals("03 - Projects", f[ParaKind.PROJECTS])
        assertEquals("02 - Areas", f[ParaKind.AREAS])
        assertNull(f[ParaKind.RESOURCES])
        assertEquals("05 - Archive", f[ParaKind.ARCHIVE])
    }

    @Test fun `quiet projects have had no edits for three weeks`() {
        val day = 86_400_000L
        val now = 100 * day
        val notes = listOf(
            NoteInfo("03 - Projects/NudgePet/NudgePet.md", now - 30 * day),
            NoteInfo("03 - Projects/NudgePet/Log.md", now - 25 * day),
            NoteInfo("03 - Projects/Portfolio/Portfolio.md", now - 2 * day),
            NoteInfo("03 - Projects/Portfolio/Old.md", now - 60 * day),
            NoteInfo("03 - Projects/Loose note.md", now - 90 * day),
            NoteInfo("02 - Areas/Health/Health.md", now - 90 * day),
        )
        assertEquals(listOf("03 - Projects/NudgePet" to now - 25 * day), Para.quietProjects(notes, "03 - Projects", now))
    }
}
