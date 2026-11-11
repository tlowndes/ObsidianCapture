package uk.tomlowndes.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Sync landing in the middle of a write: partial writes are retried, a newer note is not overwritten. */
class VaultIoTest {
    private val none: (Int) -> Unit = {}

    @Test fun `a write that lands fully is returned as it was written`() {
        val v = Script()
        assertEquals("hello\n", VaultIo.write(v, "Note.md", "hello\n", none))
        assertEquals(listOf("hello\n"), v.written)
    }

    @Test fun `a partial write is retried until the whole note is there`() {
        val v = Script(partials = 2)
        val waits = mutableListOf<Int>()
        assertEquals("hello\n", VaultIo.write(v, "Note.md", "hello\n") { waits += it })
        assertEquals(3, v.written.size)
        assertEquals(listOf("h", "h", "hello\n"), v.stored)
        assertEquals(listOf(0, 1), waits)
    }

    @Test fun `an empty read-back is treated as a partial write and retried`() {
        val v = Script(empties = 1)
        assertEquals("hello\n", VaultIo.write(v, "Note.md", "hello\n", none))
        assertEquals(2, v.written.size)
    }

    @Test fun `a different note written by another device is kept`() {
        val v = Script(foreign = "theirs\n")
        assertEquals("theirs\n", VaultIo.write(v, "Note.md", "ours\n", none))
        assertEquals(1, v.written.size)
        assertEquals("theirs\n", v.text)
    }

    @Test fun `giving up after four partial writes says which note`() {
        val v = Script(partials = 10)
        try {
            VaultIo.write(v, "01 - Inbox/Note.md", "hello\n", none)
            fail("write should have given up")
        } catch (e: IllegalStateException) {
            assertEquals("Couldn't save Note.md, try again", e.message)
        }
        assertEquals(4, v.written.size)
    }

    @Test fun `an edit that changes nothing does not write`() {
        val v = Script(start = "same\n")
        VaultIo.edit(v, "Note.md", none) { }
        assertEquals(emptyList<String>(), v.written)
    }

    @Test fun `an edit is redone when a sync lands before the write`() {
        val v = Script(reads = ArrayDeque(listOf("a", "b", "b")))
        VaultIo.edit(v, "Note.md", none) { lines -> lines[0] = lines[0] + "!" }
        assertEquals("b!\n", v.written.single())
    }

    @Test fun `an edit is redone when the write comes back as someone else's note`() {
        val v = Script(start = "a", foreign = "b")
        VaultIo.edit(v, "Note.md", none) { lines -> lines[0] = lines[0] + "!" }
        // First write is rejected (the file became "b"); the change is applied to that and written.
        assertEquals(listOf("a!\n", "b!\n"), v.written)
        assertEquals("b!\n", v.text)
    }

    @Test fun `an edit that keeps losing the race says the note kept changing`() {
        val v = Script(start = "a", alwaysForeign = true)
        try {
            VaultIo.edit(v, "Tasks/Note.md", none) { lines -> lines[0] = lines[0] + "!" }
            fail("edit should have given up")
        } catch (e: IllegalStateException) {
            assertEquals("Note.md kept changing on another device, try again", e.message)
        }
    }

    @Test fun `an edit never writes over a note that reads blank but has bytes on disk`() {
        val v = Script(reads = ArrayDeque(List(10) { "" }), size = 6388)
        try {
            VaultIo.edit(v, "01 - Inbox/⚡ Quick Task.md", none) { it.add("- [ ] #task new") }
            fail("edit should have refused")
        } catch (e: IllegalStateException) {
            assertEquals("Couldn't read ⚡ Quick Task.md, try again", e.message)
        }
        assertEquals(emptyList<String>(), v.written)
    }

    @Test fun `a blank read mid-sync is read again before editing`() {
        val v = Script(reads = ArrayDeque(listOf("", "## Active\n- [ ] a\n", "## Active\n- [ ] a\n")), size = 20)
        VaultIo.edit(v, "Note.md", none) { it.add(2, "- [ ] b") }
        assertEquals("## Active\n- [ ] a\n- [ ] b\n", v.written.single())
    }

    @Test fun `a note that really is empty can still be edited`() {
        val v = Script(start = "")
        VaultIo.edit(v, "Note.md", none) { it.add("- [ ] a") }
        assertEquals("\n- [ ] a\n", v.written.single())
    }

    /**
     * Reads come off [reads] when it isn't empty, otherwise the last thing written (or [start]).
     * The first [partials] writes store only a prefix; the first [empties] store nothing.
     */
    private class Script(
        private val start: String? = null,
        private val reads: ArrayDeque<String> = ArrayDeque(),
        private var partials: Int = 0,
        private var empties: Int = 0,
        private val foreign: String? = null,
        private val alwaysForeign: Boolean = false,
        private val size: Long? = null,
    ) : Vault {
        val written = mutableListOf<String>()
        val stored = mutableListOf<String>()
        var text: String? = start

        override fun read(path: String, max: Int): String? {
            val raw = if (reads.isNotEmpty()) reads.removeFirst() else text
            return raw?.let { if (max == Int.MAX_VALUE) it else it.take(max) }
        }

        override fun writeOnce(path: String, text: String) {
            written += text
            this.text = when {
                alwaysForeign -> "other ${written.size}\n"
                foreign != null && written.size == 1 -> foreign
                empties > 0 -> { empties--; "" }
                partials > 0 -> { partials--; text.take(1) }
                else -> text
            }
            stored += this.text!!
        }

        override fun writeBytes(path: String, bytes: ByteArray) = writeOnce(path, bytes.toString(Charsets.UTF_8))
        override fun entry(path: String): Vault.Entry? = size?.let { Vault.Entry(path, false, 0L, it) }
        override fun list(dir: String): List<Vault.Entry> = emptyList()
        override fun mkdirs(dir: String) {}
        override fun create(dir: String, base: String, ext: String) = "$base.$ext"
        override fun move(path: String, toDir: String) = path
        override fun delete(path: String) {}
        override fun handle(path: String): Any = path
    }
}
