package uk.tomlowndes.capture

/**
 * The vault folder, as each platform reaches it: the Storage Access Framework folder picked on Android, a plain
 * folder on disk on the desktop. Paths are relative to the vault root with `/` separators, e.g.
 * "01 - Inbox/⚡ Quick Task.md"; "" is the root itself.
 */
interface Vault {
    data class Entry(val name: String, val isDir: Boolean, val modified: Long, val size: Long)

    /** The file's text, at most [max] characters; null if there is no such file. */
    fun read(path: String, max: Int = Int.MAX_VALUE): String?

    /** Replaces an existing file's contents in one write. Use [VaultIo.write] to also check it landed. */
    fun writeOnce(path: String, text: String)

    fun writeBytes(path: String, bytes: ByteArray)

    /** The file or folder at [path], or null if there isn't one. */
    fun entry(path: String): Entry?

    /** What is directly inside folder [dir]; empty if it doesn't exist. */
    fun list(dir: String): List<Entry>

    /** Creates folder [dir] and any missing parents. */
    fun mkdirs(dir: String)

    /**
     * Creates an empty file called [base].[ext] in [dir] (made if missing). If the name is taken, a free one like
     * "base (1).ext" is used. Returns the new file's path.
     */
    fun create(dir: String, base: String, ext: String): String

    /** Moves the file at [path] into folder [toDir]. Returns where it ended up. */
    fun move(path: String, toDir: String): String

    fun delete(path: String)

    /** What image loaders on this platform take for the file at [path] (a content Uri on Android, a File on desktop). */
    fun handle(path: String): Any

    /** Every .md note under the vault, except in folders [skip] says no to (it gets the folder's vault path). */
    fun notes(skip: (String) -> Boolean = { false }): List<VaultNote> {
        val out = mutableListOf<VaultNote>()
        fun walk(dir: String) {
            val prefix = if (dir.isEmpty()) "" else "$dir/"
            for (e in list(dir)) {
                val path = prefix + e.name
                if (e.isDir) { if (!skip(path)) walk(path) }
                else if (e.name.endsWith(".md") && !e.name.startsWith(".")) out.add(VaultNote(path, e.modified))
            }
        }
        walk("")
        return out
    }
}

/** A markdown note found in the vault; [path] is relative to the vault root, e.g. "Projects/House move.md". */
data class VaultNote(val path: String, val modified: Long)

/** The folder settings the stores need; each platform keeps them its own way. */
interface VaultPrefs {
    val ideasDir: String
    val imagesDir: String
    /** Blank = use the folder/format from the vault's own .obsidian/daily-notes.json. */
    val dailyDir: String
}

internal fun join(dir: String, name: String) = if (dir.isEmpty()) name else "$dir/$name"
internal fun nameOf(path: String) = path.substringAfterLast('/')
internal fun parentOf(path: String) = path.substringBeforeLast('/', "")
