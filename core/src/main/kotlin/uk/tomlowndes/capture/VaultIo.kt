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
     * [read] for a note about to be edited. A blank read is checked again, because a note mid-sync can be empty
     * for a moment and a failed open looks the same. If the file still has bytes on disk but keeps reading blank,
     * this throws rather than let an edit write a near-empty note over it (2026-11-11: Quick Task lost every task).
     */
    fun readForEdit(v: Vault, path: String, wait: (Int) -> Unit = ::pause): String {
        var text = read(v, path)
        var n = 0
        while (text.isEmpty() && n < ATTEMPTS) {
            val size = v.entry(path)?.size ?: 0L
            if (size <= 0L && n > 0) return text
            wait(n++)
            text = read(v, path)
        }
        if (text.isEmpty() && (v.entry(path)?.size ?: 0L) > 0L) error("Couldn't read ${nameOf(path)}, try again")
        return text
    }

    /**
     * Writes [text] in one go and reads it back. If only part of it landed, writes it again.
     * Returns what is in the file afterwards: [text], or a newer version another device wrote on top.
     * [wait] runs before another try (the attempt number, starting at 0). Tests pass a no-op.
     */
    fun write(v: Vault, path: String, text: String, wait: (Int) -> Unit = ::pause): String {
        VaultScan.touched(path)
        repeat(ATTEMPTS) { n ->
            v.writeOnce(path, text)
            val after = read(v, path)
            if (after == text) return after
            if (after.isNotEmpty() && !text.startsWith(after)) return after
            wait(n)
        }
        error("Couldn't save ${nameOf(path)}, try again")
    }

    /**
     * Read-modify-write of a note: [change] edits the current lines in place and should only touch the part it owns.
     * Just before writing the note is read again; if it changed in the meantime (a sync landed), [change] is
     * redone on the new copy instead of writing over it. [change] can throw to stop when the bit it edits is gone.
     */
    fun edit(v: Vault, path: String, wait: (Int) -> Unit = ::pause, change: (MutableList<String>) -> Unit) {
        var current = readForEdit(v, path, wait)
        repeat(ATTEMPTS) {
            val lines = current.split("\n").toMutableList()
            change(lines)
            val out = lines.joinToString("\n").let { if (it.endsWith("\n")) it else it + "\n" }
            if (out == current) return
            val latest = if (current.isEmpty()) readForEdit(v, path, wait) else read(v, path)
            if (latest != current) { current = latest; return@repeat }
            val after = write(v, path, out, wait)
            if (after == out) return
            current = after
        }
        error("${nameOf(path)} kept changing on another device, try again")
    }

    private fun pause(attempt: Int) { Thread.sleep(100L * (attempt + 1)) }
}
