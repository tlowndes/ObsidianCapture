package uk.tomlowndes.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.max

data class IdeaSummary(
    val title: String, val snippet: String, val entries: Int, val images: Int,
    val firstImage: String?, val status: String, val modified: Long
)

/** One dated entry in an idea's Log. [heading] is the text after `### `. */
data class IdeaEntry(val heading: String, val text: String, val images: List<String>)

data class IdeaDoc(val title: String, val status: String, val summary: String, val entries: List<IdeaEntry>)

/**
 * Ideas live as `<ideas folder>/<Title>/<Title>.md` (a folder note), with images in the images folder.
 * Every edit is a read-modify-write of just the section it owns, so changes made in Obsidian are kept.
 */
class IdeaStore(private val ctx: Context, treeUri: Uri, private val prefs: AppPrefs) {
    private val root = DocumentFile.fromTreeUri(ctx, treeUri) ?: error("Vault folder unavailable")

    private fun dir(path: String, create: Boolean): DocumentFile? {
        var cur = root
        for (part in path.split("/").filter { it.isNotBlank() }) {
            cur = cur.findFile(part)?.takeIf { it.isDirectory }
                ?: (if (create) cur.createDirectory(part) else null) ?: return null
        }
        return cur
    }

    private fun read(f: DocumentFile) = VaultIo.read(ctx, f)

    private fun noteOf(title: String): DocumentFile =
        dir(prefs.ideasDir, false)?.findFile(title)?.takeIf { it.isDirectory }?.findFile("$title.md")
            ?: error("Idea \"$title\" not found")

    private fun edit(title: String, change: (MutableList<String>) -> Unit) = VaultIo.edit(ctx, noteOf(title), change)

    // ---- reading ----

    fun list(): List<IdeaSummary> {
        val folder = dir(prefs.ideasDir, false) ?: return emptyList()
        return folder.listFiles().filter { it.isDirectory }.mapNotNull { d ->
            val name = d.name ?: return@mapNotNull null
            val note = d.findFile("$name.md") ?: return@mapNotNull null
            val doc = IdeaNote.parse(name, read(note))
            IdeaSummary(
                name, doc.summary.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty(),
                doc.entries.size, doc.entries.sumOf { it.images.size },
                doc.entries.firstOrNull { it.images.isNotEmpty() }?.images?.first(),
                doc.status, note.lastModified()
            )
        }.sortedByDescending { it.modified }
    }

    fun load(title: String): IdeaDoc = IdeaNote.parse(title, read(noteOf(title)))

    /** Image file name -> uri for everything in the images folder (one directory listing). */
    fun imageIndex(): Map<String, Uri> =
        dir(prefs.imagesDir, false)?.listFiles()?.mapNotNull { f -> f.name?.let { it to f.uri } }?.toMap() ?: emptyMap()

    // ---- writing ----

    fun create(rawTitle: String, summary: String): String {
        val title = IdeaNote.safeName(rawTitle)
        require(title.isNotBlank()) { "Give the idea a title" }
        val parent = dir(prefs.ideasDir, true) ?: error("Cannot create ideas folder")
        require(parent.findFile(title) == null) { "\"$title\" already exists" }
        val folder = parent.createDirectory(title) ?: error("Cannot create folder")
        val note = folder.createFile("text/markdown", title) ?: error("Cannot create note")
        VaultIo.write(ctx, note, IdeaNote.newNote(title, summary, java.time.LocalDate.now()))
        return title
    }

    /** [was] is the summary as shown when editing started; stops rather than overwrite a change made elsewhere. */
    fun setSummary(title: String, was: String, text: String) = edit(title) { IdeaNote.setSummary(it, was, text) }

    fun setStatus(title: String, status: String) = edit(title) { Md.fmSet(it, "status", status) }

    /** New entries go at the top of the Log, newest first. */
    fun addEntry(title: String, text: String, images: List<String>) =
        edit(title) { IdeaNote.addEntry(it, LocalDateTime.now().format(STAMP), text, images) }

    /** [entry] is the entry as shown when editing started. */
    fun editEntry(title: String, entry: IdeaEntry, text: String) = edit(title) { IdeaNote.editEntry(it, entry, text) }

    fun deleteEntry(title: String, entry: IdeaEntry) = edit(title) { IdeaNote.deleteEntry(it, entry) }

    /** Shrinks the picked/captured image to at most 1600 px, saves it as JPEG in the images folder. */
    fun saveImage(src: Uri, baseName: String): Pair<String, Uri> {
        val cr = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(src)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > 3200) sample *= 2
        var bmp = cr.openInputStream(src)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error("Cannot read image")
        val orientation = runCatching {
            cr.openInputStream(src)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }
        }.getOrNull() ?: 1
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
        }
        val scale = 1600f / max(bmp.width, bmp.height)
        if (scale < 1f) m.postScale(scale, scale)
        if (!m.isIdentity) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        val bytes = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
        val folder = dir(prefs.imagesDir, true) ?: error("Cannot create images folder")
        val file = folder.createFile("image/jpeg", baseName) ?: error("Cannot create image file")
        cr.openOutputStream(file.uri, "wt")?.use { it.write(bytes) } ?: error("Cannot write image")
        return (file.name ?: "$baseName.jpg") to file.uri
    }

    companion object {
        private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        fun safeName(s: String) = IdeaNote.safeName(s)
    }
}
