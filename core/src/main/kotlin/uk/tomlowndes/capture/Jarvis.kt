package uk.tomlowndes.capture

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.UUID

/**
 * Talks to the Jarvis voice server on the Oracle box (reached over Tailscale only). The server hands each
 * request to the Claude CLI and sends back what it heard, the reply, and the reply as audio.
 *
 *  POST {base}/voice   multipart: file (OGG/Opus recording), worker; header Authorization: Bearer <token>
 *                      → {"transcript": "...", "reply": "...", "audio_url": "/audio/<id>.ogg", "done": [...]?}
 *  GET  {base}/audio/<id>.ogg  same token
 *  GET  {base}/done    optional: background jobs that finished, [{"id","title","detail"}]. 404 means "not offered".
 *  GET  {base}/voices  optional: [{"id","label","detail","sample_url"}] (or plain ids). The picked id goes back as
 *                      the multipart field "voice"; no field means the server's default.
 *
 * The address "demo" never touches the network: canned replies, so the tab can be tried before the server is up.
 * Blocking calls; run them off the main thread. Shared by the Android and desktop apps.
 */
object Jarvis {
    data class Worker(val id: String, val label: String, val icon: String, val blurb: String)

    val WORKERS = listOf(
        Worker("jarvis", "Jarvis", "🤖", "General help, passes it on if needed"),
        Worker("coding", "Coding", "⌨️", "Repo work, PRs, builds"),
        Worker("obsidian", "Obsidian", "🪨", "Tasks, notes and the vault"),
        Worker("research", "Research", "🔎", "Looks things up, reports back later"),
        Worker("odd-jobs", "Odd jobs", "🧰", "Everything else"),
    )

    fun worker(id: String?): Worker = WORKERS.firstOrNull { it.id == id } ?: WORKERS[0]

    /** A background job the server says has finished. */
    data class Done(val id: String, val title: String, val detail: String)

    /** A voice the server can speak in. [sampleUrl] plays a short sample, when the server offers one. */
    data class Voice(val id: String, val label: String, val detail: String, val sampleUrl: String?)

    data class Reply(val transcript: String, val reply: String, val audioUrl: String?, val done: List<Done> = emptyList())

    /** The server answered, but not with 2xx. 401/403 mean the token is wrong. */
    class HttpError(val code: Int, body: String) : Exception("Jarvis returned $code" + if (body.isBlank()) "" else ": ${body.take(200)}")

    const val DEMO = "demo"

    fun isDemo(address: String) = address.trim().equals(DEMO, ignoreCase = true)

    /**
     * The server origin from what was typed in Settings: adds https:// when there's no scheme and drops a
     * trailing slash or /voice, so "box.ts.net:8443/voice" and "https://box.ts.net:8443/" both work.
     */
    fun base(address: String): String {
        var a = address.trim().trimEnd('/')
        if (!a.contains("://")) a = "https://$a"
        if (a.endsWith("/voice")) a = a.removeSuffix("/voice")
        return a.trimEnd('/')
    }

    /** [url] from the server ("/audio/x.ogg", or a full URL) against [base]. */
    fun resolve(base: String, url: String): String =
        if (url.startsWith("http://") || url.startsWith("https://")) url else URI("${base(base)}/").resolve(url.trimStart('/')).toString()

    fun parseReply(json: String): Reply {
        val o = JSONObject(json)
        return Reply(
            transcript = o.optString("transcript"),
            reply = o.optString("reply"),
            audioUrl = o.optString("audio_url").takeIf { it.isNotBlank() && it != "null" },
            done = o.optJSONArray("done")?.let(::parseDone).orEmpty(),
        )
    }

    fun parseDone(a: JSONArray): List<Done> = (0 until a.length()).mapNotNull { i ->
        val o = a.optJSONObject(i) ?: return@mapNotNull null
        val title = o.optString("title").ifBlank { return@mapNotNull null }
        Done(o.optString("id").ifBlank { title }, title, o.optString("detail"))
    }

    /** Sends one recording. Throws [HttpError] for a non-2xx answer, or an IOException when the box can't be reached. */
    fun send(address: String, token: String, audio: File, worker: String, voice: String? = null): Reply {
        val boundary = "----jarvis" + UUID.randomUUID().toString().replace("-", "")
        val c = open("${base(address)}/voice", token, "POST", timeout = 120_000)
        c.doOutput = true
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        c.setRequestProperty("Accept", "application/json")
        try {
            c.outputStream.use { out ->
                fun line(s: String) = out.write((s + "\r\n").toByteArray())
                line("--$boundary")
                line("Content-Disposition: form-data; name=\"worker\"")
                line("")
                line(worker)
                if (!voice.isNullOrBlank()) {
                    line("--$boundary")
                    line("Content-Disposition: form-data; name=\"voice\"")
                    line("")
                    line(voice)
                }
                line("--$boundary")
                line("Content-Disposition: form-data; name=\"file\"; filename=\"${audio.name}\"")
                line("Content-Type: audio/ogg")
                line("")
                audio.inputStream().use { it.copyTo(out) }
                line("")
                line("--$boundary--")
            }
            return parseReply(body(c))
        } finally {
            c.disconnect()
        }
    }

    /** Downloads the reply audio to [out]. */
    fun fetchAudio(address: String, token: String, audioUrl: String, out: File) {
        val c = open(resolve(address, audioUrl), token, "GET", timeout = 60_000)
        try {
            if (c.responseCode !in 200..299) throw HttpError(c.responseCode, err(c))
            c.inputStream.use { i -> out.outputStream().use { i.copyTo(it) } }
        } finally {
            c.disconnect()
        }
    }

    /** Finished background jobs, or empty when the server doesn't offer /done. */
    fun done(address: String, token: String): List<Done> {
        val c = open("${base(address)}/done", token, "GET", timeout = 10_000)
        try {
            if (c.responseCode == 404) return emptyList()
            return parseDone(JSONArray(body(c)))
        } finally {
            c.disconnect()
        }
    }

    /** The server's voices, or empty when it doesn't offer /voices. */
    fun voices(address: String, token: String): List<Voice> {
        if (isDemo(address)) return DEMO_VOICES
        val c = open("${base(address)}/voices", token, "GET", timeout = 10_000)
        try {
            if (c.responseCode == 404) return emptyList()
            return parseVoices(JSONArray(body(c)))
        } finally {
            c.disconnect()
        }
    }

    fun parseVoices(a: JSONArray): List<Voice> = (0 until a.length()).mapNotNull { i ->
        when (val v = a.opt(i)) {
            is String -> v.takeIf { it.isNotBlank() }?.let { Voice(it, it, "", null) }
            is JSONObject -> v.optString("id").ifBlank { null }?.let { id ->
                Voice(id, v.optString("label").ifBlank { id }, v.optString("detail"),
                    v.optString("sample_url").takeIf { it.isNotBlank() && it != "null" })
            }
            else -> null
        }
    }

    private val DEMO_VOICES = listOf(
        Voice("en_GB-alan-medium", "Alan", "UK, male (demo)", null),
        Voice("en_GB-jenny_dioco-medium", "Jenny", "UK, female (demo)", null),
    )

    /** Playback speeds offered in Settings and on the reply's speed chip. */
    val SPEEDS = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)

    fun speedLabel(s: Float): String = (if (s % 1f == 0f) s.toInt().toString() else s.toString().trimEnd('0')) + "×"

    /** The next speed for the chip, wrapping round. */
    fun nextSpeed(s: Float): Float = SPEEDS.getOrNull(SPEEDS.indexOfFirst { it > s + 0.001f }) ?: SPEEDS[0]

    data class Ping(val ok: Boolean, val ms: Long, val message: String)

    /**
     * Settings › Test connection. Any answer from the box counts as reachable; 401/403 mean the token was refused.
     */
    fun ping(address: String, token: String): Ping {
        if (isDemo(address)) return Ping(true, 0, "Demo mode, no server")
        val start = System.nanoTime()
        return try {
            val c = open("${base(address)}/voice", token, "GET", timeout = 10_000)
            val code = try { c.responseCode } finally { c.disconnect() }
            val ms = (System.nanoTime() - start) / 1_000_000
            when (code) {
                401, 403 -> Ping(false, ms, "Token refused ($code)")
                else -> Ping(true, ms, "Connected · $ms ms")
            }
        } catch (e: Exception) {
            Ping(false, 0, "Can't reach it: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Demo mode: a reply that says what it got, so the whole loop can be tried without the server. */
    fun demoReply(worker: String, seconds: Double): Reply {
        val w = worker(worker)
        val secs = String.format(java.util.Locale.UK, "%.1f", seconds)
        return Reply(
            transcript = "(Demo) $secs seconds of audio for ${w.label}.",
            reply = "Demo mode: ${w.label} got your $secs second recording. Put the Oracle address in Settings to talk for real. You'll hear your own recording played back now.",
            audioUrl = null,
        )
    }

    private fun open(url: String, token: String, method: String, timeout: Int): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.setRequestProperty("Authorization", "Bearer $token")
        c.connectTimeout = 10_000
        c.readTimeout = timeout
        return c
    }

    private fun body(c: HttpURLConnection): String {
        val code = c.responseCode
        if (code !in 200..299) throw HttpError(code, err(c))
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    private fun err(c: HttpURLConnection): String =
        runCatching { c.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty()
}
