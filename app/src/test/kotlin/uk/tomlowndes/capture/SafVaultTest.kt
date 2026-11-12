package uk.tomlowndes.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

/** [SafVault] against an in-memory document tree, including the copy-and-delete move used when a provider can't move. */
class SafVaultTest {
    private fun vault(root: MemDoc = MemDoc("vault")) = SafVault(root) to root

    @Test fun `reading a missing file is null and writing one fails`() {
        val (v, _) = vault()
        assertNull(v.read("missing.md"))
        try {
            v.writeOnce("missing.md", "x")
            fail("write should have failed")
        } catch (e: IllegalStateException) {
            assertEquals("missing.md not found", e.message)
        }
    }

    @Test fun `read stops at the character limit`() {
        val (v, root) = vault()
        val path = v.create("", "Hello", "md")
        root.findChild("Hello.md")!!.writeBytes("hello there".toByteArray())
        assertEquals("hell", v.read(path, 4))
        assertEquals("hello there", v.read(path))
    }

    @Test fun `create picks the mime name and a free one when it's taken`() {
        val (v, _) = vault()
        assertEquals("notes/Hello.md", v.create("notes", "Hello", "md"))
        assertEquals("notes/Hello (1).md", v.create("notes", "Hello", "md"))
        assertEquals("photo.jpg", v.create("", "photo", "jpg"))
        assertEquals("cfg.json", v.create("", "cfg", "json"))
        assertTrue(v.entry("notes")!!.isDir)
    }

    @Test fun `notes walk skips dot files and folders the caller rejects`() {
        val (v, _) = vault()
        v.create("Keep", "Yes", "md")
        v.create("Keep", ".secret", "md")
        v.create("Skip", "No", "md")
        v.create("", "Root", "md")
        val paths = v.notes { it == "Skip" }.map { it.path }.sorted()
        assertEquals(listOf("Keep/Yes.md", "Root.md"), paths)
    }

    @Test fun `list of a missing folder is empty`() {
        val (v, _) = vault()
        assertEquals(emptyList<Vault.Entry>(), v.list("nope"))
    }

    @Test fun `a move the provider can do changes the path and removes the original`() {
        val (v, root) = vault()
        v.writeOnce(v.create("", "Note", "md"), "body")
        assertEquals("Archive/Note.md", v.move("Note.md", "Archive"))
        assertNull(root.findChild("Note.md"))
        assertEquals("body", v.read("Archive/Note.md"))
    }

    @Test fun `a move the provider refuses copies the bytes and then deletes the original`() {
        val (v, root) = vault()
        root.allowMove = false
        v.writeOnce(v.create("in", "Note", "md"), "body")
        assertEquals("out/Note.md", v.move("in/Note.md", "out"))
        assertNull(v.entry("in/Note.md"))
        assertEquals("body", v.read("out/Note.md"))
    }

    @Test fun `a copy that doesn't read back is not followed by deleting the original`() {
        val (v, root) = vault()
        root.allowMove = false
        v.writeOnce(v.create("", "Note", "md"), "body")
        root.sabotageNewFileReads = true
        try {
            v.move("Note.md", "out")
            fail("move should have failed")
        } catch (e: IllegalStateException) {
            assertEquals("Cannot write Note.md", e.message)
        }
        assertEquals("body", v.read("Note.md"))
    }

    @Test fun `a write swaps in a finished copy and leaves no scratch file`() {
        val (v, root) = vault()
        v.writeOnce(v.create("", "Note", "md"), "one")
        v.writeOnce("Note.md", "two")
        assertEquals("two", v.read("Note.md"))
        assertEquals(listOf("Note.md"), root.children().map { it.name })
        assertEquals(listOf("Note.md"), v.list("").map { it.name })
    }

    @Test fun `a failed swap puts the original note back`() {
        val (v, root) = vault()
        v.writeOnce(v.create("", "Note", "md"), "safe")
        root.failTempPromote = true
        try {
            v.writeOnce("Note.md", "new")
            fail("write should have failed")
        } catch (e: IllegalStateException) {
            assertEquals("Cannot write Note.md", e.message)
        }
        assertEquals("safe", v.read("Note.md"))
        assertEquals(listOf("Note.md"), root.children().map { it.name })
    }

    @Test fun `a backup left behind is restored on the next look`() {
        val (v, root) = vault()
        v.writeOnce(v.create("", "Note", "md"), "safe")
        val note = root.findChild("Note.md")!!
        note.rename(".Note.md.capture-bak")
        assertEquals("safe", v.read("Note.md"))
        assertEquals(listOf("Note.md"), v.list("").map { it.name })
    }
}

/** In-memory stand-in for a SAF document. [allowMove] false forces the copy fallback. */
internal class MemDoc(
    override var name: String,
    private val top: MemDoc? = null,
) : VaultDoc {
    private val kids = mutableListOf<MemDoc>()
    private var bytes = ByteArray(0)
    private var parent: MemDoc? = null
    override var isDirectory: Boolean = true
    override var modified: Long = 1L
    override val length get() = if (isDirectory) 0L else bytes.size.toLong()
    var allowMove = true
    /** The temp copy refuses to take the real file's name, so the swap must restore the original. */
    var failTempPromote = false
    /** New files report different bytes than were written, so a move must not delete the original. */
    var sabotageNewFileReads = false
    private var sabotageReads = false

    private val root get() = top ?: this

    override fun findChild(name: String): VaultDoc? = kids.find { it.name == name }

    override fun children(): List<VaultDoc> = kids.toList()

    override fun mkdir(name: String): VaultDoc = add(MemDoc(name, root).apply { isDirectory = true })

    override fun createFile(mime: String, displayName: String): VaultDoc {
        val raw = when (mime) {
            "text/markdown" -> if (displayName.endsWith(".md")) displayName else "$displayName.md"
            "image/jpeg" -> if (displayName.endsWith(".jpg") || displayName.endsWith(".jpeg")) displayName else "$displayName.jpg"
            else -> displayName
        }
        return add(MemDoc(unique(raw), root).apply {
            isDirectory = false
            sabotageReads = root.sabotageNewFileReads
        })
    }

    override fun open(): InputStream? = ByteArrayInputStream(if (sabotageReads) byteArrayOf(7) else bytes)

    override fun writeBytes(bytes: ByteArray) { this.bytes = bytes.copyOf(); modified += 1 }

    override fun delete() { parent?.kids?.remove(this) }

    override fun rename(displayName: String): VaultDoc? {
        val p = parent ?: return null
        if (root.failTempPromote && name.contains(".capture-tmp")) return null
        if (p.kids.any { it !== this && it.name == displayName }) return null
        name = displayName
        modified += 1
        return this
    }

    override fun moveInto(fromDir: VaultDoc, targetDir: VaultDoc): VaultDoc? {
        if (!root.allowMove) return null
        val from = fromDir as MemDoc
        val to = targetDir as MemDoc
        if (!from.kids.remove(this)) return null
        parent = to
        to.kids += this
        return this
    }

    override fun handle(): Any = name

    private fun add(child: MemDoc): MemDoc {
        child.parent = this
        kids += child
        return child
    }

    private fun unique(base: String): String {
        if (kids.none { it.name == base }) return base
        val dot = base.lastIndexOf('.')
        val stem = if (dot > 0) base.substring(0, dot) else base
        val ext = if (dot > 0) base.substring(dot) else ""
        var n = 1
        while (kids.any { it.name == "$stem ($n)$ext" }) n++
        return "$stem ($n)$ext"
    }
}
