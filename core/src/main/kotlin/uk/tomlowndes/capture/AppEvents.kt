package uk.tomlowndes.capture

import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** An event added in the app (tower-bar-events-v1). No [time] means all day. */
data class AppEvent(
    val date: LocalDate, val title: String, val time: LocalTime? = null, val minutes: Int? = null,
    val place: String? = null, val note: String? = null,
) {
    fun toCal(): CalEvent {
        val start = date.atTime(time ?: LocalTime.MIDNIGHT)
        val end = if (time == null) date.plusDays(1).atStartOfDay() else start.plusMinutes((minutes ?: AppEvents.DEFAULT_MINUTES).toLong())
        return CalEvent(AppEvents.CAL_ID, title, start, end, time == null, place, note)
    }
}

/**
 * Events added from Capture, kept one per line in "99 - Meta/Events.md":
 * `- ○ 2026-11-20 14:30 1h Dentist check-up 📍 Headingley Dental — Bring the form`.
 * They join the Proton calendars (read only) everywhere the calendar shows, as calendar [CAL_ID].
 */
object AppEvents {
    const val PATH = "99 - Meta/Events.md"
    const val CAL_ID = "app"
    const val NAME = "Events"
    const val COLOUR = "#5fd3c8"
    const val DEFAULT_MINUTES = 60
    private val HM = DateTimeFormatter.ofPattern("HH:mm")
    private val LINE = Regex(
        "^- ○ (\\d{4}-\\d{2}-\\d{2})(?: (\\d{1,2}:\\d{2}))?(?: (\\d+h(?:\\d+m)?|\\d+m))? (.+?)(?: 📍 (.+?))?(?: — (.+))?$"
    )

    fun line(e: AppEvent): String =
        "- ○ ${e.date}" + (e.time?.let { " " + it.format(HM) } ?: "") +
            (if (e.time != null) e.minutes?.let { " " + length(it) } ?: "" else "") +
            " " + e.title.trim().replace("\n", " ") +
            (e.place?.trim()?.takeIf { it.isNotEmpty() }?.let { " 📍 $it" } ?: "") +
            (e.note?.trim()?.takeIf { it.isNotEmpty() }?.let { " — " + it.replace("\n", " ") } ?: "")

    /** "1h", "30m", "1h30m": no space, so the line reads back. */
    fun length(m: Int) = when { m < 60 -> "${m}m"; m % 60 == 0 -> "${m / 60}h"; else -> "${m / 60}h${m % 60}m" }

    fun parse(line: String): AppEvent? {
        val m = LINE.matchEntire(line.trim()) ?: return null
        val (d, t, len, title, place, note) = m.destructured
        val date = runCatching { LocalDate.parse(d) }.getOrNull() ?: return null
        val time = t.takeIf { it.isNotEmpty() }?.let { runCatching { LocalTime.parse(it.padStart(5, '0')) }.getOrNull() }
        return AppEvent(date, title.trim(), time, len.takeIf { it.isNotEmpty() }?.let { RapidLog.minutes(it) }, place.ifEmpty { null }, note.ifEmpty { null })
    }

    fun all(text: String): List<AppEvent> = text.lines().mapNotNull(::parse).sortedWith(compareBy({ it.date }, { it.time ?: LocalTime.MIN }))

    fun load(v: Vault): List<AppEvent> = all(VaultIo.read(v, PATH))

    /** Events from [from] for [days] days, as calendar events. */
    fun calendar(v: Vault, from: LocalDate, days: Long = 7): List<CalEvent> =
        load(v).filter { !it.date.isBefore(from) && it.date.isBefore(from.plusDays(days)) }.map { it.toCal() }

    fun add(v: Vault, e: AppEvent) {
        if (v.entry(PATH) == null) {
            v.mkdirs(parentOf(PATH))
            val p = v.create(parentOf(PATH), nameOf(PATH).removeSuffix(".md"), "md")
            VaultIo.write(v, p, "---\ntype: events\n---\n\nEvents added in Flight Deck. One per line; they show with your calendar.\n\n${line(e)}\n")
            return
        }
        VaultIo.edit(v, PATH) { lines ->
            while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.size - 1)
            lines.add(line(e))
        }
    }

    /** Takes off the line for [e] (the first that reads back the same). */
    fun remove(v: Vault, e: AppEvent) = VaultIo.edit(v, PATH) { lines ->
        val i = lines.indexOfFirst { parse(it) == e }
        if (i >= 0) lines.removeAt(i)
    }
}
