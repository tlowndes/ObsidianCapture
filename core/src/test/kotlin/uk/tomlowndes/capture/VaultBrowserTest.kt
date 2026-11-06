package uk.tomlowndes.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate

class VaultBrowserTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun note(path: String, text: String, modified: Long = 0): File =
        File(tmp.root, path).apply { parentFile.mkdirs(); writeText(text); if (modified > 0) setLastModified(modified) }

    @Test fun `index reads notes, skips dot folders and reuses the cache`() {
        note("03 - Projects/Garden.md", "---\ntags: [garden, Personal]\n---\n# Garden\n\nPrune the apple tree in Feb. #fruit\n", 1_000_000)
        note(".obsidian/workspace.md", "x")
        val cache = File(tmp.root, "cache/index.json")
        val v = FileVault(tmp.root)
        val first = NoteIndex.build(v, cache)
        assertEquals(listOf("03 - Projects/Garden.md"), first.map { it.path })
        val g = first.single()
        assertEquals("Prune the apple tree in Feb. #fruit", g.preview)
        assertEquals(listOf("#garden", "#personal", "#fruit"), g.tags)
        // Same modified time: comes from the cache, not the file.
        File(tmp.root, "03 - Projects/Garden.md").apply { writeText("changed"); setLastModified(1_000_000) }
        assertEquals("Prune the apple tree in Feb. #fruit", NoteIndex.build(v, cache).single().preview)
    }

    @Test fun `folder lists subfolders with counts then newest notes, hiding 99 folders`() {
        val all = listOf(
            NoteInfo("03 - Projects/A/a.md", 1), NoteInfo("03 - Projects/A/b.md", 2), NoteInfo("03 - Projects/Index.md", 5),
            NoteInfo("03 - Projects/Old.md", 1), NoteInfo("99 - Meta/Templates/t.md", 9), NoteInfo("Home.md", 3)
        )
        val root = VaultBrowse.folder(all, "")
        assertEquals(listOf("03 - Projects" to 4), root.folders)
        assertEquals(listOf("Home.md"), root.notes.map { it.path })
        assertEquals(2, VaultBrowse.folder(all, "", showHidden = true).folders.size)
        val p = VaultBrowse.folder(all, "03 - Projects")
        assertEquals(listOf("03 - Projects/A" to 2), p.folders)
        assertEquals(listOf("Index", "Old"), p.notes.map { it.title })
        assertEquals(listOf("03 - Projects/Index.md", "Home.md"), VaultBrowse.recent(all, 2).map { it.path })
    }

    @Test fun `resolve finds wikilinks by path or name`() {
        val all = listOf(NoteInfo("A/Plan.md", 1), NoteInfo("B/Plan.md", 1), NoteInfo("B/Other.md", 1))
        assertEquals("B/Plan.md", VaultBrowse.resolve(all, "Plan|the plan", from = "B/Other.md")?.path)
        assertEquals("A/Plan.md", VaultBrowse.resolve(all, "A/Plan#Heading")?.path)
        assertNull(VaultBrowse.resolve(all, "Nope"))
    }

    @Test fun `search puts title hits first and filters by tag`() {
        val all = listOf(
            NoteInfo("Jobs.md", 1, tags = listOf("#jobs"), text = "Send portfolio link to Rosie"),
            NoteInfo("Portfolio and CV.md", 2, tags = listOf("#jobs", "#work"), text = "Case study"),
            NoteInfo("Garden.md", 3, tags = listOf("#garden"), text = "portfolio of plants"),
            NoteInfo("Todo.md", 4, text = "- [ ] Update portfolio #task\n- [x] Old portfolio thing")
        )
        val hits = VaultSearch.search(all, "portfolio", listOf("#jobs"))
        assertEquals(listOf("Portfolio and CV.md", "Jobs.md"), hits.map { it.note.path })
        assertEquals(listOf(5..13), hits[1].marks)
        assertEquals(listOf("Garden.md"), VaultSearch.search(all, "", listOf("#garden")).map { it.note.path })
        val tasks = VaultSearch.search(all, "portfolio", mode = VaultSearch.Mode.TASKS)
        assertEquals(listOf("Todo.md"), tasks.map { it.note.path })
        assertEquals("Update portfolio #task", tasks.single().snippet)
    }

    @Test fun `typed tags become filters once a space follows`() {
        assertEquals("portfolio " to listOf("#jobs"), VaultSearch.takeTags("#jobs portfolio "))
        assertEquals("portfolio #wo" to emptyList<String>(), VaultSearch.takeTags("portfolio #wo"))
    }

    @Test fun `note tags read every frontmatter form and inline tags outside code`() {
        assertEquals(listOf("#a", "#b"), NoteTags.of(NoteText.lines("---\ntags: [a, \"b\"]\n---\n")))
        assertEquals(listOf("#a", "#b"), NoteTags.of(NoteText.lines("---\ntags: a b\n---\n")))
        assertEquals(listOf("#work", "#x"), NoteTags.of(NoteText.lines("---\ntags:\n  - work\n  - x\nstatus: active\n---\nHi\n```\n#notatag\n```")))
        assertEquals(listOf("#inline"), NoteTags.of(NoteText.lines("Some text #inline\n")))
    }

    @Test fun `setting tags adds to properties and removes wherever they are`() {
        val ls = NoteText.lines("---\nstatus: active\ntags:\n  - work\n---\n- [ ] Do it #jobs #task\nMore #old text").toMutableList()
        NoteTags.set(ls, listOf("#work", "#case-study"))
        assertEquals(
            "---\nstatus: active\ntags:\n  - work\n  - case-study\n---\n- [ ] Do it #task\nMore text",
            ls.joinToString("\n")
        )
        val bare = mutableListOf("Just text")
        NoteTags.set(bare, listOf("#garden"))
        assertEquals("---\ntags:\n  - garden\n---\nJust text", bare.joinToString("\n"))
        NoteTags.set(bare, emptyList())
        assertEquals("---\n---\nJust text", bare.joinToString("\n"))
        val inline = mutableListOf("Text", "")
        NoteTags.set(inline, listOf("#a"), toProps = false)
        assertEquals("Text\n#a\n", inline.joinToString("\n"))
    }

    @Test fun `chunks split on blank lines, headings and code`() {
        val ls = NoteText.lines("---\na: 1\n---\n# Title\n\nPara one\nstill one\n\n- [ ] x\n- [x] y\n```\ncode\n\nmore\n```\nEnd")
        val c = NoteChunks.split(ls)
        assertEquals(
            listOf(NoteChunk.Kind.FRONTMATTER, NoteChunk.Kind.HEADING, NoteChunk.Kind.TEXT, NoteChunk.Kind.TEXT, NoteChunk.Kind.CODE, NoteChunk.Kind.TEXT),
            c.map { it.kind }
        )
        assertEquals("Para one\nstill one", c[2].text)
        assertEquals(listOf(8, 9), NoteChunks.itemLines(ls, c[3]))
        assertEquals(listOf("a" to "1"), NoteChunks.properties(ls))
    }

    @Test fun `checkbox toggles add and remove the done date only on tasks`() {
        val d = LocalDate.of(2026, 11, 6)
        assertEquals("- [x] Plain", NoteChunks.toggleCheckbox("- [ ] Plain", d))
        assertEquals("- [x] Job #task ✅ 2026-11-06", NoteChunks.toggleCheckbox("- [ ] Job #task", d))
        assertEquals("- [ ] Job #task", NoteChunks.toggleCheckbox("- [x] Job #task ✅ 2026-11-06", d))
    }

    @Test fun `saving lines rebases onto a changed note, or reports a conflict`() {
        val v = FileVault(tmp.root)
        val base = "# T\n\nOld para\n\nOther\n"
        note("n.md", base)
        assertEquals(NoteEdit.Result.Saved("# T\n\nNew para\n\nOther\n"), NoteEdit.saveLines(v, "n.md", base, 2..2, "New para"))
        // Another device added a line elsewhere: the edit still lands.
        val base2 = File(tmp.root, "n.md").readText()
        note("n.md", "Synced line\n" + base2)
        val r = NoteEdit.saveLines(v, "n.md", base2, 2..2, "Newer para")
        assertEquals(NoteEdit.Result.Saved("Synced line\n# T\n\nNewer para\n\nOther\n"), r)
        // The paragraph itself changed elsewhere: conflict.
        note("n.md", "# T\n\nTheir para\n\nOther\n")
        val c = NoteEdit.saveLines(v, "n.md", "# T\n\nNewer para\n\nOther\n", 2..2, "Mine")
        assertTrue(c is NoteEdit.Result.Conflict)
        assertEquals("# T\n\nTheir para\n\nOther\n", (c as NoteEdit.Result.Conflict).theirs)
        assertEquals("# T\n\nMine\n\nOther\n", c.mine)
    }

    @Test fun `replace keeps windows line endings and tidies removed blocks`() {
        assertEquals("a\r\nB\r\nC\r\nc", NoteEdit.replace("a\r\nb\r\nc", 1..1, "B\nC"))
        assertEquals("a\n\nc", NoteEdit.replace("a\n\nb\n\nc", 2..2, ""))
    }

    @Test fun `edit keeps crlf notes crlf`() {
        val v = FileVault(tmp.root)
        note("w.md", "- [ ] one\r\n- [ ] two\r\n")
        NoteEdit.edit(v, "w.md") { it[1] = NoteChunks.toggleCheckbox(it[1], LocalDate.of(2026, 1, 1)) }
        assertEquals("- [ ] one\r\n- [x] two\r\n", File(tmp.root, "w.md").readText())
    }
}
