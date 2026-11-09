package uk.tomlowndes.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class RapidLogTest {
    private val today = LocalDate.of(2026, 11, 9) // Monday

    @Test fun `marks pick the kind`() {
        assertEquals(LogKind.TASK, RapidLog.mark("• Book dentist").first)
        assertEquals(LogKind.NOTE, RapidLog.mark("– Monzo wants motion").first)
        assertEquals(LogKind.NOTE, RapidLog.mark("- Monzo wants motion").first)
        assertEquals(LogKind.EVENT, RapidLog.mark("○ Lunch with Mum").first)
        assertEquals(LogKind.PRIORITY, RapidLog.mark("★ Send link").first)
        assertEquals(LogKind.PRIORITY, RapidLog.mark("! Send link").first)
        assertEquals(true, RapidLog.mark("× Water plants").third)
        assertNull(RapidLog.mark("Just words").first)
        assertEquals("Book dentist", RapidLog.mark("• Book dentist").second)
    }

    @Test fun `date estimate and tags come off the end`() {
        val e = RapidLog.parse("Call Sarah about the portfolio fri 15m #work", today)
        assertEquals("Call Sarah about the portfolio #work", e.text)
        assertEquals(LocalDate.of(2026, 11, 13), e.due)
        assertEquals(15, e.minutes)
        val e2 = RapidLog.parse("• Renew domain 25 nov 1h30", today)
        assertEquals("Renew domain", e2.text)
        assertEquals(LocalDate.of(2026, 11, 25), e2.due)
        assertEquals(90, e2.minutes)
        assertEquals(LocalDate.of(2026, 11, 10), RapidLog.parse("Ring garage tomorrow", today).due)
        assertEquals("Email Tom", RapidLog.parse("Email Tom", today).text)
        assertNull(RapidLog.parse("Email Tom", today).due)
    }

    @Test fun `notes keep their dates and a lone date word stays the text`() {
        val n = RapidLog.parse("– Rosie said try Rive friday", today)
        assertEquals(LogKind.NOTE, n.kind)
        assertEquals("Rosie said try Rive friday", n.text)
        assertNull(n.due)
        assertEquals("Friday", RapidLog.parse("Friday", today).text)
    }

    @Test fun `task text and inbox lines`() {
        assertEquals("Send link ⏱ 10m ⏫", RapidLog.taskText(LogEntry(LogKind.PRIORITY, "Send link", null, 10)))
        assertEquals("- ○ Lunch 13:00", RapidLog.inboxLine(LogEntry(LogKind.EVENT, "Lunch 13:00")))
        assertEquals("- An idea", RapidLog.inboxLine(LogEntry(LogKind.NOTE, "An idea")))
    }

    @Test fun `a scanned page`() {
        val page = """
            Tue 10 Nov
            • ring garage re MOT
            tomorrow
            – Rosie: try Rive for
            logo anim
            × water plants
            ★ send portfolio link
        """.trimIndent()
        val es = RapidLog.page(page, today)
        assertEquals(4, es.size)
        assertEquals(LogEntry(LogKind.NOTE, "Tue 10 Nov"), es[0])
        assertEquals(LogEntry(LogKind.TASK, "ring garage re MOT", LocalDate.of(2026, 11, 10)), es[1])
        assertEquals("Rosie: try Rive for logo anim", es[2].text)
        assertEquals(LogKind.PRIORITY, es[3].kind)
    }

    @Test fun `estimates`() {
        assertEquals(15, RapidLog.minutes("15m"))
        assertEquals(60, RapidLog.minutes("1h"))
        assertEquals(90, RapidLog.minutes("1h30"))
        assertEquals(5, RapidLog.minutes("5min"))
        assertNull(RapidLog.minutes("m"))
        assertNull(RapidLog.minutes("hello"))
    }
}

class InboxAppendTest {
    @get:org.junit.Rule val tmp = org.junit.rules.TemporaryFolder()

    @Test fun `lines go to the end of Inbox md`() {
        val w = VaultWriter(FileVault(tmp.root))
        w.appendInbox(listOf("- first"))
        w.appendInbox(listOf("- ○ Lunch", "- second"))
        assertEquals("- first\n- ○ Lunch\n- second\n", java.io.File(tmp.root, "01 - Inbox/Inbox.md").readText())
    }
}
