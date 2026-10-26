package uk.tomlowndes.capture

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class WeatherTest {
    private fun forecast(rain: (Int) -> Int, cloud: (Int) -> Int, code: Int = 3, temp: (Int) -> Double = { 10.0 }): String {
        val hours = 0..23
        return JSONObject()
            .put("daily", JSONObject()
                .put("temperature_2m_max", JSONArray(listOf(11.2)))
                .put("temperature_2m_min", JSONArray(listOf(7.6)))
                .put("sunset", JSONArray(listOf("2026-10-25T16:51")))
                .put("weather_code", JSONArray(listOf(code))))
            .put("hourly", JSONObject()
                .put("time", JSONArray(hours.map { "2026-10-25T%02d:00".format(it) }))
                .put("temperature_2m", JSONArray(hours.map(temp)))
                .put("precipitation_probability", JSONArray(hours.map(rain)))
                .put("cloud_cover", JSONArray(hours.map(cloud)))
                .put("weather_code", JSONArray(hours.map { code })))
            .toString()
    }

    @Test fun `a wet morning reads like the morning email`() {
        val rain = mapOf(7 to 10, 8 to 40, 9 to 100, 10 to 100, 11 to 100, 12 to 45, 13 to 25, 14 to 25)
        val w = Weather.summary(
            forecast({ rain[it] ?: 5 }, { 90 }, code = 61, temp = { if (it < 12) 9.0 else 15.0 }),
            LocalDateTime.of(2026, 10, 25, 7, 30)
        )
        assertEquals("Wet morning, mild afternoon", w.headline)
        assertEquals(
            "Rain from 08:00, near-certain 09:00–12:00 (100%), easing after 13:00 and mostly dry from 15:00. Sunset 16:51.",
            w.detail
        )
        assertEquals("Leave the washing in. Best walk: 15:00–16:15.", w.tip)
        assertEquals(listOf("Morning", "Afternoon", "Evening"), w.parts.map { it.name })
        assertEquals("🌧️", w.parts[0].icon)
        assertEquals(9, w.parts[0].temp)
        assertEquals(100, w.parts[0].rain)
        assertEquals(15, w.parts[1].temp)
    }

    @Test fun `a grey mostly dry day`() {
        val w = Weather.summary(forecast({ if (it == 14) 8 else 0 }, { if (it == 12) 40 else 90 }), LocalDateTime.of(2026, 10, 25, 8, 12))
        assertEquals(11, w.high)
        assertEquals(8, w.low)
        assertEquals("Grey and mostly dry", w.headline)
        assertEquals("A whiff of drizzle about 14:00 (8% at most), brief sun around midday. Sunset 16:51.", w.detail)
        assertEquals("Walk any time before 16:15.", w.tip)
    }

    @Test fun `rain later in the day`() {
        val w = Weather.summary(forecast({ if (it >= 13) 70 else 5 }, { 95 }), LocalDateTime.of(2026, 10, 25, 9, 0))
        assertEquals("Dry morning, wet afternoon", w.headline)
        assertTrue(w.detail, w.detail.startsWith("Rain from 13:00, up to 70% and on and off until dark."))
        assertEquals("Leave the washing in. Best walk: 09:00–13:00.", w.tip)
    }

    @Test fun `no tip after dark`() {
        val w = Weather.summary(forecast({ 0 }, { 95 }), LocalDateTime.of(2026, 10, 25, 18, 0))
        assertEquals("Sunset 16:51.", w.detail)
        assertNull(w.tip)
        assertEquals(3, w.parts.size)
    }

    @Test fun `the forecast url asks for today in local time`() {
        val u = Weather.url(51.4546, -2.5879)
        assertTrue(u.startsWith("https://api.open-meteo.com/v1/forecast?latitude=51.455&longitude=-2.588"))
        assertTrue(u.contains("timezone=auto"))
        assertTrue(u.contains("temperature_2m,"))
    }
}
