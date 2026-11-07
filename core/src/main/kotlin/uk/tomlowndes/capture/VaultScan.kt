package uk.tomlowndes.capture

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Finds open tasks across the whole vault. Task lines are cached per note by modified time, in memory and in
 * [cacheFile], so only notes changed since the last look are read again.
 */
object VaultScan {
    private const val VERSION = 2
    private class Cached(val modified: Long, val lines: List<String>)
    private var cache: MutableMap<String, Cached>? = null

    /** The vault's templates folders (core Templates and Templater), which hold placeholder tasks. */
    fun templateFolders(v: Vault): List<String> {
        fun json(path: String): JSONObject? = v.read(path)?.let { runCatching { JSONObject(it) }.getOrNull() }
        return listOfNotNull(
            json(".obsidian/templates.json")?.optString("folder"),
            json(".obsidian/plugins/templater-obsidian/data.json")?.optString("templates_folder")
        ).map { it.trim().trim('/') }.filter { it.isNotEmpty() }
    }

    /** Open `#task` tasks from every scanned note except Quick Task, reading only notes that changed. */
    fun tasks(v: Vault, cacheFile: File?): List<TaskItem> = scan(v, cacheFile).first

    /** Open tasks and ticked `#task` tasks from every scanned note except Quick Task, in one pass. */
    @Synchronized
    fun scan(v: Vault, cacheFile: File?): Pair<List<TaskItem>, List<TickedTask>> {
        val templates = templateFolders(v)
        val notes = v.notes { VaultTasks.skipped(it, templates) }
            .filter { it.path != VaultTasks.QUICK_TASK && !VaultTasks.skipped(it.path, templates) }
        val known = cache ?: load(cacheFile)
        val fresh = mutableMapOf<String, Cached>()
        val out = mutableListOf<TaskItem>()
        val done = mutableListOf<TickedTask>()
        for (n in notes) {
            val hit = known[n.path]?.takeIf { it.modified == n.modified && n.modified != 0L }
            val entry = hit ?: runCatching {
                val lines = VaultIo.read(v, n.path).split("\n")
                Cached(n.modified, VaultTasks.taskLines(lines))
            }.getOrNull() ?: continue
            fresh[n.path] = entry
            entry.lines.mapNotNullTo(out) { TaskNote.parse(it)?.copy(path = n.path) }
            entry.lines.mapNotNullTo(done) { TaskNote.parseDone(it)?.copy(path = n.path) }
        }
        cache = fresh
        save(cacheFile, fresh)
        return out to done
    }

    private fun load(f: File?): MutableMap<String, Cached> = runCatching {
        val top = JSONObject(f!!.readText())
        // v2 also keeps ticked lines; an older cache is dropped and the vault read again once.
        if (top.optInt("v") != VERSION) return mutableMapOf()
        val o = top.getJSONObject("notes")
        o.keys().asSequence().associateWith { k ->
            val e = o.getJSONObject(k)
            val ls = e.getJSONArray("t")
            Cached(e.getLong("m"), List(ls.length()) { ls.getString(it) })
        }.toMutableMap()
    }.getOrElse { mutableMapOf() }

    private fun save(f: File?, map: Map<String, Cached>) {
        if (f == null) return
        runCatching {
            val o = JSONObject()
            map.forEach { (k, v) -> o.put(k, JSONObject().put("m", v.modified).put("t", JSONArray(v.lines))) }
            f.parentFile?.mkdirs()
            f.writeText(JSONObject().put("v", VERSION).put("notes", o).toString())
        }
    }
}
