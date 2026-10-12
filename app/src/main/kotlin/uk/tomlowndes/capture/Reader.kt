package uk.tomlowndes.capture

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
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
 */
class ReadStore(private val ctx: Context, treeUri: Uri) {
    private val root = DocumentFile.fromTreeUri(ctx, treeUri) ?: error("Vault folder unavailable")

    private fun dir(parts: List<String>, create: Boolean): DocumentFile? {
        var cur = root
        for (part in parts) {
            cur = cur.findFile(part)?.takeIf { it.isDirectory }
                ?: (if (create) cur.createDirectory(part) else null) ?: return null
        }
        return cur
    }

    private fun folder(archived: Boolean, create: Boolean = false): DocumentFile? {
        val parts = listOf(VaultWriter.INBOX, VaultWriter.RIL_DIR) + if (archived) listOf(ARCHIVE) else emptyList()
        return dir(parts, create)
    }

    private fun fileOf(ref: ReadRef): DocumentFile =
        folder(ref.archived)?.findFile(ref.file)?.takeIf { it.isFile } ?: error("Article moved in Obsidian, refreshed")

    private fun read(f: DocumentFile, max: Int = Int.MAX_VALUE): String =
        ctx.contentResolver.openInputStream(f.uri)?.bufferedReader()?.use { r ->
            if (max == Int.MAX_VALUE) return@use r.readText()
            val buf = CharArray(max)
            var n = 0
            while (n < max) { val k = r.read(buf, n, max - n); if (k < 0) break; n += k }
            String(buf, 0, n)
        }.orEmpty()


    // ---- reading ----

    /** Inbox and archive together, newest first. Only the head of each note is read. */
    fun list(): List<SavedArticle> = articles(listOf(false, true))

    /** Just the inbox (no Archive), for the Read widget. */
    fun inbox(): List<SavedArticle> = articles(listOf(false))

    private fun articles(folders: List<Boolean>): List<SavedArticle> = folders.flatMap { archived ->
        folder(archived)?.listFiles().orEmpty().filter { it.isFile && it.name.orEmpty().endsWith(".md") }.map { f ->
            val doc = ReadNote.parse(ReadRef(f.name!!, archived), read(f, HEAD), f.lastModified())
            SavedArticle(doc.ref, doc.title, doc.url, ReadNote.snippet(doc.body), doc.read, doc.saved,
                (f.length() / 6).toInt())
        }
    }.sortedByDescending { it.saved }

    fun load(ref: ReadRef): ArticleDoc = fileOf(ref).let { ReadNote.parse(ref, read(it), it.lastModified()) }

    // ---- writing ----

    private fun edit(f: DocumentFile, change: (MutableList<String>) -> Unit) {
        val saved = stamp(f.lastModified())
        VaultIo.edit(ctx, f) { lines ->
            if (Md.fmGet(lines, "saved") == null) Md.fmSet(lines, "saved", saved)
            change(lines)
        }
        Widgets.readChanged(ctx)
    }

    fun setRead(ref: ReadRef, read: Boolean) = edit(fileOf(ref)) { Md.fmSet(it, "status", if (read) "read" else "unread") }

    /** Adds [text] as a `> ` line under "## Highlights" (created at the end of the note), or removes it if already there. */
    fun toggleHighlight(ref: ReadRef, text: String) = edit(fileOf(ref)) { ReadNote.toggleHighlight(it, text) }

    /** Marks archived and moves the note into Archive/. Returns where it ended up. */
    fun archive(ref: ReadRef): ReadRef {
        val f = fileOf(ref)
        edit(f) { Md.fmSet(it, "status", "archived") }
        return ReadRef(move(f, folder(false)!!, folder(true, create = true)!!), true).also { Widgets.readChanged(ctx) }
    }

    /** Moves the note back to the inbox as read. */
    fun unarchive(ref: ReadRef): ReadRef {
        val f = fileOf(ref)
        edit(f) { Md.fmSet(it, "status", "read") }
        return ReadRef(move(f, folder(true)!!, folder(false)!!), false).also { Widgets.readChanged(ctx) }
    }

    private fun move(f: DocumentFile, from: DocumentFile, to: DocumentFile): String {
        val name = f.name ?: error("Unnamed note")
        val moved = runCatching { DocumentsContract.moveDocument(ctx.contentResolver, f.uri, from.uri, to.uri) }.getOrNull()
        if (moved != null) return DocumentFile.fromSingleUri(ctx, moved)?.name ?: name
        // Provider can't move: copy, then remove the original.
        val copy = to.createFile("text/markdown", name.removeSuffix(".md")) ?: error("Cannot move $name")
        val text = read(f)
        if (VaultIo.write(ctx, copy, text) != text) error("Cannot move $name")
        f.delete()
        return copy.name ?: name
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
