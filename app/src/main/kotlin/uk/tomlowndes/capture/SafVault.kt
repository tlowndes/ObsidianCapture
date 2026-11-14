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
    /** Renames this document inside its folder, or null if the provider refuses. */
    fun rename(displayName: String): VaultDoc?
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
        heal(path)
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

    /**
     * Writes the full bytes to a sibling first, then swaps the names, the same idea as [FileVault].
     * The original note stays intact until the new copy has been read back. A crash in the middle
     * leaves a `.capture-bak` which the next read puts back. The Storage Access Framework has no
     * atomic replace, so this is as close as the folder API allows.
     */
    override fun writeBytes(path: String, bytes: ByteArray) {
        heal(path)
        val target = find(path) ?: error("${nameOf(path)} not found")
        if (target.isDirectory) error("${nameOf(path)} is a folder")
        val folder = folderOf(path) ?: error("Cannot write ${nameOf(path)}")
        val name = target.name
        val tmpName = ".$name.capture-tmp"
        val bakName = ".$name.capture-bak"
        folder.findChild(tmpName)?.delete()
        val tmp = folder.createFile("application/octet-stream", tmpName) ?: error("Cannot write $name")
        val made = tmp.name
        try {
            tmp.writeBytes(bytes)
            val back = tmp.open()?.use { it.readBytes() }
            if (back == null || !back.contentEquals(bytes)) error("Cannot write $name")
            val bak = target.rename(bakName) ?: error("Cannot write $name")
            if (tmp.rename(name) == null) {
                bak.rename(name)
                error("Cannot write $name")
            }
            bak.delete()
        } catch (e: Exception) {
            folder.findChild(made)?.delete()
            if (find(path) == null) folder.findChild(bakName)?.rename(name)
            throw e
        }
    }

    /** Puts a note back if a swap stopped halfway, and drops leftover temp files once the real name is there. */
    private fun heal(path: String) {
        val name = nameOf(path)
        if (name.isEmpty()) return
        val folder = folderOf(path) ?: return
        val bak = folder.findChild(".$name.capture-bak")
        val tmp = folder.findChild(".$name.capture-tmp")
        if (folder.findChild(name) != null) {
            bak?.delete()
            tmp?.delete()
        } else if (bak != null) {
            bak.rename(name)
            tmp?.delete()
        }
    }

    private fun folderOf(path: String): VaultDoc? {
        val parent = path.substringBeforeLast('/', "")
        if (parent.isEmpty()) return root
        return find(parent)?.takeIf { it.isDirectory }
    }

    private fun scratch(name: String) = name.endsWith(".capture-tmp") || name.endsWith(".capture-bak")

    /**
     * Restores any note in [folder] that was left under a backup name, then drops leftover scratch files.
     * Returns the folder's children (one query, or a second one only when something was healed).
     */
    private fun healFolder(folder: VaultDoc, kids: List<VaultDoc> = folder.children()): List<VaultDoc> {
        val names = kids.map { it.name }.toSet()
        var changed = false
        for (n in names) {
            if (!n.endsWith(".capture-bak") || !n.startsWith(".")) continue
            val real = n.removePrefix(".").removeSuffix(".capture-bak")
            if (real.isEmpty()) continue
            if (real !in names) folder.findChild(n)?.rename(real) else folder.findChild(n)?.delete()
            changed = true
        }
        val tmps = kids.filter { it.name.endsWith(".capture-tmp") }
        tmps.forEach { it.delete() }
        return if (changed || tmps.isNotEmpty()) folder.children() else kids
    }

    override fun entry(path: String): Vault.Entry? =
        find(path)?.let { Vault.Entry(it.name, it.isDirectory, it.modified, it.length) }

    override fun list(dir: String): List<Vault.Entry> {
        val d = find(dir)?.takeIf { it.isDirectory } ?: return emptyList()
        return healFolder(d).filter { !scratch(it.name) }.map { Vault.Entry(it.name, it.isDirectory, it.modified, it.length) }
    }

    override fun notes(skip: (String) -> Boolean): List<VaultNote> {
        // One children query per folder, and the top-level folders are listed side by side (the provider is slow per call).
        val dirs = mutableListOf<Pair<VaultDoc, String>>()
        val out = java.util.Collections.synchronizedList(mutableListOf<VaultNote>())
        fun walk(doc: VaultDoc, prefix: String, top: Boolean) {
            val sub = mutableListOf<Pair<VaultDoc, String>>()
            for (e in healFolder(doc)) {
                val path = prefix + e.name
                if (e.isDirectory) { if (!skip(path)) sub.add(e to "$path/") }
                else if (e.name.endsWith(".md") && !e.name.startsWith(".") && !scratch(e.name)) out.add(VaultNote(path, e.modified))
            }
            if (top) dirs.addAll(sub) else sub.forEach { (d, p) -> walk(d, p, false) }
        }
        walk(root, "", true)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(minOf(4, maxOf(1, dirs.size)))
        try {
            dirs.map { (d, p) -> pool.submit { walk(d, p, false) } }.forEach { it.get() }
        } catch (e: java.util.concurrent.ExecutionException) {
            throw (e.cause ?: e)
        } finally { pool.shutdown() }
        return out.toList().sortedBy { it.path }
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
    return AndroidDoc(ctx, tree, DocumentsContract.getTreeDocumentId(tree), root.uri, root.name ?: "", true, root.lastModified(), root.length())
}

private val DOC_COLS = arrayOf(
    DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
    DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_LAST_MODIFIED,
    DocumentsContract.Document.COLUMN_SIZE
)

/**
 * A SAF document, read straight from DocumentsContract queries. DocumentFile.findFile lists a folder and then asks
 * again for every child's name (one query each), so finding "01 - Inbox/⚡ Quick Task.md" cost a query per file in
 * the vault root and the inbox; [findChild] here is one query per folder.
 */
private class AndroidDoc(
    private val ctx: Context,
    private val tree: Uri,
    private val docId: String,
    private val uri: Uri,
    override val name: String,
    override val isDirectory: Boolean,
    override val modified: Long,
    override val length: Long,
) : VaultDoc {
    private val cr get() = ctx.contentResolver

    override fun findChild(name: String): VaultDoc? = children().firstOrNull { it.name == name }

    override fun children(): List<VaultDoc> {
        val out = mutableListOf<VaultDoc>()
        cr.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId), DOC_COLS, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val childName = c.getString(1) ?: continue
                val isDir = c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
                val childUri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                out.add(AndroidDoc(ctx, tree, id, childUri, childName, isDir, c.getLong(3), if (c.isNull(4)) 0L else c.getLong(4)))
            }
        }
        return out
    }

    override fun mkdir(name: String): VaultDoc? = make(DocumentsContract.Document.MIME_TYPE_DIR, name)

    override fun createFile(mime: String, displayName: String): VaultDoc? = make(mime, displayName)

    private fun make(mime: String, displayName: String): VaultDoc? {
        val made = runCatching { DocumentsContract.createDocument(cr, uri, mime, displayName) }.getOrNull() ?: return null
        return single(made)
    }

    /** The document at [doc] (a tree document Uri), with its name as the provider settled it. */
    private fun single(doc: Uri): VaultDoc? {
        val id = DocumentsContract.getDocumentId(doc)
        cr.query(doc, DOC_COLS, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val isDir = c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
                return AndroidDoc(ctx, tree, id, doc, c.getString(1) ?: "", isDir, c.getLong(3), if (c.isNull(4)) 0L else c.getLong(4))
            }
        }
        return null
    }

    override fun open(): InputStream? = cr.openInputStream(uri)

    override fun writeBytes(bytes: ByteArray) {
        cr.openOutputStream(uri, "wt")?.use { it.write(bytes); it.flush() } ?: error("Cannot write $name")
    }

    override fun delete() { DocumentsContract.deleteDocument(cr, uri) }

    override fun moveInto(fromDir: VaultDoc, targetDir: VaultDoc): VaultDoc? {
        val fromUri = (fromDir as AndroidDoc).uri
        val toUri = (targetDir as AndroidDoc).uri
        val moved = runCatching { DocumentsContract.moveDocument(cr, uri, fromUri, toUri) }.getOrNull() ?: return null
        return single(moved) ?: AndroidDoc(ctx, tree, DocumentsContract.getDocumentId(moved), moved, name, false, 0L, length)
    }

    override fun rename(displayName: String): VaultDoc? {
        val renamed = runCatching { DocumentsContract.renameDocument(cr, uri, displayName) }.getOrNull() ?: return null
        return single(renamed) ?: AndroidDoc(ctx, tree, DocumentsContract.getDocumentId(renamed), renamed, displayName, isDirectory, modified, length)
    }

    override fun handle(): Any = uri
}

// ---- the shared stores, set up for Android: the picked folder, the cache folder, and widget refreshes ----

/** How long Android reuses the vault's note list before walking every folder again (the app's own writes show at once). */
const val TASK_WALK_TTL_MS = 120_000L

fun VaultWriter(ctx: Context, tree: Uri) =
    VaultWriter(appVault(ctx, tree), File(ctx.cacheDir, "vault-tasks.json"), { Widgets.tasksChanged(ctx) }, TASK_WALK_TTL_MS,
        guardDir = if (AppPrefs(ctx).quickGuard) File(ctx.filesDir, "quick-task-copies") else null)

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
