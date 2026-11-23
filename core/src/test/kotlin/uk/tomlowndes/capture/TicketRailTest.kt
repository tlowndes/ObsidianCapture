package uk.tomlowndes.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class TicketRailTest {
    private val today = LocalDate.of(2026, 11, 20) // a Friday

    private fun task(line: String, path: String = VaultTasks.QUICK_TASK) = TaskNote.parse(line)!!.copy(path = path)

    @Test fun `an id goes on the end and is read back`() {
        val line = "- [ ] #task Ring the dentist #home 📅 2026-11-20"
        val marked = TicketRail.withId(line, "hom042")
        assertEquals("$line 🆔 hom042", marked)
        assertEquals("hom042", TicketRail.railId(marked))
        assertEquals(marked, TicketRail.withId(marked, "hom043"))
        assertEquals("- [ ] a\r".removeSuffix("\r") + " 🆔 tsk001\r", TicketRail.withId("- [ ] a\r", "tsk001"))
    }

    @Test fun `the id doesn't show in the task's text`() {
        val t = task("- [ ] #task Ring the dentist #home 📅 2026-11-20 🆔 hom042")
        assertEquals("Ring the dentist #home", t.text)
        assertEquals(today, t.due)
    }

    @Test fun `ids the Tasks plugin made aren't rail tickets`() {
        assertEquals("abc123", TicketRail.idOf("- [ ] x 🆔 abc123"))
        assertEquals("hom042", TicketRail.railId("- [ ] x 🆔 hom042"))
        assertNull(TicketRail.railId("- [ ] x 🆔 x7f2k"))
    }

    @Test fun `editing a task keeps its id`() {
        val raw = "- [ ] #task Ring the dentist 📅 2026-11-20 🆔 hom042"
        val edited = TaskRules.editedLine(raw, "Ring the dentist today", today.plusDays(1), null, null)
        assertEquals("- [ ] #task Ring the dentist today 📅 2026-11-21 🆔 hom042", edited)
    }

    @Test fun `a repeat's next occurrence doesn't inherit the ticket`() {
        val raw = "- [ ] #task Water the plants 🔁 every week 📅 2026-11-20 🆔 hom043"
        val c = TaskNote.completion(raw, today, recurring = true)
        assertTrue(c.doneLine.contains("🆔 hom043"))
        assertEquals("- [ ] #task Water the plants 🔁 every week 📅 2026-11-27", c.nextLine)
    }

    @Test fun `prefix comes from the first tag`() {
        assertEquals("hom", TicketRail.prefix("Ring the dentist #home"))
        assertEquals("job", TicketRail.prefix("Send link #jobs/applied"))
        assertEquals("tsk", TicketRail.prefix("No tags here"))
        assertEquals("ptt", TicketRail.prefix("#pt something"))
    }

    @Test fun `ids count on from the highest and never repeat`() {
        assertEquals("hom001", TicketRail.nextId("hom", emptyList()))
        assertEquals("hom043", TicketRail.nextId("hom", listOf("hom042", "job017", "hom007")))
        assertEquals("hom001", TicketRail.nextId("hom", listOf("hom999")))
        assertEquals("hom003", TicketRail.nextId("hom", listOf("hom999", "hom001", "hom002")))
    }

    @Test fun `codes read like callsigns`() {
        assertEquals("HOM 042", TicketRail.code("hom042"))
    }

    @Test fun `links round trip and anything else is ignored`() {
        assertEquals("flightdeck://clear/hom042", TicketRail.link("hom042"))
        assertEquals("hom042", TicketRail.parseLink("flightdeck://clear/HOM042/"))
        assertNull(TicketRail.parseLink("https://example.com/clear/hom042"))
        assertNull(TicketRail.parseLink("flightdeck://clear/../../x"))
    }

    @Test fun `the morning run is overdue then today, skips the rail, and stops at five`() {
        val open = listOf(
            task("- [ ] Later 📅 2026-11-25"),
            task("- [ ] Today one 📅 2026-11-20"),
            task("- [ ] Old 📅 2026-11-17"),
            task("- [ ] Printed 📅 2026-11-20 🆔 hom001"),
            task("- [ ] Undated"),
            task("- [ ] Yesterday 📅 2026-11-19"),
        )
        assertEquals(listOf("Old", "Yesterday", "Today one"), TicketRail.morningRun(open, today).map { it.text })
        val many = (1..8).map { task("- [ ] T$it 📅 2026-11-20") }
        assertEquals(5, TicketRail.morningRun(many, today).size)
        assertEquals(2, TicketRail.morningRun(many, today, max = 2).size)
    }

    @Test fun `the rail is open tasks with ids, and cleared ones are counted by day`() {
        val open = listOf(task("- [ ] B 🆔 job017"), task("- [ ] A 🆔 hom043"), task("- [ ] C"))
        assertEquals(listOf("hom043", "job017"), TicketRail.onRail(open).map { TicketRail.railId(it.raw) })
        val done = listOf(
            TaskNote.parseDone("- [x] D 🆔 hom042 ✅ 2026-11-20")!!,
            TaskNote.parseDone("- [x] E ✅ 2026-11-20")!!,
            TaskNote.parseDone("- [x] F 🆔 hom040 ✅ 2026-11-19")!!,
        )
        assertEquals(1, TicketRail.clearedOn(done, today).size)
        assertEquals(setOf("job017", "hom043", "hom042", "hom040"), TicketRail.taken(open, done))
        assertEquals("B", TicketRail.find(open, "JOB017")?.text)
        assertEquals("D", TicketRail.findDone(done, "hom042")?.text)
    }

    @Test fun `slot words`() {
        assertEquals("TODAY" to "FRI 20 NOV", TicketRail.slot(today, today))
        assertEquals("LATE 2D" to "WAS WED", TicketRail.slot(today.minusDays(2), today))
        assertEquals("TOMORROW" to "SAT", TicketRail.slot(today.plusDays(1), today))
        assertEquals("TUE" to "24 NOV", TicketRail.slot(today.plusDays(4), today))
        assertEquals("ANY TIME" to "", TicketRail.slot(null, today))
    }

    @Test fun `a ticket carries what prints`() {
        val t = task("- [ ] #task Send portfolio link #jobs 📅 2026-11-18", "08 - Jobs/Applied.md")
        val k = TicketRail.ticket(t, "job017", today, LocalTime.of(7, 42))
        assertEquals("JOB 017", k.code)
        assertEquals("LATE 2D", k.slot)
        assertEquals("JOBS", k.tag)
        assertEquals("Send portfolio link", k.text)
        assertEquals("Jobs / Applied", k.source)
        assertEquals("Inbox / Quick Task", TicketRail.source(VaultTasks.QUICK_TASK))
    }

    @Test fun `the QR is square with finder corners`() {
        val m = TicketRail.qr("hom042")
        assertEquals(m.size, m[0].size)
        assertTrue(m[0][0] && m[0][6] && m[6][0] && !m[1][1])
    }

    @Test fun `Ticket Rail is a Hangar tool that sits at the desk`() {
        assertEquals(listOf(HangarTool.DID_I, HangarTool.TICKET_RAIL), Hangar.parse("didi,rail"))
        assertTrue(HangarTool.DID_I.out)
        assertTrue(!HangarTool.TICKET_RAIL.out)
        assertTrue(HangarTool.TICKET_RAIL.ready)
    }

    @Test fun `giving an id writes it to the task's own note`() {
        val note = mutableListOf("## Active", "- [ ] #task Book the MOT 📅 2026-11-20", "", "## Archive")
        val t = TaskNote.list(note).single()
        TaskNote.replace(note, t.raw, TicketRail.withId(t.raw, "tsk001"))
        assertEquals("- [ ] #task Book the MOT 📅 2026-11-20 🆔 tsk001", note[1])
        assertEquals("Book the MOT", TaskNote.list(note).single().text)
    }
    @Test fun `a Print now link names a task by its line`() {
        val link = "flightdeck://print?note=00%20-%20Inbox%2FNext%20steps.md&task=%23task%20Reply%20yes%20or%20no%20to%20Sam%20%23mail%20%F0%9F%93%85%202026-11-20"
        val ask = TicketRail.parsePrint(link)!!
        assertEquals(PrintAsk("00 - Inbox/Next steps.md", "#task Reply yes or no to Sam #mail 📅 2026-11-20"), ask)
        assertNull(TicketRail.parsePrint("flightdeck://clear/mai004"))
        assertNull(TicketRail.parsePrint("flightdeck://print?note=..%2Fx.md&task=a"))
        assertNull(TicketRail.parsePrint("flightdeck://print?note=x.md"))

        val path = "00 - Inbox/Next steps.md"
        val open = listOf(
            task("- [ ] #task Reply yes or no to Sam later #mail 📅 2026-11-20", path),
            task("- [ ] #task Reply yes or no to Sam #mail 📅 2026-11-20", "Elsewhere.md"),
            task("- [ ] #task Reply yes or no to Sam #mail 📅 2026-11-20", path),
        )
        assertEquals(open[2], TicketRail.findForPrint(open, ask))
        // Printed before: still found, and keeps its id.
        val printed = task("- [ ] #task Reply yes or no to Sam #mail 📅 2026-11-20 🆔 mai004", path)
        assertEquals("mai004", TicketRail.railId(TicketRail.findForPrint(listOf(printed), ask)!!.raw))
        assertNull(TicketRail.findForPrint(open.take(2), ask))
        assertEquals("mai", TicketRail.prefix(open[2].text))
    }

    @Test fun `coming up is the next week's unprinted tasks, soonest first`() {
        val open = listOf(
            task("- [ ] #task Sat job 📅 2026-11-28"),
            task("- [ ] #task Today job 📅 2026-11-20"),
            task("- [ ] #task Sun job 📅 2026-11-22"),
            task("- [ ] #task Printed 📅 2026-11-23 🆔 err007"),
            task("- [ ] #task Far off 📅 2026-12-10"),
            task("- [ ] #task No date"),
        )
        assertEquals(listOf("Sun job", "Sat job"), TicketRail.comingUp(open, today).map { it.text })
        assertEquals(listOf("Sun job", "Sat job", "Far off"), TicketRail.comingUp(open, today, 30).map { it.text })
    }

    @Test fun `a ticket printed ahead says which day it's for`() {
        val t = task("- [ ] #task #errand Pick up parcel 📅 2026-11-24") // Tuesday
        val k = TicketRail.ticket(t, "err007", today, LocalTime.of(21, 59))
        assertEquals("FOR TUE · PRINTED FRI", k.early)
        assertEquals("FRI 21:59", k.printedAt)
        assertTrue(TicketRail.isLater(t, today))
        val now = TicketRail.ticket(task("- [ ] #task Bins 📅 2026-11-20"), "hom001", today, LocalTime.of(7, 5))
        assertEquals("", now.early)
        assertEquals("07:05", now.printedAt)
        val far = TicketRail.ticket(task("- [ ] #task Far 📅 2026-12-05"), "tsk001", today, LocalTime.of(7, 5))
        assertEquals("FOR 5 DEC · PRINTED FRI", far.early)
    }

    @Test fun `new ticket words`() {
        assertEquals("#home Fix the shed door", TicketRail.jobText(" Fix the shed door ", "#home"))
        assertEquals("Fix it", TicketRail.jobText("Fix it", null))
        assertEquals("hom", TicketRail.prefix(TicketRail.jobText("Fix", "#home")))
        assertEquals(listOf("Tomorrow", "Sun", "Mon"), TicketRail.dayChips(today).take(3).map { TicketRail.chipLabel(it, today) })
        assertEquals("WED 25", TicketRail.upChip(LocalDate.of(2026, 11, 25)))
    }
}
