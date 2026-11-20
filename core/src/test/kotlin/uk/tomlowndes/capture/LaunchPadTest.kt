package uk.tomlowndes.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDateTime
import java.time.LocalTime

class LaunchPadTest {
    @get:Rule val tmp = TemporaryFolder()

    private val day = LocalDateTime.of(2026, 11, 20, 9, 5)
    private fun trip(title: String? = "Gym", rain: Int = 0, low: Int? = 9, hours: Long = 1, drive: Int? = 12) = Trip(
        title, day.withHour(9).withMinute(30), day.withHour(9).withMinute(30).plusHours(hours),
        drive?.let { day.withHour(9).withMinute(30).minusMinutes(it + 5L) }, drive, rain, low,
    )

    @Test fun `the starter kit reads always items and rules`() {
        val k = LaunchPad.kit(LaunchPad.STARTER)
        assertEquals(listOf("Keys", "Wallet", "Phone", "Headphones"), k.always.map { it.name })
        assertEquals(5, k.rules.count { it.group == KitGroup.BAG })
        assertEquals(listOf("Gym bag", "Towel"), k.rules.first { it.kind == KitRule.Kind.EVENT }.items.map { it.name })
    }

    @Test fun `rules add items for the day with a reason`() {
        val k = LaunchPad.kit(LaunchPad.STARTER)
        val items = LaunchPad.itemsFor(k, trip(rain = 80))
        assertEquals(listOf("Keys", "Wallet", "Phone", "Headphones", "Umbrella", "Gym bag", "Towel"), items.map { it.name })
        assertEquals("rain", items.first { it.name == "Umbrella" }.reason)
        val long = LaunchPad.itemsFor(k, trip(title = "Interview at Studio North", low = 3, hours = 5))
        assertTrue(listOf("Scarf", "Portfolio", "CV", "Charger", "Meds").all { n -> long.any { it.name == n } })
        assertEquals(4, LaunchPad.essentials(long).size)
    }

    @Test fun `bad rule lines are ignored and duplicates dropped`() {
        val k = LaunchPad.kit("## Always\n- 🔑 Keys\n## When\n- snow · Boots\n- event · Nothing\n- rain · 🔑 keys, Coat")
        assertEquals(1, k.rules.size)
        assertEquals(listOf("Keys", "Coat"), LaunchPad.itemsFor(k, trip(rain = 60)).map { it.name })
    }

    @Test fun `timing counts down, then says how late with a new arrival`() {
        val t = trip()
        assertEquals(8L, LaunchPad.timing(t, day).minutesLeft)
        val late = LaunchPad.timing(t, day.withMinute(16))
        assertEquals(3L, late.lateBy)
        assertEquals(LocalTime.of(9, 30), late.arriveAt)
        val later = LaunchPad.timing(t, day.withMinute(25))
        assertEquals(LocalTime.of(9, 37), later.arriveAt)
        assertEquals(LocalDateTime.of(2026, 11, 20, 9, 30), LaunchPad.leaveBy(trip(drive = null)))
        assertNull(LaunchPad.timing(Trip(null, null, null, null, null, 0, null), day).minutesLeft)
    }

    @Test fun `the kit note is made on first use`() {
        val v = FileVault(tmp.root)
        assertEquals(4, LaunchPad.load(v).always.size)
        assertTrue(v.read(LaunchPad.KIT)!!.startsWith("# Launch Pad kit"))
    }

    @Test fun `heads up plans buzzes, skips the past, quiet hours and duplicates`() {
        val leave = HeadsTarget(HeadsTarget.Kind.LEAVE, "Gym", day.withHour(9).withMinute(13))
        val same = HeadsTarget(HeadsTarget.Kind.EVENT, "Gym", day.withHour(9).withMinute(13))
        val late = HeadsTarget(HeadsTarget.Kind.EVENT, "Late call", day.withHour(21).withMinute(30))
        val plan = HeadsUp.plan(listOf(same, leave, late), day)
        assertEquals(listOf(5, 1), plan.map { it.minutes })
        assertTrue(plan.all { it.target.kind == HeadsTarget.Kind.LEAVE })
        assertEquals("1 min · time to go", HeadsUp.words(plan.last()).first)
    }

    @Test fun `night pad lists charge, bag and wear for tomorrow`() {
        val k = LaunchPad.kit(LaunchPad.STARTER)
        assertEquals(listOf("Phone", "Headphones"), k.charge.map { it.name })
        val night = NightPad.items(k, trip(rain = 70))
        assertEquals(listOf("Phone", "Headphones", "Umbrella", "Gym bag", "Towel", "Waterproof"), night.map { it.item.name })
        assertEquals(KitGroup.WEAR, night.last().group)
        assertEquals(listOf(KitGroup.CHARGE, KitGroup.CHARGE), NightPad.items(k, Trip(null, null, null, null, null, 90, 0)).map { it.group })
        // Launch Pad's morning list doesn't pick up Charge or Wear rules.
        assertTrue(LaunchPad.itemsFor(k, trip(rain = 70)).none { it.name == "Waterproof" })
        assertEquals(LocalDateTime.of(2026, 11, 20, 8, 13), NightPad.wakeAt(trip()))
    }

    @Test fun `older kits get the night sections once`() {
        val old = "# Launch Pad kit\n\n## Always\n- 🔑 Keys\n"
        val up = NightPad.upgrade(old)!!
        assertTrue(up.contains("## Charge") && up.contains("## Wear"))
        assertNull(NightPad.upgrade(up))
        val events = listOf(
            CalEvent("a", "Late", day.plusDays(1).withHour(14), day.plusDays(1).withHour(15), false),
            CalEvent("b", "Gym", day.plusDays(1).withHour(9), day.plusDays(1).withHour(10), false),
            CalEvent("c", "Today", day.withHour(18), day.withHour(19), false),
        )
        assertEquals("Gym", NightPad.firstTomorrow(events, day)?.title)
    }

    @Test fun `stages and quiet hours parse`() {
        assertEquals(listOf(10, 5, 1), HeadsUp.parseStages(null))
        assertEquals(listOf(15, 5), HeadsUp.parseStages("5, 15, 7"))
        assertTrue(HeadsUp.quiet(LocalTime.of(23, 0), LocalTime.of(21, 0), LocalTime.of(7, 0)))
        assertTrue(!HeadsUp.quiet(LocalTime.of(8, 0), LocalTime.of(21, 0), LocalTime.of(7, 0)))
    }
}
