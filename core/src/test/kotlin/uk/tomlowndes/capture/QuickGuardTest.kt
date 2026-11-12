package uk.tomlowndes.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class QuickGuardTest {
    @get:Rule val tmp = TemporaryFolder()

    private val shelves = "- [ ] #task Sort shelves in garage #home ⏱ 60m"
    private val s1 = "\t- [ ] Clear a 1m walkway #task ⏱ 3m"
    private val s2 = "\t- [ ] Wipe the empty shelf #task ⏱ 5m"
    private val good = """
        ---
        AutoNoteMover: disable
        ---

        ## Active

        - [ ] #task Sort edc bag #home
        $shelves
        $s1
        $s2
        - [ ] #task Take bins out 📅 2026-11-22

        ## Archive

        - [x] #task Old thing ✅ 2026-11-01
    """.trimIndent() + "\n"

    private val flattened = good.replace("$s1\n", "${s1.trimStart()}\n").replace("$s2\n", "${s2.trimStart()}\n")

    @Test fun `flattened steps are spotted and named`() {
        val a = QuickGuard.check(good, flattened)
        assertEquals(2, a.flattened.size)
        assertTrue(a.fixable)
        assertEquals("2 steps of Sort shelves in garage lost their indent", a.headline())
    }

    @Test fun `fix puts the steps back under their parent`() {
        val a = QuickGuard.check(good, flattened)
        assertEquals(good, QuickGuard.fix(flattened.split("\n"), a).joinToString("\n"))
    }

    @Test fun `fix works when the flat steps moved elsewhere in the note`() {
        val moved = flattened.replace("${s1.trimStart()}\n${s2.trimStart()}\n", "")
            .replace("- [ ] #task Take bins out 📅 2026-11-22\n", "- [ ] #task Take bins out 📅 2026-11-22\n${s1.trimStart()}\n${s2.trimStart()}\n")
        val a = QuickGuard.check(good, moved)
        assertEquals(2, a.flattened.size)
        assertEquals(good, QuickGuard.fix(moved.split("\n"), a).joinToString("\n"))
    }

    @Test fun `normal edits are not odd`() {
        val ticked = good.replace("- [ ] #task Sort edc bag #home\n", "")
            .replace("## Archive\n\n", "## Archive\n\n- [x] #task Sort edc bag #home ✅ 2026-11-12\n")
        val added = good.replace("## Archive", "- [ ] #task New one\n\n## Archive")
        val reworded = good.replace("Take bins out", "Take the bins out")
        listOf(ticked, added, reworded).forEach { assertTrue(QuickGuard.check(good, it).isEmpty) }
    }

    @Test fun `several tasks vanishing is odd, one or two is not`() {
        val two = good.replace("- [ ] #task Sort edc bag #home\n", "").replace("- [ ] #task Take bins out 📅 2026-11-22\n", "")
        assertTrue(QuickGuard.check(good, two).isEmpty)
        val four = two.replace("$shelves\n$s1\n$s2\n", "")
        assertEquals(5, QuickGuard.check(good, four).missing.size)
        assertFalse(QuickGuard.check(good, four).fixable)
    }

    @Test fun `empty or missing note is odd`() {
        assertTrue(QuickGuard.check(good, "").emptied)
        assertTrue(QuickGuard.check(good, null).emptied)
        assertEquals(5, QuickGuard.check(good, "\n- [ ] #task Only one\n").missing.size)
    }

    @Test fun `store keeps the good copy and flags odd reads`() {
        var t = 1000L
        val store = QuickGuard.Store(tmp.newFolder("qt")) { t++ }
        assertNull(store.seen(good))
        assertNull(store.seen(good))
        assertEquals(1, store.copies().size)
        val a = store.seen(flattened)
        assertNotNull(a)
        assertNotNull(store.pending())
        assertEquals(good, store.lastGood()!!.text())
        // It's fine: the current note becomes the good copy and the alert clears.
        store.save(flattened, true)
        assertNull(store.pending())
        assertNull(store.seen(flattened))
    }

    @Test fun `store keeps only the last ten`() {
        var t = 1L
        val store = QuickGuard.Store(tmp.newFolder("qt")) { t++ }
        repeat(14) { store.save(good.replace("Sort edc bag", "Sort edc bag $it"), true) }
        assertEquals(QuickGuard.KEEP, store.copies().size)
        assertTrue(store.lastGood()!!.text().contains("Sort edc bag 13"))
    }
}
