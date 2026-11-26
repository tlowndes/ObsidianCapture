package uk.tomlowndes.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedSettingsTest {
    private class MapStore : KeyStore {
        val m = HashMap<String, Any>()
        override fun string(key: String) = m[key] as String?
        override fun putString(key: String, value: String?) { if (value == null) m.remove(key) else m[key] = value }
        override fun bool(key: String, def: Boolean) = m[key] as Boolean? ?: def
        override fun putBool(key: String, value: Boolean) { m[key] = value }
        override fun int(key: String, def: Int) = m[key] as Int? ?: def
        override fun putInt(key: String, value: Int) { m[key] = value }
        override fun long(key: String, def: Long) = m[key] as Long? ?: def
        override fun putLong(key: String, value: Long) { m[key] = value }
        override fun float(key: String, def: Float) = m[key] as Float? ?: def
        override fun putFloat(key: String, value: Float) { m[key] = value }
    }
    private class Phone(s: KeyStore) : SharedSettings(s, 16.5f, false, true)

    @Test fun `defaults and the keys each app already used`() {
        val st = MapStore(); val p = Phone(st)
        assertTrue(p.recurring); assertEquals("03 - Projects", p.ideasDir); assertEquals(16.5f, p.readerSize)
        assertTrue(p.jarvisSpeak); assertEquals("jarvis", p.jarvisWorker); assertEquals(7000, p.stepGoal)
        p.ideasDir = " /Projects/ "; p.reshuffleUsual = 99; p.jarvisAddress = "  "
        assertEquals("Projects", st.m["ideas_dir"]); assertEquals(30, st.m["reshuffle_usual"])
        assertFalse("jarvis_address" in st.m); assertEquals("", p.jarvisAddress)
        assertNull(p.updateSkip)
    }

    @Test fun `spend adds to the month and the feature ledger`() {
        val p = Phone(MapStore())
        p.spend("jarvis", 0.25)
        assertEquals(1, p.brainSpend.answers)
        assertEquals(250_000L, p.brainSpend.microUsd)
        assertTrue(p.spendByFeature.contains("jarvis"))
    }
}
