package uk.tomlowndes.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate

class JobsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun note(company: String, role: String, status: String, followUp: String) = """
        ---
        type: application
        company: $company
        role: $role
        status: $status
        applied: 2026-10-20
        follow_up: $followUp
        tags:
          - job
        ---

        # $role
    """.trimIndent()

    @Test fun `application notes read their frontmatter`() {
        val j = Jobs.parse("a/Monzo.md", note("\"Monzo\"", "UX designer", "Applied", "2026-10-30"))!!
        assertEquals(Job("a/Monzo.md", "Monzo", "UX designer", "Applied", LocalDate.of(2026, 10, 30), LocalDate.of(2026, 10, 20)), j)
        assertNull(Jobs.parse("a/Other.md", "---\ntype: area\n---\n"))
        assertNull(Jobs.parse("a/New.md", note("", "", "", "")).next)
    }

    @Test fun `open jobs sort by next date, closed ones apart`() {
        val jobs = listOf(
            Job("1", "Bloom", "PD", "Applied", LocalDate.of(2026, 11, 4), null),
            Job("2", "Studio X", "In-house", "Interview", LocalDate.of(2026, 10, 26), null),
            Job("3", "Acme", "VD", "Rejected", null, null),
            Job("4", "Nowhere", "Maybe", "Shortlisted", null, null)
        )
        val (open, closed) = Jobs.sorted(jobs)
        assertEquals(listOf("Studio X", "Bloom", "Nowhere"), open.map { it.company })
        assertEquals(listOf("Acme"), closed.map { it.company })
        assertEquals(LocalDate.of(2026, 10, 26), Jobs.nextDate(jobs))
    }

    @Test fun `new jobs come from the vault template`() {
        val t = File(tmp.root, Jobs.TEMPLATE).apply { parentFile.mkdirs() }
        t.writeText("---\ntype: application\ncompany: \nrole: <% tp.file.title %>\nstatus: Shortlisted\nfollow_up: \ncreated: <% tp.date.now(\"YYYY-MM-DD\") %>\n---\n\n# <% tp.file.title %>\n")
        val v = FileVault(tmp.root)
        val path = Jobs.create(v, "Monzo", "UX designer", LocalDate.of(2026, 10, 25))
        val text = File(tmp.root, path).readText()
        assertTrue(text.contains("created: 2026-10-25"))
        assertTrue(text.contains("# Monzo - UX designer"))
        val job = Jobs.list(v).single()
        assertEquals("Monzo", job.company)
        assertEquals("UX designer", job.role)
        Jobs.setNext(v, path, LocalDate.of(2026, 11, 1))
        Jobs.setStatus(v, path, "Applied")
        assertEquals(LocalDate.of(2026, 11, 1), Jobs.list(v).single().next)
        assertEquals("Applied", Jobs.list(v).single().status)
    }
}
