package uk.tomlowndes.capture

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.LocalDateTime

/** What the phone last fetched for one calendar. [ok] is false when the last try failed (events are then old). */
data class CalendarCache(val id: String, val ok: Boolean, val syncedAt: Long, val events: List<CalEvent>)

/** Fetches the Proton ICS links (read only) and keeps the next 7 days on the phone. */
object CalendarSync {
    const val EVERY_MS = 30 * 60_000L
    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences("calendar", Context.MODE_PRIVATE)

    fun cached(ctx: Context): List<CalendarCache> = runCatching {
        val a = JSONArray(p(ctx).getString("cache", "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val ev = o.getJSONArray("events")
            CalendarCache(o.getString("id"), o.getBoolean("ok"), o.getLong("syncedAt"), (0 until ev.length()).map { j ->
                val e = ev.getJSONObject(j)
                CalEvent(o.getString("id"), e.getString("title"), LocalDateTime.parse(e.getString("start")), LocalDateTime.parse(e.getString("end")), e.getBoolean("allDay"), e.optString("location").ifEmpty { null })
            })
        }
    }.getOrDefault(emptyList())

    private fun store(ctx: Context, list: List<CalendarCache>) {
        val a = JSONArray(list.map { c ->
            JSONObject().put("id", c.id).put("ok", c.ok).put("syncedAt", c.syncedAt).put("events", JSONArray(c.events.map { e ->
                JSONObject().put("title", e.title).put("start", e.start.toString()).put("end", e.end.toString()).put("allDay", e.allDay).put("location", e.location ?: "")
            }))
        })
        p(ctx).edit().putString("cache", a.toString()).apply()
    }

    fun lastSync(ctx: Context) = cached(ctx).filter { it.ok }.maxOfOrNull { it.syncedAt } ?: 0L

    private fun fetch(url: String): String {
        val c = URL(CalendarConfig.fetchUrl(url)).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 20_000
        try {
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            val body = c.inputStream.bufferedReader().readText()
            if (!body.contains("BEGIN:VCALENDAR")) error("Not a calendar")
            return body
        } finally { c.disconnect() }
    }

    /** Fetches every enabled calendar. A failed one keeps its old events, marked not ok. Call off the main thread. */
    fun sync(ctx: Context, vault: Uri): List<CalendarCache> {
        val sources = CalendarConfig.load(SafVault(ctx, vault)).filter { it.enabled }
        val old = cached(ctx).associateBy { it.id }
        val today = LocalDate.now()
        val now = System.currentTimeMillis()
        val out = sources.map { s ->
            runCatching { CalendarCache(s.id, true, now, Ics.events(fetch(s.url), s.id, today, today.plusDays(6))) }
                .getOrElse { old[s.id]?.copy(ok = false) ?: CalendarCache(s.id, false, 0, emptyList()) }
        }
        store(ctx, out)
        return out
    }

    /** Checks a pasted link before it's saved. */
    fun test(url: String): Boolean = runCatching { fetch(url) }.isSuccess
}
