package uk.tomlowndes.capture.desktop

import uk.tomlowndes.capture.FlightNotes
import uk.tomlowndes.capture.Habits
import uk.tomlowndes.capture.Jobs
import uk.tomlowndes.capture.PiDeck
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.time.LocalDate
import kotlin.concurrent.thread

/**
 * Piholegotchi from Windows (windows-settings-gaps-v1): the same Deck summary and Focus session the phone sends to
 * the e-ink screen at home. Best effort and silent when the Pi isn't there. Plain HTTP on a socket, like the phone.
 */
object DesktopPi {
    private const val TIMEOUT_MS = 3000

    /** Sends today's Deck. Call off the UI thread. */
    fun sendDeck(state: AppState) {
        val address = state.prefs.piholegotchiAddress.ifBlank { return }
        val v = state.vault ?: return
        val tasks = runCatching { state.writer()!!.listTasks() }.getOrDefault(emptyList())
        val body = PiDeck.deck(
            tasks, state.prefs.laterToday, null, LocalDate.now(),
            runCatching { FlightNotes.loadToday(v).sumOf { it.minutes } }.getOrDefault(0),
            runCatching { Habits.load(v) }.getOrNull(),
            runCatching { Jobs.list(v) }.getOrDefault(emptyList()),
            "MED",
        )
        runCatching { post(address, "/api/deck", "{\"deck\":$body}") }
    }

    /** Sends the Focus session (or that there isn't one) whenever it starts, pauses or ends. */
    fun sendFocus(state: AppState) {
        val address = state.prefs.piholegotchiAddress.ifBlank { return }
        val f = state.focus
        val total = (f?.minutes ?: 0) * 60_000L
        val body = PiDeck.focus(f?.task?.text, f != null && f.pausedLeft == null, f?.endsAt, f?.left() ?: 0, total)
        thread(isDaemon = true, name = "piholegotchi") { runCatching { post(address, "/api/deck", body) } }
    }

    /** For Settings: "Connected" or why not. Call off the UI thread. */
    fun test(address: String): String = runCatching {
        val code = post(address, "/api/deck", "{}")
        if (code in 200..299) "Connected" else "The Pi answered $code. Update it from its settings page."
    }.getOrElse { "Can't reach it. Use the IP address shown on the Pi's screen, on home Wi-Fi." }

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
