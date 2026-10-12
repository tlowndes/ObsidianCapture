package uk.tomlowndes.capture

import java.time.LocalDate

/**
 * How an idea note is laid out and edited (frontmatter, `## Summary`, then a newest-first `## Log` of `### ` entries),
 * as plain functions on its lines so they can be tested without a phone. [IdeaStore] does the file side.
 */
object IdeaNote {
    private val IMAGE = Regex("^!\\[\\[(.+?)(?:\\|[^\\]]*)?]]$")

    fun safeName(s: String) = s.replace(Regex("[\\\\/:*?\"<>|#^\\[\\]]"), "-").replace(Regex("\\s+"), " ").trim().take(80).trim()

    fun newNote(title: String, summary: String, today: LocalDate) =
        "---\ntype: idea\nstatus: idea\ncreated: $today\ntags: []\n---\n\n# $title\n\n## Summary\n\n${summary.trim()}\n\n## Log\n\n"

    fun parse(title: String, text: String): IdeaDoc {
        val lines = text.split("\n")
        val entries = entryRanges(lines).map { entryAt(lines, it) }
        return IdeaDoc(title, Md.fmGet(lines, "status") ?: "idea", summaryOf(lines), entries)
    }

    fun summaryOf(lines: List<String>) =
        Md.sectionRange(lines, "Summary")?.let { lines.slice(it) }?.joinToString("\n")?.trim().orEmpty()

    /** Each Log entry, from its `### ` heading up to the next one. */
    private fun entryRanges(lines: List<String>): List<IntRange> {
        val log = Md.sectionRange(lines, "Log") ?: return emptyList()
        val starts = log.filter { lines[it].startsWith("### ") }
        return starts.mapIndexed { n, s -> s until (if (n + 1 < starts.size) starts[n + 1] else log.last + 1) }
    }

    private fun entryAt(lines: List<String>, r: IntRange): IdeaEntry {
        val body = lines.subList(r.first + 1, r.last + 1)
        val images = body.mapNotNull { IMAGE.find(it.trim())?.groupValues?.get(1) }
        val text = body.filter { IMAGE.find(it.trim()) == null }.joinToString("\n").trim()
        return IdeaEntry(lines[r.first].removePrefix("### ").trim(), text, images)
    }

    /** [was] is the summary as shown when editing started; stops rather than overwrite a change made elsewhere. */
    fun setSummary(lines: MutableList<String>, was: String, text: String) {
        if (summaryOf(lines) != was.trim()) error("Summary was changed on another device, refreshed")
        val r = Md.sectionRange(lines, "Summary")
        if (r == null) {
            val log = Md.headingIndex(lines, "Log")
            val at = if (log >= 0) log else lines.size
            lines.addAll(at, listOf("## Summary", "", text.trim(), ""))
        } else {
            repeat(r.last - r.first + 1) { lines.removeAt(r.first) }
            lines.addAll(r.first, listOf("", text.trim(), ""))
        }
    }

    /** New entries go at the top of the Log, newest first. [stamp] becomes the entry's heading. */
    fun addEntry(lines: MutableList<String>, stamp: String, text: String, images: List<String>) {
        if (Md.headingIndex(lines, "Log") < 0) lines.addAll(listOf("", "## Log", ""))
        val h = Md.headingIndex(lines, "Log")
        lines.addAll(h + 1, listOf("") + entryLines(stamp, text, images))
    }

    /** [entry] is the entry as shown when editing started; its images are kept. */
    fun editEntry(lines: MutableList<String>, entry: IdeaEntry, text: String) {
        val r = entryRange(lines, entry)
        val images = lines.slice(r).mapNotNull { IMAGE.find(it.trim())?.groupValues?.get(1) }
        repeat(r.last - r.first + 1) { lines.removeAt(r.first) }
        lines.addAll(r.first, entryLines(entry.heading, text, images))
    }

    fun deleteEntry(lines: MutableList<String>, entry: IdeaEntry) {
        val r = entryRange(lines, entry)
        repeat(r.last - r.first + 1) { lines.removeAt(r.first) }
    }

    private fun entryLines(heading: String, text: String, images: List<String>): List<String> {
        val out = mutableListOf("### $heading", "")
        if (text.isNotBlank()) { out.addAll(text.trim().split("\n")); out.add("") }
        if (images.isNotEmpty()) { images.forEach { out.add("![[$it]]") }; out.add("") }
        return out
    }

    /**
     * Finds [entry] by its heading and text rather than its position, so entries added elsewhere don't shift it.
     * Stops if the entry itself was changed or removed on another device.
     */
    private fun entryRange(lines: List<String>, entry: IdeaEntry): IntRange {
        val same = entryRanges(lines).filter { entryAt(lines, it).heading == entry.heading }
        if (same.isEmpty()) error("Entry was removed on another device, refreshed")
        return same.firstOrNull { entryAt(lines, it).text == entry.text }
            ?: error("Entry was changed on another device, refreshed")
    }
}
