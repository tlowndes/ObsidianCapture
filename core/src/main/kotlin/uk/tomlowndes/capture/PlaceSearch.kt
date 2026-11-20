package uk.tomlowndes.capture

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

/** One search result: a shop or address with where it is, and how far from you when known. */
data class PlaceHit(val name: String, val address: String, val lat: Double, val lon: Double, val metres: Double? = null)

/**
 * Finding errand places by name, street or postcode (errand-places-v1). Photon (komoot, OpenStreetMap data) is built for
 * search-as-you-type; Nominatim is the fallback. Both are free with no key, and ask for a User-Agent and an attribution line.
 */
object PlaceSearch {
    const val MIN_CHARS = 3
    const val ATTRIBUTION = "Results © OpenStreetMap contributors"
    val RADII = listOf(100, 200, 400, 800)

    private fun enc(s: String) = URLEncoder.encode(s.trim(), "UTF-8")
    private fun f(d: Double) = "%.5f".format(Locale.ROOT, d)

    /** Photon, nudged towards [near] so the closest branch ranks higher. */
    fun photonUrl(q: String, near: Pair<Double, Double>? = null) =
        "https://photon.komoot.io/api/?limit=8&lang=en&q=" + enc(q) + (near?.let { "&lat=${f(it.first)}&lon=${f(it.second)}" } ?: "")

    fun nominatimUrl(q: String) = "https://nominatim.openstreetmap.org/search?format=json&limit=8&addressdetails=0&q=" + enc(q)

    fun parsePhoton(json: String): List<PlaceHit> = runCatching {
        val fs = JSONObject(json).optJSONArray("features") ?: return emptyList()
        (0 until fs.length()).mapNotNull { i ->
            val o = fs.optJSONObject(i) ?: return@mapNotNull null
            val c = o.optJSONObject("geometry")?.optJSONArray("coordinates") ?: return@mapNotNull null
            val p = o.optJSONObject("properties") ?: JSONObject()
            fun s(k: String) = p.optString(k, "").trim()
            val street = listOf(s("housenumber"), s("street")).filter { it.isNotEmpty() }.joinToString(" ")
            val name = s("name").ifEmpty { street }.ifEmpty { s("postcode") }.ifEmpty { return@mapNotNull null }
            val town = s("district").ifEmpty { s("locality") }.ifEmpty { s("city") }
            val addr = listOf(street.takeIf { it != name }, town, s("postcode").takeIf { it != name })
                .filter { !it.isNullOrEmpty() }.distinct().joinToString(", ")
            PlaceHit(name, addr, c.getDouble(1), c.getDouble(0))
        }
    }.getOrDefault(emptyList())

    fun parseNominatim(json: String): List<PlaceHit> = runCatching {
        val a = JSONArray(json)
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val full = o.optString("display_name").split(",").map { it.trim() }.filter { it.isNotEmpty() }
            val name = o.optString("name").trim().ifEmpty { full.firstOrNull() ?: return@mapNotNull null }
            PlaceHit(name, full.filter { it != name }.take(3).joinToString(", "), o.getString("lat").toDouble(), o.getString("lon").toDouble())
        }
    }.getOrDefault(emptyList())

    /** Nearest first when we know where you are (results with the same spot dropped); otherwise by name. */
    fun sorted(hits: List<PlaceHit>, near: Pair<Double, Double>?): List<PlaceHit> {
        val unique = hits.distinctBy { "%.4f,%.4f".format(Locale.ROOT, it.lat, it.lon) }
        if (near == null) return unique.sortedBy { it.name.lowercase() }
        return unique.map { it.copy(metres = Errands.metres(near.first, near.second, it.lat, it.lon)) }.sortedBy { it.metres }
    }

    /** "350 m", "1.2 km", "14 km". */
    fun distance(m: Double): String = when {
        m < 1000 -> "${(Math.round(m / 10.0) * 10).toInt()} m"
        m < 10_000 -> "%.1f km".format(Locale.ROOT, m / 1000)
        else -> "${Math.round(m / 1000)} km"
    }

    /**
     * A tag guessed from a place name: the first word, so "B&Q Kirkstall" is @b&q and "Tesco Extra" is @tesco.
     * If a tag you already use starts the name (say @boots for "Boots Pharmacy"), that one wins.
     */
    fun tagFor(name: String, known: Collection<String> = emptyList()): String {
        fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() || it == '&' }
        val flat = norm(name)
        known.map { it.lowercase() }.filter { norm(it).length > 1 && flat.startsWith(norm(it)) }.maxByOrNull { it.length }?.let { return it }
        val first = name.trim().split(Regex("\\s+")).firstOrNull().orEmpty()
        return Errands.cleanTag(first).takeIf { it.length > 1 } ?: Errands.cleanTag(name)
    }
}
