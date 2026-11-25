package uk.tomlowndes.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDateTime

class KitsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val day = LocalDateTime.of(2026, 11, 24, 8, 0)
    private fun trip(title: String? = "Dentist", rain: Int = 0, low: Int? = 9, hours: Long = 1) =
        Trip(title, day.withHour(9), day.withHour(9).plusHours(hours), null, null, rain, low)

    private val pocket = Kits.parse(Kits.POCKET, Kits.POCKET_STARTER)
    private val backpack = Kits.parse(Kits.BACKPACK, Kits.BACKPACK_STARTER)

    @Test fun `pocket is always on, backpack comes by its use when lines`() {
        assertNull(pocket.useWhen)
        assertEquals("👖", pocket.emoji)
        assertEquals("🎒", backpack.emoji)
        assertEquals(listOf(KitTrigger(KitRule.Kind.EVENT, "work"), KitTrigger(KitRule.Kind.LONG, "4")), backpack.useWhen)
        val kits = listOf(pocket, backpack)
        assertEquals(listOf("Pocket"), Kits.active(kits, trip()).map { it.name })
        assertEquals(listOf("Pocket", "Backpack"), Kits.active(kits, trip("Work · studio day")).map { it.name })
        assertEquals(listOf("Pocket", "Backpack"), Kits.active(kits, trip(), on = setOf("Backpack")).map { it.name })
        assertEquals(listOf("Pocket"), Kits.active(kits, trip("Work"), off = setOf("Backpack")).map { it.name })
    }

    @Test fun `packs group items by kit with no repeats and say why`() {
        val packs = Kits.packs(Kits.active(listOf(pocket, backpack), trip("Work", hours = 5)), trip("Work", hours = 5))
        assertEquals(listOf("Keys", "Wallet", "Phone", "Headphones"), packs[0].items.map { it.name })
        assertEquals(listOf("Laptop", "Charger", "Snack", "Power bank"), packs[1].items.map { it.name })
        assertEquals("work", packs[1].why)
        assertNull(packs[0].why)
        val tapped = Kits.packs(listOf(pocket, backpack), trip(), on = setOf("Backpack"))
        assertNull(tapped[1].why)
    }

    @Test fun `first load moves the old kit note into Kits and adds Backpack`() {
        val v = FileVault(tmp.root)
        v.mkdirs(FlightNotes.DIR)
        VaultIo.write(v, v.create(FlightNotes.DIR, "Launch Pad kit", "md"), "# Launch Pad kit\n\nMine.\n\n## Always\n- 🔑 Keys\n- 🧢 Hat\n")
        val kits = Kits.loadAll(v)
        assertEquals(listOf("Pocket", "Backpack"), kits.map { it.name })
        assertEquals(listOf("Keys", "Hat"), kits[0].always.map { it.name })
        assertNull(v.entry(LaunchPad.KIT))
        val text = v.read(Kits.path("Pocket"))!!
        assertTrue(text.startsWith("# 👖 Pocket") && text.contains("Mine.") && text.contains("## Charge"))
        // A second load leaves things alone, even if a kit was deleted.
        v.delete(Kits.path("Backpack"))
        assertEquals(listOf("Pocket"), Kits.loadAll(v).map { it.name })
    }

    @Test fun `a fresh vault gets both starter kits`() {
        val kits = Kits.loadAll(FileVault(tmp.root))
        assertEquals(listOf("Pocket", "Backpack"), kits.map { it.name })
        assertEquals(4, kits[0].always.size)
    }

    @Test fun `editing adds, moves, renames and removes items and round trips through the note`() {
        var k = backpack
        k = Kits.put(k, KitItem("✏️", "Sketchbook"), KitSlot())
        k = Kits.put(k, KitItem("🧤", "Gloves"), KitSlot(kind = KitRule.Kind.COLD))
        k = Kits.put(k, KitItem("🥪", "Lunch"), KitSlot(kind = KitRule.Kind.LONG, arg = "4"))
        assertEquals(listOf("Snack", "Power bank", "Lunch"), k.rules.first { it.kind == KitRule.Kind.LONG }.items.map { it.name })
        k = Kits.replace(k, KitSlot(), "Charger", KitItem("🔌", "USB-C charger"), KitSlot())
        assertEquals("USB-C charger", k.always[1].name)
        k = Kits.replace(k, KitSlot(kind = KitRule.Kind.RAIN), "Packable jacket", KitItem("🧥", "Packable jacket"), KitSlot())
        assertTrue(k.rules.none { it.kind == KitRule.Kind.RAIN })
        k = Kits.moveAlways(k, 0, 2)
        assertEquals("Laptop", k.always[2].name)
        k = Kits.remove(k, KitSlot(KitGroup.CHARGE), "Power bank")
        assertTrue(k.charge.isEmpty())
        k = Kits.removeTrigger(Kits.addTrigger(k, KitTrigger(KitRule.Kind.EVENT, "uni")), KitTrigger(KitRule.Kind.LONG, "4"))
        val text = Kits.render(k, Kits.BACKPACK_STARTER + "\n## Notes\nKeep this.\n")
        val back = Kits.parse("Backpack", text)
        assertEquals(k.always.map { it.name }, back.always.map { it.name })
        assertEquals(k.rules.map { it.kind to it.items.map { i -> i.name } }, back.rules.map { it.kind to it.items.map { i -> i.name } })
        assertEquals(listOf(KitTrigger(KitRule.Kind.EVENT, "work"), KitTrigger(KitRule.Kind.EVENT, "uni")), back.useWhen)
        assertTrue(text.contains("## Notes\nKeep this."))
        assertTrue(text.contains("Comes along when"))
    }

    @Test fun `removing every use when line leaves a kit you tap on`() {
        var k = backpack
        k.useWhen!!.forEach { k = Kits.removeTrigger(k, it) }
        assertEquals(emptyList<KitTrigger>(), Kits.parse("Backpack", Kits.render(k, "")).useWhen)
        assertFalse(Kits.auto(k, trip("Work")))
    }

    @Test fun `new kits and saves land in the Kits folder`() {
        val v = FileVault(tmp.root)
        Kits.loadAll(v)
        val gym = Kits.create(v, "Gym bag", "🏋️")
        Kits.save(v, Kits.put(gym, KitItem("🧴", "Towel"), KitSlot()))
        val all = Kits.loadAll(v)
        assertEquals(listOf("Pocket", "Backpack", "Gym bag"), all.map { it.name })
        assertEquals(listOf("Towel"), all[2].always.map { it.name })
        assertEquals("🏋️", all[2].emoji)
        Kits.delete(v, all[0])
        assertEquals(3, Kits.loadAll(v).size)
    }

    @Test fun `night pad packs the whole backpack and only pocket extras`() {
        val night = NightPad.items(listOf(pocket, backpack), trip("Work", rain = 80))
        val bag = night.filter { it.group == KitGroup.BAG }
        assertEquals(listOf("Umbrella", "Laptop", "Charger", "Packable jacket"), bag.map { it.item.name })
        assertEquals("Backpack", bag.first { it.item.name == "Laptop" }.kit)
        assertEquals(listOf("Phone", "Headphones", "Power bank"), night.filter { it.group == KitGroup.CHARGE }.map { it.item.name })
        // No backpack day: no backpack items, Power bank isn't charged.
        assertTrue(NightPad.items(listOf(pocket, backpack), trip()).none { it.kit == "Backpack" })
    }
}
