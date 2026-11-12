package uk.tomlowndes.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class BillsTest {
    @Test fun parsesAmounts() {
        assertEquals(BigDecimal("38.50"), Bills.amount("38.50"))
        assertEquals(BigDecimal("38.5"), Bills.amount("£38.5"))
        assertEquals(BigDecimal("38.50"), Bills.amount(" 38,50 "))
        assertNull(Bills.amount(""))
        assertNull(Bills.amount("abc"))
    }

    @Test fun writesTheTaskLine() {
        assertEquals("- [ ] #task Car insurance £38.50 #bill 🔁 every month 📅 2026-11-26",
            Bills.preview("Car  insurance ", BigDecimal("38.5"), BillRepeat.MONTHLY, LocalDate.of(2026, 11, 26)))
        assertEquals("TV licence #bill", Bills.text("TV licence", null, BillRepeat.ONCE))
        // What it writes reads back as a bill with its name and amount.
        val raw = Bills.preview("Car insurance", BigDecimal("38.5"), BillRepeat.MONTHLY, LocalDate.of(2026, 11, 26))
        val t = TaskNote.list(listOf("## Active", raw)).single()
        assertEquals(true, Deck.isBill(t))
        val b = TaskViews.bill(t)
        assertEquals("Car insurance", b.name)
        assertEquals(BigDecimal("38.50"), b.amount)
        assertEquals(LocalDate.of(2026, 11, 26), t.due)
    }

    @Test fun suggestsPastBillsNewestFirst() {
        val open = listOf(
            TaskItem("- [ ] Council tax £142 #bill 📅 2026-11-12", "Council tax £142 #bill", LocalDate.of(2026, 11, 12)),
            TaskItem("- [ ] #task Book dentist", "Book dentist", null),
        )
        val done = listOf(
            TickedTask("- [x] Spotify £11.99 #bill 📅 2026-11-14 ✅ 2026-11-14", "Spotify £11.99 #bill", LocalDate.of(2026, 11, 14)),
            TickedTask("- [x] Council tax £138 #bill 📅 2026-10-12 ✅ 2026-10-12", "Council tax £138 #bill", LocalDate.of(2026, 10, 12)),
        )
        val s = Bills.suggestions(open, done)
        assertEquals(listOf("Spotify", "Council tax"), s.map { it.name })
        assertEquals(BigDecimal("142"), s[1].amount)
    }
}
