package uk.tomlowndes.capture

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** A vault that is a plain folder on disk (the desktop app, and tests). */
class FileVault(val root: File) : Vault {
    private fun f(path: String) = if (path.isEmpty()) root else File(root, path)

    override fun read(path: String, max: Int): String? {
        val file = f(path).takeIf { it.isFile } ?: return null
        if (max == Int.MAX_VALUE) return file.readText()
        return file.bufferedReader().use { r ->
            val buf = CharArray(max)
            var n = 0
            while (n < max) { val k = r.read(buf, n, max - n); if (k < 0) break; n += k }
            String(buf, 0, n)
        }
    }

    override fun writeOnce(path: String, text: String) = writeBytes(path, text.toByteArray())

    /** Writes a temporary file beside the note and swaps it in, so a sync tool never sees half a note. */
    override fun writeBytes(path: String, bytes: ByteArray) {
        val target = f(path)
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, ".${target.name}.capture-tmp")
        tmp.writeBytes(bytes)
        try {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            tmp.delete()
        }
    }

    override fun entry(path: String): Vault.Entry? {
        val file = f(path).takeIf { it.exists() } ?: return null
        return Vault.Entry(file.name, file.isDirectory, file.lastModified(), if (file.isFile) file.length() else 0L)
    }

    override fun list(dir: String): List<Vault.Entry> =
        f(dir).listFiles().orEmpty().filter { !it.name.endsWith(".capture-tmp") }
            .map { Vault.Entry(it.name, it.isDirectory, it.lastModified(), if (it.isFile) it.length() else 0L) }

    override fun mkdirs(dir: String) {
        val d = f(dir)
        if (!d.isDirectory && !d.mkdirs()) error("Cannot create ${d.name}")
    }

    private fun free(dir: File, base: String, ext: String): File {
        val suffix = if (ext.isEmpty()) "" else ".$ext"
        var n = 0
        while (true) {
            val candidate = File(dir, if (n == 0) "$base$suffix" else "$base ($n)$suffix")
            if (!candidate.exists()) return candidate
            n++
        }
    }

    override fun create(dir: String, base: String, ext: String): String {
        mkdirs(dir)
        while (true) {
            val file = free(f(dir), base, ext)
            if (file.createNewFile()) return join(dir, file.name)
        }
    }

    override fun move(path: String, toDir: String): String {
        val src = f(path)
        mkdirs(toDir)
        val name = src.name
        val target = free(f(toDir), name.substringBeforeLast('.'), name.substringAfterLast('.', ""))
        Files.move(src.toPath(), target.toPath())
        return join(toDir, target.name)
    }

    override fun delete(path: String) { f(path).delete() }

    override fun handle(path: String): Any = f(path)
}
