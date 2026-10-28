package uk.tomlowndes.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.DayOfWeek
import java.time.LocalDate

class HabitsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val today = LocalDate.of(2026, 10, 25) // Sunday

    @Test fun `the starter note reads its habits and goal`() {
        val n = Habits.parse(Habits.STARTER)
        assertEquals(listOf("Water", "Walk 20 min", "Read 10 pages", "Stretch"), n.all.map { it.name })
        assertEquals(true, n.all.first().water)
        assertEquals(2000, n.waterGoalMl)
        assertEquals(0, n.day(today).waterMl)
    }

    @Test fun `days are written in date order and replaced in place`() {
        val lines = Habits.STARTER.split("\n").toMutableList()
        val order = listOf("Walk 20 min", "Stretch")
        Habits.setDay(lines, HabitDay(today, 500, setOf("Stretch")), order)
        Habits.setDay(lines, HabitDay(today.minusDays(1), 2000, setOf("Walk 20 min")), order)
        Habits.setDay(lines, HabitDay(today, 750, setOf("Stretch", "Walk 20 min")), order)
        val n = Habits.parse(lines.joinToString("\n"))
        assertEquals(HabitDay(today, 750, setOf("Walk 20 min", "Stretch")), n.day(today))
        val rows = lines.filter { it.startsWith("| 2026") }
        assertEquals(listOf("| 2026-10-24 | 2000 | Walk 20 min |", "| 2026-10-25 | 750 | Walk 20 min, Stretch |"), rows)
    }

    @Test fun `streaks count back from today or yesterday`() {
        val days = listOf(
            HabitDay(today.minusDays(3), 0, setOf("Walk")), HabitDay(today.minusDays(2), 0, setOf("Walk")),
            HabitDay(today.minusDays(1), 0, setOf("Walk", "Read"))
        ).associateBy { it.date }
        val n = HabitsNote(listOf(Habit("Walk"), Habit("Read")), days)
        assertEquals(3, n.streak(Habit("Walk"), today))
        assertEquals(1, n.streak(Habit("Read"), today))
        assertEquals(0, n.streak(Habit("Stretch"), today))
        assertEquals(LocalDate.of(2026, 10, 19), n.week(today).first())
    }

    @Test fun `updates go through the vault note`() {
        val v = FileVault(tmp.root)
        Habits.update(v, today) { it.copy(waterMl = it.waterMl + Habits.GLASS_ML) }
        Habits.update(v, today) { it.copy(waterMl = it.waterMl + Habits.GLASS_ML, done = it.done + "Stretch") }
        assertEquals(HabitDay(today, 500, setOf("Stretch")), Habits.load(v).day(today))
    }

    @Test fun `list lines read and write the same`() {
        listOf(
            "🧘 Stretch · Mon Wed Fri",
            "☕ Coffee · counter 2 cups +1 max",
            "Read 10 pages · weekdays",
            "🎸 Guitar 15 min · paused 2026-10-12",
            "Walk · rest 2026-10-01..2026-10-05",
        ).forEach { assertEquals(it, Habits.line(Habits.parseLine(it))) }
        val c = Habits.parseLine("☕ Coffee · counter 2 cups +1 max")
        assertEquals(Habit("Coffee", "☕", HabitKind.COUNTER, goal = 2, step = 1, unit = "cups", max = true), c)
        assertEquals("Plain · odd bit", Habits.parseLine("Plain · odd bit").name)
    }

    @Test fun `streaks skip days a habit isn't due or was resting`() {
        val stretch = Habit("Stretch", days = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY))
        val days = listOf(LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 21), LocalDate.of(2026, 10, 23))
            .associateWith { HabitDay(it, 0, setOf("Stretch")) }
        val n = HabitsNote(listOf(stretch), days)
        assertEquals(3, n.streak(stretch, today))
        val walk = Habit("Walk", rest = LocalDate.of(2026, 10, 21) to LocalDate.of(2026, 10, 23))
        val w = HabitsNote(listOf(walk), listOf(19, 20, 24).associate { LocalDate.of(2026, 10, it) to HabitDay(LocalDate.of(2026, 10, it), 0, setOf("Walk")) })
        assertEquals(3, w.streak(walk, today))
        assertEquals(listOf(walk), w.on(today))
        assertEquals(emptyList<Habit>(), n.on(today))
    }

    @Test fun `counters log in the done column`() {
        val v = FileVault(tmp.root)
        val coffee = Habit("Coffee", kind = HabitKind.COUNTER, goal = 2, max = true)
        Habits.save(v, Habits.load(v).all + coffee)
        Habits.update(v, today) { it.add(coffee, 1).add(coffee, 1).toggle(Habit("Stretch")) }
        val n = Habits.load(v)
        assertEquals(2, n.day(today).value(coffee))
        assertEquals(true, n.met(coffee, n.day(today)))
        assertTrue(VaultIo.read(v, Habits.PATH).contains("| 2026-10-25 | 0 | Stretch, Coffee=2 |"))
        assertEquals(2 to 5, n.progress(today))
    }

    @Test fun `saving the list reorders, keeps water plain and moves renamed history`() {
        val v = FileVault(tmp.root)
        Habits.update(v, today) { it.copy(done = setOf("Stretch")) }
        val all = Habits.load(v).all
        val water = all.first { it.water }.copy(goal = 2500)
        val stretch = all.first { it.name == "Stretch" }.copy(name = "Yoga", emoji = "🧘")
        Habits.save(v, listOf(water, stretch, all[1]), mapOf("Stretch" to "Yoga"))
        val text = VaultIo.read(v, Habits.PATH)
        assertTrue(text.contains("## Habits\n- 🧘 Yoga\n- Walk 20 min\n\n## Water\ngoal: 2500 ml"))
        val n = Habits.load(v)
        assertEquals(listOf("Water", "Yoga", "Walk 20 min"), n.all.map { it.name })
        assertEquals(setOf("Yoga"), n.day(today).done)
        Habits.save(v, listOf(stretch.copy(name = "Yoga"), water))
        assertTrue(VaultIo.read(v, Habits.PATH).contains("- 💧 Water · counter 2500 ml +250"))
    }

    @Test fun `resuming turns the pause into rest`() {
        val h = Habits.pause(Habit("Walk"), LocalDate.of(2026, 10, 20))
        assertEquals(Habit("Walk", rest = LocalDate.of(2026, 10, 20) to LocalDate.of(2026, 10, 24)), Habits.resume(h, today))
    }

    @Test fun `the cache text reads back`() {
        val n = Habits.parse(Habits.STARTER).let { it.copy(all = it.all + Habit("Coffee", kind = HabitKind.COUNTER, goal = 2, unit = "cups")) }
        val d = HabitDay(today, 500, setOf("Stretch"), mapOf("Coffee" to 1))
        val back = Habits.parse(Habits.text(n.copy(days = mapOf(today to d))))
        assertEquals(n.all, back.all)
        assertEquals(d, back.day(today))
    }
}
