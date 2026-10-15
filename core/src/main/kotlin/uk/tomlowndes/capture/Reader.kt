package uk.tomlowndes.capture

import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Where a saved article lives: its file name (with .md) and whether it is in the Archive subfolder. */
data class ReadRef(val file: String, val archived: Boolean)

data class SavedArticle(
    val ref: ReadRef, val title: String, val url: String?, val snippet: String,
    val read: Boolean, val saved: Long, val words: Int
)

data class ArticleDoc(
    val ref: ReadRef, val title: String, val url: String?, val read: Boolean, val saved: Long,
    val body: List<String>, val highlights: List<String> = emptyList()
)

/**
 * The Read It Later inbox as a reading list. State lives in each note's frontmatter (`status: unread|read|archived`,
 * no status = unread) and archiving moves the note into `ReadItLater Inbox/Archive/`, so Obsidian sees the same thing.
 * [onChange] runs after every edit (Android refreshes its home screen widget there).
 */
class ReadStore(private val vault: Vault, private val onChange: () -> Unit = {}) {
    private fun folder(archived: Boolean): String =
        "${VaultWriter.INBOX}/${VaultWriter.RIL_DIR}" + if (archived) "/$ARCHIVE" else ""

    private fun fileOf(ref: ReadRef): String =
        join(folder(ref.archived), ref.file).takeIf { vault.entry(it)?.isDir == false }
            ?: error("Article moved in Obsidian, refreshed")

    // ---- reading ----

    /** Inbox and archive together, newest first. Only the head of each note is read. */
    fun list(): List<SavedArticle> = articles(listOf(false, true))

    /** Just the inbox (no Archive), for the Read widget. */
    fun inbox(): List<SavedArticle> = articles(listOf(false))

    private fun articles(folders: List<Boolean>): List<SavedArticle> = folders.flatMap { archived ->
        vault.list(folder(archived)).filter { !it.isDir && it.name.endsWith(".md") }.map { f ->
            val doc = ReadNote.parse(ReadRef(f.name, archived), vault.read(join(folder(archived), f.name), HEAD).orEmpty(), f.modified)
            SavedArticle(doc.ref, doc.title, doc.url, ReadNote.snippet(doc.body), doc.read, doc.saved, (f.size / 6).toInt())
        }
    }.sortedByDescending { it.saved }

    fun load(ref: ReadRef): ArticleDoc = fileOf(ref).let { p ->
        ReadNote.parse(ref, VaultIo.read(vault, p), vault.entry(p)?.modified ?: 0L)
    }

    // ---- writing ----

    private fun edit(path: String, change: (MutableList<String>) -> Unit) {
        val saved = stamp(vault.entry(path)?.modified ?: System.currentTimeMillis())
        VaultIo.edit(vault, path) { lines ->
            if (Md.fmGet(lines, "saved") == null) Md.fmSet(lines, "saved", saved)
            change(lines)
        }
        onChange()
    }

    fun setRead(ref: ReadRef, read: Boolean) = edit(fileOf(ref)) { Md.fmSet(it, "status", if (read) "read" else "unread") }

    /** Adds [text] as a `> ` line under "## Highlights" (created at the end of the note), or removes it if already there. */
    fun toggleHighlight(ref: ReadRef, text: String) = edit(fileOf(ref)) { ReadNote.toggleHighlight(it, text) }

    /** Marks archived and moves the note into Archive/. Returns where it ended up. */
    fun archive(ref: ReadRef): ReadRef {
        val f = fileOf(ref)
        edit(f) { Md.fmSet(it, "status", "archived") }
        return ReadRef(nameOf(vault.move(f, folder(true))), true).also { onChange() }
    }

    /** Moves the note back to the inbox as read. */
    fun unarchive(ref: ReadRef): ReadRef {
        val f = fileOf(ref)
        edit(f) { Md.fmSet(it, "status", "read") }
        return ReadRef(nameOf(vault.move(f, folder(false))), false).also { onChange() }
    }

    companion object {
        const val ARCHIVE = "Archive"
        const val HIGHLIGHTS = ReadNote.HIGHLIGHTS
        private const val HEAD = 6000
        private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")

        fun stamp(millis: Long = System.currentTimeMillis()): String =
            Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDateTime().format(STAMP)

        fun host(url: String?): String? = url?.let { runCatching { URI(it).host?.removePrefix("www.") }.getOrNull() }

        /** Markdown line to plain text for previews. */
        fun plainText(s: String): String = ReadNote.plainText(s)
    }
}
