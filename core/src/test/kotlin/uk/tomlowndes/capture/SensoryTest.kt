package uk.tomlowndes.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class SensoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private val utc: ZoneId = ZoneOffset.UTC
    private fun at(d: Int, h: Int, m: Int) = LocalDateTime.of(2026, 11, d, h, m)
    private fun c(d: Int, h: Int, m: Int, l: SensoryLevel) = SensoryChange(at(d, h, m), l)

    @Test fun `log lines round trip with durations`() {
        val cs = listOf(c(20, 14, 2, SensoryLevel.LOW), c(20, 14, 14, SensoryLevel.SHELTER), c(20, 14, 32, SensoryLevel.NORMAL))
        val text = Sensory.format(cs)
        assertTrue(text.contains("- 2026-11-20 14:02 · Low · 12 min\n- 2026-11-20 14:14 · Shelter · 18 min\n- 2026-11-20 14:32 · Normal\n"))
        assertEquals(cs, Sensory.parse(text + "- junk\n- 2026-11-20 15:00 · Loud\n"))
    }

    @Test fun `merge sorts, keeps one per minute and drops repeats`() {
        val old = listOf(c(20, 9, 0, SensoryLevel.NORMAL), c(20, 14, 2, SensoryLevel.LOW))
        val new = listOf(
            SensoryChange(at(20, 14, 2).withSecond(40), SensoryLevel.LOW),
            c(20, 14, 14, SensoryLevel.SHELTER), c(20, 13, 0, SensoryLevel.NORMAL), c(20, 14, 20, SensoryLevel.SHELTER),
        )
        assertEquals(listOf(c(20, 9, 0, SensoryLevel.NORMAL), c(20, 14, 2, SensoryLevel.LOW), c(20, 14, 14, SensoryLevel.SHELTER)),
            Sensory.merge(old, new))
    }

    @Test fun `turned-down minutes per day split at midnight`() {
        val cs = listOf(c(19, 23, 30, SensoryLevel.SHELTER), c(20, 0, 15, SensoryLevel.NORMAL), c(20, 10, 0, SensoryLevel.LOW))
        val now = at(20, 10, 40)
        assertEquals(30, Sensory.downMinutes(cs, now.toLocalDate().minusDays(1), now))
        assertEquals(15 + 40, Sensory.downMinutes(cs, now.toLocalDate(), now))
        val week = Sensory.week(cs, now)
        assertEquals(7, week.size)
        assertEquals(java.time.DayOfWeek.MONDAY, week.first().first.dayOfWeek)
        assertEquals(0, week.last().second)
        assertEquals(SensoryLevel.LOW, Sensory.current(cs))
        assertEquals("40 min", Sensory.sinceWords(cs, now))
        assertEquals("1 h 5 min", Sensory.sinceWords(cs, at(20, 11, 5)))
    }

    @Test fun `home assistant addresses, bodies and answers`() {
        assertEquals(Sensory.Ha(false, "homeassistant.local", 8123), Sensory.ha("homeassistant.local"))
        assertEquals(Sensory.Ha(false, "192.168.1.5", 8123), Sensory.ha("http://192.168.1.5:8123/"))
        assertEquals(Sensory.Ha(true, "x.ui.nabu.casa", 443), Sensory.ha("https://x.ui.nabu.casa"))
        assertNull(Sensory.ha(" "))
        assertEquals("{\"entity_id\":\"input_select.sensory_level\",\"option\":\"Shelter\"}", Sensory.selectBody(SensoryLevel.SHELTER))
        assertEquals(SensoryLevel.LOW, Sensory.stateOf("{\"entity_id\":\"input_select.sensory_level\",\"state\":\"Low\"}"))
        assertNull(Sensory.stateOf("{\"state\":\"unavailable\"}"))
        val json = """[[{"entity_id":"input_select.sensory_level","state":"Normal","last_changed":"2026-11-20T09:00:00+00:00"},
            {"state":"unavailable","last_changed":"2026-11-20T10:00:00+00:00"},
            {"state":"Low","last_changed":"2026-11-20T14:02:31.512+00:00"}]]"""
        assertEquals(listOf(c(20, 9, 0, SensoryLevel.NORMAL), c(20, 14, 2, SensoryLevel.LOW)), Sensory.history(json, utc))
        assertEquals(emptyList<SensoryChange>(), Sensory.history("[]", utc))
        assertTrue(Sensory.historyPath(at(20, 9, 0).toInstant(ZoneOffset.UTC)).startsWith("/api/history/period/2026-11-20T09:00:00Z?filter_entity_id=input_select.sensory_level"))
    }

    @Test fun `save makes the log, then only adds what's new`() {
        val v = FileVault(tmp.root)
        assertTrue(Sensory.save(v, emptyList()).isEmpty())
        assertNull(v.entry(Sensory.LOG))
        Sensory.save(v, listOf(c(20, 14, 2, SensoryLevel.LOW)))
        Sensory.save(v, listOf(c(20, 14, 2, SensoryLevel.LOW), c(20, 14, 14, SensoryLevel.SHELTER)))
        val text = v.read(Sensory.LOG)!!
        assertTrue(text.startsWith("# Sensory log"))
        assertTrue(text.endsWith("- 2026-11-20 14:02 · Low · 12 min\n- 2026-11-20 14:14 · Shelter\n"))
        assertEquals(2, Sensory.load(v).size)
        assertEquals(HangarTool.SENSORY, HangarTool.byId("sensory"))
        assertEquals("At the desk", HangarTool.SENSORY.group)
    }
}
