package uk.tomlowndes.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDate
import java.time.LocalDateTime

class FindingTest {
    @get:Rule val tmp = TemporaryFolder()
    private val today = LocalDate.of(2026, 11, 20)
    private val now = LocalDateTime.of(2026, 11, 20, 12, 15)

    @Test fun `pocket pass reads codes, hides and drops old bookings`() {
        val text = "# Pocket Pass\n\nsome words · here\n- Me · 🔒 NHS number · 485 777 4821\n- Home · Wi-Fi Lowndes-5G · pa;ss\n" +
            "- Bookings · Train · TRL-8KQ2M · 📅 2026-11-21\n- Bookings · Old train · X1 · 📅 2026-11-19\n- broken line\n"
        val codes = PocketPass.parse(text)
        assertEquals(4, codes.size)
        assertTrue(codes[0].hidden)
        assertEquals("NHS number", codes[0].name)
        assertEquals(listOf("NHS number", "Wi-Fi Lowndes-5G", "Train"), PocketPass.visible(codes, today).map { it.name })
        assertEquals("••• ••• 4821", PocketPass.masked("485 777 4821"))
        assertEquals("Lowndes-5G", codes[1].ssid)
        assertEquals("WIFI:T:WPA;S:Lowndes-5G;P:pa\\;ss;;", PocketPass.wifiText(codes[1].ssid, codes[1].value))
        codes.forEach { assertEquals(it, PocketPass.line(PocketPass.format(it))) }
        assertTrue(PocketPass.qr("hello").isNotEmpty())
    }

    @Test fun `pocket pass adds and removes in the note`() {
        val v = FileVault(tmp.root)
        assertEquals(0, PocketPass.load(v).size)
        val c = PassCode("Home", "Gym door", "4719#")
        PocketPass.add(v, c)
        assertEquals(listOf(c), PocketPass.load(v))
        PocketPass.remove(v, c)
        assertEquals(0, PocketPass.load(v).size)
        assertTrue(v.read(PocketPass.NOTE)!!.startsWith("# Pocket Pass"))
    }

    @Test fun `last seen round-trips, orders and searches`() {
        val car = Seen(now.minusHours(2), "🚗", "Car", "Bay 41", "NCP Broad St, Level 2", 52.48, -1.9, "20261120-101500.jpg")
        val keys = Seen(now.minusDays(5), "🔑", "Spare keys", place = "Kitchen drawer", found = today.minusDays(2))
        val old = Seen(now.minusDays(60), "🔌", "Charger", found = today.minusDays(40))
        assertEquals(car, LastSeen.line(LastSeen.format(car)))
        assertEquals(keys, LastSeen.line(LastSeen.format(keys)))
        assertEquals(listOf(car, keys), LastSeen.ordered(listOf(keys, old, car), today))
        assertEquals(listOf(car), LastSeen.search(listOf(car, keys), "where's my car"))
        assertEquals(listOf(keys), LastSeen.search(listOf(car, keys), "kitchen"))
        assertEquals("2h ago", LastSeen.ago(car.at, now))
    }

    @Test fun `last seen saves newest first and clears old found ones`() {
        val v = FileVault(tmp.root)
        val old = Seen(now.minusDays(60), "🔌", "Charger", found = today.minusDays(40))
        LastSeen.add(v, old, today.minusDays(40))
        val car = Seen(now, "🚗", "Car")
        LastSeen.add(v, car, today)
        assertEquals(listOf(car), LastSeen.load(v))
        LastSeen.markFound(v, car, today)
        assertEquals(today, LastSeen.load(v).single().found)
    }

    @Test fun `returns count down, step on and buzz before the day`() {
        val boots = Return("Boots", "ASOS", today.plusDays(1), ReturnStep.PACKED)
        val lamp = Return("Desk lamp", "IKEA", Returns.byFor(today.minusDays(21), 30))
        val jacket = Return("Jacket", "Uniqlo", today.minusDays(3), ReturnStep.REFUNDED)
        listOf(boots, lamp, jacket).forEach { assertEquals(it, Returns.line(Returns.format(it))) }
        assertEquals(LocalDate.of(2026, 11, 29), lamp.by)
        assertEquals("1 day", Returns.countdown(boots, today))
        assertEquals("9 days", Returns.countdown(lamp, today))
        assertEquals("2 days late", Returns.countdown(boots, today.plusDays(3)))
        assertEquals(listOf(boots, lamp, jacket), Returns.ordered(listOf(jacket, lamp, boots), today))
        val b = Returns.buzzes(listOf(boots, lamp, jacket), now)
        // Boots' 3-days-before buzz has gone; the rest are at 09:00.
        assertEquals(listOf(today.plusDays(1).atTime(9, 0), today.plusDays(6).atTime(9, 0), today.plusDays(9).atTime(9, 0)), b.map { it.first })
        assertNull(Returns.line("- [ ] No date · Shop"))
    }

    @Test fun `returns are kept in the note`() {
        val v = FileVault(tmp.root)
        val r = Return("Boots", "ASOS", today.plusDays(1))
        Returns.add(v, r)
        Returns.update(v, r, r.copy(step = ReturnStep.DROPPED, receipt = "r.jpg"))
        assertEquals(ReturnStep.DROPPED, Returns.load(v).single().step)
        assertTrue(v.read(Returns.NOTE)!!.startsWith("# Returns"))
    }
}
