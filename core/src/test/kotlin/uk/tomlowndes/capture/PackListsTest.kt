package uk.tomlowndes.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDate

class PackListsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val today = LocalDate.of(2026, 11, 25)

    private val packing = PackLists.parse("Packing", PackLists.PACKING_STARTER)
    private val pouch = PackLists.parse("House-to-house pouch", PackLists.POUCH_STARTER)

    @Test fun `starter packing parses sections, per day items and the pouch link`() {
        assertEquals(PackKind.PACK, packing.kind)
        assertEquals("🧳", packing.emoji)
        assertEquals(3, packing.nights)
        assertEquals("Bags", packing.sections[0].title)
        assertEquals("House-to-house pouch", packing.sections[0].items[1].link)
        val boxers = packing.sections[1].items[0]
        assertTrue(boxers.perDay)
        assertEquals("Boxers ×3", boxers.label(3))
        assertEquals("EDC bag", pouch.inside)
    }

    @Test fun `render round trips`() {
        val again = PackLists.parse("Packing", PackLists.render(packing))
        assertEquals(packing.sections, again.sections)
        assertEquals(packing.intro, again.intro)
        assertEquals(PackLists.render(packing), PackLists.render(again))
    }

    @Test fun `tick, skip and spare are kept in the note`() {
        var p = PackLists.toggle(packing, 1, 0)
        p = PackLists.skip(p, 1, 8)
        p = PackLists.putItem(p, p.sections[1].items[2].copy(spare = 1), "Clothes", 1 to 2)
        val text = PackLists.render(p)
        assertTrue(text.contains("- [x] 🩲 Boxers · per day"))
        assertTrue(text.contains("- [-] 🕶️ Sunglasses"))
        assertTrue(text.contains("- [ ] 👕 T-shirts · per day +1"))
        assertEquals("T-shirts ×4", PackLists.parse("Packing", text).sections[1].items[2].label(3))
    }

    @Test fun `skipped items don't count and a full linked list ticks its line`() {
        val lists = listOf(packing, pouch)
        assertEquals(PackCount(0, 19), PackLists.count(packing, lists))
        val skipped = PackLists.skip(packing, 4, 0)
        assertEquals(18, PackLists.count(skipped, lists).total)
        var full = pouch
        full.items.indices.forEach { full = PackLists.toggle(full, 0, it) }
        assertEquals(PackCount(1, 2), PackLists.count(packing.sections[0], listOf(packing, full)))
    }

    @Test fun `repack clears ticks and skips, sets nights and stamps the day`() {
        val p = PackLists.repack(PackLists.skip(PackLists.toggle(packing, 1, 0), 1, 1), 5, today)
        assertTrue(p.items.none { it.done || it.skipped })
        assertEquals(5, p.nights)
        assertEquals(today, p.checked)
        assertTrue(PackLists.render(p).contains("checked: 2026-11-25"))
    }

    @Test fun `print leaves skipped out and gives the pouch its own group`() {
        val d = PackLists.printDoc(PackLists.skip(packing, 1, 8), listOf(packing, pouch), today)
        assertEquals("PACKING", d.title)
        assertEquals(listOf("BAGS", "HOUSE-TO-HOUSE POUCH", "CLOTHES", "WASH BAG", "MEDS", "ODDS"), d.groups.map { it.heading })
        assertFalse(d.groups.flatMap { it.items }.contains("Sunglasses"))
        assertTrue(d.groups.flatMap { it.items }.contains("Boxers ×3"))
        assertTrue(d.sub.contains("3 nights"))
    }

    @Test fun `check lists are due after 30 days`() {
        val car = PackLists.parse("Car", PackLists.CAR_STARTER)
        assertEquals(PackKind.CHECK, car.kind)
        assertTrue(PackLists.due(car, today))
        val done = PackLists.markChecked(car, today)
        assertFalse(PackLists.due(done, today.plusDays(29)))
        assertTrue(PackLists.due(done, today.plusDays(30)))
    }

    @Test fun `lists are seeded once and kept off Launch Pad kits`() {
        val v = FileVault(tmp.root)
        val lists = PackLists.loadAll(v)
        assertEquals(listOf("Packing", "Car", "Go bag", "House-to-house pouch"), lists.map { it.name })
        assertEquals(listOf("Pocket", "Backpack"), Kits.loadAll(v).map { it.name })
        v.delete(Kits.path("Car"))
        assertEquals(3, PackLists.loadAll(v).size)
    }

    @Test fun `sync clash copies load under their real name, the plain file first`() {
        val v = FileVault(tmp.root)
        v.mkdirs(Kits.DIR)
        VaultIo.write(v, v.create(Kits.DIR, "Pocket (# Name clash 2026-11-24 ygoka3C #)", "md"), "# 👖 Pocket\n\n## Always\n- 🔑 Keys\n")
        VaultIo.write(v, v.create(Kits.DIR, "Backpack (# Name clash 2026-11-24 4ez4wsC #)", "md"), "# 🎒 Backpack\n\n## Use when\n- long 4\n\n## Always\n- 💻 Laptop\n")
        val kits = Kits.loadAll(v)
        assertEquals(listOf("Pocket", "Backpack"), kits.map { it.name })
        assertNull(kits[0].useWhen)
        VaultIo.write(v, v.create(Kits.DIR, "Pocket", "md"), "# 👖 Pocket\n\n## Always\n- 📱 Phone\n")
        assertEquals(listOf("Phone"), Kits.loadAll(v).first().always.map { it.name })
    }
}
