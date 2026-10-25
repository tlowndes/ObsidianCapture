package uk.tomlowndes.capture

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.time.LocalDateTime

/** The travel line on the Weather card: drive time to the next event today that has a place. */
data class TravelLine(val event: CalEvent, val minutes: Int, val leaveBy: LocalDateTime)

/** Address lookup with OpenStreetMap (Nominatim) and drive times from OSRM. Both free, no key. */
object Travel {
    const val BUFFER_MIN = 5L

    /** The next timed event today with a location that hasn't started yet. */
    fun nextTrip(events: List<CalEvent>, now: LocalDateTime): CalEvent? =
        events.filter { !it.allDay && it.location != null && it.start.toLocalDate() == now.toLocalDate() && it.start.isAfter(now) }
            .minByOrNull { it.start }

    fun geocodeUrl(address: String) =
        "https://nominatim.openstreetmap.org/search?format=json&limit=1&q=" + URLEncoder.encode(address, "UTF-8")

    /** Latitude and longitude of the first match, or null. */
    fun parseGeocode(json: String): Pair<Double, Double>? = runCatching {
        val first = JSONArray(json).getJSONObject(0)
        first.getString("lat").toDouble() to first.getString("lon").toDouble()
    }.getOrNull()

    fun routeUrl(from: Pair<Double, Double>, to: Pair<Double, Double>) =
        "https://router.project-osrm.org/route/v1/driving/%.5f,%.5f;%.5f,%.5f?overview=false"
            .format(java.util.Locale.ROOT, from.second, from.first, to.second, to.first)

    /** Drive time in whole minutes (rounded up), or null. */
    fun parseRoute(json: String): Int? = runCatching {
        val o = JSONObject(json)
        if (o.optString("code") != "Ok") return null
        Math.ceil(o.getJSONArray("routes").getJSONObject(0).getDouble("duration") / 60.0).toInt()
    }.getOrNull()

    fun line(event: CalEvent, minutes: Int) =
        TravelLine(event, minutes, event.start.minusMinutes(minutes + BUFFER_MIN))
}
