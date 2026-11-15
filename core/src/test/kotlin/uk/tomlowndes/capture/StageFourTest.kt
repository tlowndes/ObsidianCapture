package uk.tomlowndes.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

/** Stage 4: Proton inbox digest, spend from receipts, recruiter replies. The fixture is written by box/mailbox/mailbox.py. */
class StageFourTest {
    @get:Rule val tmp = TemporaryFolder()
    private val today = LocalDate.of(2026, 11, 15)
    private val now = LocalDateTime.of(2026, 11, 15, 19, 0)
    private val fixture = javaClass.getResource("/email-digest.md")!!.readText()

    @Test fun `reads the note the box writes`() {
        val d = MailDigest.parse(fixture)
        assertEquals(LocalDateTime.of(2026, 11, 15, 18, 40), d.checked)
        assertEquals(41, d.read); assertEquals(32, d.skipped); assertEquals(0.31, d.costMonth!!, 0.001)
        assertEquals(5, d.items.size)
        val bill = d.items.first { it.kind == MailKind.BILL }
        assertEquals(BigDecimal("86.40"), bill.amount); assertEquals(LocalDate.of(2026, 11, 26), bill.billDue)
        val job = d.items.first { it.kind == MailKind.JOB }
        assertEquals("Monzo", job.company); assertEquals("Interview", job.jobStatus)
        // Kotlin's line round-trips through the same parser.
        assertEquals(job, MailDigest.parse(MailDigest.line(job)).items.single())
    }

    @Test fun `needs you, deliveries and paused`() {
        val d = MailDigest.parse(fixture)
        val open = MailDigest.needsYou(d, emptyMap(), today)
        assertEquals(listOf("Sarah", "Hannah Lee", "Octopus Energy"), open.map { it.from })
        assertEquals(1, MailDigest.deliveries(d, emptyMap(), today).size)
        assertEquals(0, MailDigest.deliveries(d, emptyMap(), today.plusDays(1)).size)
        assertEquals("3 need you · 18:40", MailDigest.heading(d, open.size, now))
        assertTrue(MailDigest.paused(d, now.plusHours(2)))
        assertEquals("Inbox paused · last 18:40", MailDigest.heading(d, 3, now.plusHours(2)))
        val state = mapOf("e5@x" to "dismissed", "b2@x" to "snooze:2026-11-17")
        assertEquals(listOf("Hannah Lee"), MailDigest.needsYou(d, state, today).map { it.from })
        assertEquals(2, MailDigest.needsYou(d, state, today.plusDays(2)).size)
    }

    @Test fun `state is kept in its own note`() {
        val v = FileVault(tmp.root)
        MailDigest.setState(v, "a1@x", "done")
        MailDigest.setState(v, "b2@x", "snooze:2026-11-17")
        MailDigest.setState(v, "a1@x", null)
        assertEquals(mapOf("b2@x" to "snooze:2026-11-17"), MailDigest.state(v))
        MailDigest.saveSettings(v, MailDigest.BoxSettings(deliveries = false, every = 60, checkNow = now))
        val s = MailDigest.settings(v)
        assertFalse(s.deliveries); assertEquals(60, s.every); assertEquals(now, s.checkNow)
    }

    @Test fun `recruiter reply moves the job on`() {
        val v = FileVault(tmp.root)
        val w = VaultWriter(v)
        val path = Jobs.create(v, "Monzo", "Product Designer", today)
        Jobs.setStatus(v, path, "Applied")
        val d = MailDigest.parse(fixture)
        val m = d.items.first { it.kind == MailKind.JOB }
        val s = MailDigest.suggest(m, Jobs.list(v), today)
        assertEquals(path, s.job?.path)
        assertEquals("Interview", s.status)
        assertEquals("Reply to Hannah about Product Designer", s.task)
        assertEquals(2, MailDigest.save(v, w, m, s, task = true, bill = false, job = true, today = today))
        assertEquals("Interview", Jobs.list(v).single().status)
        assertTrue(v.read(path)!!.contains("✉ Hannah Lee: Liked your portfolio"))
        assertEquals("done", MailDigest.state(v)["a1@x"])
        assertTrue(w.listQuickTasks().any { it.text.contains("Reply to Hannah about Product Designer") })
        // A bill email becomes a #bill task.
        val b = d.items.first { it.kind == MailKind.BILL }
        MailDigest.save(v, w, b, MailDigest.suggest(b, emptyList(), today), task = false, bill = true, job = false, today = today)
        assertTrue(w.listQuickTasks().any { it.raw.contains("#bill") && it.raw.contains("Octopus Energy") })
    }

    @Test fun `spend counts receipts and paid bills`() {
        val v = FileVault(tmp.root)
        assertEquals(SpendCategory.FOOD, Spend.guess("Tesco Express"))
        assertEquals(SpendCategory.HOME, Spend.guess("B&Q"))
        assertEquals(SpendCategory.OTHER, Spend.guess("Somewhere"))
        val d = MailDigest.parse(fixture)
        assertEquals(1, Spend.syncMail(v, d))
        assertEquals(0, Spend.syncMail(v, d))
        val tesco = Spend.receipt(LocalDate.of(2026, 11, 14), "Tesco", BigDecimal("23.4"), SpendSource.SNAP)
        assertTrue(Spend.add(v, tesco)); assertFalse(Spend.add(v, tesco))
        val done = listOf(TickedTask("- [x] Council Tax £120 #bill 📅 2026-11-01 ✅ 2026-11-01", "Council Tax £120 #bill", LocalDate.of(2026, 11, 1)))
        val m = Spend.month(v, done, YearMonth.of(2026, 11))
        assertEquals(3, m.receipts.size)
        assertEquals(BigDecimal("162.39"), m.total.setScale(2))
        assertEquals(SpendCategory.BILLS, m.categories.first().first)
        Spend.recategorise(v, m.receipts.first { it.shop == "Tesco" }, SpendCategory.FUN)
        assertEquals(BigDecimal("23.40"), Spend.month(v, done, YearMonth.of(2026, 11)).by(SpendCategory.FUN))
        val oct = MonthSpend(YearMonth.of(2026, 10), listOf(Spend.receipt(LocalDate.of(2026, 10, 3), "Aldi", BigDecimal(70), SpendSource.SNAP)))
        val nov = MonthSpend(YearMonth.of(2026, 11), listOf(Spend.receipt(LocalDate.of(2026, 11, 3), "Aldi", BigDecimal(118), SpendSource.SNAP)))
        assertEquals("£48 more on food than October by now", Spend.compare(nov, oct, today))
        assertNull(Spend.compare(nov, MonthSpend(oct.month, emptyList()), today))
    }

    @Test fun `snap reads a receipt`() {
        val r = Snap.parse("""{"title":"Looks like a receipt","what":"Tesco receipt","items":[{"kind":"note","text":"Tesco £23.40","keep":true}],"receipt":{"shop":"Tesco","amount":"23.40","date":"2026-11-14"}}""")
        assertNotNull(r!!.receipt)
        assertEquals(SpendCategory.FOOD, r.receipt!!.category)
        assertNull(Snap.parse("""{"title":"x","what":"y","items":[],"receipt":null}""")!!.receipt)
        val v = FileVault(tmp.root)
        assertTrue(Snap.saveReceipt(v, r))
        assertTrue(File(tmp.root, "99 - Meta/Spend/2026-11.md").readText().contains("Tesco · £23.40 · Food · snap"))
    }
}
