package uk.tomlowndes.capture

/** Small helpers for editing markdown notes line by line without disturbing anything we don't own. */
object Md {
    /** Lowercased letters/digits only, so "## 🎯 Do this first" matches "Do this first". */
    fun norm(s: String) = s.filter { it.isLetterOrDigit() }.lowercase()

    /** Index of the closing `---` of the frontmatter block, or -1 if the note has none. */
    private fun fmEnd(lines: List<String>): Int {
        if (lines.isEmpty() || lines[0].trim() != "---") return -1
        for (i in 1 until lines.size) if (lines[i].trim() == "---") return i
        return -1
    }

    /** The note without its frontmatter block. */
    fun fmBody(lines: List<String>): List<String> = lines.drop(fmEnd(lines) + 1)

    fun fmGet(lines: List<String>, key: String): String? {
        val end = fmEnd(lines)
        if (end < 0) return null
        val re = Regex("^${Regex.escape(key)}:\\s*(.*)$")
        for (i in 1 until end) re.find(lines[i].trimEnd())?.let { return it.groupValues[1].trim() }
        return null
    }

    /** Sets `key: value` in the frontmatter, adding the key (or the whole block) if missing. */
    fun fmSet(lines: MutableList<String>, key: String, value: String) {
        val end = fmEnd(lines)
        if (end < 0) {
            lines.addAll(0, listOf("---", "$key: $value", "---", ""))
            return
        }
        val re = Regex("^${Regex.escape(key)}:")
        for (i in 1 until end) if (re.containsMatchIn(lines[i])) { lines[i] = "$key: $value"; return }
        lines.add(end, "$key: $value")
    }

    /** Heading line index for `## name` (ignoring emoji and case), or -1. */
    fun headingIndex(lines: List<String>, name: String): Int {
        val want = norm(name)
        return lines.indexOfFirst { it.startsWith("## ") && norm(it.removePrefix("## ")) == want }
    }

    /** Lines belonging to the `## name` section: from just after the heading to just before the next `## `. */
    fun sectionRange(lines: List<String>, name: String): IntRange? {
        val h = headingIndex(lines, name)
        if (h < 0) return null
        var e = h + 1
        while (e < lines.size && !lines[e].startsWith("## ")) e++
        return (h + 1) until e
    }
}
