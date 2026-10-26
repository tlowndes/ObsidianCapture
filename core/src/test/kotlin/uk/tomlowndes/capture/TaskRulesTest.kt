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

class RepeatRuleTest {
    private fun d(s: String) = LocalDate.parse(s)

    @Test fun `repeat rules from the capture sheet are ones nextDate understands`() {
        val due = d("2026-10-26") // a Monday
        val cases = mapOf(
            TaskRules.repeatRule(1, "day") to d("2026-10-27"),
            TaskRules.repeatRule(1, "week") to d("2026-11-02"),
            TaskRules.repeatRule(2, "week") to d("2026-11-09"),
            TaskRules.repeatRule(3, "month") to d("2027-01-26"),
            TaskRules.repeatRule(1, "year") to d("2027-10-26"),
            TaskRules.repeatRule(1, "week", day = java.time.DayOfWeek.THURSDAY) to d("2026-10-29"),
            TaskRules.repeatRule(1, "week", weekdays = true) to d("2026-10-27"),
        )
        for ((rule, next) in cases) assertEquals(rule, next, TaskRules.nextDate(rule, due, due))
        assertEquals("every 2 weeks", TaskRules.repeatRule(2, "week"))
        assertEquals("every month when done", TaskRules.repeatRule(1, "month", whenDone = true))
        assertEquals("every week on Thursday", TaskRules.repeatRule(1, "week", day = java.time.DayOfWeek.THURSDAY))
        assertEquals(d("2026-11-08"), TaskRules.nextDate(TaskRules.repeatRule(10, "day", whenDone = true), due, d("2026-10-29")))
    }

    @Test fun `a captured repeating task reads back with its rule and due date`() {
        val line = TaskNote.newTask("Bins out ⏱ 5m 🔁 every week", "2026-10-27")
        assertEquals("- [ ] #task Bins out ⏱ 5m 🔁 every week 📅 2026-10-27", line)
        assertEquals("every week", TaskRules.recurrence(line))
        assertEquals("Bins out", TaskRules.cleanText(line.removePrefix("- [ ] ")))
        assertEquals("- [ ] #task Bins out ⏱ 5m 🔁 every week 📅 2026-11-03", TaskRules.nextOccurrence(line, d("2026-10-27")))
    }
}

class RecurrenceDayTest {
    private fun d(s: String) = LocalDate.parse(s)

    @Test fun `weekly and monthly rules use Tasks plugin wording`() {
        assertEquals("every week on Tuesday", TaskRules.repeatRule(1, "week", day = java.time.DayOfWeek.TUESDAY))
        assertEquals("every 2 weeks on Friday", TaskRules.repeatRule(2, "week", day = java.time.DayOfWeek.FRIDAY))
        assertEquals("every month on the 1st", TaskRules.repeatRule(1, "month", monthDay = 1))
        assertEquals("every month on the 2nd", TaskRules.repeatRule(1, "month", monthDay = 2))
        assertEquals("every month on the 3rd", TaskRules.repeatRule(1, "month", monthDay = 3))
        assertEquals("every month on the 4th", TaskRules.repeatRule(1, "month", monthDay = 4))
        assertEquals("every month on the 11th", TaskRules.repeatRule(1, "month", monthDay = 11))
        assertEquals("every month on the 12th", TaskRules.repeatRule(1, "month", monthDay = 12))
        assertEquals("every month on the 13th", TaskRules.repeatRule(1, "month", monthDay = 13))
        assertEquals("every month on the 21st", TaskRules.repeatRule(1, "month", monthDay = 21))
        assertEquals("every month on the 22nd", TaskRules.repeatRule(1, "month", monthDay = 22))
        assertEquals("every month on the 23rd", TaskRules.repeatRule(1, "month", monthDay = 23))
        assertEquals("every month on the 31st", TaskRules.repeatRule(1, "month", monthDay = 31))
        assertEquals("every 3 months on the 15th", TaskRules.repeatRule(3, "month", monthDay = 15))
        assertEquals("every month on the last", TaskRules.repeatRule(1, "month", monthDay = -1))
        assertEquals("every week on Tuesday when done", TaskRules.repeatRule(1, "week", day = java.time.DayOfWeek.TUESDAY, whenDone = true))
    }

    @Test fun `new rules and the old wording parse and round trip`() {
        assertEquals("every week on Tuesday", TaskRules.parseRepeat("every week on Tuesday")!!.rule())
        assertEquals("every week on Tuesday", TaskRules.parseRepeat("Every Week on TUESDAY")!!.rule())
        assertEquals("every 2 weeks on Friday", TaskRules.parseRepeat("every 2 weeks on Friday")!!.rule())
        assertEquals("every month on the 1st", TaskRules.parseRepeat("every month on the 1st")!!.rule())
        assertEquals("every month on the last", TaskRules.parseRepeat("every month on the last")!!.rule())
        assertEquals("every 2 weeks", TaskRules.parseRepeat("every 2 weeks")!!.rule())
        assertEquals("every weekday", TaskRules.parseRepeat("every weekday")!!.rule())
        assertEquals("every day", TaskRules.parseRepeat("every day")!!.rule())
        assertEquals("every month when done", TaskRules.parseRepeat("every month when done")!!.rule())
        val legacy = TaskRules.parseRepeat("every monday")!!
        assertEquals(java.time.DayOfWeek.MONDAY, legacy.weekday)
        assertEquals("every week on Monday", legacy.rule())
        assertNull(TaskRules.parseRepeat("every blue moon"))
        assertNull(TaskRules.parseRepeat("every week on Tuesday, Friday"))
    }

    @Test fun `next date for a weekday and a day of the month`() {
        val monday = d("2026-10-12")
        assertEquals(d("2026-10-13"), TaskRules.nextDate("every week on Tuesday", monday, monday))
        assertEquals(d("2026-10-20"), TaskRules.nextDate("every week on Tuesday", d("2026-10-13"), d("2026-10-13")))
        assertEquals(d("2026-10-19"), TaskRules.nextDate("every week on Monday", monday, monday))
        assertEquals(d("2026-10-16"), TaskRules.nextDate("every friday", monday, monday))
        assertEquals(d("2026-11-01"), TaskRules.nextDate("every month on the 1st", monday, monday))
        assertEquals(d("2026-12-01"), TaskRules.nextDate("every month on the 1st", d("2026-11-01"), d("2026-11-01")))
        assertEquals(d("2026-10-15"), TaskRules.nextDate("every month on the 15th", d("2026-10-10"), d("2026-10-10")))
        assertEquals(d("2026-11-15"), TaskRules.nextDate("every month on the 15th", d("2026-10-15"), d("2026-10-15")))
        assertEquals(d("2026-12-01"), TaskRules.nextDate("every 2 months on the 1st", d("2026-10-26"), d("2026-10-26")))
        assertEquals(d("2022-03-31"), TaskRules.nextDate("every month on the 31st", d("2022-01-31"), d("2022-01-31")))
        assertEquals(d("2022-02-28"), TaskRules.nextDate("every month on the last", d("2022-01-31"), d("2022-01-31")))
        assertEquals(d("2024-02-29"), TaskRules.nextDate("every month on the last", d("2024-01-31"), d("2024-01-31")))
        // Wednesday 28 Oct; Tuesday of that week is already past, so the next 2-week Tuesday is 10 Nov.
        assertEquals(d("2026-11-10"), TaskRules.nextDate("every 2 weeks on Tuesday", d("2026-10-28"), d("2026-10-28")))
        assertEquals(d("2026-11-01"), TaskRules.nextDate("every month on the 1st when done", monday, d("2026-10-20")))
        assertEquals(d("2026-10-20"), TaskRules.nextDate("every week on Tuesday when done", monday, d("2026-10-14")))
        assertNull(TaskRules.nextDate("every week on Tuesday, Friday", monday, monday))
    }

    @Test fun `old and new wording agree, and the written rule is kept on the next line`() {
        val monday = d("2026-10-12")
        assertEquals(TaskRules.nextDate("every tuesday", monday, monday), TaskRules.nextDate("every week on Tuesday", monday, monday))
        assertEquals(TaskRules.nextDate("every monday", monday, monday), TaskRules.nextDate("every week on Monday", monday, monday))
        val line = "- [ ] #task Bins 🔁 every week on Tuesday 🛫 2026-10-10 ⏳ 2026-10-11 📅 2026-10-13"
        assertEquals("every week on Tuesday", TaskRules.recurrence(line))
        assertEquals("Bins", TaskRules.cleanText(line.removePrefix("- [ ] ")))
        assertEquals(
            "- [ ] #task Bins 🔁 every week on Tuesday 🛫 2026-10-17 ⏳ 2026-10-18 📅 2026-10-20",
            TaskRules.nextOccurrence(line, d("2026-10-13"))
        )
        val legacy = "- [ ] #task Bins 🔁 every tuesday 📅 2026-10-13"
        assertEquals("- [ ] #task Bins 🔁 every tuesday 📅 2026-10-20", TaskRules.nextOccurrence(legacy, d("2026-10-13")))
    }

    @Test fun `clean text drops a day-specific rule and leaves a task subtag`() {
        assertEquals("Pay rent", TaskRules.cleanText("#task Pay rent 🔁 every month on the 1st 📅 2026-11-01 ⏫"))
        assertEquals("Bins", TaskRules.cleanText("#task Bins 🔁 every week on Tuesday 📅 2026-10-13"))
        assertEquals("#task/work Send invoice", TaskRules.cleanText("#task/work Send invoice 📅 2026-10-12"))
    }
}

class TaskEditLineTest {
    private fun d(s: String) = LocalDate.parse(s)

    @Test fun `saving the same values keeps the line byte for byte`() {
        val raw = "- [ ] #task Pay  rent 📅 2026-10-01 🔁 every month ⏫"
        val item = TaskNote.parse(raw)!!
        assertEquals(raw, TaskRules.editedLine(raw, item.text, item.due, item.recurrence, Deck.minutes(item)))
    }

    @Test fun `text due recurrence and estimate change, unknown markers stay put`() {
        val raw = "- [ ] #task Pay rent 🔁 every month 📅 2026-10-01 ⏫ [id:: abc123]"
        assertEquals(
            "- [ ] #task Pay the rent ⏱ 15m 🔁 every month on the 1st 📅 2026-11-01 ⏫ [id:: abc123]",
            TaskRules.editedLine(raw, "Pay the rent", d("2026-11-01"), "every month on the 1st", 15)
        )
    }

    @Test fun `scheduled and start stay in front of the due date`() {
        val raw = "- [ ] #task Bins 🛫 2026-10-10 ⏳ 2026-10-11 📅 2026-10-12"
        assertEquals(
            "- [ ] #task Bins out 🛫 2026-10-10 ⏳ 2026-10-11 📅 2026-10-20",
            TaskRules.editedLine(raw, "Bins out", d("2026-10-20"), null, null)
        )
    }

    @Test fun `an old weekday rule is replaced in place and a removed date is dropped`() {
        val raw = "- [ ] #task Bins 🔁 every tuesday 📅 2026-10-13 ⏫"
        assertEquals(
            "- [ ] #task Bins 🔁 every week on Tuesday 📅 2026-10-13 ⏫",
            TaskRules.editedLine(raw, "Bins", d("2026-10-13"), "every week on Tuesday", null)
        )
        assertEquals("- [ ] #task Bins ⏫", TaskRules.editedLine(raw, "Bins", null, null, null))
    }

    @Test fun `completion and cancellation marks are not touched`() {
        assertEquals(
            "- [x] #task Odd one ✅ 2026-10-01 ⏫",
            TaskRules.editedLine("- [x] #task Odd ✅ 2026-10-01 ⏫", "Odd one", null, null, null)
        )
        assertEquals(
            "- [-] #task Drop it ❌ 2026-10-12",
            TaskRules.editedLine("- [-] #task Drop ❌ 2026-10-12", "Drop it", null, null, null)
        )
    }

    @Test fun `indent, a bill amount and an unknown emoji stay`() {
        val bill = "- [ ] Council tax £128 #bill 📅 2026-10-26"
        assertEquals(
            "- [ ] Council tax £130 #bill 📅 2026-11-01",
            TaskRules.editedLine(bill, "Council tax £130 #bill", d("2026-11-01"), null, null)
        )
        val flagged = "- [ ] #task Do stuff 🏁 delete 📅 2026-10-01"
        assertEquals(
            "- [ ] #task Do stuff 🏁 delete 📅 2026-10-08",
            TaskRules.editedLine(flagged, "Do stuff 🏁 delete", d("2026-10-08"), null, null)
        )
        val sub = "- [ ] #task/work Send invoice 📅 2026-10-12"
        assertEquals(
            "- [ ] #task/work Send invoice 📅 2026-10-20",
            TaskRules.editedLine(sub, TaskRules.cleanText(sub.removePrefix("- [ ] ")), d("2026-10-20"), null, null)
        )
    }

    @Test fun `a new date is added and a carriage return is kept`() {
        assertEquals(
            "- [ ] #task Call mum 📅 2026-10-20",
            TaskRules.editedLine("- [ ] #task Call mum", "Call mum", d("2026-10-20"), null, null)
        )
        val raw = "- [ ] #task Call mum 📅 2026-10-12\r"
        assertEquals(
            "- [ ] #task Call mum 📅 2026-10-20\r",
            TaskRules.editedLine(raw, "Call mum", d("2026-10-20"), null, null)
        )
    }
}
