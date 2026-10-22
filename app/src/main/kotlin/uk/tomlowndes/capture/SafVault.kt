package uk.tomlowndes.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max

/**
 * The vault through the Storage Access Framework folder the user picked. Folder listings use one query per folder
 * (DocumentFile asks again for every name and type, which is far too slow for a whole vault).
 */
class SafVault(private val ctx: Context, private val tree: Uri) : Vault {
    private val root = DocumentFile.fromTreeUri(ctx, tree) ?: error("Vault folder unavailable")
    private val cr get() = ctx.contentResolver

    private fun parts(path: String) = path.split("/").filter { it.isNotEmpty() }

    private fun find(path: String): DocumentFile? {
        var cur = root
        for (p in parts(path)) cur = cur.findFile(p) ?: return null
        return cur
    }

    private fun need(path: String) = find(path) ?: error("${nameOf(path)} not found")

    private fun nameOf(path: String) = path.substringAfterLast('/')

    private fun folder(path: String): DocumentFile {
        var cur = root
        for (p in parts(path)) {
            cur = cur.findFile(p)?.takeIf { it.isDirectory } ?: cur.createDirectory(p) ?: error("Cannot create $p")
        }
        return cur
    }

    override fun read(path: String, max: Int): String? {
        val f = find(path)?.takeIf { it.isFile } ?: return null
        return cr.openInputStream(f.uri)?.bufferedReader()?.use { r ->
            if (max == Int.MAX_VALUE) return@use r.readText()
            val buf = CharArray(max)
            var n = 0
            while (n < max) { val k = r.read(buf, n, max - n); if (k < 0) break; n += k }
            String(buf, 0, n)
        }.orEmpty()
    }

    override fun writeOnce(path: String, text: String) = writeBytes(path, text.toByteArray())

    override fun writeBytes(path: String, bytes: ByteArray) {
        cr.openOutputStream(need(path).uri, "wt")?.use { it.write(bytes); it.flush() } ?: error("Cannot write ${nameOf(path)}")
    }

    override fun entry(path: String): Vault.Entry? =
        find(path)?.let { Vault.Entry(it.name ?: nameOf(path), it.isDirectory, it.lastModified(), it.length()) }

    private val cols = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        DocumentsContract.Document.COLUMN_SIZE
    )

    /** Children of the folder with document id [docId]: (id, entry). */
    private fun children(docId: String): List<Pair<String, Vault.Entry>> {
        val out = mutableListOf<Pair<String, Vault.Entry>>()
        cr.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId), cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                val isDir = c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
                out.add(id to Vault.Entry(name, isDir, c.getLong(3), if (c.isNull(4)) 0L else c.getLong(4)))
            }
        }
        return out
    }

    override fun list(dir: String): List<Vault.Entry> {
        val d = find(dir)?.takeIf { it.isDirectory } ?: return emptyList()
        return children(DocumentsContract.getDocumentId(d.uri)).map { it.second }
    }

    override fun notes(skip: (String) -> Boolean): List<VaultNote> {
        val out = mutableListOf<VaultNote>()
        fun walk(docId: String, prefix: String) {
            val dirs = mutableListOf<Pair<String, String>>()
            for ((id, e) in children(docId)) {
                val path = prefix + e.name
                if (e.isDir) { if (!skip(path)) dirs.add(id to "$path/") }
                else if (e.name.endsWith(".md") && !e.name.startsWith(".")) out.add(VaultNote(path, e.modified))
            }
            dirs.forEach { (id, p) -> walk(id, p) }
        }
        walk(DocumentsContract.getTreeDocumentId(tree), "")
        return out
    }

    override fun mkdirs(dir: String) { folder(dir) }

    override fun create(dir: String, base: String, ext: String): String {
        // The provider adds the extension it knows for the type, and picks a free name if this one is taken.
        val (mime, name) = when (ext) {
            "md" -> "text/markdown" to base
            "jpg", "jpeg" -> "image/jpeg" to base
            "json" -> "application/json" to "$base.json"
            else -> "application/octet-stream" to "$base.$ext"
        }
        val f = folder(dir).createFile(mime, name) ?: error("Cannot create $base.$ext")
        val made = f.name ?: "$base.$ext"
        return if (dir.isEmpty()) made else "$dir/$made"
    }

    override fun move(path: String, toDir: String): String {
        val f = need(path)
        val name = f.name ?: nameOf(path)
        val from = find(path.substringBeforeLast('/', "")) ?: error("Cannot move $name")
        val to = folder(toDir)
        val moved = runCatching { DocumentsContract.moveDocument(cr, f.uri, from.uri, to.uri) }.getOrNull()
        if (moved != null) return "$toDir/" + (DocumentFile.fromSingleUri(ctx, moved)?.name ?: name)
        // Provider can't move: copy, then remove the original.
        val bytes = cr.openInputStream(f.uri)?.use { it.readBytes() } ?: error("Cannot move $name")
        val copy = create(toDir, name.substringBeforeLast('.'), name.substringAfterLast('.', "md"))
        writeBytes(copy, bytes)
        if (cr.openInputStream(need(copy).uri)?.use { it.readBytes() }?.contentEquals(bytes) != true) error("Cannot move $name")
        f.delete()
        return copy
    }

    override fun delete(path: String) { find(path)?.delete() }

    override fun handle(path: String): Any = need(path).uri
}

// ---- the shared stores, set up for Android: the picked folder, the cache folder, and widget refreshes ----

fun VaultWriter(ctx: Context, tree: Uri) =
    VaultWriter(SafVault(ctx, tree), File(ctx.cacheDir, "vault-tasks.json")) { Widgets.tasksChanged(ctx) }

fun ReadStore(ctx: Context, tree: Uri) = ReadStore(SafVault(ctx, tree)) { Widgets.readChanged(ctx) }

fun IdeaStore(ctx: Context, tree: Uri, prefs: AppPrefs) = IdeaStore(SafVault(ctx, tree), prefs)

fun ReadLater.save(ctx: Context, tree: Uri, url: String, sharedTitle: String?): Boolean =
    save(SafVault(ctx, tree), url, sharedTitle).also { Widgets.readChanged(ctx) }

/** Image file name -> content Uri for everything in the images folder. */
fun IdeaStore.imageUris(): Map<String, Uri> = imageIndex().mapValues { it.value as Uri }

/** Shrinks the picked/captured image to at most 1600 px, saves it as JPEG in the images folder. */
fun IdeaStore.saveImage(ctx: Context, src: Uri, baseName: String): Pair<String, Uri> {
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
    val (name, handle) = saveImage(bytes, baseName)
    return name to handle as Uri
}
