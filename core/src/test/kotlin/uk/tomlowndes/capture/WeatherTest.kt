package uk.tomlowndes.capture

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class WeatherTest {
    private fun forecast(rain: (Int) -> Int, cloud: (Int) -> Int, code: Int = 3): String {
        val hours = 0..23
        return JSONObject()
            .put("daily", JSONObject()
                .put("temperature_2m_max", JSONArray(listOf(11.2)))
                .put("temperature_2m_min", JSONArray(listOf(7.6)))
                .put("sunset", JSONArray(listOf("2026-10-25T16:51")))
                .put("weather_code", JSONArray(listOf(code))))
            .put("hourly", JSONObject()
                .put("time", JSONArray(hours.map { "2026-10-25T%02d:00".format(it) }))
                .put("precipitation_probability", JSONArray(hours.map(rain)))
                .put("cloud_cover", JSONArray(hours.map(cloud)))
                .put("weather_code", JSONArray(hours.map { code })))
            .toString()
    }

    @Test fun `a grey mostly dry day reads like the morning email`() {
        val w = Weather.summary(forecast({ if (it == 14) 8 else 0 }, { if (it == 12) 40 else 90 }), LocalDateTime.of(2026, 10, 25, 8, 12))
        assertEquals(11, w.high)
        assertEquals(8, w.low)
        assertEquals("Grey and mostly dry", w.headline)
        assertEquals(
            "Brief sun around midday, a whiff of drizzle about 14:00 (8% at most). Sunset 16:51. If you're going out for a walk, go before 16:21.",
            w.detail
        )
    }

    @Test fun `rain later moves the walk earlier`() {
        val w = Weather.summary(forecast({ if (it >= 15) 70 else 5 }, { 95 }), LocalDateTime.of(2026, 10, 25, 9, 0))
        assertEquals("Grey with rain likely", w.headline)
        assertTrue(w.detail, w.detail.startsWith("Rain likely about 15:00 (70%)."))
        assertTrue(w.detail.endsWith("go before 15:00."))
    }

    @Test fun `no walk line after dark`() {
        val w = Weather.summary(forecast({ 0 }, { 95 }), LocalDateTime.of(2026, 10, 25, 18, 0))
        assertEquals("Sunset 16:51.", w.detail)
    }

    @Test fun `the forecast url asks for today in local time`() {
        val u = Weather.url(51.4546, -2.5879)
        assertTrue(u.startsWith("https://api.open-meteo.com/v1/forecast?latitude=51.455&longitude=-2.588"))
        assertTrue(u.contains("timezone=auto"))
    }
}
