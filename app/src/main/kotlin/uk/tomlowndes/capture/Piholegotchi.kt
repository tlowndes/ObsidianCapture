package uk.tomlowndes.capture

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.time.LocalDate
import kotlin.concurrent.thread

/**
 * Sends the day to Piholegotchi, the e-ink screen at home: the Deck summary whenever Deck refreshes, and the Focus
 * session whenever it starts, pauses or ends. Best effort, in the background, and silent when the Pi isn't there
 * (away from home, or switched off).
 *
 * The Pi only speaks plain HTTP on the home network, so this writes the request on a socket itself rather than
 * opening the app up to cleartext traffic everywhere.
 */
internal object Piholegotchi {
    private const val TIMEOUT_MS = 3000

    fun sendDeck(ctx: Context, d: DeckData) {
        val prefs = AppPrefs(ctx)
        val address = prefs.piholegotchiAddress.ifBlank { return }
        val energy = prefs.energy
        val body = PiDeck.deck(
            d.tasks, prefs.laterToday, if (energy == Energy.LOW) TaskViews.SHORT_MIN else null, LocalDate.now(),
            d.focusMin, d.habits, d.jobs, energy.name
        )
        thread(name = "piholegotchi") { runCatching { post(address, "/api/deck", "{\"deck\":$body}") } }
    }

    fun sendFocus(ctx: Context) {
        val address = AppPrefs(ctx).piholegotchiAddress.ifBlank { return }
        val s = Focus.state(ctx)
        val body = PiDeck.focus(s?.task, s?.running == true, s?.endAt, s?.remaining() ?: 0, s?.totalMs ?: 0)
        thread(name = "piholegotchi") { runCatching { post(address, "/api/deck", body) } }
    }

    /** For Settings: "Connected" or why not. Call off the main thread. */
    fun test(address: String): String = runCatching {
        val code = post(address, "/api/deck", "{}")
        if (code in 200..299) "Connected" else "The Pi answered $code. Update it from its settings page."
    }.getOrElse { "Can't reach it. Use the IP address shown on the Pi's screen, on home Wi-Fi." }

    /** POSTs JSON and returns the status code. */
    private fun post(address: String, path: String, json: String): Int {
        val (host, port) = PiDeck.hostPort(address) ?: error("No address")
        val bytes = json.toByteArray(Charsets.UTF_8)
        Socket().use { sock ->
            sock.connect(InetSocketAddress(host, port), TIMEOUT_MS)
            sock.soTimeout = TIMEOUT_MS
            val out = sock.getOutputStream()
            out.write(("POST $path HTTP/1.1\r\nHost: $host\r\nContent-Type: application/json\r\n" +
                "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
            out.write(bytes)
            out.flush()
            val status = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.US_ASCII)).readLine().orEmpty()
            return status.split(" ").getOrNull(1)?.toIntOrNull() ?: error("No answer")
        }
    }
}
