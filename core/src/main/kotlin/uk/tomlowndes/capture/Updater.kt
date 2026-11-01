package uk.tomlowndes.capture

import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * The in-app updater: asks GitHub for the latest v* release of this app and downloads its APK or MSI.
 * The repo is private, so every call uses the read-only token saved in Settings.
 * Blocking network calls; run them off the main thread.
 */
object Updater {
    const val OWNER = "tlowndes"
    const val REPO = "ObsidianCapture"
    const val API = "https://api.github.com"

    /** Check at most this often on launch; Check now ignores it. */
    const val EVERY_MS = 6 * 60 * 60 * 1000L

    data class Asset(val id: Long, val name: String, val size: Long)

    data class Release(val tag: String, val notes: List<String>, val url: String, val assets: List<Asset>) {
        /** "1.8" for tag "v1.8". */
        val version: String get() = tag.removePrefix("v").removePrefix("V")
        fun asset(ext: String): Asset? = assets.firstOrNull { it.name.endsWith(ext, ignoreCase = true) }
    }

    /** The latest full release (GitHub leaves out drafts and pre-releases), or null if it isn't a v* tag. */
    fun latest(token: String, api: String = API): Release? {
        val o = JSONObject(get("${api.trimEnd('/')}/repos/$OWNER/$REPO/releases/latest", token))
        val tag = o.optString("tag_name")
        if (!tag.startsWith("v", ignoreCase = true) || parts(tag).isEmpty()) return null
        val arr = o.optJSONArray("assets")
        val assets = (0 until (arr?.length() ?: 0)).map { i ->
            val a = arr!!.getJSONObject(i)
            Asset(a.getLong("id"), a.getString("name"), a.optLong("size"))
        }
        return Release(tag, highlights(o.optString("body")), o.optString("html_url"), assets)
    }

    /** True when [latest] (e.g. "v1.10") is a higher version than [current] (e.g. "1.9"). */
    fun isNewer(latest: String, current: String): Boolean {
        val a = parts(latest)
        val b = parts(current)
        if (a.isEmpty()) return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun parts(v: String): List<Int> =
        Regex("^[vV]?(\\d+(?:\\.\\d+)*)").find(v.trim())?.groupValues?.get(1)?.split('.')?.map { it.toInt() } ?: emptyList()

    /**
     * Up to [max] short bullet lines from release notes. GitHub's generated notes read
     * "* Title by @someone in https://…/pull/12"; the "by … in …" tail is dropped.
     */
    fun highlights(body: String, max: Int = 3): List<String> =
        body.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("- ") || it.startsWith("* ") }
            .map { it.drop(2).replace(Regex("\\s+by @\\S+(\\s+in\\s+\\S+)?$"), "").trim() }
            .filter { it.isNotEmpty() && !it.startsWith("@") }
            .take(max)
            .toList()

    /**
     * Downloads [asset] to [dest], reporting (bytes so far, total). GitHub answers with a redirect to a signed
     * storage URL, which must be fetched without the token, so redirects are followed by hand.
     */
    fun download(asset: Asset, token: String, dest: File, api: String = API, onProgress: (Long, Long) -> Unit = { _, _ -> }) {
        var c = URL("${api.trimEnd('/')}/repos/$OWNER/$REPO/releases/assets/${asset.id}").openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false
        c.setRequestProperty("Accept", "application/octet-stream")
        c.setRequestProperty("Authorization", "Bearer $token")
        c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        if (c.responseCode in 300..399) {
            val to = c.getHeaderField("Location") ?: error("GitHub gave no download link")
            c.disconnect()
            c = URL(URL(c.url, to).toString()).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
        }
        try {
            if (c.responseCode !in 200..299) error("Download failed (${c.responseCode})")
            val total = c.contentLengthLong.takeIf { it > 0 } ?: asset.size
            dest.parentFile?.mkdirs()
            val tmp = File(dest.path + ".part")
            c.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastReport = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - lastReport >= 256 * 1024 || done == total) { onProgress(done, total); lastReport = done }
                    }
                    onProgress(done, total)
                }
            }
            if (asset.size > 0 && tmp.length() != asset.size) { tmp.delete(); error("Download was cut short") }
            dest.delete()
            if (!tmp.renameTo(dest)) error("Couldn't save the download")
        } finally {
            c.disconnect()
        }
    }

    /** "9.4 MB" */
    fun size(bytes: Long): String = if (bytes < 1_000_000) "${(bytes / 1000).coerceAtLeast(1)} KB" else String.format(java.util.Locale.UK, "%.1f MB", bytes / 1_000_000.0)

    private fun get(url: String, token: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("Authorization", "Bearer $token")
        c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        c.connectTimeout = 10_000
        c.readTimeout = 10_000
        try {
            when (c.responseCode) {
                in 200..299 -> return c.inputStream.bufferedReader().use { it.readText() }
                401 -> error("GitHub didn't accept the token")
                404 -> error("No release found, or the token can't see the repo")
                else -> error("GitHub returned ${c.responseCode}")
            }
        } finally {
            c.disconnect()
        }
    }
}
