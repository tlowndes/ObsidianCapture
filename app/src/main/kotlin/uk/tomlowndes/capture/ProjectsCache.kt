package uk.tomlowndes.capture

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** The last Projects list the phone read, so the tab shows straight away and then refreshes from the vault. */
internal object ProjectsCache {
    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences("projects_cache", Context.MODE_PRIVATE)

    fun save(ctx: Context, list: List<IdeaSummary>) = runCatching {
        val a = JSONArray(list.map { s ->
            JSONObject().put("title", s.title).put("snippet", s.snippet).put("entries", s.entries).put("images", s.images)
                .put("firstImage", s.firstImage ?: "").put("status", s.status).put("modified", s.modified)
                .put("type", s.type ?: "").put("area", s.area ?: "").put("next", s.next ?: "").put("couldContinue", s.couldContinue)
        })
        p(ctx).edit().putString("list", a.toString()).apply()
    }

    fun load(ctx: Context): List<IdeaSummary>? = runCatching {
        val a = JSONArray(p(ctx).getString("list", null) ?: return null)
        (0 until a.length()).map { i ->
            val s = a.getJSONObject(i)
            IdeaSummary(
                s.getString("title"), s.getString("snippet"), s.getInt("entries"), s.getInt("images"),
                s.optString("firstImage").ifEmpty { null }, s.getString("status"), s.getLong("modified"),
                s.optString("type").ifEmpty { null }, s.optString("area").ifEmpty { null }, s.optString("next").ifEmpty { null },
                s.optBoolean("couldContinue")
            )
        }
    }.getOrNull()
}
