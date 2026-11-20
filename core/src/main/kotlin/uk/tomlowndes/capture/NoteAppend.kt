package uk.tomlowndes.capture

import java.time.LocalDate

/**
 * Adding a dated line to an existing note (Tower notes). It only ever inserts lines, so what Obsidian or another
 * device wrote stays as it was. Under a `Log` heading the newest line goes first; elsewhere the line goes at the end
 * of its section, or at the end of the note.
 */
object NoteAppend {
    private val HEADING = Regex("^(#{1,6})\\s+(.+?)\\s*#*\\s*$")

    /** `## ` headings in the note body (not in frontmatter or code), as written, for the "Under" chips. */
    fun headings(lines: List<String>): List<String> = scan(lines).map { "${"#".repeat(it.level)} ${it.name}" }

    private data class Head(val index: Int, val level: Int, val name: String)

    private fun scan(lines: List<String>): List<Head> {
        val out = mutableListOf<Head>()
        var fence = false
        val start = fmEnd(lines)
        for (i in start until lines.size) {
            val l = lines[i]
            if (l.trimStart().startsWith("```") || l.trimStart().startsWith("~~~")) { fence = !fence; continue }
            if (fence) continue
            HEADING.find(l)?.let { out.add(Head(i, it.groupValues[1].length, it.groupValues[2].trim())) }
        }
        return out
    }

    private fun fmEnd(lines: List<String>): Int {
        if (lines.firstOrNull()?.trim() != "---") return 0
        val close = (1 until lines.size).firstOrNull { lines[it].trim() == "---" } ?: return 0
        return close + 1
    }

    /** The lines to add for [text]: a dated bullet, with any further lines indented under it. */
    fun entry(text: String, today: LocalDate): List<String> {
        val ls = text.replace("\r\n", "\n").lines().map { it.trimEnd() }.dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }
        if (ls.isEmpty()) return emptyList()
        val first = ls.first().replace(Regex("^[-*+]\\s+"), "")
        return listOf("- $today $first") + ls.drop(1).filter { it.isNotBlank() }.map { "  $it" }
    }

    /**
     * Inserts the entry for [text] into [lines] under [heading] ("## Log", "Log" or null for the end of the note).
     * A heading that isn't there is added at the end. Returns the lines added, or null when they are already there
     * (a retry), so nothing is written twice.
     */
    fun insert(lines: MutableList<String>, heading: String?, text: String, today: LocalDate): List<String>? {
        val add = entry(text, today)
        if (add.isEmpty()) return null
        if (containsBlock(lines, add)) return null
        val name = heading?.replace(Regex("^#+\\s*"), "")?.trim()?.takeIf { it.isNotEmpty() }
        val heads = scan(lines)
        val h = name?.let { n -> heads.firstOrNull { it.name.equals(n, ignoreCase = true) } }
        when {
            name == null -> appendEnd(lines, add)
            h == null -> { appendEnd(lines, listOf("## $name") + add, blankBefore = true) }
            h.name.equals("log", ignoreCase = true) -> lines.addAll(h.index + 1, add)
            else -> {
                val end = heads.firstOrNull { it.index > h.index && it.level <= h.level }?.index ?: lines.size
                var at = end
                while (at > h.index + 1 && lines[at - 1].isBlank()) at--
                lines.addAll(at, add)
            }
        }
        return add
    }

    /** Takes out a block [insert] added, if it is still there. */
    fun remove(lines: MutableList<String>, block: List<String>): Boolean {
        val i = indexOfBlock(lines, block)
        if (i < 0) return false
        repeat(block.size) { lines.removeAt(i) }
        return true
    }

    private fun appendEnd(lines: MutableList<String>, add: List<String>, blankBefore: Boolean = false) {
        while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.size - 1)
        if (blankBefore && lines.isNotEmpty()) lines.add("")
        lines.addAll(add)
        lines.add("")
    }

    private fun containsBlock(lines: List<String>, block: List<String>) = indexOfBlock(lines, block) >= 0

    private fun indexOfBlock(lines: List<String>, block: List<String>): Int {
        if (block.isEmpty() || lines.size < block.size) return -1
        for (i in 0..lines.size - block.size) {
            if (block.indices.all { lines[i + it].trimEnd() == block[it] }) return i
        }
        return -1
    }

    /**
     * The note [name] most likely means, from the notes' titles: an exact title first, then titles that start with it,
     * then titles that contain every word. Project notes win a tie, then the shorter path. Null when nothing fits.
     */
    fun find(all: List<NoteInfo>, name: String): NoteInfo? {
        val q = name.trim().removeSurrounding("[[", "]]").lowercase()
        if (q.isEmpty()) return null
        val words = q.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val visible = all.filter { !VaultBrowse.isHidden(it.path.substringBefore('/')) }
        fun pick(list: List<NoteInfo>) = list.minWithOrNull(compareBy<NoteInfo>({ if (it.path.startsWith("03 - Projects")) 0 else 1 }, { it.path.length }))
        pick(visible.filter { it.title.lowercase() == q })?.let { return it }
        pick(visible.filter { it.title.lowercase().startsWith(q) })?.let { return it }
        return pick(visible.filter { n -> words.all { it in n.title.lowercase() } })
    }
}
