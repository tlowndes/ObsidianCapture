package uk.tomlowndes.capture

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.net.URI
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Where a saved article lives: its file name (with .md) and whether it is in the Archive subfolder. */
data class ReadRef(val file: String, val archived: Boolean)

data class SavedArticle(
    val ref: ReadRef, val title: String, val url: String?, val snippet: String,
    val read: Boolean, val saved: Long, val words: Int
)

data class ArticleDoc(val ref: ReadRef, val title: String, val url: String?, val read: Boolean, val saved: Long, val body: List<String>)

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

    private fun write(f: DocumentFile, text: String) {
        ctx.contentResolver.openOutputStream(f.uri, "wt")?.use { it.write(text.toByteArray()) } ?: error("Cannot write ${f.name}")
    }

    // ---- reading ----

    /** Inbox and archive together, newest first. Only the head of each note is read. */
    fun list(): List<SavedArticle> = listOf(false, true).flatMap { archived ->
        folder(archived)?.listFiles().orEmpty().filter { it.isFile && it.name.orEmpty().endsWith(".md") }.map { f ->
            val doc = parse(ReadRef(f.name!!, archived), read(f, HEAD), f.lastModified())
            SavedArticle(doc.ref, doc.title, doc.url, snippet(doc.body), doc.read, doc.saved,
                (f.length() / 6).toInt())
        }
    }.sortedByDescending { it.saved }

    fun load(ref: ReadRef): ArticleDoc = fileOf(ref).let { parse(ref, read(it), it.lastModified()) }

    private fun parse(ref: ReadRef, text: String, modified: Long): ArticleDoc {
        val lines = text.replace("\r", "").split("\n")
        val status = Md.fmGet(lines, "status")?.lowercase()
        val saved = Md.fmGet(lines, "saved")?.let(::parseStamp) ?: modified
        val body = Md.fmBody(lines).dropWhile { it.isBlank() || TAG_LINE.matches(it.trim()) }.toMutableList()
        var title = ref.file.removeSuffix(".md")
        var url: String? = null
        val first = body.indexOfFirst { it.isNotBlank() }
        if (first >= 0) {
            val line = body[first].trim()
            val linked = TITLE_LINK.matchEntire(line)
            when {
                linked != null -> { title = linked.groupValues[2]; url = linked.groupValues[3]; body.removeAt(first) }
                line.startsWith("# ") -> { title = line.removePrefix("# ").trim(); body.removeAt(first) }
            }
        }
        if (url == null) url = body.firstNotNullOfOrNull { LINK.find(it)?.groupValues?.get(2) }
        return ArticleDoc(ref, title, url, ref.archived || status == "read" || status == "archived", saved, body)
    }

    private fun snippet(body: List<String>): String {
        val plain = body.asSequence().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("!") && !it.startsWith("```") && it != "---" }
            .map { plainText(it) }.filter { it.isNotBlank() }
        return (plain.firstOrNull { it.length >= 60 } ?: plain.firstOrNull()).orEmpty().take(200)
    }

    // ---- writing ----

    private fun edit(f: DocumentFile, change: (MutableList<String>) -> Unit) {
        val lines = read(f).split("\n").toMutableList()
        if (Md.fmGet(lines, "saved") == null) Md.fmSet(lines, "saved", stamp(f.lastModified()))
        change(lines)
        val out = lines.joinToString("\n")
        write(f, if (out.endsWith("\n")) out else out + "\n")
    }

    fun setRead(ref: ReadRef, read: Boolean) = edit(fileOf(ref)) { Md.fmSet(it, "status", if (read) "read" else "unread") }

    /** Marks archived and moves the note into Archive/. Returns where it ended up. */
    fun archive(ref: ReadRef): ReadRef {
        val f = fileOf(ref)
        edit(f) { Md.fmSet(it, "status", "archived") }
        return ReadRef(move(f, folder(false)!!, folder(true, create = true)!!), true)
    }

    /** Moves the note back to the inbox as read. */
    fun unarchive(ref: ReadRef): ReadRef {
        val f = fileOf(ref)
        edit(f) { Md.fmSet(it, "status", "read") }
        return ReadRef(move(f, folder(true)!!, folder(false)!!), false)
    }

    private fun move(f: DocumentFile, from: DocumentFile, to: DocumentFile): String {
        val name = f.name ?: error("Unnamed note")
        val moved = runCatching { DocumentsContract.moveDocument(ctx.contentResolver, f.uri, from.uri, to.uri) }.getOrNull()
        if (moved != null) return DocumentFile.fromSingleUri(ctx, moved)?.name ?: name
        // Provider can't move: copy, then remove the original.
        val copy = to.createFile("text/markdown", name.removeSuffix(".md")) ?: error("Cannot move $name")
        write(copy, read(f))
        f.delete()
        return copy.name ?: name
    }

    companion object {
        const val ARCHIVE = "Archive"
        private const val HEAD = 6000
        private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
        private val TAG_LINE = Regex("^(\\[\\[[^\\]]+]]\\s*)+$")
        private val TITLE_LINK = Regex("^#\\s+(\\[(.+)]\\((\\S+)\\))$")
        private val LINK = Regex("\\[([^\\]]*)]\\((https?://[^)\\s]+)\\)")

        fun stamp(millis: Long = System.currentTimeMillis()): String =
            Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDateTime().format(STAMP)

        private fun parseStamp(s: String): Long? = runCatching {
            val t = s.trim().trim('"')
            val dt = if (t.length == 10) java.time.LocalDate.parse(t).atStartOfDay() else LocalDateTime.parse(t.replace(' ', 'T'))
            dt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrNull()

        fun host(url: String?): String? = url?.let { runCatching { URI(it).host?.removePrefix("www.") }.getOrNull() }

        /** Markdown line to plain text for previews. */
        fun plainText(s: String): String = s
            .replace(Regex("!\\[[^\\]]*]\\([^)]*\\)"), "")
            .replace(Regex("\\[([^\\]]*)]\\([^)]*\\)"), "$1")
            .replace(Regex("^(>\\s*|[-*+]\\s+|\\d+\\.\\s+)"), "")
            .replace(Regex("[*`_]"), "")
            .trim()
    }
}
