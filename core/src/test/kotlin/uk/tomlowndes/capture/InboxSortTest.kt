package uk.tomlowndes.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/** inbox-flags-groups-v1: important / not important, sender groups, Bills and Deliveries, Proton Calendar queue. */
class InboxSortTest {
    @get:Rule val tmp = TemporaryFolder()
    private val today = LocalDate.of(2026, 11, 16)

    private fun mail(id: String, from: String, kind: MailKind, hour: Int, due: LocalDate? = null, amount: String? = null, window: String? = null) =
        Mail(id, kind, from, "Subject $id", LocalDateTime.of(2026, 11, 16, hour, 0), "Summary $id", "Gist $id", due = due,
            amount = amount?.let { BigDecimal(it) }, billDue = if (kind == MailKind.BILL) due else null, window = window)

    private val digest = Digest(
        LocalDateTime.of(2026, 11, 16, 11, 0), 20, 9, listOf(
            mail("1", "Indeed", MailKind.JOB, 10), mail("2", "Indeed", MailKind.JOB, 8),
            mail("3", "Shakti Chawdary", MailKind.REPLY, 9), mail("4", "Shakti Chawdary <s@davisons.law>", MailKind.REPLY, 7),
            mail("5", "Currys flexpay", MailKind.BILL, 6, today.plusDays(8), "42.40"),
            mail("6", "Octopus Energy", MailKind.BILL, 5, today.plusDays(12), "26"),
            mail("7", "Amazon", MailKind.DELIVERY, 4, today, window = "07:00-13:00"),
            mail("8", "Evri", MailKind.DELIVERY, 3, today.minusDays(1)),
            mail("9", "LinkedIn", MailKind.JOB, 2), mail("10", "LinkedIn", MailKind.REPLY, 1),
        )
    )

    @Test fun `sections put each email in one place`() {
        val s = MailDigest.BoxSettings(hide = listOf(HideRule("LinkedIn", alertsOnly = true)), important = listOf("Shakti Chawdary"))
        val sec = InboxSort.sections(digest, mapOf("2" to "dismissed"), s, today)
        assertEquals(listOf("3", "4"), sec[InboxSection.IMPORTANT]!!.map { it.id })
        assertEquals(listOf("5", "6"), sec[InboxSection.BILLS]!!.map { it.id })
        assertEquals(listOf("7"), sec[InboxSection.DELIVERIES]!!.map { it.id })
        assertEquals(listOf("1", "10"), sec[InboxSection.NEEDS_YOU]!!.map { it.id })
        assertEquals(listOf("9"), sec[InboxSection.HIDDEN]!!.map { it.id })
        assertEquals(setOf("2", "8"), sec[InboxSection.HANDLED]!!.map { it.id }.toSet())
        assertEquals(10, sec.values.sumOf { it.size })
        assertEquals(6, InboxSort.needsCount(sec))
        assertEquals(BigDecimal("68.40"), InboxSort.billsTotal(sec[InboxSection.BILLS]!!))
    }

    @Test fun `one email can be pinned and a whole sender hidden`() {
        val s = MailDigest.BoxSettings(hide = listOf(HideRule("indeed")))
        val sec = InboxSort.sections(digest, mapOf("10" to MailDigest.IMPORTANT), s, today)
        assertEquals(listOf("10"), sec[InboxSection.IMPORTANT]!!.map { it.id })
        assertEquals(listOf("1", "2"), sec[InboxSection.HIDDEN]!!.map { it.id })
        assertTrue(MailDigest.open(digest.items.first { it.id == "10" }, mapOf("10" to MailDigest.IMPORTANT), today))
    }

    @Test fun `groups fold the same sender, address or not`() {
        val g = InboxSort.groups(digest.items.filter { it.kind == MailKind.REPLY || it.kind == MailKind.JOB })
        assertEquals(listOf("Indeed", "Shakti Chawdary", "LinkedIn"), g.map { it.sender })
        assertEquals(listOf("3", "4"), g[1].mails.map { it.id })
    }

    @Test fun `hidden and important lists survive a settings save`() {
        val v = FileVault(tmp.root)
        var s = MailDigest.BoxSettings(every = 60)
        s = InboxSort.hide(s, "Indeed", false, today)
        s = InboxSort.hide(s, "LinkedIn <jobs@linkedin.com>", true, today)
        s = InboxSort.setAlwaysImportant(s, "Shakti Chawdary", true)
        MailDigest.saveSettings(v, s)
        val back = MailDigest.settings(v)
        assertEquals(60, back.every)
        assertEquals(listOf(HideRule("Indeed", false, today), HideRule("LinkedIn", true, today)), back.hide)
        assertEquals(listOf("Shakti Chawdary"), back.important)
        // Check now rewrites the file and keeps both lists.
        MailDigest.checkNow(v, back, LocalDateTime.of(2026, 11, 16, 12, 0))
        assertEquals(2, MailDigest.settings(v).hide.size)
        // Always important takes a sender off the hidden list, and Show again removes it.
        assertEquals(listOf("LinkedIn"), InboxSort.setAlwaysImportant(back, "indeed", true).hide.map { it.sender })
        assertEquals(1, InboxSort.unhide(back, "INDEED").hide.size)
    }

    @Test fun `bill draft falls back to the sender and saves with edits`() {
        val m = digest.items.first { it.id == "5" }
        val d = MailDigest.billDraft(m)
        assertEquals("Currys flexpay", d.name); assertEquals(BigDecimal("42.40"), d.amount); assertEquals(today.plusDays(8), d.due)
        assertEquals(BillRepeat.ONCE, d.repeat)
        assertEquals(BillRepeat.MONTHLY, MailDigest.billDraft(m.copy(repeat = "monthly")).repeat)
        assertTrue(MailDigest.suggest(m, emptyList(), today).bill)
    }

    @Test fun `calendar asks queue once and read back sent`() {
        val v = FileVault(tmp.root)
        val bill = InboxSort.calendarAsk(digest.items.first { it.id == "5" })!!
        assertEquals("Pay Currys flexpay £42.40", bill.title); assertEquals(today.plusDays(8), bill.date); assertNull(bill.start)
        val parcel = InboxSort.calendarAsk(digest.items.first { it.id == "7" })!!
        assertEquals("07:00", parcel.start); assertEquals("13:00", parcel.end)
        assertNull(InboxSort.calendarAsk(digest.items.first { it.id == "1" }))
        InboxSort.askCalendar(v, bill); InboxSort.askCalendar(v, parcel); InboxSort.askCalendar(v, bill)
        val q = InboxSort.calendar(v)
        assertEquals(2, q.size); assertFalse(q.any { it.sent })
        assertEquals("Invite on its way…", InboxSort.calendarStatus(q, "5"))
        // The box ticks the line once it has sent the invite.
        val text = v.read(InboxSort.CALENDAR)!!.replaceFirst("- [ ]", "- [x]")
        VaultIo.write(v, InboxSort.CALENDAR, text)
        assertEquals("In Proton Calendar ✓", InboxSort.calendarStatus(InboxSort.calendar(v), "5"))
    }
}
