package uk.tomlowndes.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime

/** Weather for the card: the phone's rough location, Open-Meteo, cached for an hour. */
object WeatherFetch {
    private const val FRESH_MS = 60 * 60_000L
    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences("weather", Context.MODE_PRIVATE)

    fun allowed(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Last known rough location from any provider, remembered so the card works when there's no fix. */
    private fun location(ctx: Context): Pair<Double, Double>? {
        if (allowed(ctx)) {
            val lm = ctx.getSystemService(LocationManager::class.java)
            val best = runCatching {
                lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
            }.getOrNull()
            if (best != null) {
                p(ctx).edit().putString("lat", best.latitude.toString()).putString("lon", best.longitude.toString()).apply()
                return best.latitude to best.longitude
            }
        }
        val lat = p(ctx).getString("lat", null)?.toDoubleOrNull() ?: return null
        val lon = p(ctx).getString("lon", null)?.toDoubleOrNull() ?: return null
        return lat to lon
    }

    /** Today's summary, from the cache if it's under an hour old. Call off the main thread. */
    fun today(ctx: Context, force: Boolean = false): WeatherSummary? {
        val now = LocalDateTime.now()
        val cachedJson = p(ctx).getString("json", null)
        val cachedAt = p(ctx).getLong("at", 0)
        val sameDay = cachedJson != null && java.time.Instant.ofEpochMilli(cachedAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate() == now.toLocalDate()
        if (!force && sameDay && System.currentTimeMillis() - cachedAt < FRESH_MS) {
            return runCatching { Weather.summary(cachedJson!!, now) }.getOrNull()
        }
        val (lat, lon) = location(ctx) ?: return null
        val fresh = runCatching {
            val c = URL(Weather.url(lat, lon)).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000; c.readTimeout = 15_000
            try { c.inputStream.bufferedReader().readText() } finally { c.disconnect() }
        }.getOrNull()
        if (fresh != null) p(ctx).edit().putString("json", fresh).putLong("at", System.currentTimeMillis()).apply()
        val json = fresh ?: cachedJson?.takeIf { sameDay } ?: return null
        return runCatching { Weather.summary(json, now) }.getOrNull()
    }
}
