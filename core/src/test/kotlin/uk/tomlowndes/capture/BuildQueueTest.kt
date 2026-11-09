package uk.tomlowndes.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class BuildQueueTest {
    private val today = LocalDate.of(2026, 11, 10) // a Tuesday
    private fun t(text: String, due: LocalDate? = null, mins: Int? = null) =
        TaskItem("- [ ] $text #task" + (mins?.let { " ⏱ ${it}m" } ?: "") + (due?.let { " 📅 $it" } ?: ""), text, due)

    // ---- Triage ----
    @Test fun `triage takes overdue oldest first then undated, no bills`() {
        val tasks = listOf(t("b", today.minusDays(2)), t("a", today.minusDays(9)), t("none"), t("today", today),
            TaskItem("- [ ] Rent £10 #bill 📅 ${today.minusDays(3)}", "Rent", today.minusDays(3)))
        assertEquals(listOf("a", "b", "none"), Triage.candidates(tasks, today).map { it.text })
        assertFalse(Triage.show(tasks, today))
        assertEquals("Overdue · a week", Triage.label(tasks[1], today))
        assertEquals("No date", Triage.label(tasks[2], today))
    }

    // ---- Visual day ----
    @Test fun `time is written at the start and read back`() {
        val raw = "- [ ] Apply to Monzo #task ⏱ 45m 📅 $today"
        val timed = DayPlan.withTime(raw, LocalTime.of(11, 0), 45)
        assertEquals("- [ ] 11:00 - 11:45 Apply to Monzo #task ⏱ 45m 📅 $today", timed)
        val moved = DayPlan.withTime(timed, LocalTime.of(9, 30), 45)
        assertEquals("- [ ] 09:30 - 10:15 Apply to Monzo #task ⏱ 45m 📅 $today", moved)
        assertEquals(raw, DayPlan.withTime(moved, null, 0))
        val tt = DayPlan.timeOf(TaskItem(moved, "x", today))!!
        assertEquals(LocalTime.of(9, 30), tt.start); assertEquals(LocalTime.of(10, 15), tt.end)
        assertEquals("Apply to Monzo", DayPlan.bareText("09:30 - 10:15 Apply to Monzo"))
    }

    @Test fun `timeline mixes events and timed tasks`() {
        val ev = CalEvent("p", "Call", today.atTime(10, 0), today.atTime(10, 30), false)
        val tasks = listOf(TaskItem("- [ ] 12:30 Lunch #task 📅 $today", "12:30 Lunch", today), t("untimed", today))
        val b = DayPlan.blocks(tasks, listOf(ev), today)
        assertEquals(2, b.size)
        assertTrue(b[0] is DayBlock.Event)
        assertEquals(LocalTime.of(12, 45), b[1].end)
        assertEquals(listOf("untimed"), DayPlan.untimed(tasks, today).map { it.text })
    }

    // ---- Plan today ----
    @Test fun `free minutes skip events and overlaps`() {
        val now = today.atTime(8, 0)
        val evs = listOf(
            CalEvent("p", "a", today.atTime(10, 0), today.atTime(11, 0), false),
            CalEvent("p", "b", today.atTime(10, 30), today.atTime(11, 30), false),
        )
        assertEquals(9 * 60 - 90, DayPlan.freeMinutes(evs, now))
        assertEquals(0, DayPlan.freeMinutes(emptyList(), today.atTime(19, 0)))
        assertEquals(60, DayPlan.planned(listOf(t("x", mins = 45), t("y"))))
    }

    @Test fun `plan candidates put due first then the week then undated`() {
        val tasks = listOf(t("undated"), t("next", today.plusDays(3)), t("late", today.minusDays(1)), t("far", today.plusDays(30)))
        assertEquals(listOf("late", "next", "undated"), DayPlan.candidates(tasks, today).map { it.text })
    }

    // ---- Break it down ----
    @Test fun `steps parse from a list with minutes`() {
        val reply = "Here you go:\n- Open Figma, sort by last edited · 2m\n2. Make an \"Archive 2025\" project (2 min)\n* Rename the 5 you still use\n"
        val s = Breakdown.parse(reply)
        assertEquals(3, s.size)
        assertEquals(Step("Open Figma, sort by last edited", 2), s[0])
        assertEquals(Step("Make an \"Archive 2025\" project", 2), s[1])
        assertNull(s[2].minutes)
    }

    @Test fun `steps go under the task after existing subtasks`() {
        val lines = mutableListOf("- [ ] Sort Figma #task", "    - [ ] old step", "- [ ] Next one #task")
        Breakdown.insert(lines, "- [ ] Sort Figma #task", listOf(Step("Open it", 2)))
        assertEquals("    - [ ] Open it #task ⏱ 2m", lines[2])
        assertEquals("- [ ] Next one #task", lines[3])
    }

    // ---- Highlights ----
    @Test fun `own highlights win, then quotable sentences`() {
        val ref = ReadRef("a.md", false)
        val doc = ArticleDoc(ref, "A", null, true, 0, listOf("Good design is as little design as possible and it means less."), listOf("Less, but better."))
        assertEquals(listOf("Less, but better."), Highlights.passages(doc))
        val plain = doc.copy(highlights = emptyList(), body = listOf(
            "Good design is as little design as possible, because it concentrates on the essential aspects of the product.",
            "Short one."))
        assertEquals(1, Highlights.passages(plain).size)
        val ps = (1..5).map { Passage("passage number $it is long enough", "T$it", ReadRef("f$it.md", false)) }
        val picked = Highlights.pick(ps, today, setOf(Highlights.key(ps[0])))
        assertEquals(3, picked.size)
        assertFalse(ps[0] in picked)
        assertEquals(picked, Highlights.pick(ps, today, setOf(Highlights.key(ps[0]))))
    }

    // ---- Habits: freeze and mood ----
    private val note = Habits.parse("""
        ## Habits
        - 🧘 Stretch
        - 🚶 Walk

        ## Log
        | Date | Water ml | Done |
        |---|---|---|
        | 2026-11-02 | 0 | Stretch, Walk, mood=4 |
        | 2026-11-03 | 0 | Stretch, mood=2 |
        | 2026-11-05 | 0 | Stretch, Walk, mood=5 |
        | 2026-11-06 | 0 | Stretch, Walk, mood=4 |
        | 2026-11-08 | 0 | Stretch |
        | 2026-11-09 | 0 | Stretch |
        | 2026-11-10 | 0 | Stretch |
    """.trimIndent())

    @Test fun `one missed day a week is frozen and keeps the streak`() {
        val stretch = note.all.first { it.name == "Stretch" }
        // Missed Wed 4th and Sat 7th: Wed is that week's freeze, Sat breaks it.
        assertTrue(note.frozen(stretch, LocalDate.of(2026, 11, 4), today))
        assertFalse(note.frozen(stretch, LocalDate.of(2026, 11, 7), today))
        assertEquals(3, note.streak(stretch, today))
        // This week (from Mon 9th) nothing missed yet.
        assertEquals(1, note.freezesLeft(stretch, today))
        // A week later, Sunday missed only: frozen, streak carries.
        val walk = note.all.first { it.name == "Walk" }
        assertEquals(0, note.streak(walk, today))
    }

    @Test fun `mood is stored as mood=N and read back`() {
        val d = Mood.set(note.day(today), 3)
        assertEquals(3, Mood.of(d))
        assertTrue(Habits.row(d, note.all.map { it.name }).contains("mood=3"))
        assertEquals("🙂", Mood.face(4))
        val days = (2L..6L).map { LocalDate.of(2026, 11, 1).plusDays(it - 1) }
        assertEquals("Best days had a walk.", Mood.insight(note, days, emptyMap()))
    }

    // ---- Routines ----
    @Test fun `routines parse from headings and steps`() {
        val r = Routines.parse(Routines.STARTER)
        assertEquals(listOf("Morning", "Wind down"), r.map { it.name })
        assertEquals(RoutineStep("🧘", "Stretch", 10), r[0].steps[2])
        assertEquals(27, r[0].minutes)
        assertEquals("Stretch", Routines.habitFor(r[0].steps[2], note.all)?.name)
        assertEquals(LocalTime.of(7, 27), Routines.doneBy(r[0], 0, LocalTime.of(7, 0)))
    }

    // ---- Weekly recap ----
    @Test fun `recap counts the last seven days`() {
        val done = listOf(DoneTask("a", today, "p"), DoneTask("b", today, "p"), DoneTask("c", today.minusDays(2), "p"))
        val focus = listOf(FocusSession(today.atTime(9, 0), "x", 50), FocusSession(today.minusDays(9).atTime(9, 0), "old", 99))
        val jobs = listOf(Job("j", "Monzo", "Designer", "applied", null, today.minusDays(1)))
        val r = Recaps.build(done, focus, note, jobs, today)
        assertEquals(3, r.tasksDone); assertEquals(50, r.focusMin); assertEquals(1, r.jobsApplied)
        assertEquals("Best day: Tuesday, 2 tasks.", Recaps.bestLine(r))
        assertEquals("4h 10m", Recaps.hours(250))
        assertTrue(Recaps.note(r).contains("- Tasks done: 3"))
    }

    // ---- Bills this month ----
    @Test fun `bills this month split paid and unpaid`() {
        val open = listOf(
            TaskItem("- [ ] Council tax £142 #bill 📅 2026-11-12", "Council tax £142 #bill", LocalDate.of(2026, 11, 12)),
            TaskItem("- [ ] Gym £35 #bill 📅 2026-11-27", "Gym £35 #bill", LocalDate.of(2026, 11, 27)),
            TaskItem("- [ ] Next month £9 #bill 📅 2026-12-02", "Next month £9 #bill", LocalDate.of(2026, 12, 2)),
        )
        val done = listOf(
            TickedTask("- [x] Rent share £180 #bill 📅 2026-11-01 ✅ 2026-11-01", "Rent share £180 #bill", LocalDate.of(2026, 11, 1)),
            TickedTask("- [x] Old £5 #bill ✅ 2026-10-01", "Old £5 #bill", LocalDate.of(2026, 10, 1)),
        )
        val m = TaskViews.billsMonth(open, done, today)
        assertEquals(2, m.unpaid.size); assertEquals(1, m.paid.size)
        assertEquals(BigDecimal("177"), m.left)
        assertEquals(BigDecimal("142"), m.dueThisWeek)
        assertEquals("Rent share", m.all.first().first.name)
        assertNotNull(m.all.first().second)
    }
}
