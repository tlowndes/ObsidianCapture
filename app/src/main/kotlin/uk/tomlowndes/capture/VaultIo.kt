package uk.tomlowndes.capture

import android.content.Context
import androidx.documentfile.provider.DocumentFile

/**
 * Reads and writes vault notes while a sync tool (Resilio, Syncthing...) may be changing the same files.
 * Resilio keeps whichever copy was written last, so the app must never write over a version it hasn't seen,
 * and must never leave a half-written note behind for the sync tool to pick up.
 */
object VaultIo {
    private const val ATTEMPTS = 4

    fun read(ctx: Context, f: DocumentFile): String =
        ctx.contentResolver.openInputStream(f.uri)?.bufferedReader()?.use { it.readText() }.orEmpty()

    private fun writeOnce(ctx: Context, f: DocumentFile, text: String) {
        ctx.contentResolver.openOutputStream(f.uri, "wt")?.use { it.write(text.toByteArray()); it.flush() }
            ?: error("Cannot write ${f.name}")
    }

    /**
     * Writes [text] in one go and reads it back. If only part of it landed, writes it again.
     * Returns what is in the file afterwards: [text], or a newer version another device wrote on top.
     */
    fun write(ctx: Context, f: DocumentFile, text: String): String {
        repeat(ATTEMPTS) { n ->
            writeOnce(ctx, f, text)
            val after = read(ctx, f)
            if (after == text) return after
            if (after.isNotEmpty() && !text.startsWith(after)) return after
            Thread.sleep(100L * (n + 1))
        }
        error("Couldn't save ${f.name}, try again")
    }

    /**
     * Read-modify-write of a note: [change] edits the current lines in place and should only touch the part it owns.
     * Just before writing the note is read again; if it changed in the meantime (a sync landed), [change] is
     * redone on the new copy instead of writing over it. [change] can throw to stop when the bit it edits is gone.
     */
    fun edit(ctx: Context, f: DocumentFile, change: (MutableList<String>) -> Unit) {
        var current = read(ctx, f)
        repeat(ATTEMPTS) {
            val lines = current.split("\n").toMutableList()
            change(lines)
            val out = lines.joinToString("\n").let { if (it.endsWith("\n")) it else it + "\n" }
            if (out == current) return
            val latest = read(ctx, f)
            if (latest != current) { current = latest; return@repeat }
            val after = write(ctx, f, out)
            if (after == out) return
            current = after
        }
        error("${f.name} kept changing on another device, try again")
    }
}
