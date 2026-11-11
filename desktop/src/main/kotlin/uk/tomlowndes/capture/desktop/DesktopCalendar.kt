package uk.tomlowndes.capture.desktop

import uk.tomlowndes.capture.CalEvent
import uk.tomlowndes.capture.CalendarConfig
import uk.tomlowndes.capture.CalendarSource
import uk.tomlowndes.capture.Ics
import uk.tomlowndes.capture.Vault
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

/**
 * The Proton ICS links the phone set up (in the vault's Deck config), fetched read only for the next 7 days
 * (windows-catchup-v1). Kept in memory for half an hour; a calendar that fails is left out until the next try.
 */
object DesktopCalendar {
    private const val EVERY_MS = 30 * 60_000L
    private var cached: Pair<Long, Pair<List<CalendarSource>, List<CalEvent>>>? = null

    fun fetch(url: String): String {
        val c = URL(CalendarConfig.fetchUrl(url)).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 20_000
        try {
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            val body = c.inputStream.bufferedReader().readText()
            if (!body.contains("BEGIN:VCALENDAR")) error("Not a calendar")
            return body
        } finally { c.disconnect() }
    }

    /** Sources and events for today and the 6 days after. Call off the UI thread. */
    @Synchronized
    fun forget() { cached = null }

    fun load(v: Vault, force: Boolean = false): Pair<List<CalendarSource>, List<CalEvent>> {
        val now = System.currentTimeMillis()
        cached?.let { (at, data) -> if (!force && now - at < EVERY_MS) return data }
        val sources = runCatching { CalendarConfig.load(v) }.getOrDefault(emptyList()).filter { it.enabled }
        val today = LocalDate.now()
        val events = sources.flatMap { s -> runCatching { Ics.events(fetch(s.url), s.id, today, today.plusDays(6)) }.getOrDefault(emptyList()) }
        val data = sources to events
        cached = now to data
        return data
    }
}
