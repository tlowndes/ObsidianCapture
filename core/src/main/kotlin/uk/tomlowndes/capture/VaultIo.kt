package uk.tomlowndes.capture

/**
 * Reads and writes vault notes while a sync tool (Obsidian Sync, Resilio...) may be changing the same files.
 * The app must never write over a version it hasn't seen, and must never leave a half-written note behind
 * for the sync tool to pick up and merge.
 */
object VaultIo {
    private const val ATTEMPTS = 4

    fun read(v: Vault, path: String): String = v.read(path).orEmpty()

    /**
     * Writes [text] in one go and reads it back. If only part of it landed, writes it again.
     * Returns what is in the file afterwards: [text], or a newer version another device wrote on top.
     */
    fun write(v: Vault, path: String, text: String): String {
        repeat(ATTEMPTS) { n ->
            v.writeOnce(path, text)
            val after = read(v, path)
            if (after == text) return after
            if (after.isNotEmpty() && !text.startsWith(after)) return after
            Thread.sleep(100L * (n + 1))
        }
        error("Couldn't save ${nameOf(path)}, try again")
    }

    /**
     * Read-modify-write of a note: [change] edits the current lines in place and should only touch the part it owns.
     * Just before writing the note is read again; if it changed in the meantime (a sync landed), [change] is
     * redone on the new copy instead of writing over it. [change] can throw to stop when the bit it edits is gone.
     */
    fun edit(v: Vault, path: String, change: (MutableList<String>) -> Unit) {
        var current = read(v, path)
        repeat(ATTEMPTS) {
            val lines = current.split("\n").toMutableList()
            change(lines)
            val out = lines.joinToString("\n").let { if (it.endsWith("\n")) it else it + "\n" }
            if (out == current) return
            val latest = read(v, path)
            if (latest != current) { current = latest; return@repeat }
            val after = write(v, path, out)
            if (after == out) return
            current = after
        }
        error("${nameOf(path)} kept changing on another device, try again")
    }
}
