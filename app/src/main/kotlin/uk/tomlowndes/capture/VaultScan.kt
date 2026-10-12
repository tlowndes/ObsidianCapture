package uk.tomlowndes.capture

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A markdown note found in the vault; [path] is relative to the vault root, e.g. "Projects/House move.md". */
data class VaultNote(val path: String, val uri: Uri, val modified: Long) {
    fun file(ctx: Context): DocumentFile = DocumentFile.fromSingleUri(ctx, uri) ?: error("Note unavailable")
}

/**
 * Walks the vault folder. Uses one Storage Access Framework query per folder (DocumentFile asks again for every
 * name and type, which is far too slow for a whole vault). Task lines are cached per note by modified time, in memory
 * and in the app's cache folder, so only notes changed since the last look are read again.
 */
object VaultScan {
    private const val CACHE_FILE = "vault-tasks.json"
    private val cols = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_LAST_MODIFIED
    )

    private class Cached(val modified: Long, val lines: List<String>)
    private var cache: MutableMap<String, Cached>? = null

    /** Every .md note under the vault, except folders [skip] says no to (it gets the folder's vault path). */
    fun notes(ctx: Context, tree: Uri, skip: (String) -> Boolean = { false }): List<VaultNote> {
        val out = mutableListOf<VaultNote>()
        fun walk(docId: String, prefix: String) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            val dirs = mutableListOf<Pair<String, String>>()
            ctx.contentResolver.query(children, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val path = prefix + name
                    if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (!skip(path)) dirs.add(id to "$path/")
                    } else if (name.endsWith(".md") && !name.startsWith(".")) {
                        out.add(VaultNote(path, DocumentsContract.buildDocumentUriUsingTree(tree, id), c.getLong(3)))
                    }
                }
            }
            dirs.forEach { (id, p) -> walk(id, p) }
        }
        walk(DocumentsContract.getTreeDocumentId(tree), "")
        return out
    }

    /** The vault's templates folders (core Templates and Templater), which hold placeholder tasks. */
    fun templateFolders(ctx: Context, tree: Uri): List<String> {
        val root = DocumentFile.fromTreeUri(ctx, tree) ?: return emptyList()
        fun json(vararg path: String): JSONObject? {
            var f: DocumentFile = root
            for (p in path) f = f.findFile(p) ?: return null
            return runCatching { JSONObject(VaultIo.read(ctx, f)) }.getOrNull()
        }
        return listOfNotNull(
            json(".obsidian", "templates.json")?.optString("folder"),
            json(".obsidian", "plugins", "templater-obsidian", "data.json")?.optString("templates_folder")
        ).map { it.trim().trim('/') }.filter { it.isNotEmpty() }
    }

    /** Open `#task` tasks from every scanned note except Quick Task, reading only notes that changed. */
    @Synchronized
    fun tasks(ctx: Context, tree: Uri): List<TaskItem> {
        val templates = templateFolders(ctx, tree)
        val notes = notes(ctx, tree) { VaultTasks.skipped(it, templates) }
            .filter { it.path != VaultTasks.QUICK_TASK && !VaultTasks.skipped(it.path, templates) }
        val known = cache ?: load(ctx)
        val fresh = mutableMapOf<String, Cached>()
        val out = mutableListOf<TaskItem>()
        for (n in notes) {
            val hit = known[n.path]?.takeIf { it.modified == n.modified && n.modified != 0L }
            val entry = hit ?: runCatching {
                val lines = VaultIo.read(ctx, n.file(ctx)).split("\n")
                Cached(n.modified, VaultTasks.parseNote(lines, n.path).map { it.raw })
            }.getOrNull() ?: continue
            fresh[n.path] = entry
            entry.lines.mapNotNullTo(out) { TaskNote.parse(it)?.copy(path = n.path) }
        }
        cache = fresh
        save(ctx, fresh)
        return out
    }

    private fun load(ctx: Context): MutableMap<String, Cached> = runCatching {
        val o = JSONObject(File(ctx.cacheDir, CACHE_FILE).readText())
        o.keys().asSequence().associateWith { k ->
            val e = o.getJSONObject(k)
            val ls = e.getJSONArray("t")
            Cached(e.getLong("m"), List(ls.length()) { ls.getString(it) })
        }.toMutableMap()
    }.getOrElse { mutableMapOf() }

    private fun save(ctx: Context, map: Map<String, Cached>) {
        runCatching {
            val o = JSONObject()
            map.forEach { (k, v) -> o.put(k, JSONObject().put("m", v.modified).put("t", JSONArray(v.lines))) }
            File(ctx.cacheDir, CACHE_FILE).writeText(o.toString())
        }
    }
}
