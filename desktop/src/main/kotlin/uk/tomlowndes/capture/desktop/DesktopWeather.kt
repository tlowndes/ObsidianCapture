package uk.tomlowndes.capture.desktop

import org.json.JSONObject
import uk.tomlowndes.capture.Weather
import uk.tomlowndes.capture.WeatherSummary
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** Weather for Deck: the town from Settings, Open-Meteo, cached for an hour. The phone uses its location instead. */
object DesktopWeather {
    private const val FRESH_MS = 60 * 60_000L

    /** Finds [place] with Open-Meteo's geocoder: (lat, lon, "Manchester, United Kingdom"), or null. Blocking. */
    fun find(place: String): Triple<Double, Double, String>? {
        val q = URLEncoder.encode(place.trim(), "UTF-8")
        val json = get("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=1&language=en&format=json")
        val r = JSONObject(json).optJSONArray("results")?.optJSONObject(0) ?: return null
        val name = listOf(r.optString("name"), r.optString("country")).filter { it.isNotBlank() }.joinToString(", ")
        return Triple(r.getDouble("latitude"), r.getDouble("longitude"), name)
    }

    /** Today's summary, from the cache if it's under an hour old. Null when no town is set. Blocking. */
    fun today(prefs: DesktopPrefs, force: Boolean = false): WeatherSummary? {
        val (lat, lon) = prefs.weatherLatLon ?: return null
        val now = LocalDateTime.now()
        val cached = prefs.weatherJson
        val sameDay = cached != null && Instant.ofEpochMilli(prefs.weatherAt).atZone(ZoneId.systemDefault()).toLocalDate() == now.toLocalDate()
        if (!force && sameDay && Weather.hasTomorrow(cached) && System.currentTimeMillis() - prefs.weatherAt < FRESH_MS) {
            return runCatching { Weather.summary(cached!!, now) }.getOrNull()
        }
        val fresh = runCatching { get(Weather.url(lat, lon)) }.getOrNull()
        if (fresh != null) { prefs.weatherJson = fresh; prefs.weatherAt = System.currentTimeMillis() }
        val json = fresh ?: cached?.takeIf { sameDay } ?: return null
        return runCatching { Weather.summary(json, now) }.getOrNull()
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 15_000
        try { return c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
    }
}
