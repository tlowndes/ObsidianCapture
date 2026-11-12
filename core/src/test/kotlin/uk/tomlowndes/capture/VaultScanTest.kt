package uk.tomlowndes.capture

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Reusing the vault's note list for a while (Android, where walking every folder is slow). */
class VaultScanTest {
    @get:Rule val tmp = TemporaryFolder()

    /** A folder vault that counts its folder listings. */
    private class Counting(root: File) : Vault by FileVault(root) {
        var lists = 0
        private val inner = FileVault(root)
        override fun list(dir: String): List<Vault.Entry> { lists++; return inner.list(dir) }
        override fun notes(skip: (String) -> Boolean): List<VaultNote> {
            val out = mutableListOf<VaultNote>()
            fun walk(dir: String) {
                for (e in list(dir)) {
                    val p = if (dir.isEmpty()) e.name else "$dir/${e.name}"
                    if (e.isDir) { if (!skip(p)) walk(p) } else if (e.name.endsWith(".md")) out.add(VaultNote(p, e.modified))
                }
            }
            walk("")
            return out
        }
    }

    private fun note(path: String, text: String) = File(tmp.root, path).apply { parentFile.mkdirs(); writeText(text) }

    @Before fun start() = VaultScan.reset()
    @After fun end() = VaultScan.reset()

    @Test fun `inside the TTL the vault is not walked again`() {
        note("Projects/A.md", "- [ ] #task One\n")
        val v = Counting(tmp.root)
        assertEquals(listOf("One"), VaultScan.tasks(v, null, 60_000, now = 1_000).map { it.text })
        val after = v.lists
        assertEquals(listOf("One"), VaultScan.tasks(v, null, 60_000, now = 30_000).map { it.text })
        assertEquals(after, v.lists)
    }

    @Test fun `after the TTL the vault is walked again and new notes show`() {
        note("Projects/A.md", "- [ ] #task One\n")
        val v = Counting(tmp.root)
        VaultScan.tasks(v, null, 60_000, now = 1_000)
        note("Projects/B.md", "- [ ] #task Two\n") // e.g. Obsidian Sync
        assertEquals(listOf("One"), VaultScan.tasks(v, null, 60_000, now = 2_000).map { it.text })
        assertEquals(setOf("One", "Two"), VaultScan.tasks(v, null, 60_000, now = 70_000).map { it.text }.toSet())
    }

    @Test fun `a note the app writes shows at once, even inside the TTL`() {
        note("Projects/A.md", "- [ ] #task One\n")
        val v = Counting(tmp.root)
        VaultScan.tasks(v, null, 60_000, now = 1_000)
        VaultIo.edit(v, "Projects/A.md", wait = {}) { it.add(0, "- [ ] #task Zero") }
        VaultIo.write(v, "Projects/New.md".also { note(it, "") }, "- [ ] #task Fresh\n", wait = {})
        val walked = v.lists
        assertEquals(setOf("Zero", "One", "Fresh"), VaultScan.tasks(v, null, 60_000, now = 2_000).map { it.text }.toSet())
        assertEquals(walked, v.lists)
    }

    @Test fun `invalidate walks again`() {
        note("Projects/A.md", "- [ ] #task One\n")
        val v = Counting(tmp.root)
        VaultScan.tasks(v, null, 60_000, now = 1_000)
        note("Projects/B.md", "- [ ] #task Two\n")
        VaultScan.invalidate()
        assertEquals(setOf("One", "Two"), VaultScan.tasks(v, null, 60_000, now = 2_000).map { it.text }.toSet())
    }

    @Test fun `with no TTL every scan walks, as on the desktop`() {
        note("Projects/A.md", "- [ ] #task One\n")
        val v = Counting(tmp.root)
        VaultScan.tasks(v, null, now = 1_000)
        val first = v.lists
        note("Projects/B.md", "- [ ] #task Two\n")
        assertEquals(2, VaultScan.tasks(v, null, now = 1_001).size)
        assertEquals(first * 2, v.lists)
    }
}
