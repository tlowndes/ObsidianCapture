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
import java.time.YearMonth

class DayToDayTest {
    @get:Rule val tmp = TemporaryFolder()
    private val today = LocalDate.of(2026, 11, 20)
    private val now = LocalDateTime.of(2026, 11, 20, 17, 40)

    @Test fun `cool-off lines round trip and count down`() {
        val text = "# Cool-off\n\n- 2026-11-19 10:40 · Sony headphones · £249 · https://amazon.co.uk/dp/1 · ⏳ 48h\n" +
            "- 2026-11-18 11:00 · Desk lamp · £45.50 · ⏳ 48h · 📉 2026-11-19 £39, 2026-11-20 £42\n" +
            "- 2026-11-01 09:00 · Keyboard · £86 · ⏳ 24h · ✗ let go 2026-11-02\n- junk\n"
        val cs = CoolOff.parse(text)
        assertEquals(3, cs.size)
        cs.forEach { assertEquals(it, CoolOff.line(CoolOff.format(it))) }
        assertEquals("17h", CoolOff.leftWords(cs[0], now))
        assertTrue(CoolOff.isDue(cs[1], now))
        assertEquals(listOf("Desk lamp", "Sony headphones"), CoolOff.cooling(cs).map { it.what })
        assertEquals(86.0, CoolOff.notSpent(cs, YearMonth.of(2026, 11)), 0.001)
        assertEquals("now £42, was £45.50", CoolOff.priceWords(cs[1]))
        assertEquals(3.5, CoolOff.drop(cs[1])!!, 0.001)
        val more = CoolOff.wait(cs[1], now)
        assertEquals(now.plusHours(48).withMinute(0).hour, more.dueAt.hour)
    }

    @Test fun `price tracking keeps changes and replaces the same day`() {
        val c = CoolItem("Lamp", now, 45.0, "https://ikea.com/lamp")
        val a = CoolOff.track(c, 45.0, today)
        assertTrue(a.prices.isEmpty())
        val b = CoolOff.track(a, 39.0, today)
        assertEquals(listOf(PricePoint(today, 39.0)), b.prices)
        assertTrue(CoolOff.droppedSince(a, b))
        val b2 = CoolOff.track(b, 41.0, today)
        assertEquals(listOf(PricePoint(today, 41.0)), b2.prices)
        val c2 = CoolOff.track(b2, 41.0, today.plusDays(1))
        assertEquals(1, c2.prices.size)
        val noPrice = CoolOff.track(CoolItem("Kettle", now, link = "https://x.com"), 30.0, today)
        assertEquals("£30", CoolOff.priceWords(noPrice))
        var many = c
        repeat(20) { many = CoolOff.track(many, 50.0 + it, today.plusDays(it.toLong())) }
        assertEquals(8, many.prices.size)
        assertEquals(50.0, many.prices.first().price, 0.001)
    }

    @Test fun `price scrape reads structured data and price tags`() {
        val ld = """<html><head><script type="application/ld+json">{"@type":"Product","offers":{"@type":"Offer","price":"229.99","priceCurrency":"GBP"}}</script></head></html>"""
        assertEquals(229.99, PriceScrape.parse(ld)!!, 0.001)
        assertEquals(45.0, PriceScrape.parse("""<meta property="product:price:amount" content="45.00">""")!!, 0.001)
        assertEquals(1249.0, PriceScrape.parse("""<div class="a-price"><span class="a-offscreen">£1,249.00</span></div>""")!!, 0.001)
        assertNull(PriceScrape.parse("<p>No price here</p>"))
    }

    @Test fun `cool-off buzzes skip the night and shares become items`() {
        val late = CoolItem("Late", LocalDateTime.of(2026, 11, 19, 22, 30), hours = 24)
        val day = CoolItem("Day", LocalDateTime.of(2026, 11, 19, 18, 0), hours = 48)
        val b = CoolOff.buzzes(listOf(late, day), now)
        assertEquals(LocalDateTime.of(2026, 11, 21, 9, 0), b.first { it.second == late }.first)
        assertEquals(LocalDateTime.of(2026, 11, 21, 18, 0), b.first { it.second == day }.first)
        val s = CoolOff.fromShare("Check this out £35.00 https://www.ikea.com/gb/lamp.", "Desk lamp - IKEA", now)!!
        assertEquals("Desk lamp", s.what)
        assertEquals("https://www.ikea.com/gb/lamp", s.link)
        assertEquals(35.0, s.price!!, 0.001)
        assertNull(CoolOff.fromShare("no link", null, now))
    }

    @Test fun `cool-off saves in the vault`() {
        val v = FileVault(tmp.root)
        assertEquals(0, CoolOff.load(v).size)
        val c = CoolItem("Lamp", now, 45.0, "https://ikea.com/lamp")
        CoolOff.add(v, c)
        val tracked = CoolOff.track(c, 39.0, today)
        CoolOff.saveAll(v, listOf(tracked))
        assertEquals(listOf(tracked), CoolOff.load(v))
        CoolOff.update(v, tracked, CoolOff.letGo(tracked, today))
        assertEquals(CoolStep.LET_GO, CoolOff.load(v).single().step)
    }

    @Test fun `refill counts down from Did I ticks`() {
        val m = Refill.line("- Sertraline 50mg · 10 left · 1 a day · follows Meds · counted 2026-11-18 20:00 · box 28")!!
        assertEquals(m, Refill.line(Refill.format(m)))
        val stamps = listOf(
            LocalDate.of(2026, 11, 18) to DidStamp(LocalTime.of(8, 0), "Meds"), // before the count
            LocalDate.of(2026, 11, 19) to DidStamp(LocalTime.of(8, 0), "Meds"),
            LocalDate.of(2026, 11, 19) to DidStamp(LocalTime.of(8, 5), "meds"), // a double tap counts once
            LocalDate.of(2026, 11, 20) to DidStamp(LocalTime.of(8, 0), "Meds"),
            LocalDate.of(2026, 11, 20) to DidStamp(LocalTime.of(9, 0), "Door"),
        )
        val left = Refill.left(m, stamps)
        assertEquals(8, left)
        assertEquals(LocalDate.of(2026, 11, 28), Refill.runsOut(m, left, today))
        assertEquals(LocalDate.of(2026, 11, 21), Refill.reorderOn(m, left, today))
        assertFalse(Refill.needsOrder(m, left, today))
        assertTrue(Refill.needsOrder(m, 6, today))
        assertEquals(LocalDateTime.of(2026, 11, 21, 9, 0), Refill.buzz(m, left, now))
        assertEquals(LocalDateTime.of(2026, 11, 21, 9, 0), Refill.buzz(m, 3, now))
        assertNull(Refill.buzz(m.copy(ordered = true), 3, now))
        val p = Refill.pickedUp(m.copy(ordered = true), left, now)
        assertEquals(36, p.count)
        assertFalse(p.ordered)
        assertEquals("runs out Sat 28 Nov", Refill.runsOutWords(m, left, today))
        val half = Refill.line("- Vitamin D · 41 left · ½ a day · counted 2026-11-20 08:00")!!
        assertEquals(0.5, half.perDay, 0.001)
        assertEquals("Meds", half.check)
    }

    @Test fun `refill reads Did I logs from the vault`() {
        val v = FileVault(tmp.root)
        DidI.stamp(v, "Meds", LocalDateTime.of(2026, 11, 19, 8, 0))
        DidI.stamp(v, "Meds", LocalDateTime.of(2026, 11, 20, 8, 0))
        val m = Med("Sertraline", 10, LocalDateTime.of(2026, 11, 18, 20, 0))
        Refill.add(v, m)
        assertEquals(listOf(m), Refill.load(v))
        assertEquals(8, Refill.left(m, Refill.stamps(v, listOf(m), today)))
    }

    @Test fun `waiting mode picks short tasks and reads`() {
        val tasks = listOf(TaskItem("- [ ] Reply to Sam ⏱ 5m", "Reply to Sam", null), TaskItem("- [ ] Tidy #quick", "Tidy", null),
            TaskItem("- [ ] Write report ⏱ 60m", "Write report", null))
        assertEquals(listOf("Reply to Sam", "Tidy"), WaitingMode.quickTasks(tasks).map { it.text })
        val reads = listOf(SavedArticle(ReadRef("a.md", false), "Short", null, "", false, 0, 800),
            SavedArticle(ReadRef("b.md", false), "Long", null, "", false, 0, 5000),
            SavedArticle(ReadRef("c.md", false), "Done", null, "", true, 0, 500))
        assertEquals(listOf("Short"), WaitingMode.shortReads(reads).map { it.title })
        assertEquals("Tidy", WaitingMode.pick(WaitingMode.quickTasks(tasks), 3)?.text)
        assertNull(WaitingMode.pick(emptyList<String>(), 0))
    }

    @Test fun `errand run orders stops nearest first and adds the post office`() {
        val tesco = Place("@tesco", "Tesco", 51.500, -0.100, 200)
        val bq = Place("@b&q", "B&Q", 51.530, -0.100, 200)
        val po = Place("@postoffice", "Post Office", 51.510, -0.100, 200)
        val t1 = TaskItem("- [ ] Wall plugs @b&q", "Wall plugs @b&q", null)
        val errands = listOf(Errand(bq, "@b&q", listOf("Wall plugs"), listOf(t1)), Errand(tesco, "@tesco", listOf("Milk"), emptyList()),
            Errand(null, "@chemist", listOf("Plasters"), emptyList()))
        val ret = Return("Boots", "ASOS", today.plusDays(1), ReturnStep.PACKED)
        val stops = ErrandRun.stops(errands, listOf(ret, Return("Coat", "Zara", today, ReturnStep.DROPPED)), listOf(tesco, bq, po), 51.4995 to -0.100, today)
        assertEquals(listOf("Tesco", "Post Office", "B&Q", "Chemist"), stops.map { it.title })
        assertEquals("↩ Return boots (ASOS) · due tomorrow", stops[1].items.single().text)
        assertEquals(t1, stops[2].items.single().task)
        assertEquals("Here", ErrandRun.distance(stops[0].metres!!))
        assertEquals("2.1 mi", ErrandRun.distance(3400.0))
        val url = ErrandRun.mapsUrl(stops)!!
        assertTrue(url.contains("destination=51.53000,-0.10000"))
        assertTrue(url.contains("waypoints=51.50000,-0.10000%7C51.51000,-0.10000"))
        assertEquals("4 stops" to "Tesco, Post Office, B&Q", ErrandRun.summary(stops))
        assertEquals(listOf("B&Q", "Tesco", "Chemist"), ErrandRun.stops(errands, emptyList(), listOf(tesco, bq), null, today).map { it.title })
    }
}
