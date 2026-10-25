package uk.tomlowndes.capture

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime

/** Drive time from where the phone is to the next event today with a place. Addresses are looked up once. */
object TravelFetch {
    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences("travel", Context.MODE_PRIVATE)

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 15_000
        // OpenStreetMap's usage policy asks every app to say who it is.
        c.setRequestProperty("User-Agent", "FlightDeck/2.0 (personal Obsidian app)")
        try { return c.inputStream.bufferedReader().readText() } finally { c.disconnect() }
    }

    private fun geocode(ctx: Context, address: String): Pair<Double, Double>? {
        val key = "geo_" + address.lowercase().trim()
        p(ctx).getString(key, null)?.split(',')?.let { (a, b) -> return a.toDouble() to b.toDouble() }
        val hit = runCatching { Travel.parseGeocode(get(Travel.geocodeUrl(address))) }.getOrNull() ?: return null
        p(ctx).edit().putString(key, "${hit.first},${hit.second}").apply()
        return hit
    }

    /** The travel line, or null when there's no trip, no location, or no route. Call off the main thread. */
    fun line(ctx: Context, events: List<CalEvent>, now: LocalDateTime = LocalDateTime.now()): TravelLine? {
        val e = Travel.nextTrip(events, now) ?: return null
        val from = WeatherFetch.location(ctx) ?: return null
        val to = geocode(ctx, e.location ?: return null) ?: return null
        val mins = runCatching { Travel.parseRoute(get(Travel.routeUrl(from, to))) }.getOrNull() ?: return null
        return Travel.line(e, mins)
    }
}
