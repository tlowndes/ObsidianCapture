package uk.tomlowndes.capture

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * The GitHub repo a project links to, and what's happening in it. Uses the token the user saved in Settings.
 * Only reads: the last push, open pull requests and the latest release.
 */
object GitHubRepo {
    data class Activity(val lastPush: String?, val openPrs: Int, val release: String?)

    private val LINK = Regex("github\\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+?)(?:\\.git)?(?=[\\s)\\]/]|$)")

    /** The first github.com/owner/repo link in a project note, as (owner, repo). */
    fun repoOf(note: String): Pair<String, String>? =
        LINK.find(note)?.let { it.groupValues[1] to it.groupValues[2] }

    /** Blocking network call; run it off the main thread. Throws if GitHub can't be reached. */
    fun fetch(owner: String, repo: String, token: String): Activity {
        val base = "https://api.github.com/repos/$owner/$repo"
        val info = JSONObject(get(base, token))
        val prs = JSONArray(get("$base/pulls?state=open&per_page=100", token))
        val release = runCatching { JSONObject(get("$base/releases/latest", token)).optString("tag_name") }.getOrNull()
        return Activity(
            lastPush = info.optString("pushed_at").takeIf { it.isNotBlank() }?.take(10),
            openPrs = prs.length(),
            release = release?.takeIf { it.isNotBlank() },
        )
    }

    private fun get(url: String, token: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("Authorization", "Bearer $token")
        c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        c.connectTimeout = 10_000
        c.readTimeout = 10_000
        try {
            if (c.responseCode !in 200..299) error("GitHub returned ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}
