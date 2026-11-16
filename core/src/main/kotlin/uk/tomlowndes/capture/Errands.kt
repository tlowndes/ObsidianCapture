package uk.tomlowndes.capture

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A place set up once: its @tag, a label, where it is and how close counts as "there". */
data class Place(val tag: String, val label: String, val lat: Double, val lon: Double, val radius: Int = 200)

/** The things to do at one place: open tasks with its @tag (and the shopping list, at the shopping place). */
data class Errand(val place: Place?, val tag: String, val items: List<String>, val tasks: List<TaskItem>)

/**
 * Errands by place (body-people-errands-v1). Add @tesco or @b&q to any task; set up places once in
 * "99 - Meta/Places.md". The phone nudges at most once per visit and keeps no history of where you've been.
 * Open items on the Shopping list go under the shopping place (@tesco unless changed).
 */
object Errands {
    const val PLACES = "99 - Meta/Places.md"
    const val DEFAULT_SHOP = "@tesco"
    private val TAG = Regex("(?<![\\w@])@([A-Za-z][A-Za-z0-9&'_-]*)")
    private val LINE = Regex("^- (@\\S+) · (.+?) · (-?\\d+\\.\\d+), ?(-?\\d+\\.\\d+)(?: · (\\d+) ?m)?\\s*$")

    fun tags(raw: String): List<String> = TAG.findAll(raw).map { "@" + it.groupValues[1].lowercase() }.toList()

    fun cleanTag(s: String) = "@" + s.trim().removePrefix("@").lowercase().replace(Regex("[^a-z0-9&'_-]"), "")

    fun parsePlaces(text: String?): List<Place> = text.orEmpty().replace("\r", "").split("\n").mapNotNull { l ->
        LINE.find(l.trim())?.groupValues?.let { g ->
            Place(g[1].lowercase(), g[2].trim(), g[3].toDouble(), g[4].toDouble(), g[5].toIntOrNull() ?: 200)
        }
    }

    fun places(v: Vault): List<Place> = parsePlaces(v.read(PLACES))

    fun line(p: Place) = "- ${p.tag} · ${p.label.replace("·", "-")} · ${"%.5f".format(java.util.Locale.ROOT, p.lat)},${"%.5f".format(java.util.Locale.ROOT, p.lon)} · ${p.radius}m"

    fun savePlaces(v: Vault, places: List<Place>) {
        val body = "---\ntype: places\n---\n\nPlaces for errands. Add @tag to any task to do it there. Flight Deck keeps this list.\n\n" +
            places.distinctBy { it.tag }.joinToString("\n") { line(it) } + "\n"
        if (v.entry(PLACES) == null) { v.mkdirs(PLACES.substringBeforeLast('/')); VaultIo.write(v, v.create(PLACES.substringBeforeLast('/'), "Places", "md"), body) }
        else VaultIo.write(v, PLACES, body)
    }

    fun upsert(v: Vault, p: Place) = savePlaces(v, places(v).filter { it.tag != p.tag } + p)
    /** Saves [p], dropping the place it was edited from when its tag changed. */
    fun replace(v: Vault, oldTag: String?, p: Place) = savePlaces(v, places(v).filter { it.tag != p.tag && it.tag != oldTag } + p)
    fun remove(v: Vault, tag: String) = savePlaces(v, places(v).filter { it.tag != tag })

    /** Errands grouped by @tag, places you've set up first, biggest first. */
    fun errands(tasks: List<TaskItem>, shopping: List<String>, places: List<Place>, shopTag: String = DEFAULT_SHOP): List<Errand> {
        val byTag = linkedMapOf<String, MutableList<TaskItem>>()
        tasks.forEach { t -> tags(t.raw).distinct().forEach { byTag.getOrPut(it) { mutableListOf() }.add(t) } }
        val tagsAll = (byTag.keys + (if (shopping.isNotEmpty()) listOf(shopTag) else emptyList())).distinct()
        return tagsAll.map { tag ->
            val ts = byTag[tag].orEmpty()
            val items = ts.map { TaskViews.plainText(it).replace(TAG, "").replace(Regex("\\s+"), " ").trim() } + (if (tag == shopTag) shopping.map { SharedList.item(it).let { i -> if (i.partner) "${i.text} · partner" else i.text } } else emptyList())
            Errand(places.firstOrNull { it.tag == tag }, tag, items, ts)
        }.sortedWith(compareBy<Errand> { it.place == null }.thenByDescending { it.items.size })
    }

    /** Metres between two points. */
    fun metres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Places you're at now (within their radius, plus [slack] metres for a rough fix). */
    fun at(places: List<Place>, lat: Double, lon: Double, slack: Double = 0.0) = places.filter { metres(lat, lon, it.lat, it.lon) <= it.radius + slack }

    /**
     * Once per visit: given the places you're at now and the ones you were already at, returns the places to nudge
     * about (just arrived, with errands) and the new "inside" set to remember. Leaving a place clears it.
     */
    fun arrivals(here: List<Place>, inside: Set<String>, errands: List<Errand>): Pair<List<Errand>, Set<String>> {
        val now = here.map { it.tag }.toSet()
        val nudge = errands.filter { e -> e.place != null && e.tag in now && e.tag !in inside && e.items.isNotEmpty() }
        return nudge to now
    }

    /** "Shelf brackets ×4 · Sandpaper" (first few). */
    fun summary(e: Errand, max: Int = 3) = e.items.take(max).joinToString(" · ") + if (e.items.size > max) " +${e.items.size - max}" else ""

    fun title(e: Errand) = "📍 Near ${e.place?.label?.substringBefore(" ")?.ifEmpty { null } ?: e.tag} · ${e.items.size} errand${if (e.items.size == 1) "" else "s"}"
}
