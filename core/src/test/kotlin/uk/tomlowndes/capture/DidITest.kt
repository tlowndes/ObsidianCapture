package uk.tomlowndes.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class DidITest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `the starter checks read with their questions`() {
        val c = DidI.checks(DidI.STARTER)
        assertEquals(listOf("Meds", "Door", "Hob"), c.map { it.name })
        assertEquals(DidCheck("🔥", "Hob", "Is the hob off?"), c[2])
    }

    @Test fun `a check without an emoji or question still reads`() {
        val c = DidI.checks("- Plugs\n- 🪟 Windows\n- [ ] not a check")
        assertEquals(listOf(DidCheck("✓", "Plugs"), DidCheck("🪟", "Windows")), c)
        assertEquals("- 🪟 Windows", DidI.checkLine(c[1]))
    }

    @Test fun `checks are added after the last one and not twice`() {
        val lines = DidI.STARTER.split("\n").toMutableList()
        DidI.addCheck(lines, DidCheck("🔌", "Straighteners", "Straighteners off?"))
        DidI.addCheck(lines, DidCheck("🔒", "door"))
        val c = DidI.checks(lines.joinToString("\n"))
        assertEquals(listOf("Meds", "Door", "Hob", "Straighteners"), c.map { it.name })
    }

    @Test fun `status uses the latest stamp and finds the next open check`() {
        val checks = DidI.checks(DidI.STARTER)
        val stamps = DidI.stamps("# Did I?\n\n- 07:55 · Meds\n- 08:01 · door\n- 08:12 · Door\n")
        val s = DidI.status(checks, stamps)
        assertEquals(LocalTime.of(8, 12), s[1].at)
        assertEquals("Hob", DidI.nextOpen(s)?.check?.name)
        assertEquals("2 of 3", DidI.progress(s))
        val all = DidI.status(checks, stamps + DidStamp(LocalTime.of(8, 13), "Hob"))
        assertNull(DidI.nextOpen(all))
        assertEquals("All done", DidI.progress(all))
    }

    @Test fun `undo removes only the newest matching line`() {
        val lines = mutableListOf("# Did I?", "", "- 08:12 · Door", "- 08:12 · Door")
        assertTrue(DidI.removeStamp(lines, DidStamp(LocalTime.of(8, 12), "Door")))
        assertEquals(listOf("# Did I?", "", "- 08:12 · Door"), lines)
        assertFalse(DidI.removeStamp(lines, DidStamp(LocalTime.of(9, 0), "Door")))
    }

    @Test fun `stamping makes the notes and undo takes it back`() {
        val v = FileVault(tmp.root)
        val day = LocalDate.of(2026, 11, 20)
        assertEquals("0 of 3", DidI.progress(DidI.today(v, day)))
        val s = DidI.stamp(v, "Door", LocalDateTime.of(2026, 11, 20, 8, 12, 40))
        assertEquals(DidStamp(LocalTime.of(8, 12), "Door"), s)
        assertTrue(v.read(DidI.logPath(day))!!.startsWith("# Did I? · Friday 20 November 2026"))
        assertEquals(LocalTime.of(8, 12), DidI.today(v, day)[1].at)
        DidI.undo(v, day, s)
        assertNull(DidI.today(v, day)[1].at)
        DidI.saveCheck(v, DidCheck("🪴", "Plants"))
        assertEquals(4, DidI.loadChecks(v).size)
    }

    @Test fun `hangar layout round-trips and moves`() {
        assertNull(Hangar.parse(null))
        assertEquals(emptyList<HangarTool>(), Hangar.parse(""))
        val t = Hangar.parse("didi,nope,launch,didi")!!
        assertEquals(listOf(HangarTool.DID_I, HangarTool.LAUNCH_PAD), t)
        assertEquals("launch,didi", Hangar.format(Hangar.move(t, 1, -1)))
        assertEquals(t, Hangar.move(t, 0, -1))
        assertEquals(setOf(HangarTool.HEADS_UP, HangarTool.NIGHT_PAD, HangarTool.POCKET_PASS, HangarTool.LAST_SEEN, HangarTool.RETURN_RADAR, HangarTool.COOL_OFF, HangarTool.REFILL, HangarTool.WAITING, HangarTool.TICKET_RAIL, HangarTool.SENSORY,
            HangarTool.INBOX, HangarTool.HOME, HangarTool.PROJECTS, HangarTool.ERRANDS, HangarTool.MEALS), Hangar.available(t).toSet())
        assertEquals(HangarTool.DID_I, Hangar.available(emptyList()).first())
    }

    @Test fun `errand run leaves hangar but is remembered once`() {
        val saved = "didi,run,launch"
        assertEquals(listOf(HangarTool.DID_I, HangarTool.LAUNCH_PAD), Hangar.parse(saved))
        assertTrue(Hangar.hadRun(saved))
        assertFalse(Hangar.hadRun(Hangar.format(Hangar.parse(saved)!!)))
        assertFalse(HangarTool.ERRAND_RUN in Hangar.available(emptyList()))
    }

    @Test
    fun deckToolsMoveIntoAnExistingHangar() {
        val old = listOf(HangarTool.DID_I, HangarTool.LAUNCH_PAD, HangarTool.HEADS_UP, HangarTool.NIGHT_PAD)
        val now = Hangar.withDeckTools(old)
        assertEquals(listOf(HangarTool.INBOX, HangarTool.DID_I, HangarTool.HOME, HangarTool.ERRANDS, HangarTool.LAUNCH_PAD,
            HangarTool.HEADS_UP, HangarTool.PROJECTS, HangarTool.MEALS, HangarTool.NIGHT_PAD), now)
        // Running it twice adds nothing more, and a tool already there stays put.
        assertEquals(now, Hangar.withDeckTools(now))
        assertEquals(listOf(HangarTool.INBOX, HangarTool.HOME, HangarTool.ERRANDS, HangarTool.PROJECTS, HangarTool.MEALS), Hangar.withDeckTools(null))
        assertEquals("Around home", HangarTool.MEALS.group)
    }

    @Test
    fun hangarOrderSharedWithWindows() {
        val phone = listOf(HangarTool.DID_I, HangarTool.INBOX, HangarTool.HOME)
        val pc = listOf(HangarTool.INBOX, HangarTool.HOME, HangarTool.DID_I)
        assertEquals(pc, Hangar.merged(phone, pc))
        assertEquals(phone, Hangar.merged(phone, null))
        assertEquals(phone, Hangar.merged(phone, listOf(HangarTool.INBOX)))
        assertEquals(pc, Hangar.moved(phone, 0, 2))
        assertEquals(phone, Hangar.moved(phone, 1, 9))
        val json = DeckConfig.withHangar("""{"cards":["TODAY"]}""", Hangar.format(pc))
        assertEquals(pc, Hangar.parse(DeckConfig.hangar(json)))
        assertTrue(json.contains("TODAY"))
        assertNull(DeckConfig.hangar("{}"))
    }
}
