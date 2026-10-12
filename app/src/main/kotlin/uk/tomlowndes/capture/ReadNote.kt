package uk.tomlowndes.capture

import java.time.LocalDateTime
import java.time.ZoneId

/**
 * How a Read It Later note is read and edited (status and saved time in frontmatter, highlights as `> ` quotes under
 * `## Highlights`), as plain functions so they can be tested without a phone. [ReadStore] does the file side.
 */
object ReadNote {
    const val HIGHLIGHTS = "Highlights"
    private val TAG_LINE = Regex("^(\\[\\[[^\\]]+]]\\s*)+$")
    private val TITLE_LINK = Regex("^#\\s+(\\[(.+)]\\((\\S+)\\))$")
    private val LINK = Regex("\\[([^\\]]*)]\\((https?://[^)\\s]+)\\)")

    /** [modified] is the file's time, used as the saved time when the note has no `saved:`. */
    fun parse(ref: ReadRef, text: String, modified: Long): ArticleDoc {
        val lines = text.replace("\r", "").split("\n")
        val status = Md.fmGet(lines, "status")?.lowercase()
        val saved = Md.fmGet(lines, "saved")?.let(::parseStamp) ?: modified
        val body = Md.fmBody(lines).dropWhile { it.isBlank() || TAG_LINE.matches(it.trim()) }.toMutableList()
        var title = ref.file.removeSuffix(".md")
        var url: String? = null
        val first = body.indexOfFirst { it.isNotBlank() }
        if (first >= 0) {
            val line = body[first].trim()
            val linked = TITLE_LINK.matchEntire(line)
            when {
                linked != null -> { title = linked.groupValues[2]; url = linked.groupValues[3]; body.removeAt(first) }
                line.startsWith("# ") -> { title = line.removePrefix("# ").trim(); body.removeAt(first) }
            }
        }
        if (url == null) url = body.firstNotNullOfOrNull { LINK.find(it)?.groupValues?.get(2) }
        // Highlights live in their own "## Highlights" section; the reader shows them inline instead.
        val highlights = mutableListOf<String>()
        Md.sectionRange(body, HIGHLIGHTS)?.let { r ->
            body.slice(r).map { it.trim() }.filter { it.startsWith(">") }.mapTo(highlights) { it.removePrefix(">").trim() }
            repeat(r.last - r.first + 2) { body.removeAt(r.first - 1) }
        }
        return ArticleDoc(ref, title, url, ref.archived || status == "read" || status == "archived", saved, body, highlights)
    }

    /** First reasonably long line of plain text, for the list. */
    fun snippet(body: List<String>): String {
        val plain = body.asSequence().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("!") && !it.startsWith("```") && it != "---" }
            .map { plainText(it) }.filter { it.isNotBlank() }
        return (plain.firstOrNull { it.length >= 60 } ?: plain.firstOrNull()).orEmpty().take(200)
    }

    /** Adds [text] as a `> ` line under "## Highlights" (created at the end of the note), or removes it if already there. */
    fun toggleHighlight(lines: MutableList<String>, text: String) {
        val line = "> " + text.replace("\n", " ").trim()
        val r = Md.sectionRange(lines, HIGHLIGHTS)
        if (r == null) {
            while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.size - 1)
            lines.addAll(listOf("", "## $HIGHLIGHTS", "", line, ""))
            return
        }
        val at = r.firstOrNull { lines[it].trim() == line }
        if (at != null) {
            lines.removeAt(at)
            if (at < lines.size && lines[at].isBlank() && at > 0 && lines[at - 1].isBlank()) lines.removeAt(at)
        } else {
            var end = r.last + 1
            while (end > r.first && lines[end - 1].isBlank()) end--
            lines.addAll(end, listOf("", line))
        }
    }

    fun parseStamp(s: String): Long? = runCatching {
        val t = s.trim().trim('"')
        val dt = if (t.length == 10) java.time.LocalDate.parse(t).atStartOfDay() else LocalDateTime.parse(t.replace(' ', 'T'))
        dt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }.getOrNull()

    /** Markdown line to plain text for previews. */
    fun plainText(s: String): String = s
        .replace(Regex("!\\[[^\\]]*]\\([^)]*\\)"), "")
        .replace(Regex("\\[([^\\]]*)]\\([^)]*\\)"), "$1")
        .replace(Regex("^(>\\s*|[-*+]\\s+|\\d+\\.\\s+)"), "")
        .replace(Regex("[*`_]"), "")
        .trim()
}
