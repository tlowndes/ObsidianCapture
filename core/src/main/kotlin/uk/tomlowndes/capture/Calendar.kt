package uk.tomlowndes.capture

import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** A Proton calendar shared as an ICS link (full view). [colour] is a hex like "#7aa2ff". */
data class CalendarSource(val id: String, val name: String, val url: String, val colour: String, val enabled: Boolean = true)

/** One event occurrence in local time. All-day events run from midnight to midnight. */
data class CalEvent(val calendarId: String, val title: String, val start: LocalDateTime, val end: LocalDateTime, val allDay: Boolean) {
    fun isNow(now: LocalDateTime) = !allDay && !now.isBefore(start) && now.isBefore(end)
    fun on(d: LocalDate) = start.isBefore(d.plusDays(1).atStartOfDay()) && (end.isAfter(d.atStartOfDay()) || start.toLocalDate() == d)
}

/** Reading ICS feeds, enough for Proton's share links: VEVENT with DTSTART/DTEND/SUMMARY, RRULE and EXDATE. */
object Ics {
    private const val MAX_OCCURRENCES = 20_000

    /** Events overlapping [from]..[to] (dates inclusive), in [zone], sorted by start. */
    fun events(text: String, calendarId: String, from: LocalDate, to: LocalDate, zone: ZoneId = ZoneId.systemDefault()): List<CalEvent> {
        val lines = unfold(text)
        val out = mutableListOf<CalEvent>()
        var props: MutableList<Pair<String, String>>? = null
        for (l in lines) {
            when {
                l == "BEGIN:VEVENT" -> props = mutableListOf()
                l == "END:VEVENT" -> { props?.let { out += expand(it, calendarId, from, to, zone) }; props = null }
                props != null -> { val i = l.indexOf(':'); if (i > 0) props.add(l.substring(0, i) to l.substring(i + 1)) }
            }
        }
        return out.sortedWith(compareBy({ !it.allDay }, { it.start }))
    }

    private fun unfold(text: String): List<String> {
        val out = mutableListOf<String>()
        text.replace("\r\n", "\n").split("\n").forEach { l ->
            if ((l.startsWith(" ") || l.startsWith("\t")) && out.isNotEmpty()) out[out.lastIndex] = out.last() + l.substring(1)
            else if (l.isNotEmpty()) out += l
        }
        return out
    }

    private fun prop(props: List<Pair<String, String>>, name: String) = props.firstOrNull { it.first.substringBefore(';').equals(name, true) }

    private fun params(key: String) = key.split(';').drop(1).associate { p -> p.substringBefore('=').uppercase() to p.substringAfter('=').trim('"') }

    /** A DTSTART/DTEND/EXDATE value as local time in [zone]; date-only values come back at midnight with allDay = true. */
    internal fun time(key: String, value: String, zone: ZoneId): Pair<LocalDateTime, Boolean>? = runCatching {
        val p = params(key)
        val v = value.trim()
        if (p["VALUE"] == "DATE" || v.length == 8) return LocalDate.parse(v, DateTimeFormatter.BASIC_ISO_DATE).atStartOfDay() to true
        val local = LocalDateTime.parse(v.removeSuffix("Z"), DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"))
        val src = when {
            v.endsWith("Z") -> ZoneOffset.UTC
            p["TZID"] != null -> runCatching { ZoneId.of(p["TZID"]) }.getOrDefault(zone)
            else -> zone
        }
        ZonedDateTime.of(local, src).withZoneSameInstant(zone).toLocalDateTime() to false
    }.getOrNull()

    private fun unescape(s: String) = s.replace("\\n", " ").replace("\\N", " ").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\").trim()

    private fun expand(props: List<Pair<String, String>>, cal: String, from: LocalDate, to: LocalDate, zone: ZoneId): List<CalEvent> {
        if (prop(props, "STATUS")?.second.equals("CANCELLED", true)) return emptyList()
        val (sk, sv) = prop(props, "DTSTART") ?: return emptyList()
        val (start, allDay) = time(sk, sv, zone) ?: return emptyList()
        val end = prop(props, "DTEND")?.let { time(it.first, it.second, zone)?.first }
            ?: prop(props, "DURATION")?.let { d -> runCatching { start.plus(java.time.Duration.parse(d.second.trim())) }.getOrNull() }
            ?: if (allDay) start.plusDays(1) else start.plusHours(1)
        val length = ChronoUnit.MINUTES.between(start, end)
        val title = unescape(prop(props, "SUMMARY")?.second ?: "")
        val exdates = props.filter { it.first.substringBefore(';').equals("EXDATE", true) }
            .flatMap { (k, v) -> v.split(',').mapNotNull { time(k, it, zone)?.first } }.toSet()
        val windowStart = from.atStartOfDay()
        val windowEnd = to.plusDays(1).atStartOfDay()
        fun hit(s: LocalDateTime) = s !in exdates && s.isBefore(windowEnd) && s.plusMinutes(length).isAfter(windowStart)
        val starts = prop(props, "RRULE")?.let { occurrences(start, it.second, windowEnd, zone) } ?: listOf(start)
        return starts.filter(::hit).map { CalEvent(cal, title, it, it.plusMinutes(length), allDay) }
    }

    /** Start times from a recurrence rule, up to [until]. Handles FREQ, INTERVAL, COUNT, UNTIL and BYDAY (weekly). */
    internal fun occurrences(start: LocalDateTime, rule: String, until: LocalDateTime, zone: ZoneId): List<LocalDateTime> {
        val r = rule.split(';').associate { it.substringBefore('=').uppercase() to it.substringAfter('=') }
        val interval = r["INTERVAL"]?.toLongOrNull() ?: 1
        val count = r["COUNT"]?.toIntOrNull()
        val stop = r["UNTIL"]?.let { time("UNTIL", it, zone)?.first }?.let { if (it.toLocalTime() == LocalTime.MIDNIGHT) it.plusDays(1).minusSeconds(1) else it }
        val days = r["BYDAY"]?.split(',')?.mapNotNull { d -> DAYS[d.takeLast(2)] }?.toSet()
        val out = mutableListOf<LocalDateTime>()
        fun add(s: LocalDateTime): Boolean {
            if (stop != null && s.isAfter(stop)) return false
            if (s.isAfter(until)) return false
            if (count != null && out.size >= count) return false
            out += s
            return out.size < MAX_OCCURRENCES
        }
        when (r["FREQ"]) {
            "DAILY" -> { var s = start; while (add(s)) s = s.plusDays(interval) }
            "WEEKLY" -> {
                val weekDays = days ?: setOf(start.dayOfWeek)
                var weekStart = start.toLocalDate().minusDays(start.dayOfWeek.value - 1L)
                loop@ while (true) {
                    for (d in DayOfWeek.values().filter { it in weekDays }) {
                        val s = weekStart.plusDays(d.value - 1L).atTime(start.toLocalTime())
                        if (s.isBefore(start)) continue
                        if (!add(s)) break@loop
                    }
                    weekStart = weekStart.plusWeeks(interval)
                    if (weekStart.atStartOfDay().isAfter(until)) break
                }
            }
            "MONTHLY" -> { var n = 0L; while (add(start.plusMonths(n))) n += interval }
            "YEARLY" -> { var n = 0L; while (add(start.plusYears(n))) n += interval }
            else -> out += start
        }
        return out
    }

    private val DAYS = mapOf(
        "MO" to DayOfWeek.MONDAY, "TU" to DayOfWeek.TUESDAY, "WE" to DayOfWeek.WEDNESDAY, "TH" to DayOfWeek.THURSDAY,
        "FR" to DayOfWeek.FRIDAY, "SA" to DayOfWeek.SATURDAY, "SU" to DayOfWeek.SUNDAY
    )
}

/** Calendar links live in the vault config next to the card order, so they follow Obsidian Sync. */
object CalendarConfig {
    fun sources(json: String?): List<CalendarSource> {
        val o = json?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return emptyList()
        val a = o.optJSONArray("calendars") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map {
            CalendarSource(it.optString("id"), it.optString("name"), it.optString("url"), it.optString("colour", "#7aa2ff"), it.optBoolean("enabled", true))
        }.filter { it.url.isNotBlank() }
    }

    fun withSources(json: String?, list: List<CalendarSource>): String {
        val o = json?.takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
        o.put("calendars", JSONArray(list.map {
            JSONObject().put("id", it.id).put("name", it.name).put("url", it.url).put("colour", it.colour).put("enabled", it.enabled)
        }))
        return o.toString(2)
    }

    fun load(v: Vault) = sources(v.read(DeckConfig.PATH))

    fun save(v: Vault, list: List<CalendarSource>) {
        if (v.entry(DeckConfig.PATH) == null) {
            v.mkdirs(parentOf(DeckConfig.PATH))
            v.create(parentOf(DeckConfig.PATH), nameOf(DeckConfig.PATH).removeSuffix(".json"), "json")
        }
        VaultIo.write(v, DeckConfig.PATH, withSources(v.read(DeckConfig.PATH), list))
    }

    /** Proton shows share links as webcal:// sometimes; fetch them over https. */
    fun fetchUrl(url: String) = url.trim().replaceFirst(Regex("^webcals?://", RegexOption.IGNORE_CASE), "https://")
}
