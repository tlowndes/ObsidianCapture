package uk.tomlowndes.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate

class DataViewsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val today = LocalDate.of(2026, 11, 6)
    private var m = 1L
    private fun n(path: String, text: String) = NoteIndex.info(path, m++, text)

    private val vault = listOf(
        n("Bills.md", "- [ ] #task Pay council tax #bill ⏫ 📅 2026-11-05\n- [x] #task Paid gas 📅 2026-11-01 ✅ 2026-11-01"),
        n("03 - Projects/Portfolio/Portfolio.md", "---\ntype: project\nstatus: active\ndeadline: 2026-11-05\n---\n- [ ] #task Send link to Rosie #jobs 🔼 📅 2026-11-06\n- [ ] #task Update CV ⏳ 2026-11-04\n- [ ] #task Later thing 📅 2026-12-01\nSee [[Garden]]"),
        n("03 - Projects/Garden/Garden.md", "---\ntype: project\nstatus: active\n---\n- [ ] #task Prune the apple tree ⏳ 2026-11-06\n- [ ] Not a task 📅 2026-11-01"),
        n("03 - Projects/Pet/Pet.md", "---\ntype: project\nstatus: paused\ndeadline: 2026-11-30\n---\n"),
        n("03 - Projects/Board games/Board games.md", "---\ntype: idea\nstatus: idea\n---\nrating:: 4\n"),
        n("99 - Meta/Templates/T.md", "- [ ] #task {{title}} 📅 2026-01-01"),
        n("Home.md", "x"),
    )

    @Test fun `recognises query blocks only`() {
        assertEquals("tasks" to "not done", DataViews.of("```tasks\nnot done\n```"))
        assertEquals("dataviewjs", DataViews.of("```dataviewjs\ndv.list()\n```")?.first)
        assertEquals(null, DataViews.of("```kotlin\nval x = 1\n```"))
    }

    @Test fun `tasks query from the home note`() {
        val q = """
            not done
            (due on or before today) OR (scheduled on or before today)
            path does not include 99 - Meta
            hide recurrence rule
            sort by priority
            sort by due
            limit 3
        """.trimIndent()
        val v = DataViews.run("tasks", q, vault, "Home.md", today) as QueryView.Tasks
        assertEquals("Due or scheduled by today", v.title)
        assertEquals(4, v.total)
        assertEquals(listOf("Pay council tax #bill", "Send link to Rosie #jobs", "Update CV"), v.tasks.map { it.text })
        assertEquals("Limit 3 · sorted by priority, due", v.footer)
        assertEquals(1, v.tasks[0].priority)
    }

    @Test fun `tasks filters for done, tags, paths and not`() {
        fun run(q: String) = (DataViews.run("tasks", q, vault, "Home.md", today) as QueryView.Tasks).tasks.map { it.text }
        assertEquals(listOf("Paid gas"), run("done"))
        assertEquals(listOf("Send link to Rosie #jobs"), run("not done\ntags include #jobs"))
        assertEquals(listOf("Prune the apple tree"), run("path includes Garden"))
        assertEquals(listOf("Later thing"), run("not done\ndue after today\npath includes {{query.file.folder}}".replace("{{query.file.folder}}", "Portfolio")))
        assertEquals(2, run("not done\nNOT (path includes Projects)").size)
        assertEquals(listOf("Prune the apple tree", "Update CV"), run("no due date\npath does not include 99"))
    }

    @Test fun `unknown tasks filters fall back`() {
        val v = DataViews.run("tasks", "not done\nheading includes Today", vault, "Home.md", today)
        assertTrue(v is QueryView.Unsupported)
    }

    @Test fun `dataview table with properties, where and sort`() {
        val q = "TABLE WITHOUT ID file.link AS \"Project\", deadline AS \"Due\"\nFROM \"03 - Projects\"\nWHERE type = \"project\" AND status = \"active\"\nSORT file.name ASC"
        val v = DataViews.run("dataview", q, vault, "Home.md", today) as QueryView.Table
        assertEquals("03 - Projects · active", v.title)
        assertEquals(listOf("Project", "Due"), v.headers)
        assertEquals(listOf("Garden", "Portfolio"), v.rows.map { it[0].text })
        assertEquals("03 - Projects/Garden/Garden.md", v.rows[0][0].link)
        assertEquals("—", v.rows[0][1].text)
        assertEquals("5 Nov", v.rows[1][1].text)
        assertEquals(LocalDate.of(2026, 11, 5), v.rows[1][1].date)
    }

    @Test fun `dataview list, tags, links and inline fields`() {
        val list = DataViews.run("dataview", "LIST status FROM \"03 - Projects\" WHERE status != \"active\" SORT file.name DESC", vault, "Home.md", today) as QueryView.Items
        assertEquals(listOf("Pet", "Board games"), list.items.map { it.first.text })
        assertEquals("paused", list.items[0].second?.text)
        val linked = DataViews.run("dataview", "LIST FROM [[Garden]]", vault, "Home.md", today) as QueryView.Items
        assertEquals(listOf("Portfolio"), linked.items.map { it.first.text })
        val rated = DataViews.run("dataview", "TABLE rating WHERE rating > 3", vault, "Home.md", today) as QueryView.Table
        assertEquals(listOf("File", "rating"), rated.headers)
        assertEquals("4", rated.rows.single()[1].text)
        val overdue = DataViews.run("dataview", "LIST WHERE deadline < date(today) AND contains(file.folder, \"Projects\")", vault, "Home.md", today) as QueryView.Items
        assertEquals(listOf("Portfolio"), overdue.items.map { it.first.text })
    }

    @Test fun `dataview task query and fallbacks`() {
        val t = DataViews.run("dataview", "TASK FROM \"03 - Projects/Garden\" WHERE !completed", vault, "Home.md", today) as QueryView.Tasks
        assertEquals(listOf("Prune the apple tree", "Not a task"), t.tasks.map { it.text })
        assertTrue((DataViews.run("dataview", "TABLE rows.file.link FROM \"x\" GROUP BY status", vault, "Home.md", today) as QueryView.Unsupported).reason.contains("GROUP BY"))
        assertTrue((DataViews.run("dataviewjs", "dv.list()", vault, "Home.md", today) as QueryView.Unsupported).script)
        assertTrue(DataViews.run("dataview", "CALENDAR file.ctime", vault, "Home.md", today) is QueryView.Unsupported)
        assertTrue(DataViews.run("dataview", "TABLE regexreplace(x, \"a\", \"b\")", vault, "Home.md", today) is QueryView.Unsupported)
    }

    @Test fun `ticking from a card writes to the task's own note`() {
        File(tmp.root, "Bills.md").writeText("- [ ] #task Pay tax 📅 2026-11-05\n- [ ] Plain\n")
        val v = FileVault(tmp.root)
        val all = listOf(NoteIndex.info("Bills.md", 1, File(tmp.root, "Bills.md").readText()))
        val t = (DataViews.run("tasks", "not done", all, "Home.md", today) as QueryView.Tasks).tasks.single()
        assertEquals("- [x] #task Pay tax 📅 2026-11-05 ✅ 2026-11-06\n- [ ] Plain\n", DataViews.toggle(v, t, today))
    }
}
