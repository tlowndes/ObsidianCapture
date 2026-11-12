package uk.tomlowndes.capture

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Finds open tasks across the whole vault. Task lines are cached per note by modified time, in memory and in
 * [cacheFile], so only notes changed since the last look are read again.
 *
 * Walking every folder is the slow part on Android (one Storage Access Framework query per folder), so with a
 * walk TTL the list of notes is reused for a while. Notes the app writes itself are marked with [touched] and
 * read again straight away; anything Obsidian Sync brings in shows once the walk is older than the TTL, or
 * after [invalidate] (pull to refresh).
 */
object VaultScan {
    private const val VERSION = 2
    private class Cached(val modified: Long, val lines: List<String>)
    private class Walk(val key: String, val at: Long, val notes: List<VaultNote>, val templates: List<String>)
    private var cache: MutableMap<String, Cached>? = null
    private var walk: Walk? = null
    // Its own lock (a concurrent set), so a write never waits behind a scan in progress.
    private val touchedPaths: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** The note at [path] was just written by the app: read it again on the next scan, even inside the TTL. */
    fun touched(path: String) { if (path.endsWith(".md")) touchedPaths.add(path) }

    /** Forget the note list, so the next scan walks the whole vault again. */
    @Synchronized
    fun invalidate() { walk = null }

    /** For tests: start from nothing. */
    @Synchronized
    internal fun reset() { cache = null; walk = null; touchedPaths.clear() }

    /** The vault's templates folders (core Templates and Templater), which hold placeholder tasks. */
    fun templateFolders(v: Vault): List<String> {
        fun json(path: String): JSONObject? = v.read(path)?.let { runCatching { JSONObject(it) }.getOrNull() }
        return listOfNotNull(
            json(".obsidian/templates.json")?.optString("folder"),
            json(".obsidian/plugins/templater-obsidian/data.json")?.optString("templates_folder")
        ).map { it.trim().trim('/') }.filter { it.isNotEmpty() }
    }

    /** Open `#task` tasks from every scanned note except Quick Task, reading only notes that changed. */
    fun tasks(v: Vault, cacheFile: File?, walkTtlMs: Long = 0, now: Long = System.currentTimeMillis()): List<TaskItem> =
        scan(v, cacheFile, walkTtlMs, now).first

    /**
     * Open tasks and ticked `#task` tasks from every scanned note except Quick Task, in one pass. With [walkTtlMs]
     * above 0, a note list from the same kind of vault younger than that is reused instead of walking again.
     */
    @Synchronized
    fun scan(
        v: Vault, cacheFile: File?, walkTtlMs: Long = 0, now: Long = System.currentTimeMillis()
    ): Pair<List<TaskItem>, List<TickedTask>> {
        val key = v.javaClass.name + "|" + (cacheFile?.path ?: "")
        val known = cache ?: load(cacheFile)
        val touched = touchedPaths.toSet()
        touchedPaths.removeAll(touched)
        touched.forEach { known.remove(it) }
        val last = walk?.takeIf { walkTtlMs > 0 && it.key == key && now - it.at in 0 until walkTtlMs }
        val notes = if (last != null) refreshed(v, last.notes, touched, last.templates)
            .also { walk = Walk(key, last.at, it, last.templates) }
        else {
            val templates = templateFolders(v)
            v.notes { VaultTasks.skipped(it, templates) }
                .filter { it.path != VaultTasks.QUICK_TASK && !VaultTasks.skipped(it.path, templates) }
                .also { walk = Walk(key, now, it, templates) }
        }
        val fresh = mutableMapOf<String, Cached>()
        val out = mutableListOf<TaskItem>()
        val done = mutableListOf<TickedTask>()
        var changed = last == null || touched.isNotEmpty()
        for (n in notes) {
            val hit = known[n.path]?.takeIf { it.modified == n.modified && n.modified != 0L }
            if (hit == null) changed = true
            val entry = hit ?: runCatching {
                val lines = VaultIo.read(v, n.path).split("\n")
                Cached(n.modified, VaultTasks.taskLines(lines))
            }.getOrNull() ?: continue
            fresh[n.path] = entry
            entry.lines.mapNotNullTo(out) { TaskNote.parse(it)?.copy(path = n.path) }
            entry.lines.mapNotNullTo(done) { TaskNote.parseDone(it)?.copy(path = n.path) }
        }
        cache = fresh
        if (changed) save(cacheFile, fresh)
        return out to done
    }

    /** [notes] with each [touched] note's entry looked up again: new ones added, deleted ones dropped. */
    private fun refreshed(v: Vault, notes: List<VaultNote>, touched: Set<String>, templates: List<String>): List<VaultNote> {
        if (touched.isEmpty()) return notes
        val out = notes.filter { it.path !in touched }.toMutableList()
        for (p in touched) {
            if (p == VaultTasks.QUICK_TASK || VaultTasks.skipped(p, templates)) continue
            val e = runCatching { v.entry(p) }.getOrNull() ?: continue
            if (!e.isDir) out.add(VaultNote(p, e.modified))
        }
        return out
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
