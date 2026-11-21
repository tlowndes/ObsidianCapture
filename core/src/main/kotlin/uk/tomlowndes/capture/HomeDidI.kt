package uk.tomlowndes.capture

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Locale

/** How a Did I? check is answered by the house: `ha lock.front_door = locked`, `ha sensor.hob_plug_power < 5`. */
data class HaLink(val entity: String, val op: Op, val value: String) {
    enum class Op(val sym: String, val words: String) { EQ("=", "is"), NE("!=", "isn't"), LT("<", "under"), GT(">", "over") }

    fun format() = "ha $entity ${op.sym} $value"

    companion object {
        private val RE = Regex("""^ha\s+([a-z_]+\.[a-z0-9_]+)\s*(!=|≠|=|<|>)\s*(.+?)\s*$""")

        fun parse(s: String): HaLink? {
            val m = RE.matchEntire(s.trim()) ?: return null
            val op = when (m.groupValues[2]) { "=" -> Op.EQ; "!=", "≠" -> Op.NE; "<" -> Op.LT; else -> Op.GT }
            return HaLink(m.groupValues[1], op, m.groupValues[3])
        }
    }
}

/** One Home Assistant entity as `/api/states` gives it. */
data class HaState(val entity: String, val state: String, val changed: Instant? = null, val unit: String? = null, val name: String? = null) {
    val domain get() = entity.substringBefore('.')
    val known get() = state.isNotBlank() && state != "unknown" && state != "unavailable"
}

/**
 * Home Did I? (hangar-phase4): Home Assistant answers Did I? checks. A lock or door sensor answers "Is the door
 * locked?", a smart plug's power answers "Is the hob off?". When a linked check becomes true today, Flight Deck logs
 * it in the day's note as `- 08:39 · Door · auto`, at the time HA says it changed. If you've left home (your HA
 * person isn't "home") and a linked check is false, it buzzes once.
 */
object HomeDidI {
    const val STATES_PATH = "/api/states"
    /** Domains worth linking a check to, in the order the picker lists them. */
    val DOMAINS = listOf("lock", "binary_sensor", "sensor", "switch", "light", "cover", "climate", "fan", "input_boolean")
    private val NUMBER = Regex("""-?\d+(?:\.\d+)?""")

    fun states(json: String): Map<String, HaState> = runCatching {
        val a = JSONArray(json)
        (0 until a.length()).mapNotNull { i -> state(a.optJSONObject(i)) }.associateBy { it.entity }
    }.getOrDefault(emptyMap())

    fun state(o: JSONObject?): HaState? {
        o ?: return null
        val id = o.optString("entity_id").ifBlank { return null }
        val attrs = o.optJSONObject("attributes")
        val changed = o.optString("last_changed").ifBlank { null }?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() }
        return HaState(id, o.optString("state"), changed, attrs?.optString("unit_of_measurement")?.ifBlank { null },
            attrs?.optString("friendly_name")?.ifBlank { null })
    }

    private fun num(s: String) = NUMBER.find(s)?.value?.toDoubleOrNull()

    /** True when [st] meets [link], false when it doesn't, null when HA doesn't know (offline, unavailable). */
    fun answer(link: HaLink, st: HaState?): Boolean? {
        if (st == null || !st.known) return null
        return when (link.op) {
            HaLink.Op.EQ -> st.state.equals(link.value.trim(), ignoreCase = true)
            HaLink.Op.NE -> !st.state.equals(link.value.trim(), ignoreCase = true)
            HaLink.Op.LT -> num(st.state)?.let { a -> num(link.value)?.let { a < it } }
            HaLink.Op.GT -> num(st.state)?.let { a -> num(link.value)?.let { a > it } }
        }
    }

    /** The state in words: "Locked", "0 W", "Off", or "No answer from HA". */
    fun words(st: HaState?): String {
        if (st == null || !st.known) return "No answer from HA"
        val v = st.state.replace('_', ' ').replaceFirstChar { it.uppercase(Locale.UK) }
        return if (st.unit != null) "${st.state} ${st.unit}" else v
    }

    /**
     * Auto stamps to add today: each linked check that's true, changed to that today, and isn't stamped (by you or
     * by HA) since. A door locked since last night isn't stamped; its live answer shows instead.
     */
    fun autoStamps(checks: List<DidCheck>, states: Map<String, HaState>, stamps: List<DidStamp>, today: LocalDate,
                   zone: ZoneId = ZoneId.systemDefault()): List<DidStamp> = checks.mapNotNull { c ->
        val link = c.link ?: return@mapNotNull null
        val st = states[link.entity] ?: return@mapNotNull null
        if (answer(link, st) != true) return@mapNotNull null
        val at = st.changed?.atZone(zone)?.toLocalDateTime() ?: return@mapNotNull null
        if (at.toLocalDate() != today) return@mapNotNull null
        val time = at.toLocalTime().withSecond(0).withNano(0)
        if (stamps.any { it.name.equals(c.name, ignoreCase = true) && !it.time.isBefore(time) }) return@mapNotNull null
        DidStamp(time, c.name, auto = true)
    }

    /** Linked checks the house says aren't done: the hob still drawing power, the door unlocked. */
    fun stillOn(checks: List<DidCheck>, states: Map<String, HaState>): List<Pair<DidCheck, HaState>> =
        checks.mapNotNull { c -> c.link?.let { l -> states[l.entity]?.takeIf { answer(l, it) == false }?.let { c to it } } }

    /** You've left: your HA person (or tracker) is somewhere other than home, and HA knows it. */
    fun away(person: HaState?): Boolean = person != null && person.known && !person.state.equals("home", ignoreCase = true)

    /** One buzz per check per time you leave: the key is when you left plus the check. */
    fun alertKey(person: HaState, c: DidCheck) = "${person.changed?.epochSecond ?: 0}|${c.name.lowercase(Locale.UK)}"

    /** "Hob's still on" with the reading, for the left-home buzz. */
    fun alertWords(c: DidCheck, st: HaState): Pair<String, String> =
        "${c.emoji} ${c.name}: not done" to "${words(st)}, and you've left home. Open Home Assistant to sort it?"

    /** A sensible test for [st]: locked, closed (off), under 5 W, or off. */
    fun suggest(st: HaState): HaLink = when {
        st.domain == "lock" -> HaLink(st.entity, HaLink.Op.EQ, "locked")
        st.domain == "sensor" && st.unit in listOf("W", "kW") -> HaLink(st.entity, HaLink.Op.LT, if (st.unit == "kW") "0.005" else "5")
        st.domain == "sensor" && num(st.state) != null -> HaLink(st.entity, HaLink.Op.LT, st.state)
        st.domain == "cover" -> HaLink(st.entity, HaLink.Op.EQ, "closed")
        else -> HaLink(st.entity, HaLink.Op.EQ, "off")
    }

    /** Entities a check can link to, matching [query] (name or id), lock and door sensors first. */
    fun linkable(states: Map<String, HaState>, query: String = ""): List<HaState> {
        val q = query.trim().lowercase(Locale.UK)
        return states.values.filter { it.domain in DOMAINS }
            .filter { q.isEmpty() || it.entity.contains(q) || it.name?.lowercase(Locale.UK)?.contains(q) == true }
            .sortedWith(compareBy<HaState> { DOMAINS.indexOf(it.domain) }.thenBy { it.name ?: it.entity })
    }

    /** People and trackers that say whether you're home. */
    fun people(states: Map<String, HaState>) = states.values.filter { it.domain == "person" || it.domain == "device_tracker" }
        .sortedWith(compareBy<HaState> { if (it.domain == "person") 0 else 1 }.thenBy { it.entity })
}
