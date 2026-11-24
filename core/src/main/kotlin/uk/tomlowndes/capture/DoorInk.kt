package uk.tomlowndes.capture

import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Door Ink (hangar-phase4): what the e-ink screen by the front door shows. Before a trip it's the leave-by time,
 * what to grab (ticked or not) and the weather; the rest of the day it's the weather and today's Did I? checks.
 *
 * Flight Deck pushes this to the Pi over home Wi-Fi (`POST /api/door`), the same way it talks to Piholegotchi. The
 * Pi works out the countdown from `leave.by` itself, so the payload only changes, and the screen only redraws,
 * when something real does: a new trip, a tick, a stamp, the weather.
 */
object DoorInk {
    const val PATH = "/api/door"
    /** How far ahead a leave-by turns the screen into the leave view. */
    const val AHEAD_HOURS = 3L
    /** How long after the leave-by it stays up (running late is when you need it most). */
    const val LATE_MINUTES = 30L
    private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
    private val HM = DateTimeFormatter.ofPattern("HH:mm")

    enum class Mode { LEAVE, DAY }

    /** LEAVE when a leave-by is within [AHEAD_HOURS] ahead, or under [LATE_MINUTES] gone. */
    fun mode(trip: Trip?, now: LocalDateTime): Mode {
        val by = trip?.let { LaunchPad.leaveBy(it) } ?: return Mode.DAY
        val mins = Duration.between(now, by).toMinutes()
        return if (mins <= AHEAD_HOURS * 60 && mins >= -LATE_MINUTES) Mode.LEAVE else Mode.DAY
    }

    /**
     * The door screen as JSON. [items] is Launch Pad's list for the trip, [ticked] the names ticked today
     * (Launch Pad or last night's Night Pad), [did] today's Did I? checks.
     */
    fun payload(
        trip: Trip?, items: List<KitItem>, ticked: Set<String>, did: List<DidStatus>, weather: WeatherSummary?,
        now: LocalDateTime = LocalDateTime.now(),
    ): String = wrap(body(trip, items, ticked, did, weather, now), now)

    /** The body from [body] with the version and send time around it. */
    fun wrap(body: String, now: LocalDateTime): String =
        obj("v" to "1", "sent" to str(now.withSecond(0).withNano(0).format(ISO)), "body" to body)

    /** Everything but the send time: what [changed] compares. */
    fun body(trip: Trip?, items: List<KitItem>, ticked: Set<String>, did: List<DidStatus>, weather: WeatherSummary?,
             now: LocalDateTime): String {
        val mode = mode(trip, now)
        val leave = if (mode == Mode.LEAVE && trip != null) LaunchPad.leaveBy(trip)?.let { by ->
            obj(
                "title" to (trip.title?.let(::str) ?: "null"),
                "by" to str(by.format(ISO)),
                "starts" to (trip.start?.let { str(it.format(HM)) } ?: "null"),
                "drive_min" to (trip.driveMinutes?.toString() ?: "null"),
            )
        } else null
        val grab = if (leave == null) emptyList() else items.map { i ->
            obj("e" to str(i.emoji), "name" to str(i.name), "done" to ticked.any { it.equals(i.name, ignoreCase = true) }.toString(),
                "why" to (i.reason?.let(::str) ?: "null"))
        }
        val checks = did.map { d ->
            obj("e" to str(d.check.emoji), "name" to str(d.check.name), "at" to (d.at?.let { str(it.format(HM)) } ?: "null"))
        }
        return obj(
            "mode" to str(mode.name.lowercase(Locale.UK)),
            "leave" to (leave ?: "null"),
            "grab" to grab.joinToString(",", "[", "]"),
            "checks" to checks.joinToString(",", "[", "]"),
            "weather" to (weather?.let { w ->
                obj("high" to w.high.toString(), "low" to w.low.toString(), "rain" to (w.parts.maxOfOrNull { it.rain } ?: 0).toString(),
                    "headline" to str(w.headline), "tomorrow" to w.tomorrow.toString())
            } ?: "null"),
        )
    }

    /** True when [body] differs from what was last sent, so the Pi isn't woken for nothing. */
    fun changed(body: String, last: String?) = body != last

    /** "Leave in 12 min", "Leave now", "8 min late": the same words the Pi draws, for Settings' preview. */
    fun leaveWords(by: LocalDateTime, now: LocalDateTime): String {
        val mins = Duration.between(now.withSecond(0).withNano(0), by).toMinutes()
        return when {
            mins > 60 -> "Leave by ${by.format(HM)}"
            mins > 0 -> "Leave in $mins min"
            mins == 0L -> "Leave now"
            else -> "${-mins} min late"
        }
    }

    private fun obj(vararg kv: Pair<String, String>) = kv.joinToString(",", "{", "}") { (k, v) -> "${str(k)}:$v" }
    private fun str(s: String) = PiDeck.str(s)
}
