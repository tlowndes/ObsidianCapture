package uk.tomlowndes.capture

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The last task lists the Tasks screen read, kept in the app's private storage (not the vault), so the list shows
 * straight away when Tasks opens and is then refreshed from the vault in the background.
 */
internal object TaskSnapshot {
    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences("task_snapshot", Context.MODE_PRIVATE)

    @Volatile private var memory: TaskLists? = null

    fun save(ctx: Context, lists: TaskLists) {
        memory = lists
        runCatching {
            fun arr(items: List<Pair<String, String>>) = JSONArray(items.map { (raw, path) -> JSONObject().put("raw", raw).put("path", path) })
            val o = JSONObject()
                .put("open", arr(lists.open.map { it.raw to it.path }))
                .put("done", arr(lists.done.map { it.raw to it.path }))
            p(ctx).edit().putString("tasks", o.toString()).apply()
        }
    }

    fun load(ctx: Context): TaskLists? = memory ?: runCatching {
        val o = JSONObject(p(ctx).getString("tasks", null) ?: return null)
        fun <T> each(key: String, f: (String, String) -> T?): List<T> = o.getJSONArray(key).let { a ->
            (0 until a.length()).mapNotNull { i -> a.getJSONObject(i).let { f(it.getString("raw"), it.getString("path")) } }
        }
        TaskLists(
            each("open") { raw, path -> TaskNote.parse(raw)?.copy(path = path) },
            each("done") { raw, path -> TaskNote.parseDone(raw)?.copy(path = path) },
        )
    }.getOrNull()?.also { memory = it }
}
