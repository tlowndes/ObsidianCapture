package uk.tomlowndes.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class DailyNoteTest {
    private val date = LocalDate.parse("2026-10-12")
    private val now = LocalDateTime.parse("2026-10-12T08:30")

    /** Shaped like the vault's Daily Note template, Templater tags included. `·` marks a trailing space. */
    private val template = """
        ---
        date: <% moment(tp.file.title, "DD-MM-YYYY").format("YYYY-MM-DD") %>
        created: <% tp.file.creation_date() %>
        energy:
        overwhelmed: false
        ---

        # <% moment(tp.file.title, "DD-MM-YYYY").format("DD/MM/YYYY") %>

        ## 🎯 Do this first

        -·
        - **If today is hard, the one thing that counts:**·

        ## 🌤️ Top 3

        1.·
        2.·
        3.·

        ## Wind down

        - **Went well:**·
        - **Leave for tomorrow:**·
        - **First step tomorrow:**·
    """.trimIndent().replace("·", " ") + "\n"

    private val rendered get() = DailyNote.renderTemplate(template, date, now)
    private val fields get() = DailyNote.fields(DailyNote.headings(template), emptyList())
    private fun field(id: String) = fields.first { it.id == id }

    private val morning = mapOf(
        "do_first" to Value.Text("Ship the tests"),
        "energy" to Value.Text("High"),
        "overwhelmed" to Value.Flag(true),
        "hard_day" to Value.Text("Walk"),
        "top3" to Value.Items(listOf("CI", "Release")),
        "went_well" to Value.Items(listOf("Gym", "Lunch")),
        "first_step" to Value.Text("Open laptop"),
    )

    @Test fun `daily note path follows the Obsidian date format`() {
        assertEquals("04 - Journal/01 Daily/2026/10/12-10-2026.md", DailyNote.notePath("04 - Journal/01 Daily", "YYYY/MM/DD-MM-YYYY", date))
        assertEquals("Daily/2026-10-12.md", DailyNote.notePath("Daily", "YYYY-MM-DD", date))
    }

    @Test fun `template tags are filled in`() {
        val t = rendered
        assertTrue(t.contains("date: 2026-10-12\n"))
        assertTrue(t.contains("created: 2026-10-12 08:30\n"))
        assertTrue(t.contains("# 12/10/2026\n"))
        assertTrue(!t.contains("<%"))
    }

    @Test fun `no template gives a minimal note`() {
        assertEquals("---\ndate: 2026-10-12\ntype: daily\n---\n\n# 2026-10-12\n", DailyNote.renderTemplate(null, date, now))
    }

    @Test fun `built-in fields follow the template order`() {
        assertEquals(
            listOf("do_first", "energy", "overwhelmed", "hard_day", "top3", "went_well", "leave", "first_step"),
            fields.map { it.id }
        )
        val noTop3 = DailyNote.fields(listOf("Wind down", "🎯 Do this first"), emptyList())
        assertEquals(listOf("went_well", "leave", "first_step", "do_first", "energy", "overwhelmed", "hard_day"), noTop3.map { it.id })
    }

    @Test fun `saving the form into a new note writes each field in its place`() {
        val ls = DailyNote.lines("")
        val skipped = DailyNote.merge(ls, fields, morning, emptyMap(), rendered)
        assertTrue(skipped.isEmpty())
        val expected = rendered
            .replace("energy:\n", "energy: High\n")
            .replace("overwhelmed: false", "overwhelmed: true")
            .replace("## 🎯 Do this first\n\n- \n", "## 🎯 Do this first\n\n- Ship the tests\n")
            .replace("counts:** \n", "counts:** Walk\n")
            .replace("1. \n2. \n3. \n", "1. CI\n2. Release\n3. \n")
            .replace("- **Went well:** \n", "- **Went well:** Gym\n  - Lunch\n")
            .replace("- **First step tomorrow:** \n", "- **First step tomorrow:** Open laptop\n")
        assertEquals(expected, ls.joinToString("\n"))
    }

    @Test fun `what was written reads back the same`() {
        val ls = DailyNote.lines("")
        DailyNote.merge(ls, fields, morning, emptyMap(), rendered)
        val back = DailyNote.read(ls, fields)
        for ((id, v) in morning) assertTrue("$id read back as ${back[id]}", DailyNote.same(v, back[id]))
        assertTrue(back.getValue("leave").isEmpty())
    }

    @Test fun `a blank form leaves the template as it is`() {
        val ls = DailyNote.lines("")
        DailyNote.merge(ls, fields, emptyMap(), emptyMap(), rendered)
        assertEquals(rendered, ls.joinToString("\n"))
        assertTrue(DailyNote.read(ls, fields).values.all { it.isEmpty() })
    }

    @Test fun `edits made elsewhere since the form loaded are kept`() {
        val ls = DailyNote.lines("")
        DailyNote.merge(ls, fields, morning, emptyMap(), rendered)
        val loaded = DailyNote.read(ls, fields)

        // Meanwhile, on the desktop: a different first task and lower energy.
        DailyNote.writeValue(ls, field("do_first"), Value.Text("Desktop's task"))
        DailyNote.writeValue(ls, field("energy"), Value.Text("Low"))

        // On the phone: also changed the first task, and the first step, but not energy.
        val form = morning + mapOf("do_first" to Value.Text("Phone's task"), "first_step" to Value.Text("Coffee"))
        val skipped = DailyNote.merge(ls, fields, form, loaded, rendered)

        assertEquals(listOf("do_first"), skipped.map { it.id })
        val now = DailyNote.read(ls, fields)
        assertEquals(Value.Text("Desktop's task"), now["do_first"])
        assertEquals(Value.Text("Low"), now["energy"])
        assertEquals(Value.Text("Coffee"), now["first_step"])
    }

    @Test fun `the same change on both sides is not a conflict`() {
        val ls = DailyNote.lines(rendered)
        DailyNote.writeValue(ls, field("do_first"), Value.Text("Same"))
        val skipped = DailyNote.merge(ls, fields, mapOf("do_first" to Value.Text("Same")), emptyMap(), rendered)
        assertTrue(skipped.isEmpty())
    }

    @Test fun `spacing and blank items don't count as changes`() {
        assertTrue(DailyNote.same(Value.Text("  hi "), Value.Text("hi")))
        assertTrue(DailyNote.same(Value.Items(listOf("a", " ", "")), Value.Items(listOf(" a"))))
        assertTrue(DailyNote.same(null, Value.Text("")))
        assertTrue(DailyNote.same(Value.Flag(false), null))
        assertTrue(!DailyNote.same(Value.Flag(true), null))
    }

    @Test fun `a labelled list can shrink back to one line`() {
        val ls = DailyNote.lines(rendered)
        DailyNote.writeValue(ls, field("went_well"), Value.Items(listOf("A", "B", "C")))
        DailyNote.writeValue(ls, field("went_well"), Value.Items(listOf("Only")))
        assertEquals(rendered.replace("- **Went well:** \n", "- **Went well:** Only\n"), ls.joinToString("\n"))
    }

    @Test fun `new section ids avoid clashes`() {
        assertEquals("gratitude", DailyNote.newSectionId("🙏 Gratitude", emptyList()))
        assertEquals("energy_", DailyNote.newSectionId("Energy", emptyList()))
        assertEquals("mood__", DailyNote.newSectionId("Mood", listOf("mood", "mood_")))
        assertEquals("section", DailyNote.newSectionId("🙏", emptyList()))
    }

    @Test fun `adding a section puts it after the chosen one, once`() {
        val ls = DailyNote.lines(template)
        DailyNote.addSection(ls, "Gratitude", Kind.CHECKLIST, emptyList(), "gratitude", after = "Top 3")
        val expected = template.replace("3. \n\n## Wind down", "3. \n\n## Gratitude\n\n- [ ] \n\n## Wind down")
        assertEquals(expected, ls.joinToString("\n"))
        DailyNote.addSection(ls, "Gratitude", Kind.CHECKLIST, emptyList(), "gratitude", after = "Top 3")
        assertEquals(expected, ls.joinToString("\n"))
    }

    @Test fun `a yes-no section adds its frontmatter key`() {
        val ls = DailyNote.lines(template)
        DailyNote.addSection(ls, "Meds", Kind.YESNO, emptyList(), "meds", after = null)
        assertEquals("meds: false", ls[5])
        assertTrue(ls.joinToString("\n").endsWith("tomorrow:** \n\n## Meds\n\n`INPUT[toggle:meds]`\n"))
    }

    @Test fun `a choice section writes a Meta Bind select`() {
        val ls = DailyNote.lines(template)
        DailyNote.addSection(ls, "Sleep", Kind.CHOICE, listOf("Bad", "OK", "Good"), "sleep", after = "Wind down")
        assertTrue(ls.contains("`INPUT[inlineSelect(option(Bad), option(OK), option(Good)):sleep]`"))
        assertEquals("sleep: ", ls[5])
    }

    @Test fun `a new last section goes above the hidden Meta Bind buttons`() {
        val withButtons = template + "\n```meta-bind-button\nid: save\n```\n"
        val ls = DailyNote.lines(withButtons)
        DailyNote.addSection(ls, "Notes", Kind.TEXT, emptyList(), "notes", after = "Wind down")
        val heading = ls.indexOf("## Notes")
        assertTrue(heading in 0 until ls.indexOf("```meta-bind-button"))
    }

    @Test fun `custom checklist and text sections round-trip`() {
        val ls = DailyNote.lines(template)
        DailyNote.addSection(ls, "Gratitude", Kind.CHECKLIST, emptyList(), "gratitude", after = "Top 3")
        DailyNote.addSection(ls, "Notes", Kind.TEXT, emptyList(), "notes", after = "Wind down")
        val checks = LogField("gratitude", "Gratitude", Kind.CHECKLIST, Slot.ANYTIME, Where.Body("Gratitude"), "Gratitude", custom = true)
        val notes = LogField("notes", "Notes", Kind.TEXT, Slot.ANYTIME, Where.Body("Notes"), "Notes", custom = true)
        val v1 = Value.Checks(listOf(true to "Sun", false to "Tea"))
        val v2 = Value.Text("Line one\nLine two")
        DailyNote.writeValue(ls, checks, v1)
        DailyNote.writeValue(ls, notes, v2)
        assertEquals(v1, DailyNote.readValue(ls, checks))
        assertEquals(v2, DailyNote.readValue(ls, notes))
        assertTrue(ls.joinToString("\n").contains("## Gratitude\n\n- [x] Sun\n- [ ] Tea\n\n## Wind down"))
    }
}
