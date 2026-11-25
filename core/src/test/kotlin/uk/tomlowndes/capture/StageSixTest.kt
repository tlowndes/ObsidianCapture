package uk.tomlowndes.capture

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDate
import java.time.YearMonth

/** Stage 6: shared shopping and chores, the Claude spend cap. */
class StageSixTest {
    @get:Rule val tmp = TemporaryFolder()
    private val nov = YearMonth.of(2026, 11)

    @After fun reset() { ClaudeCap.capGbp = null; ClaudeCap.ownUsd = 0.0; ClaudeCap.othersGbp = 0.0; ClaudeCap.shared = true }

    @Test fun `partner items and chores`() {
        val text = "# Shopping list\n\n- [ ] Mozzarella\n- [ ] Oat milk 👥\n- [x] Eggs\n"
        assertEquals(listOf(SharedItem("Mozzarella", false), SharedItem("Oat milk", true)), SharedList.open(text))
        // Adding "oat milk" from the app doesn't double up the partner's line.
        assertEquals(0, Recipes.withShopping(text, listOf("oat milk")).second)

        val c = Chores.parseLine("- 🛁 Clean bathroom · every 7 · done 2026-11-15 · by partner")!!
        assertEquals("partner", c.by)
        assertEquals("1 day · every 7 · partner did it last", c.ageLabel(LocalDate.of(2026, 11, 16)))
        assertEquals("- 🛁 Clean bathroom · every 7 · done 2026-11-15 · by partner", Chores.line(c))

        val e = Errands.errands(emptyList(), listOf("Oat milk 👥", "Bread"), emptyList()).single()
        assertEquals(listOf("Oat milk · partner", "Bread"), e.items)

        val v = FileVault(tmp.root)
        assertFalse(SharedList.on(v))
        v.mkdirs(FlightNotes.DIR); VaultIo.write(v, v.create(FlightNotes.DIR, "Shared list", "md"), "---\nupdated: 2026-11-16T08:20:16\n---\n")
        assertTrue(SharedList.on(v))
        assertEquals("2026-11-16T08:20:16", SharedList.updated(v.read(SharedList.STATE)).toString())
    }

    @Test fun `spend ledger, cap and sharing`() {
        var raw = ClaudeCap.added(null, "Jarvis", 1.0, nov)
        raw = ClaudeCap.added(raw, "Snap", 0.5, nov)
        raw = ClaudeCap.added(raw, "Jarvis", 0.25, nov)
        assertEquals(mapOf("Jarvis" to 1_250_000L, "Snap" to 500_000L), ClaudeCap.ledger(raw, nov))
        assertTrue(ClaudeCap.ledger(raw, nov.plusMonths(1)).isEmpty())

        val v = FileVault(tmp.root)
        // The PC's note, as the PC writes it.
        v.mkdirs(ClaudeCap.DIR)
        VaultIo.write(v, v.create(ClaudeCap.DIR, "pc", "md"), ClaudeCap.deviceNote("pc", mapOf("Jarvis" to 2_000_000L, "Helpers" to 1_000_000L), nov))
        ClaudeCap.saveCap(v, 5.0, true)

        val s = ClaudeCap.sync(v, "phone", ClaudeCap.ledger(raw, nov), 0.31, nov)
        // Phone $1.75 + PC $3 at 0.76, plus the box's 31p.
        assertEquals(1.75 * 0.76 + 3.0 * 0.76 + 0.31, s.spentGbp, 1e-6)
        assertEquals("Jarvis", s.byFeature.keys.first())
        assertTrue(ClaudeCap.BOX in s.byFeature)
        assertEquals("£4.06 of £5", ClaudeCap.label(s))
        assertTrue(s.warn); assertFalse(s.paused)
        assertEquals("Claude: £4.06 of £5 this month (81%)", ClaudeCap.banner(s))
        assertTrue(v.read("${ClaudeCap.DIR}/phone.md")!!.contains("- Jarvis · \$1.2500"))

        // The gate: one more answer over the cap stops the next call.
        assertFalse(ClaudeCap.paused())
        ClaudeCap.ownUsd += 1.5
        assertTrue(ClaudeCap.paused())
        try { ClaudeCap.check(); fail() } catch (e: CapReached) { assertTrue(e.message!!.startsWith("Claude paused until 1 Dec")) }

        // Not shared: only this phone counts.
        ClaudeCap.saveCap(v, 5.0, false)
        val alone = ClaudeCap.sync(v, "phone", ClaudeCap.ledger(raw, nov), 0.31, nov)
        assertEquals(1.75 * 0.76, alone.spentGbp, 1e-6)
        assertNull(ClaudeCap.banner(alone))
        assertFalse(ClaudeCap.paused())

        // No cap: never paused.
        ClaudeCap.saveCap(v, null, true)
        assertNull(ClaudeCap.sync(v, "phone", mapOf("Jarvis" to 99_000_000L), null, nov).capGbp)
        assertFalse(ClaudeCap.paused())
    }
}
