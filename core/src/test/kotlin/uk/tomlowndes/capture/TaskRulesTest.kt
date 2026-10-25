package uk.tomlowndes.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class TaskRulesTest {
    private fun d(s: String) = LocalDate.parse(s)

    @Test fun `reads due date and recurrence`() {
        val line = "- [ ] #task Bins out 🔁 every week ⏳ 2026-10-11 📅 2026-10-12"
        assertEquals(d("2026-10-12"), TaskRules.due(line))
        assertEquals("every week", TaskRules.recurrence(line))
        assertNull(TaskRules.due("- [ ] #task Call mum"))
        assertNull(TaskRules.recurrence("- [ ] #task Call mum"))
    }

    @Test fun `clean text drops plugin markers`() {
        assertEquals("Pay rent", TaskRules.cleanText("#task Pay rent 🔁 every month 📅 2026-10-01 ⏫ [id:: abc123]"))
        assertEquals("Buy milk", TaskRules.cleanText("#task Buy milk ➕ 2026-10-01 ⏳ 2026-10-02"))
    }

    @Test fun `next date for simple rules`() {
        val due = d("2026-10-12") // a Monday
        assertEquals(d("2026-10-13"), TaskRules.nextDate("every day", due, due))
        assertEquals(d("2026-10-14"), TaskRules.nextDate("every 2 days", due, due))
        assertEquals(d("2026-10-19"), TaskRules.nextDate("every week", due, due))
        assertEquals(d("2026-11-12"), TaskRules.nextDate("every month", due, due))
        assertEquals(d("2027-10-12"), TaskRules.nextDate("every year", due, due))
        assertEquals(d("2026-10-16"), TaskRules.nextDate("every friday", due, due))
        assertEquals(d("2026-10-19"), TaskRules.nextDate("Every Monday", due, due))
    }

    @Test fun `weekday skips the weekend`() {
        assertEquals(d("2026-10-19"), TaskRules.nextDate("every weekday", d("2026-10-16"), d("2026-10-16")))
    }

    @Test fun `month end clamps`() {
        assertEquals(d("2026-02-28"), TaskRules.nextDate("every month", d("2026-01-31"), d("2026-01-31")))
    }

    @Test fun `recurrence counts from the due date unless when done`() {
        val due = d("2026-10-12")
        val late = d("2026-10-14")
        assertEquals(d("2026-10-19"), TaskRules.nextDate("every week", due, late))
        assertEquals(d("2026-10-21"), TaskRules.nextDate("every week when done", due, late))
        assertEquals(d("2026-10-21"), TaskRules.nextDate("every week", null, late))
    }

    @Test fun `rules we don't understand give no next date`() {
        assertNull(TaskRules.nextDate("every blue moon", d("2026-10-12"), d("2026-10-12")))
        assertNull(TaskRules.nextOccurrence("- [ ] #task Odd 🔁 every blue moon 📅 2026-10-12", d("2026-10-12")))
        assertNull(TaskRules.nextOccurrence("- [ ] #task Not recurring 📅 2026-10-12", d("2026-10-12")))
    }

    @Test fun `next occurrence moves scheduled and start dates too`() {
        assertEquals(
            "- [ ] #task Bins out 🔁 every week 🛫 2026-10-17 ⏳ 2026-10-18 📅 2026-10-19",
            TaskRules.nextOccurrence("- [ ] #task Bins out 🔁 every week 🛫 2026-10-10 ⏳ 2026-10-11 📅 2026-10-12", d("2026-10-12"))
        )
    }

    @Test fun `next occurrence without a due date adds one`() {
        assertEquals(
            "- [ ] #task Stretch 🔁 every day 📅 2026-10-13",
            TaskRules.nextOccurrence("- [ ] #task Stretch 🔁 every day", d("2026-10-12"))
        )
    }

    @Test fun `with due adds, changes and removes the date`() {
        val plain = "- [ ] #task Call mum"
        assertEquals("$plain 📅 2026-10-20", TaskRules.withDue(plain, d("2026-10-20")))
        assertEquals("$plain 📅 2026-10-21", TaskRules.withDue("$plain 📅 2026-10-20", d("2026-10-21")))
        assertEquals(plain, TaskRules.withDue("$plain 📅 2026-10-20", null))
        assertEquals("- [ ] #task Call mum ⏫", TaskRules.withDue("- [ ] #task Call mum 📅 2026-10-20 ⏫", null))
    }
}

class EstimateTest {
    @org.junit.Test fun `estimates are set before the date, replaced and removed`() {
        val line = "- [ ] #task Setup WOL 📅 2026-10-25"
        val set = TaskRules.withMinutes(line, 60)
        org.junit.Assert.assertEquals("- [ ] #task Setup WOL ⏱ 60m 📅 2026-10-25", set)
        org.junit.Assert.assertEquals("- [ ] #task Setup WOL ⏱ 15m 📅 2026-10-25", TaskRules.withMinutes(set, 15))
        org.junit.Assert.assertEquals(line, TaskRules.withMinutes(set, null))
        org.junit.Assert.assertEquals("- [ ] #task Bins ⏱ 5m", TaskRules.withMinutes("- [ ] #task Bins", 5))
        org.junit.Assert.assertEquals(60, Deck.minutes(TaskNote.parse(set)!!))
        org.junit.Assert.assertEquals("Setup WOL", TaskNote.parse(set)!!.text)
        org.junit.Assert.assertEquals("1h 30m", TaskRules.minutesLabel(90))
    }
}
