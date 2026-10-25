package uk.tomlowndes.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectTypeTest {
    private fun doc(fm: String) = IdeaNote.parse("P", "---\n$fm\n---\n\n# P\n\n## Summary\n\n- [ ] Mock up settings\n\n## Log\n")

    @Test fun `type comes from type, or a claude tag on older notes`() {
        assertEquals("concept", doc("type: concept\nstatus: active").type)
        assertEquals("claude", doc("type: project\nstatus: active\ntags:\n  - claude").type)
        assertEquals("claude", doc("type: project\ntags: [claude, work]").type)
        assertNull(doc("type: project\ntags: []").type)
    }

    @Test fun `area, next step and could continue are read`() {
        val d = doc("type: claude\nstatus: shelved\narea: Work\ncould_continue: true")
        assertEquals("work", d.area)
        assertEquals("Mock up settings", d.next)
        assertTrue(d.couldContinue)
    }
}
