package uk.tomlowndes.capture

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime

class DoorInkTest {
    private val now = LocalDateTime.of(2026, 11, 21, 8, 20)
    private val trip = Trip("Gym \"class\"", now.withHour(9), now.withHour(10), now.withHour(8).withMinute(40), 15, 70, 3)
    private val items = listOf(KitItem("🔑", "Keys"), KitItem("☂️", "Umbrella", "rain"))
    private val did = listOf(DidStatus(DidCheck("🔒", "Door"), LocalTime.of(8, 5)), DidStatus(DidCheck("🔥", "Hob"), null))
    private val weather = WeatherSummary(9, 3, "Wet morning", "", parts = listOf(DayPart("Morning", "🌧️", 6, 80)))

    @Test fun `leave view before a trip, day view otherwise`() {
        assertEquals(DoorInk.Mode.LEAVE, DoorInk.mode(trip, now))
        assertEquals(DoorInk.Mode.LEAVE, DoorInk.mode(trip, now.withHour(9).withMinute(5)))
        assertEquals(DoorInk.Mode.DAY, DoorInk.mode(trip, now.withHour(9).withMinute(20)))
        assertEquals(DoorInk.Mode.DAY, DoorInk.mode(trip, now.withHour(5)))
        assertEquals(DoorInk.Mode.DAY, DoorInk.mode(null, now))
    }

    @Test fun `payload carries the trip, ticks, checks and weather`() {
        val j = JSONObject(DoorInk.payload(trip, items, setOf("keys"), did, weather, now))
        assertEquals("2026-11-21T08:20", j.getString("sent"))
        val b = j.getJSONObject("body")
        assertEquals("leave", b.getString("mode"))
        assertEquals("Gym \"class\"", b.getJSONObject("leave").getString("title"))
        assertEquals("2026-11-21T08:40", b.getJSONObject("leave").getString("by"))
        assertEquals(15, b.getJSONObject("leave").getInt("drive_min"))
        assertTrue(b.getJSONArray("grab").getJSONObject(0).getBoolean("done"))
        assertEquals("rain", b.getJSONArray("grab").getJSONObject(1).getString("why"))
        assertEquals("08:05", b.getJSONArray("checks").getJSONObject(0).getString("at"))
        assertTrue(b.getJSONArray("checks").getJSONObject(1).isNull("at"))
        assertEquals(80, b.getJSONObject("weather").getInt("rain"))
        val day = JSONObject(DoorInk.payload(null, items, emptySet(), did, null, now)).getJSONObject("body")
        assertTrue(day.isNull("leave"))
        assertEquals(0, day.getJSONArray("grab").length())
        assertTrue(day.isNull("weather"))
    }

    @Test fun `only real changes count, not the clock`() {
        val a = DoorInk.body(trip, items, emptySet(), did, weather, now)
        assertFalse(DoorInk.changed(a, DoorInk.body(trip, items, emptySet(), did, weather, now.plusMinutes(7))))
        assertTrue(DoorInk.changed(a, DoorInk.body(trip, items, setOf("Keys"), did, weather, now)))
        assertTrue(DoorInk.changed(a, null))
        assertEquals("Leave in 20 min", DoorInk.leaveWords(trip.leaveBy!!, now))
        assertEquals("Leave now", DoorInk.leaveWords(trip.leaveBy, now.withMinute(40)))
        assertEquals("8 min late", DoorInk.leaveWords(trip.leaveBy, now.withMinute(48)))
        assertEquals("Leave by 08:40", DoorInk.leaveWords(trip.leaveBy, now.withHour(6)))
    }
}
