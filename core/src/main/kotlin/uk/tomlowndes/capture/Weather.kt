package uk.tomlowndes.capture

import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** One tile of the day strip: "MORNING 🌧️ 9° 100%". */
data class DayPart(val name: String, val icon: String, val temp: Int, val rain: Int)

/**
 * The Weather & travel card: "15° / 7° · Wet morning, mild afternoon", the rain story, a practical tip
 * ("Leave the washing in. Best walk: 15:00–16:15.") and the Morning / Afternoon / Evening strip.
 */
data class WeatherSummary(
    val high: Int, val low: Int, val headline: String, val detail: String,
    val tip: String? = null, val parts: List<DayPart> = emptyList(),
    /** True after today's sunset, when the card shows tomorrow instead. */
    val tomorrow: Boolean = false,
    /** "Tonight: 6°, drizzle easing by 21:00." Only set when [tomorrow] is. */
    val tonight: String? = null
)

/** Today's forecast from Open-Meteo (free, no key), written up from the hourly data like the morning email. */
object Weather {
    /** Days of forecast fetched: today plus six, so the Daily Slip can print up to a week ahead. */
    const val DAYS = 7

    fun url(lat: Double, lon: Double) =
        "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f".format(java.util.Locale.ROOT, lat, lon) +
            "&hourly=temperature_2m,precipitation_probability,weather_code,cloud_cover" +
            "&daily=temperature_2m_max,temperature_2m_min,sunset,weather_code&timezone=auto&forecast_days=$DAYS"

    /** True when a cached forecast has tomorrow in it. Older versions cached today only, which left the card on today after sunset. */
    fun hasTomorrow(json: String?): Boolean =
        json != null && runCatching { JSONObject(json).getJSONObject("daily").getJSONArray("sunset").length() > 1 }.getOrDefault(false)

    /** True when a cached forecast covers [n] days. Caches from before v1.27 only had two. */
    fun hasDays(json: String?, n: Int = DAYS): Boolean =
        json != null && runCatching { JSONObject(json).getJSONObject("daily").getJSONArray("sunset").length() >= n }.getOrDefault(false)

    /**
     * The write-up for [date], as if the day hadn't started, for a Daily Slip printed ahead. Null when the forecast
     * doesn't reach that far. For today it's the same as [summary].
     */
    fun forDay(json: String, date: LocalDate, now: LocalDateTime): WeatherSummary? {
        if (date == now.toLocalDate()) return summary(json, now)
        val o = JSONObject(json)
        val sunsets = o.getJSONObject("daily").getJSONArray("sunset")
        val d = (0 until sunsets.length()).firstOrNull { LocalDateTime.parse(sunsets.getString(it)).toLocalDate() == date } ?: return null
        return write(o, d, date.atStartOfDay(), null).copy(tomorrow = false)
    }

    private data class Hour(val at: LocalDateTime, val rain: Int, val cloud: Int, val code: Int, val temp: Double?)

    private val HM = DateTimeFormatter.ofPattern("HH:mm")

    /** Today's write-up; after today's sunset, tomorrow's (when the forecast has it) plus a line for tonight. */
    fun summary(json: String, now: LocalDateTime): WeatherSummary {
        val o = JSONObject(json)
        val daily = o.getJSONObject("daily")
        val todaySunset = LocalDateTime.parse(daily.getJSONArray("sunset").getString(0))
        val next = !now.isBefore(todaySunset) && daily.getJSONArray("sunset").length() > 1
        return write(o, if (next) 1 else 0, now, if (next) now else null)
    }

    /**
     * Writes up forecast day [d]. When [tonightFrom] is set, it's tomorrow seen from that evening: written as if the
     * day hadn't started, plus a line for tonight. Otherwise [now] is where "ahead" starts.
     */
    private fun write(o: JSONObject, d: Int, now: LocalDateTime, tonightFrom: LocalDateTime?): WeatherSummary {
        val next = tonightFrom != null || d > 0
        val daily = o.getJSONObject("daily")
        val hi = Math.round(daily.getJSONArray("temperature_2m_max").getDouble(d)).toInt()
        val lo = Math.round(daily.getJSONArray("temperature_2m_min").getDouble(d)).toInt()
        val sunset = LocalDateTime.parse(daily.getJSONArray("sunset").getString(d))
        val dayCode = daily.getJSONArray("weather_code").optInt(d, 3)

        val h = o.getJSONObject("hourly")
        val times = h.getJSONArray("time")
        val temps = h.optJSONArray("temperature_2m")
        val hours = (0 until times.length()).map { i ->
            Hour(
                LocalDateTime.parse(times.getString(i)), h.getJSONArray("precipitation_probability").optInt(i, 0),
                h.getJSONArray("cloud_cover").optInt(i, 100), h.getJSONArray("weather_code").optInt(i, 3),
                temps?.optDouble(i)?.takeIf { !it.isNaN() }
            )
        }
        val day = sunset.toLocalDate()
        val tonight = tonightFrom?.let { t -> tonightLine(hours.filter { it.at.toLocalDate() == t.toLocalDate() && it.at.hour >= t.hour }) }
        // For tomorrow, write it up as if the day hadn't started yet.
        val from = if (next) day.atStartOfDay() else now
        val dayHours = hours.filter { it.at.toLocalDate() == day }
        val daylight = dayHours.filter { it.at.toLocalTime() >= LocalTime.of(7, 0) && it.at.isBefore(sunset) }
        val ahead = daylight.filter { !it.at.isBefore(from.withMinute(0).withSecond(0).withNano(0)) }

        val parts = listOf("MORNING" to 7..11, "AFTERNOON" to 12..16, "EVENING" to 17..21).mapNotNull { (name, range) ->
            val hs = dayHours.filter { it.at.hour in range }
            if (hs.isEmpty()) null else part(name, hs, sunset)
        }

        val sentences = mutableListOf<String>()
        val story = rainStory(ahead, from)
        val sun = sunLine(daylight, dayCode)
        listOfNotNull(story, sun).joinToString(", ").takeIf { it.isNotEmpty() }?.let { sentences += it.replaceFirstChar { c -> c.uppercase() } + "." }
        sentences += "Sunset ${sunset.format(HM)}."

        return WeatherSummary(
            hi, lo, headline(daylight, ahead, dayCode, from), sentences.joinToString(" "), tip(ahead, from, sunset), parts,
            tomorrow = next, tonight = tonight
        )
    }

    /** "Tonight: 6°, drizzle easing by 21:00." from the hours left before midnight. */
    private fun tonightLine(hs: List<Hour>): String? {
        if (hs.isEmpty()) return null
        val low = hs.mapNotNull { it.temp }.minOrNull()?.let { "${Math.round(it)}°, " } ?: ""
        val peak = hs.maxOf { it.rain }
        val wetWord = if (hs.any { it.code in 51..57 } && hs.none { it.code in 61..67 || it.code in 80..82 }) "drizzle" else "rain"
        val lastWet = hs.indexOfLast { it.rain >= 30 }
        val rest = when {
            peak < 20 -> "dry"
            lastWet in 0 until hs.size - 1 && hs[0].rain >= 30 -> "$wetWord easing by ${hs[lastWet].at.plusHours(1).format(HM)}"
            peak >= 50 -> "$wetWord likely ($peak%)"
            else -> "chance of showers ($peak%)"
        }
        return "Tonight: $low$rest."
    }

    private fun headline(daylight: List<Hour>, ahead: List<Hour>, dayCode: Int, now: LocalDateTime): String {
        val am = daylight.filter { it.at.hour < 12 }
        val pm = daylight.filter { it.at.hour >= 12 }
        if (now.hour < 12 && am.isNotEmpty() && pm.isNotEmpty()) {
            val a = periodWord(am)
            val p = periodWord(pm)
            if (a != p) {
                val wetA = a == "wet" || a == "showery"
                val wetP = p == "wet" || p == "showery"
                val pWord = if (wetA && !wetP) tempWord(pm) ?: "drier" else p
                val aWord = if (wetP && !wetA) "dry" else a
                return "${aWord.replaceFirstChar { it.uppercase() }} morning, $pWord afternoon"
            }
        }
        val sky = when (dayCode) {
            0 -> "Clear"
            1 -> "Mostly sunny"
            2 -> "Bright with some cloud"
            3 -> "Grey"
            45, 48 -> "Foggy"
            in 51..57 -> "Drizzly"
            in 61..67, in 80..82 -> "Wet"
            in 71..77, 85, 86 -> "Snowy"
            in 95..99 -> "Stormy"
            else -> "Mixed"
        }
        val maxRain = (ahead.ifEmpty { daylight }).maxOfOrNull { it.rain } ?: 0
        val wetWord = dayCode in 51..67 || dayCode in 80..82 || dayCode >= 95
        return when {
            wetWord -> sky
            maxRain < 20 -> "$sky and mostly dry"
            maxRain < 50 -> "$sky, chance of showers"
            else -> "$sky with rain likely"
        }
    }

    private fun periodWord(hs: List<Hour>): String {
        val rain = hs.map { it.rain }.average()
        val cloud = hs.map { it.cloud }.average()
        return when {
            rain >= 50 -> "wet"
            rain >= 25 -> "showery"
            cloud < 30 -> "sunny"
            cloud < 70 -> "bright"
            else -> "grey"
        }
    }

    private fun tempWord(hs: List<Hour>): String? {
        val t = hs.mapNotNull { it.temp }.maxOrNull() ?: return null
        return when {
            t >= 20 -> "warm"
            t >= 12 -> "mild"
            t <= 5 -> "cold"
            else -> null
        }
    }

    /** "rain from 08:00, near-certain 09:00–12:00 (100%), easing after 13:00 and mostly dry from 15:00" */
    private fun rainStory(ahead: List<Hour>, now: LocalDateTime): String? {
        val firstWet = ahead.indexOfFirst { it.rain >= 30 }
        if (firstWet < 0) {
            val peak = ahead.maxByOrNull { it.rain } ?: return null
            return if (peak.rain > 0) "a whiff of drizzle about ${peak.at.format(HM)} (${peak.rain}% at most)" else "dry all day"
        }
        var end = firstWet
        while (end + 1 < ahead.size && ahead[end + 1].rain >= 30) end++
        val run = ahead.subList(firstWet, end + 1)
        val start = run.first().at
        val bits = mutableListOf(if (start.hour <= now.hour) "rain now" else "rain from ${start.format(HM)}")
        val sure = run.filter { it.rain >= 80 }
        val peak = run.maxOf { it.rain }
        if (sure.size >= 2) bits += "near-certain ${sure.first().at.format(HM)}–${sure.last().at.plusHours(1).format(HM)} ($peak%)"
        else bits += "up to $peak%"
        if (end + 1 >= ahead.size) return bits.joinToString(", ") + " and on and off until dark"
        val after = ahead.subList(end + 1, ahead.size)
        val easing = after.first().at
        val dryFrom = after.indices.firstOrNull { i -> after.subList(i, after.size).all { it.rain < 20 } }?.let { after[it].at }
        var tail = "easing after ${easing.format(HM)}"
        if (dryFrom != null && dryFrom.isAfter(easing)) tail += " and mostly dry from ${dryFrom.format(HM)}"
        else if (dryFrom == null) after.firstOrNull { it.rain >= 30 }?.let { tail += ", more showers from ${it.at.format(HM)} (${it.rain}%)" }
        return bits.joinToString(", ") + ", " + tail
    }

    private fun sunLine(daylight: List<Hour>, dayCode: Int): String? {
        val sunny = daylight.filter { it.cloud < 50 && it.rain < 30 }
        if (sunny.isEmpty() || dayCode < 2) return null
        return if (sunny.size <= 2) "brief sun around ${partOfDay(sunny.map { it.at.hour }.average().toInt())}"
        else "sunny spells in ${partOfDay(sunny.map { it.at.hour }.average().toInt())}"
    }

    /** "Leave the washing in. Best walk: 15:00–16:15." */
    private fun tip(ahead: List<Hour>, now: LocalDateTime, sunset: LocalDateTime): String? {
        val bits = mutableListOf<String>()
        val maxRain = ahead.maxOfOrNull { it.rain } ?: return null
        if (maxRain >= 50) bits += "Leave the washing in."
        else if (maxRain < 20 && ahead.map { it.cloud }.average() < 70) bits += "Good drying day."

        // The longest dry stretch before dusk.
        val dusk = sunset.minusMinutes(30)
        var best: Pair<LocalDateTime, LocalDateTime>? = null
        var i = 0
        while (i < ahead.size) {
            if (ahead[i].rain >= 20) { i++; continue }
            var j = i
            while (j + 1 < ahead.size && ahead[j + 1].rain < 20) j++
            val from = maxOf(ahead[i].at, now)
            val to = minOf(ahead[j].at.plusHours(1), dusk)
            if (java.time.Duration.between(from, to).toMinutes() >= 45 &&
                (best == null || java.time.Duration.between(from, to) > java.time.Duration.between(best.first, best.second))) best = from to to
            i = j + 1
        }
        best?.let { (from, to) ->
            bits += if (maxRain < 20) "Walk any time before ${floor15(to).format(HM)}." else "Best walk: ${round15(from).format(HM)}–${floor15(to).format(HM)}."
        }
        return bits.joinToString(" ").ifEmpty { null }
    }

    private fun round15(t: LocalDateTime): LocalDateTime {
        val m = t.minute
        val up = ((m + 14) / 15) * 15
        return t.withSecond(0).withNano(0).withMinute(0).plusMinutes(up.toLong())
    }

    private fun floor15(t: LocalDateTime) = t.withSecond(0).withNano(0).withMinute(t.minute / 15 * 15)

    private fun part(name: String, hs: List<Hour>, sunset: LocalDateTime): DayPart {
        val rain = hs.maxOf { it.rain }
        val code = hs.maxOf { it.code }
        val cloud = hs.map { it.cloud }.average()
        val dark = hs.all { !it.at.isBefore(sunset) }
        val icon = when {
            code >= 95 -> "⛈️"
            code in 71..77 || code in 85..86 -> "🌨️"
            rain >= 50 || code in 61..67 || code in 80..82 -> "🌧️"
            rain >= 30 || code in 51..57 -> "🌦️"
            code == 45 || code == 48 -> "🌫️"
            cloud < 30 -> if (dark) "🌙" else "☀️"
            cloud < 70 -> if (dark) "☁️" else "⛅"
            else -> "☁️"
        }
        val temps = hs.mapNotNull { it.temp }
        val temp = if (temps.isEmpty()) 0 else Math.round(temps.average()).toInt()
        return DayPart(name.lowercase().replaceFirstChar { it.uppercase() }, icon, temp, rain)
    }

    private fun partOfDay(hour: Int) = when {
        hour < 11 -> "the morning"
        hour < 14 -> "midday"
        hour < 17 -> "the afternoon"
        else -> "the evening"
    }
}
