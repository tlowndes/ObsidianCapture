package uk.tomlowndes.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

class PlacesTest {
    private val today = LocalDate.of(2026, 11, 24)
    private fun task(line: String) = TaskNote.parse(line)!!

    private val start = PlaceState(
        layout = DeckLayout(defaultCardOrder(), setOf(HomeCard.SPEND, HomeCard.MOOD)),
        hangar = listOf(HangarTool.DID_I, HangarTool.HOME, HangarTool.INBOX),
        tiles = listOf(HangarTool.TICKET_RAIL),
    )

    @Test fun `places come from the cards, Hangar and More tiles`() {
        assertEquals(Lives.BOTH, start.place(Element.CHORES)) // card on and Home in Hangar
        assertEquals(Lives.HANGAR, start.place(Element.DID_I)) // in Hangar, not a More tile
        assertEquals(Lives.BOTH, start.place(Element.TICKET_RAIL)) // a More tile, not in Hangar until the move
        assertEquals(Lives.OFF, start.place(Element.REFILL))
        assertEquals(Lives.MAIN, start.place(Element.NEXT_STEP))
        assertEquals(Lives.OFF, start.place(Element.MOOD))
        assertEquals(Lives.MAIN, start.place(Element.UPCOMING))
    }

    @Test fun `moving bills to Hangar hides the card and adds the tile`() {
        val s = start.with(Element.BILLS, Lives.HANGAR)
        assertTrue(HomeCard.BILLS in s.layout.hidden)
        assertTrue(HangarTool.BILLS in s.hangar)
        assertEquals(Lives.HANGAR, s.place(Element.BILLS))
        assertFalse(s.main(Element.BILLS))
    }

    @Test fun `both and off for a plain tool use the More tiles`() {
        val both = start.with(Element.DID_I, Lives.BOTH)
        assertTrue(HangarTool.DID_I in both.tiles)
        assertEquals(Lives.BOTH, both.place(Element.DID_I))
        val off = both.with(Element.DID_I, Lives.OFF)
        assertFalse(HangarTool.DID_I in off.hangar)
        assertFalse(HangarTool.DID_I in off.tiles)
        assertEquals(Lives.OFF, off.place(Element.DID_I))
    }

    @Test fun `Upcoming switches its Tasks view`() {
        val off = start.with(Element.UPCOMING, Lives.OFF)
        assertTrue("UPCOMING" in off.viewsOff)
        assertEquals(Lives.MAIN, off.with(Element.UPCOMING, Lives.MAIN).place(Element.UPCOMING))
    }

    @Test fun `the one-off move keeps everything on and puts Bills first in Hangar only`() {
        val m = Places.migrated(start)
        assertEquals(HangarTool.BILLS, m.hangar.first())
        assertEquals(Lives.HANGAR, m.place(Element.BILLS))
        // Habits was on as a card: it keeps the card and gains its Hangar tile.
        assertEquals(Lives.BOTH, m.place(Element.HABITS))
        Element.values().filter { it != Element.BILLS }.forEach { e -> assertEquals(e.name, start.on(e), m.on(e)) }
    }

    @Test fun `state round trips through capture-config json and keeps other keys`() {
        val s = start.with(Element.BILLS, Lives.HANGAR).with(Element.UPCOMING, Lives.OFF)
        val json = Places.withState("""{"plannedOn":"2026-11-24"}""", s, 42L)
        val back = Places.state(json)
        assertEquals(s.hangar, back.hangar)
        assertEquals(s.tiles, back.tiles)
        assertEquals(s.viewsOff, back.viewsOff)
        assertEquals(s.layout.hidden, back.layout.hidden)
        assertEquals(42L, Places.hangarAt(json))
        assertTrue(Places.moved(json))
        assertEquals("2026-11-24", DeckConfig.dayDone(json).plannedOn)
    }

    @Test fun `a newer Hangar list from the other device wins, even with different tools`() {
        val phone = listOf(HangarTool.DID_I, HangarTool.INBOX)
        val vault = listOf(HangarTool.INBOX)
        assertEquals(vault, Places.synced(phone, 10L, vault, 20L))
        assertEquals(phone, Places.synced(phone, 30L, vault, 20L))
    }

    private val bills = listOf(
        task("- [ ] #task DVLA £37.62 #bill 🔁 every month 📅 2026-12-01"),
        task("- [ ] #task Hastings Direct £85.40 #bill 🔁 every month 📅 2026-12-01"),
        task("- [ ] #task Car tax £180 #bill 🔁 every year 📅 2027-03-05"),
        task("- [ ] #task Bins out #home 📅 2026-12-01"),
    )

    @Test fun `a month lists its bills, totals them and groups by day`() {
        val dec = BillPage.month(bills, emptyList(), YearMonth.of(2026, 12), today)
        assertEquals(2, dec.rows.size)
        assertEquals(BigDecimal("123.02"), dec.toPay)
        assertEquals(1, dec.byDay.size)
    }

    @Test fun `monthly bills show in later months as coming, yearly only in their month`() {
        val jan = BillPage.month(bills, emptyList(), YearMonth.of(2027, 1), today)
        assertEquals(2, jan.rows.size)
        assertTrue(jan.rows.all { it.projected && it.due == LocalDate.of(2027, 1, 1) })
        val mar = BillPage.month(bills, emptyList(), YearMonth.of(2027, 3), today)
        assertTrue(mar.rows.any { it.bill.name.startsWith("Car tax") && !it.projected })
    }

    @Test fun `paid bills count as paid in the month they were ticked`() {
        val done = listOf(TickedTask("- [x] #task Proton £17.58 #bill 📅 2026-11-20 ✅ 2026-11-20", "Proton £17.58 #bill", LocalDate.of(2026, 11, 20)))
        val nov = BillPage.month(bills, done, YearMonth.of(2026, 11), today)
        assertEquals(1, nov.rows.count { it.paid })
        assertEquals(BigDecimal("17.58"), nov.paid)
    }

    @Test fun `repeat words read the rule`() {
        assertEquals("monthly", BillPage.repeatWords("- [ ] #task DVLA £37.62 #bill 🔁 every month 📅 2026-12-01"))
        assertEquals(null, BillPage.repeatWords("- [ ] #task One-off £5 #bill 📅 2026-12-01"))
    }

    @Test fun `next line names the soonest day`() {
        assertEquals("£37.62 + 1 more due Tue 1 Dec", BillPage.nextLine(bills, today))
    }

    @Test fun `settings clean-up gives each element plain choices and one word`() {
        assertEquals(listOf(Lives.OFF, Lives.HANGAR, Lives.BOTH), Element.BILLS.choices)
        assertEquals(listOf(Lives.OFF, Lives.MAIN), Element.WEATHER.choices)
        assertEquals("Hangar", Element.BILLS.pill(Lives.HANGAR))
        assertEquals("Hangar + Deck + Tasks", Element.BILLS.pill(Lives.BOTH))
        assertEquals("Hangar + Deck", Element.BRIEF.pill(Lives.BOTH))
        assertEquals("On", Element.WEATHER.pill(Lives.MAIN))
        assertEquals("Always on", Element.NEXT_STEP.pill(Lives.MAIN))
        assertEquals("Off", Element.BILLS.pill(Lives.OFF))
    }
}
