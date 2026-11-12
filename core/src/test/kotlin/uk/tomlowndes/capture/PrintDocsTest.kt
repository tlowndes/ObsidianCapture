package uk.tomlowndes.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class PrintDocsTest {
    private val thu = LocalDate.of(2026, 11, 12)

    @Test fun listGroupsByHeadingAndSkipsTicked() {
        val note = """
            ---
            tags: [list]
            ---
            # Shopping
            ## Fruit & veg
            - [ ] Bananas
            - [x] Apples
            - Spinach #weekly
            ## Fridge
            1. Oat milk
            - [ ] [[Halloumi]] 📅 2026-11-13
            Some note text
        """.trimIndent()
        val d = PrintDocs.listFrom("Shopping", note, date = thu)
        assertEquals(listOf("FRUIT & VEG", "FRIDGE"), d.groups.map { it.heading })
        assertEquals(listOf("Bananas", "Spinach"), d.groups[0].items)
        assertEquals(listOf("Oat milk", "Halloumi"), d.groups[1].items)
        assertEquals("SHOPPING", d.title)
        assertEquals("Thu 12 Nov · 4 items", d.sub)
        val flat = PrintDocs.listFrom("Shopping", note, byHeading = false, skipTicked = false, date = thu)
        assertEquals(1, flat.groups.size)
        assertEquals(5, flat.count)
    }

    @Test fun typedAndShared() {
        assertEquals(listOf("Milk", "Bread"), PrintDocs.typed("Milk\n\n- Bread\n", thu).groups.single().items)
        val text = PrintDocs.shared("Harissa squash", "Preheat to 200°C.\nRoast squash.", ShareAs.TEXT, "Chrome", "14:10")
        assertEquals("HARISSA SQUASH", text.title)
        assertEquals("Shared from Chrome · 14:10", text.sub)
        assertEquals("Preheat to 200°C.\nRoast squash.", text.body)
        val ticks = PrintDocs.shared("x", "Preheat to 200°C.\nRoast squash.", ShareAs.TICKS, null, "14:10")
        assertNull(ticks.body)
        assertEquals(2, ticks.count)
        assertEquals("Preheat to 200°C. Roast", PrintDocs.shareTitle(null, "Preheat to 200°C. Roast squash 25 min"))
        assertEquals("Recipe", PrintDocs.shareTitle(" Recipe ", "x"))
    }

    @Test fun billsForTheMonth() {
        val open = listOf(
            TaskItem("- [ ] Council tax £142 #bill 📅 2026-11-12", "Council tax £142 #bill", thu),
            TaskItem("- [ ] Phone £20 #bill 📅 2026-12-03", "Phone £20 #bill", LocalDate.of(2026, 12, 3)),
        )
        val done = listOf(TickedTask("- [x] Gym £20 #bill 📅 2026-11-04 ✅ 2026-11-04", "Gym £20 #bill", LocalDate.of(2026, 11, 4)))
        val b = PrintDocs.bills(open, done, thu, today = thu)
        assertEquals(listOf("Council tax"), b.toPay.map { it.name })
        assertEquals(listOf("Gym"), b.paid.map { it.name })
        assertEquals(BigDecimal("142"), b.left)
        assertEquals("NOVEMBER 2026", b.title)
        assertEquals("Thu 12", b.day(b.toPay[0]))
        val dec = PrintDocs.bills(open, done, LocalDate.of(2026, 12, 1), showPaid = false, today = thu)
        assertEquals(listOf("Council tax", "Phone"), dec.toPay.map { it.name })
        assertEquals(0, dec.paid.size)
    }
}
