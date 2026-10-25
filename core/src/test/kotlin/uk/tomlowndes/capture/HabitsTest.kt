package uk.tomlowndes.capture

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDate

class HabitsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val today = LocalDate.of(2026, 10, 25) // Sunday

    @Test fun `the starter note reads its habits and goal`() {
        val n = Habits.parse(Habits.STARTER)
        assertEquals(listOf("Walk 20 min", "Read 10 pages", "Stretch"), n.habits)
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
        val n = HabitsNote(listOf("Walk", "Read"), 2000, days)
        assertEquals(3, n.streak("Walk", today))
        assertEquals(1, n.streak("Read", today))
        assertEquals(0, n.streak("Stretch", today))
        assertEquals(LocalDate.of(2026, 10, 19), n.week(today).first())
    }

    @Test fun `updates go through the vault note`() {
        val v = FileVault(tmp.root)
        Habits.update(v, today) { it.copy(waterMl = it.waterMl + Habits.GLASS_ML) }
        Habits.update(v, today) { it.copy(waterMl = it.waterMl + Habits.GLASS_ML, done = it.done + "Stretch") }
        assertEquals(HabitDay(today, 500, setOf("Stretch")), Habits.load(v).day(today))
    }
}
