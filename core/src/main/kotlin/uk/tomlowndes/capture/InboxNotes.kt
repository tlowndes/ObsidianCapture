package uk.tomlowndes.capture

import org.json.JSONArray
import org.json.JSONObject

/**
 * Inbox notes in the weekly review (evening-week-v1): the loose notes sitting in `01 - Inbox`. Each can be opened
 * in Obsidian, filed into a project folder, or ticked to stop showing (kept in `capture-config.json`, so the PC
 * agrees). The app's own notes there (Quick Task, Read It Later, the Inbox log) never show.
 */
object InboxNotes {
    private const val KEY = "inboxSeen"
    private const val MAX = 500
    private val OWN = setOf(VaultWriter.TASK_FILE, VaultWriter.INBOX_NOTE)

    /** Notes to look at, newest first: top-level `.md` files in the inbox, minus the app's own and those ticked. */
    fun list(v: Vault): List<String> {
        val seen = seen(v.read(DeckConfig.PATH))
        return v.list(VaultWriter.INBOX).filter { !it.isDir && it.name.endsWith(".md") && it.name !in OWN && it.name !in seen }
            .sortedByDescending { it.modified }.map { join(VaultWriter.INBOX, it.name) }
    }

    fun seen(json: String?): Set<String> {
        val o = json?.takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return emptySet()
        val a = o.optJSONArray(KEY) ?: return emptySet()
        return (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() }.toSet()
    }

    /** [json] with [name] added to the ticked list (oldest dropped past [MAX]); other keys kept. */
    fun withSeen(json: String?, name: String): String {
        val o = json?.takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
        val list = (seen(json).toList() + name).distinct().takeLast(MAX)
        o.put(KEY, JSONArray(list))
        return o.toString(2)
    }

    /** Stops [path] showing in the review. The note itself isn't touched. */
    fun markSeen(v: Vault, path: String) {
        if (v.entry(DeckConfig.PATH) == null) {
            v.mkdirs(parentOf(DeckConfig.PATH))
            v.create(parentOf(DeckConfig.PATH), nameOf(DeckConfig.PATH).removeSuffix(".json"), "json")
        }
        VaultIo.write(v, DeckConfig.PATH, withSeen(v.read(DeckConfig.PATH), nameOf(path)))
    }

    /** Moves [path] into the project folder [folder] (e.g. `03 - Projects/Flight Deck`). Returns the new path. */
    fun file(v: Vault, path: String, folder: String): String = v.move(path, folder)

    /** "Capture 2026-11-19" from `01 - Inbox/Capture 2026-11-19.md`. */
    fun title(path: String) = nameOf(path).removeSuffix(".md")
}
