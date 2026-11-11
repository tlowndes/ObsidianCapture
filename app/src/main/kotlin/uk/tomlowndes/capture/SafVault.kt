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
import java.io.InputStream
import kotlin.math.max

/**
 * One node in a document tree. On Android this is a Storage Access Framework document; tests use a
 * memory tree so the path, create and move logic can run without a device.
 */
internal interface VaultDoc {
    val name: String
    val isDirectory: Boolean
    val modified: Long
    val length: Long
    fun findChild(name: String): VaultDoc?
    fun children(): List<VaultDoc>
    fun mkdir(name: String): VaultDoc?
    fun createFile(mime: String, displayName: String): VaultDoc?
    fun open(): InputStream?
    fun writeBytes(bytes: ByteArray)
    fun delete()
    /** Moves this document from [fromDir] into [targetDir], or null if this tree can't move it. */
    fun moveInto(fromDir: VaultDoc, targetDir: VaultDoc): VaultDoc?
    fun handle(): Any
}

/**
 * The vault through the Storage Access Framework folder the user picked. Folder listings use one query per folder
 * (DocumentFile asks again for every name and type, which is far too slow for a whole vault).
 */
class SafVault internal constructor(private val root: VaultDoc) : Vault {
    constructor(ctx: Context, tree: Uri) : this(androidRoot(ctx, tree))

    private fun parts(path: String) = path.split("/").filter { it.isNotEmpty() }

    private fun find(path: String): VaultDoc? {
        var cur = root
        for (p in parts(path)) cur = cur.findChild(p) ?: return null
        return cur
    }

    private fun nameOf(path: String) = path.substringAfterLast('/')

    private fun folder(path: String): VaultDoc {
        var cur = root
        for (p in parts(path)) {
            cur = cur.findChild(p)?.takeIf { it.isDirectory } ?: cur.mkdir(p) ?: error("Cannot create $p")
        }
        return cur
    }

    override fun read(path: String, max: Int): String? {
        val f = find(path)?.takeIf { !it.isDirectory } ?: return null
        return f.open()?.bufferedReader()?.use { r ->
            if (max == Int.MAX_VALUE) return@use r.readText()
            val buf = CharArray(max)
            var n = 0
            while (n < max) { val k = r.read(buf, n, max - n); if (k < 0) break; n += k }
            String(buf, 0, n)
        } // null when the provider can't open it: never pass that off as an empty note
    }

    override fun writeOnce(path: String, text: String) = writeBytes(path, text.toByteArray())

    override fun writeBytes(path: String, bytes: ByteArray) {
        (find(path) ?: error("${nameOf(path)} not found")).writeBytes(bytes)
    }

    override fun entry(path: String): Vault.Entry? =
        find(path)?.let { Vault.Entry(it.name, it.isDirectory, it.modified, it.length) }

    override fun list(dir: String): List<Vault.Entry> {
        val d = find(dir)?.takeIf { it.isDirectory } ?: return emptyList()
        return d.children().map { Vault.Entry(it.name, it.isDirectory, it.modified, it.length) }
    }

    override fun notes(skip: (String) -> Boolean): List<VaultNote> {
        val out = mutableListOf<VaultNote>()
        fun walk(doc: VaultDoc, prefix: String) {
            val dirs = mutableListOf<Pair<VaultDoc, String>>()
            for (e in doc.children()) {
                val path = prefix + e.name
                if (e.isDirectory) { if (!skip(path)) dirs.add(e to "$path/") }
                else if (e.name.endsWith(".md") && !e.name.startsWith(".")) out.add(VaultNote(path, e.modified))
            }
            dirs.forEach { (d, p) -> walk(d, p) }
        }
        walk(root, "")
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
        val made = f.name.ifEmpty { "$base.$ext" }
        return if (dir.isEmpty()) made else "$dir/$made"
    }

    override fun move(path: String, toDir: String): String {
        val f = find(path) ?: error("${nameOf(path)} not found")
        val name = f.name
        val from = find(path.substringBeforeLast('/', "")) ?: error("Cannot move $name")
        val to = folder(toDir)
        val moved = f.moveInto(from, to)
        if (moved != null) return "$toDir/" + moved.name.ifEmpty { name }
        // Provider can't move: copy, then remove the original.
        val bytes = f.open()?.use { it.readBytes() } ?: error("Cannot move $name")
        val copy = create(toDir, name.substringBeforeLast('.'), name.substringAfterLast('.', "md"))
        writeBytes(copy, bytes)
        val back = find(copy)?.open()?.use { it.readBytes() }
        if (back == null || !back.contentEquals(bytes)) error("Cannot move $name")
        f.delete()
        return copy
    }

    override fun delete(path: String) { find(path)?.delete() }

    override fun handle(path: String): Any = (find(path) ?: error("${nameOf(path)} not found")).handle()
}

private fun androidRoot(ctx: Context, tree: Uri): VaultDoc {
    val root = DocumentFile.fromTreeUri(ctx, tree) ?: error("Vault folder unavailable")
    return AndroidDoc(ctx, tree, root, DocumentsContract.getTreeDocumentId(tree), root.uri, root.name ?: "", true, root.lastModified(), root.length())
}

/** A SAF document. [file] is set for the tree root and anything reached by [VaultDoc.findChild]; listings use a query. */
private class AndroidDoc(
    private val ctx: Context,
    private val tree: Uri,
    private val file: DocumentFile?,
    private val docId: String,
    private val uri: Uri,
    override val name: String,
    override val isDirectory: Boolean,
    override val modified: Long,
    override val length: Long,
) : VaultDoc {
    private val cr get() = ctx.contentResolver

    override fun findChild(name: String): VaultDoc? {
        val f = file?.findFile(name) ?: return null
        return fromFile(f)
    }

    override fun children(): List<VaultDoc> {
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE
        )
        val out = mutableListOf<VaultDoc>()
        cr.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId), cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val childName = c.getString(1) ?: continue
                val isDir = c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
                val childUri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                out.add(AndroidDoc(ctx, tree, null, id, childUri, childName, isDir, c.getLong(3), if (c.isNull(4)) 0L else c.getLong(4)))
            }
        }
        return out
    }

    override fun mkdir(name: String): VaultDoc? = file?.createDirectory(name)?.let(::fromFile)

    override fun createFile(mime: String, displayName: String): VaultDoc? = file?.createFile(mime, displayName)?.let(::fromFile)

    override fun open(): InputStream? = cr.openInputStream(uri)

    override fun writeBytes(bytes: ByteArray) {
        cr.openOutputStream(uri, "wt")?.use { it.write(bytes); it.flush() } ?: error("Cannot write $name")
    }

    override fun delete() {
        if (file != null) file.delete() else DocumentsContract.deleteDocument(cr, uri)
    }

    override fun moveInto(fromDir: VaultDoc, targetDir: VaultDoc): VaultDoc? {
        val fromUri = (fromDir as AndroidDoc).uri
        val toUri = (targetDir as AndroidDoc).uri
        val moved = runCatching { DocumentsContract.moveDocument(cr, uri, fromUri, toUri) }.getOrNull() ?: return null
        val movedName = DocumentFile.fromSingleUri(ctx, moved)?.name ?: name
        return AndroidDoc(ctx, tree, DocumentFile.fromSingleUri(ctx, moved), DocumentsContract.getDocumentId(moved), moved, movedName, false, 0L, length)
    }

    override fun handle(): Any = uri

    private fun fromFile(f: DocumentFile) = AndroidDoc(
        ctx, tree, f, DocumentsContract.getDocumentId(f.uri), f.uri,
        f.name ?: "", f.isDirectory, f.lastModified(), f.length()
    )
}

// ---- the shared stores, set up for Android: the picked folder, the cache folder, and widget refreshes ----

fun VaultWriter(ctx: Context, tree: Uri) =
    VaultWriter(appVault(ctx, tree), File(ctx.cacheDir, "vault-tasks.json")) { Widgets.tasksChanged(ctx) }

fun ReadStore(ctx: Context, tree: Uri) = ReadStore(appVault(ctx, tree)) { Widgets.readChanged(ctx) }

fun IdeaStore(ctx: Context, tree: Uri, prefs: AppPrefs) = IdeaStore(appVault(ctx, tree), prefs)

fun ReadLater.save(ctx: Context, tree: Uri, url: String, sharedTitle: String?, tags: List<String> = emptyList()): Boolean =
    save(appVault(ctx, tree), url, sharedTitle, tags = tags).also { Widgets.readChanged(ctx) }

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
