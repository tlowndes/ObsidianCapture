package uk.tomlowndes.capture

import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Sensory Dial's three levels, as Home Assistant's `input_select.sensory_level` names them. */
enum class SensoryLevel(val option: String, val words: String) {
    NORMAL("Normal", "Room as usual"),
    LOW("Low", "Lights down, soft noise, priority calls only"),
    SHELTER("Shelter", "Room's turned down. Take the time you need.");

    val down get() = this != NORMAL

    companion object {
        fun byOption(s: String) = values().firstOrNull { it.option.equals(s.trim(), ignoreCase = true) }
    }
}

/** The level changed to [level] at [at] (local time, to the minute). */
data class SensoryChange(val at: LocalDateTime, val level: SensoryLevel)

/**
 * Sensory Dial (sensory-dial-v1): a knob on the desk (an IKEA STYRBAR for now) turns the room down through Home
 * Assistant. HA holds the level; Flight Deck sets it from its three buttons and keeps a quiet log of every change in
 * the vault, `- 2026-11-20 14:02 · Low · 12 min`, the same shape as the Focus log. The minutes are how long that
 * level lasted, so the latest line has none yet.
 *
 * HA never writes to the vault: Flight Deck reads the level's history from HA's API and merges it into the log.
 */
object Sensory {
    const val LOG = "99 - Meta/Sensory log.md"
    const val ENTITY = "input_select.sensory_level"
    private const val HEADER = "# Sensory log\n\nEvery turn of the Sensory Dial, written by Flight Deck from Home Assistant.\n\n"
    private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private val LINE = Regex("""^- (\d{4}-\d{2}-\d{2} \d{2}:\d{2}) · (\w+)(?: · \d+ min)?\s*$""")

    // ---- The log ----

    fun parse(text: String): List<SensoryChange> = text.lines().mapNotNull { l ->
        val m = LINE.find(l.trim()) ?: return@mapNotNull null
        val at = runCatching { LocalDateTime.parse(m.groupValues[1], STAMP) }.getOrNull() ?: return@mapNotNull null
        SensoryChange(at, SensoryLevel.byOption(m.groupValues[2]) ?: return@mapNotNull null)
    }

    /** One log line; [until] is when the next change came, if it has. */
    fun line(c: SensoryChange, until: LocalDateTime?): String {
        val mins = until?.let { Duration.between(c.at, it).toMinutes() }
        return "- ${c.at.format(STAMP)} · ${c.level.option}" + (mins?.let { " · $it min" } ?: "")
    }

    fun format(changes: List<SensoryChange>): String = HEADER + changes.mapIndexed { i, c ->
        line(c, changes.getOrNull(i + 1)?.at)
    }.joinToString("\n") + "\n"

    /**
     * Old and new changes as one tidy run: oldest first, to the minute, one per minute (the later wins), and a level
     * that repeats the one before it dropped (HA's history starts with the level it was already at).
     */
    fun merge(old: List<SensoryChange>, new: List<SensoryChange>): List<SensoryChange> {
        val byMinute = LinkedHashMap<LocalDateTime, SensoryChange>()
        (old + new).map { it.copy(at = it.at.withSecond(0).withNano(0)) }.sortedBy { it.at }.forEach { byMinute[it.at] = it }
        val out = mutableListOf<SensoryChange>()
        for (c in byMinute.values) if (out.lastOrNull()?.level != c.level) out += c
        return out
    }

    fun load(v: Vault): List<SensoryChange> = v.read(LOG)?.let(::parse).orEmpty()

    /** Adds [new] to the log. Returns the merged log; writes only when something changed. */
    fun save(v: Vault, new: List<SensoryChange>): List<SensoryChange> {
        if (v.entry(LOG) == null) {
            val all = merge(emptyList(), new)
            if (all.isEmpty()) return all
            v.mkdirs(parentOf(LOG))
            VaultIo.write(v, v.create(parentOf(LOG), nameOf(LOG).removeSuffix(".md"), "md"), format(all))
            return all
        }
        var out = emptyList<SensoryChange>()
        VaultIo.edit(v, LOG) { lines ->
            val old = parse(lines.joinToString("\n"))
            out = merge(old, new)
            if (out == old) return@edit
            lines.clear(); lines.addAll(format(out).removeSuffix("\n").lines())
        }
        return out
    }

    /** The level now: the last change, or Normal when there's none. */
    fun current(changes: List<SensoryChange>) = changes.lastOrNull()?.level ?: SensoryLevel.NORMAL

    /** Minutes the room was turned down (Low or Shelter) on [day], up to [now] for today. */
    fun downMinutes(changes: List<SensoryChange>, day: LocalDate, now: LocalDateTime): Int {
        val start = day.atStartOfDay()
        val end = minOf(day.plusDays(1).atStartOfDay(), now)
        if (!end.isAfter(start)) return 0
        var total = 0L
        changes.forEachIndexed { i, c ->
            if (!c.level.down) return@forEachIndexed
            val from = maxOf(c.at, start)
            val to = minOf(changes.getOrNull(i + 1)?.at ?: now, end)
            if (to.isAfter(from)) total += Duration.between(from, to).toMinutes()
        }
        return total.toInt()
    }

    /** This week, Monday to Sunday: each day's turned-down minutes (0 for days still to come). */
    fun week(changes: List<SensoryChange>, now: LocalDateTime): List<Pair<LocalDate, Int>> {
        val monday = now.toLocalDate().minusDays(now.dayOfWeek.value - 1L)
        return (0L until 7L).map { monday.plusDays(it) }.map { it to downMinutes(changes, it, now) }
    }

    /** How long the current level has lasted, in words: "18 min", "2 h 5 min". */
    fun sinceWords(changes: List<SensoryChange>, now: LocalDateTime): String? {
        val last = changes.lastOrNull() ?: return null
        val m = Duration.between(last.at, now).toMinutes().coerceAtLeast(0)
        return if (m < 60) "$m min" else "${m / 60} h ${m % 60} min"
    }

    // ---- Home Assistant ----

    /** The base address as typed (`homeassistant.local:8123`, `https://x.ui.nabu.casa`): scheme, host and port. */
    data class Ha(val https: Boolean, val host: String, val port: Int)

    fun ha(address: String): Ha? {
        val a = address.trim().ifEmpty { return null }
        val https = a.startsWith("https://")
        val rest = a.removePrefix("https://").removePrefix("http://").substringBefore("/")
        if (rest.isEmpty()) return null
        val host = rest.substringBefore(":")
        val port = if (":" in rest) rest.substringAfter(":").toIntOrNull() ?: return null else if (https) 443 else 8123
        return Ha(https, host, port)
    }

    /** POST /api/services/input_select/select_option */
    const val SELECT_PATH = "/api/services/input_select/select_option"
    fun selectBody(level: SensoryLevel) = JSONObject().put("entity_id", ENTITY).put("option", level.option).toString()

    const val STATE_PATH = "/api/states/$ENTITY"
    fun stateOf(json: String): SensoryLevel? = runCatching { SensoryLevel.byOption(JSONObject(json).getString("state")) }.getOrNull()

    /** GET path for the level's history since [since]: changes only, no attributes. */
    fun historyPath(since: Instant): String =
        "/api/history/period/${since.toString()}?filter_entity_id=$ENTITY&minimal_response&no_attributes"

    /** HA's history answer as changes in local time; unknown and unavailable states are skipped. */
    fun history(json: String, zone: ZoneId = ZoneId.systemDefault()): List<SensoryChange> {
        val outer = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        val rows = outer.optJSONArray(0) ?: return emptyList()
        return (0 until rows.length()).mapNotNull { i ->
            val o = rows.optJSONObject(i) ?: return@mapNotNull null
            val level = SensoryLevel.byOption(o.optString("state")) ?: return@mapNotNull null
            val at = runCatching { OffsetDateTime.parse(o.optString("last_changed")) }.getOrNull() ?: return@mapNotNull null
            SensoryChange(at.atZoneSameInstant(zone).toLocalDateTime().withSecond(0).withNano(0), level)
        }
    }

    /** Where to ask HA's history from: the last logged change, or a week back when the log is new. */
    fun since(changes: List<SensoryChange>, now: LocalDateTime, zone: ZoneId = ZoneId.systemDefault()): Instant =
        (changes.lastOrNull()?.at ?: now.minusDays(7)).atZone(zone).toInstant()
}
