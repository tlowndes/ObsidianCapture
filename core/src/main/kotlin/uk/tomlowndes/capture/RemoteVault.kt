package uk.tomlowndes.capture

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * The Jarvis server's copy of the vault (kept fresh on the box by Obsidian headless sync), reached over Tailscale
 * with the Jarvis token. The app uses it in place of this device's folder while that folder's sync is behind
 * (see [ServerFallback]). Images still come from [local], so the image loaders keep working.
 *
 * Server side: GET /vault/status, /vault/list?dir=, /vault/entry?path=, /vault/file?path=, /vault/notes;
 * PUT /vault/file?path=; POST /vault/mkdirs, /vault/create, /vault/move, /vault/delete.
 */
class RemoteVault(address: String, private val token: String, private val local: Vault? = null) : Vault {
    private val base = Jarvis.base(address)

    override fun read(path: String, max: Int): String? {
        val c = open("GET", "/vault/file?path=${q(path)}")
        return try {
            if (c.responseCode == 404) null else body(c).let { if (it.length > max) it.take(max) else it }
        } finally { c.disconnect() }
    }

    override fun writeOnce(path: String, text: String) = put(path, text.toByteArray(Charsets.UTF_8))

    override fun writeBytes(path: String, bytes: ByteArray) = put(path, bytes)

    override fun entry(path: String): Vault.Entry? {
        val c = open("GET", "/vault/entry?path=${q(path)}")
        return try { if (c.responseCode == 404) null else entryOf(JSONObject(body(c))) } finally { c.disconnect() }
    }

    override fun list(dir: String): List<Vault.Entry> {
        val a = JSONArray(get("/vault/list?dir=${q(dir)}"))
        return (0 until a.length()).map { entryOf(a.getJSONObject(it)) }
    }

    override fun mkdirs(dir: String) { post("/vault/mkdirs", JSONObject().put("dir", dir)) }

    override fun create(dir: String, base: String, ext: String): String =
        JSONObject(post("/vault/create", JSONObject().put("dir", dir).put("base", base).put("ext", ext))).getString("path")

    override fun move(path: String, toDir: String): String =
        JSONObject(post("/vault/move", JSONObject().put("path", path).put("toDir", toDir))).getString("path")

    override fun delete(path: String) { post("/vault/delete", JSONObject().put("path", path)) }

    override fun handle(path: String): Any = local?.handle(path) ?: "$base/vault/file?path=${q(path)}"

    /** One call for the whole vault instead of a listing per folder. */
    override fun notes(skip: (String) -> Boolean): List<VaultNote> {
        val a = JSONArray(get("/vault/notes"))
        val skipped = HashMap<String, Boolean>()
        fun inSkipped(path: String): Boolean {
            var dir = path.substringBeforeLast('/', "")
            val chain = mutableListOf<String>()
            while (dir.isNotEmpty()) { chain += dir; dir = dir.substringBeforeLast('/', "") }
            return chain.asReversed().any { d -> skipped.getOrPut(d) { skip(d) } }
        }
        return (0 until a.length()).map { a.getJSONObject(it) }
            .map { VaultNote(it.getString("path"), it.optLong("modified")) }
            .filter { it.path.endsWith(".md") && !it.path.substringAfterLast('/').startsWith(".") && !inSkipped(it.path) }
    }

    /** What the server says about its newest note; see [ServerFallback.judge]. */
    fun status(): ServerFallback.Status {
        val n = JSONObject(get("/vault/status", timeout = 6_000)).getJSONObject("newest")
        return ServerFallback.Status(n.getString("path"), n.getLong("modified"), n.getString("sha1"))
    }

    private fun entryOf(o: JSONObject) =
        Vault.Entry(o.getString("name"), o.optBoolean("isDir"), o.optLong("modified"), o.optLong("size"))

    private fun q(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun open(method: String, pathAndQuery: String, timeout: Int = 20_000): HttpURLConnection {
        val c = URL(base + pathAndQuery).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.setRequestProperty("Authorization", "Bearer $token")
        c.connectTimeout = 6_000
        c.readTimeout = timeout
        return c
    }

    private fun get(pathAndQuery: String, timeout: Int = 20_000): String {
        val c = open("GET", pathAndQuery, timeout)
        return try { body(c) } finally { c.disconnect() }
    }

    private fun post(path: String, json: JSONObject): String = send("POST", path, json.toString().toByteArray(), "application/json")

    private fun put(path: String, bytes: ByteArray) {
        send("PUT", "/vault/file?path=${q(path)}", bytes, "application/octet-stream")
    }

    private fun send(method: String, pathAndQuery: String, bytes: ByteArray, type: String): String {
        val c = open(method, pathAndQuery)
        return try {
            c.doOutput = true
            c.setRequestProperty("Content-Type", type)
            c.setFixedLengthStreamingMode(bytes.size)
            c.outputStream.use { it.write(bytes) }
            body(c)
        } finally { c.disconnect() }
    }

    private fun body(c: HttpURLConnection): String {
        val code = c.responseCode
        if (code !in 200..299) {
            val err = runCatching { c.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty()
            throw Jarvis.HttpError(code, err)
        }
        return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}

/**
 * Decides when this device's vault folder is behind the server's copy. The server names its newest note; if this
 * device's copy of that note differs, isn't newer, and the server's change is at least [Verdict] minutes old
 * (time Obsidian Sync should have had), the app reads and saves through the server until the device catches up.
 */
object ServerFallback {
    data class Status(val path: String, val modified: Long, val sha1: String)

    data class Verdict(val useServer: Boolean, val behindMin: Long, val serverAt: Long, val phoneAt: Long?)

    fun judge(server: Status, localText: String?, localModified: Long?, now: Long, staleMin: Int): Verdict {
        val same = localText != null && sha1(localText.toByteArray(Charsets.UTF_8)) == server.sha1
        val ahead = localModified != null && localModified >= server.modified
        val age = ((now - server.modified) / 60_000).coerceAtLeast(0)
        val use = !same && !ahead && age >= staleMin
        return Verdict(use, if (use) age else 0, server.modified, localModified)
    }

    fun sha1(bytes: ByteArray): String = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
}
