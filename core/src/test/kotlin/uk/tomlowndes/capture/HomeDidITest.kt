package uk.tomlowndes.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class HomeDidITest {
    @get:Rule val tmp = TemporaryFolder()
    private val zone = ZoneId.of("Europe/London")
    private val today = LocalDate.of(2026, 11, 21)

    private val json = """[
        {"entity_id":"lock.front_door","state":"locked","last_changed":"2026-11-21T08:39:12+00:00","attributes":{"friendly_name":"Front door"}},
        {"entity_id":"sensor.hob_plug_power","state":"1400","last_changed":"2026-11-21T08:30:00+00:00","attributes":{"unit_of_measurement":"W","friendly_name":"Hob plug"}},
        {"entity_id":"light.downstairs","state":"off","last_changed":"2026-11-20T23:10:00+00:00","attributes":{}},
        {"entity_id":"person.tom","state":"not_home","last_changed":"2026-11-21T08:45:00+00:00","attributes":{}},
        {"entity_id":"sun.sun","state":"above_horizon","attributes":{}}
    ]"""

    @Test fun `links read and write on check lines`() {
        val text = "- 🔒 Door · Is the door locked? · ha lock.front_door = locked\n- 🔥 Hob · ha sensor.hob_plug_power < 5\n- 💊 Meds · Taken? · with · dots"
        val c = DidI.checks(text)
        assertEquals(HaLink("lock.front_door", HaLink.Op.EQ, "locked"), c[0].link)
        assertEquals("Is the door locked?", c[0].question)
        assertEquals("Hob done?", c[1].question)
        assertEquals(HaLink.Op.LT, c[1].link!!.op)
        assertNull(c[2].link)
        assertEquals("Taken? · with · dots", c[2].question)
        c.forEach { assertEquals(it, DidI.checks(DidI.checkLine(it)).single()) }
        val lines = text.lines().toMutableList()
        assertTrue(DidI.setLink(lines, "meds", HaLink("input_boolean.meds", HaLink.Op.EQ, "on")))
        assertEquals("- 💊 Meds · Taken? · with · dots · ha input_boolean.meds = on", lines[2])
        DidI.setLink(lines, "Door", null)
        assertEquals("- 🔒 Door · Is the door locked?", lines[0])
    }

    @Test fun `auto stamps keep their mark and their time`() {
        val s = DidI.stamps("- 08:39 · Door · auto\n- 08:41 · Meds")
        assertEquals(listOf(DidStamp(LocalTime.of(8, 39), "Door", true), DidStamp(LocalTime.of(8, 41), "Meds")), s)
        s.forEach { assertEquals(it, DidI.stamps(DidI.stampLine(it)).single()) }
        val v = FileVault(tmp.root)
        DidI.stamp(v, "Meds", today.atTime(8, 41))
        DidI.stampAt(v, today, DidStamp(LocalTime.of(8, 39), "Door", true))
        assertEquals(listOf("Door", "Meds"), DidI.loadStamps(v, today).map { it.name })
    }

    @Test fun `the house answers checks`() {
        val st = HomeDidI.states(json)
        assertEquals(5, st.size)
        val door = HaLink("lock.front_door", HaLink.Op.EQ, "locked")
        val hob = HaLink("sensor.hob_plug_power", HaLink.Op.LT, "5")
        assertEquals(true, HomeDidI.answer(door, st["lock.front_door"]))
        assertEquals(false, HomeDidI.answer(hob, st["sensor.hob_plug_power"]))
        assertNull(HomeDidI.answer(door, HaState("lock.front_door", "unavailable")))
        assertEquals("1400 W", HomeDidI.words(st["sensor.hob_plug_power"]))
        assertEquals("Locked", HomeDidI.words(st["lock.front_door"]))
        assertEquals(HaLink.Op.LT, HomeDidI.suggest(st["sensor.hob_plug_power"]!!).op)
        assertEquals("locked", HomeDidI.suggest(st["lock.front_door"]!!).value)
        assertEquals(listOf("lock.front_door", "sensor.hob_plug_power", "light.downstairs"), HomeDidI.linkable(st).map { it.entity })
        assertEquals(listOf("sensor.hob_plug_power"), HomeDidI.linkable(st, "hob").map { it.entity })
        assertEquals(listOf("person.tom"), HomeDidI.people(st).map { it.entity })
    }

    @Test fun `auto stamps only for changes today, not already stamped`() {
        val st = HomeDidI.states(json)
        val checks = listOf(
            DidCheck("🔒", "Door", link = HaLink("lock.front_door", HaLink.Op.EQ, "locked")),
            DidCheck("🔥", "Hob", link = HaLink("sensor.hob_plug_power", HaLink.Op.LT, "5")),
            DidCheck("💡", "Lights", link = HaLink("light.downstairs", HaLink.Op.EQ, "off")),
            DidCheck("💊", "Meds"),
        )
        assertEquals(listOf(DidStamp(LocalTime.of(8, 39), "Door", true)), HomeDidI.autoStamps(checks, st, emptyList(), today, zone))
        assertEquals(emptyList<DidStamp>(), HomeDidI.autoStamps(checks, st, listOf(DidStamp(LocalTime.of(8, 40), "door")), today, zone))
        val on = HomeDidI.stillOn(checks, st)
        assertEquals(listOf("Hob"), on.map { it.first.name })
        assertTrue(HomeDidI.away(st["person.tom"]))
        assertFalse(HomeDidI.away(HaState("person.tom", "home")))
        assertFalse(HomeDidI.away(HaState("person.tom", "unknown")))
        assertEquals("1795250700|hob", HomeDidI.alertKey(st["person.tom"]!!, on[0].first))
        assertTrue(HomeDidI.alertWords(on[0].first, on[0].second).second.startsWith("1400 W"))
    }
}
