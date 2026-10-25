package uk.tomlowndes.capture

import org.json.JSONObject
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** The Weather & travel card: "11° / 8° · Grey and mostly dry" plus one short line of detail. */
data class WeatherSummary(val high: Int, val low: Int, val headline: String, val detail: String)

/** Today's forecast from Open-Meteo (free, no key), written up from the hourly data like the morning email. */
object Weather {
    fun url(lat: Double, lon: Double) =
        "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f".format(java.util.Locale.ROOT, lat, lon) +
            "&hourly=temperature_2m,precipitation_probability,weather_code,cloud_cover" +
            "&daily=temperature_2m_max,temperature_2m_min,sunset,weather_code&timezone=auto&forecast_days=1"

    private data class Hour(val at: LocalDateTime, val rain: Int, val cloud: Int, val code: Int)

    private val HM = DateTimeFormatter.ofPattern("HH:mm")

    fun summary(json: String, now: LocalDateTime): WeatherSummary {
        val o = JSONObject(json)
        val daily = o.getJSONObject("daily")
        val hi = Math.round(daily.getJSONArray("temperature_2m_max").getDouble(0)).toInt()
        val lo = Math.round(daily.getJSONArray("temperature_2m_min").getDouble(0)).toInt()
        val sunset = LocalDateTime.parse(daily.getJSONArray("sunset").getString(0))
        val dayCode = daily.getJSONArray("weather_code").optInt(0, 3)

        val h = o.getJSONObject("hourly")
        val times = h.getJSONArray("time")
        val hours = (0 until times.length()).map { i ->
            Hour(
                LocalDateTime.parse(times.getString(i)), h.getJSONArray("precipitation_probability").optInt(i, 0),
                h.getJSONArray("cloud_cover").optInt(i, 100), h.getJSONArray("weather_code").optInt(i, 3)
            )
        }
        val daylight = hours.filter { it.at.toLocalTime() >= LocalTime.of(7, 0) && it.at.isBefore(sunset) }
        val maxRain = daylight.maxOfOrNull { it.rain } ?: 0

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
        val wetWord = dayCode in 51..67 || dayCode in 80..82 || dayCode >= 95
        val headline = when {
            wetWord -> sky
            maxRain < 20 -> "$sky and mostly dry"
            maxRain < 50 -> "$sky, chance of showers"
            else -> "$sky with rain likely"
        }

        val parts = mutableListOf<String>()
        val sunny = daylight.filter { it.cloud < 50 }
        if (sunny.isNotEmpty() && dayCode >= 2) {
            val mid = sunny.map { it.at.hour }
            parts += if (sunny.size <= 2) "Brief sun around ${partOfDay(mid.average().toInt())}" else "Sunny spells"
        }
        val firstRain = daylight.firstOrNull { it.rain >= 30 && !it.at.isBefore(now.withMinute(0)) }
        val peak = daylight.maxByOrNull { it.rain }
        when {
            firstRain != null -> parts += "rain likely about ${firstRain.at.format(HM)} (${firstRain.rain}%)"
            peak != null && peak.rain > 0 -> parts += "a whiff of drizzle about ${peak.at.format(HM)} (${peak.rain}% at most)"
        }
        val sentences = mutableListOf<String>()
        if (parts.isNotEmpty()) sentences += parts.joinToString(", ").replaceFirstChar { it.uppercase() } + "."
        sentences += "Sunset ${sunset.format(HM)}."
        val walkBy = firstRain?.at ?: sunset.minusMinutes(30)
        if (now.isBefore(walkBy.minusMinutes(30))) sentences += "If you're going out for a walk, go before ${walkBy.format(HM)}."
        return WeatherSummary(hi, lo, headline, sentences.joinToString(" "))
    }

    private fun partOfDay(hour: Int) = when {
        hour < 11 -> "the morning"
        hour < 14 -> "midday"
        hour < 17 -> "the afternoon"
        else -> "the evening"
    }
}
