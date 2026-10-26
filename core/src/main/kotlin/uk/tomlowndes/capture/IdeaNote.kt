package uk.tomlowndes.capture

import java.time.LocalDate

/**
 * How an idea is laid out and edited, as plain functions on lines so they can be tested without a phone.
 * [IdeaStore] does the file side. The idea note has frontmatter, `## Summary`, and a `## Log` section that links to
 * a separate `<Title> - Log.md`, which holds the newest-first `### ` entries. Ideas from before the split keep their
 * entries in the idea note until the next one is added, when they move across.
 */
object IdeaNote {
    private val IMAGE = Regex("^!\\[\\[(.+?)(?:\\|[^\\]]*)?]]$")

    fun safeName(s: String) = s.replace(Regex("[\\\\/:*?\"<>|#^\\[\\]]"), "-").replace(Regex("\\s+"), " ").trim().take(80).trim()

    fun logName(title: String) = "$title - Log"

    private fun logLink(title: String) = "[[${logName(title)}]]"

    fun newNote(title: String, summary: String, today: LocalDate) =
        "---\ntype: idea\nstatus: idea\ncreated: $today\ntags: []\n---\n\n# $title\n\n## Summary\n\n${summary.trim()}\n\n## Log\n\n${logLink(title)}\n"

    /** The log file, starting with [entries] (lines from [logEntries]) when moving an older idea's log across. */
    fun newLog(title: String, entries: List<String> = emptyList()) =
        "---\ntype: idea-log\nidea: \"[[$title]]\"\n---\n\n# ${logName(title)}\n\n## Log\n\n" +
            (if (entries.isEmpty()) "" else entries.joinToString("\n") + "\n\n")

    /** The `### ` entry lines still in an idea note's Log (ideas from before the split), trailing blanks dropped. */
    fun logEntries(lines: List<String>): List<String> {
        val r = Md.sectionRange(lines, "Log") ?: return emptyList()
        val start = r.firstOrNull { lines[it].startsWith("### ") } ?: return emptyList()
        return lines.subList(start, r.last + 1).dropLastWhile { it.isBlank() }
    }

    /**
     * Swaps the entries in an idea note's Log for a link to the log file, once [moved] has been copied there.
     * Stops if the entries changed since they were copied, so nothing is lost.
     */
    fun moveLogOut(lines: MutableList<String>, title: String, moved: List<String>) {
        if (logEntries(lines) != moved) error("Log was changed on another device, refreshed")
        val link = logLink(title)
        val r = Md.sectionRange(lines, "Log")
        if (r == null) { lines.addAll(listOf("", "## Log", "", link, "")); return }
        val start = r.firstOrNull { lines[it].startsWith("### ") }
        if (start != null) repeat(r.last - start + 1) { lines.removeAt(start) }
        if (lines.none { it.trim() == link }) {
            if (start != null) lines.addAll(start, listOf(link, "")) else lines.addAll(r.first, listOf("", link))
        }
    }

    fun parse(title: String, text: String): IdeaDoc {
        val lines = text.split("\n")
        val entries = entryRanges(lines).map { entryAt(lines, it) }
        return IdeaDoc(
            title, Md.fmGet(lines, "status") ?: "idea", summaryOf(lines), entries,
            typeOf(lines), Md.fmGet(lines, "area")?.trim()?.lowercase()?.ifEmpty { null },
            Md.fmBody(lines).firstNotNullOfOrNull { TaskNote.parse(it)?.text },
            Md.fmGet(lines, "could_continue")?.trim()?.lowercase() == "true"
        )
    }

    /** Ticks the note's first open checkbox (the next step) where it stands, with today's ✅ date. */
    fun tickNext(lines: MutableList<String>, today: LocalDate) {
        val start = lines.size - Md.fmBody(lines).size
        val i = (start until lines.size).firstOrNull { TaskNote.parse(lines[it]) != null } ?: error("No next step to tick")
        lines[i] = TaskNote.completion(lines[i], today, recurring = false).doneLine
    }

    /** A link in the note's Links section: a web address, or a note name for `[[wikilinks]]`. */
    data class Link(val label: String, val target: String, val web: Boolean)

    private val MD_LINK = Regex("\\[([^\\]]+)]\\((https?://[^)\\s]+)\\)")
    private val WIKI = Regex("\\[\\[([^\\]|#]+)(?:#[^\\]|]*)?(?:\\|([^\\]]+))?]]")
    private val BARE = Regex("(?<![(\\w])https?://[^\\s)>\\]]+")

    /** The links in `## Links`, in the order written. */
    fun links(text: String): List<Link> {
        val lines = text.split("\n")
        val body = Md.sectionRange(lines, "Links")?.let { lines.slice(it) }?.joinToString("\n") ?: return emptyList()
        val found = mutableListOf<Pair<Int, Link>>()
        MD_LINK.findAll(body).forEach { found += it.range.first to Link(it.groupValues[1].trim(), it.groupValues[2], true) }
        WIKI.findAll(body).forEach {
            val note = it.groupValues[1].trim()
            found += it.range.first to Link(it.groupValues[2].trim().ifEmpty { note.substringAfterLast('/') }, note, false)
        }
        BARE.findAll(body).forEach { m ->
            if (found.none { it.second.web && it.second.target == m.value }) found += m.range.first to Link(m.value.removePrefix("https://").removePrefix("http://"), m.value, true)
        }
        return found.sortedBy { it.first }.map { it.second }
    }

    /** Project types Flight Deck shows as a tag. */
    val TYPES = listOf("claude", "concept", "idea")

    /**
     * The project's type: `type` when it's one of [TYPES], otherwise "claude" for notes tagged claude (how the
     * vault marked them before), otherwise null. Nothing in the note is rewritten.
     */
    fun typeOf(lines: List<String>): String? {
        val type = Md.fmGet(lines, "type")?.trim()?.lowercase()
        if (type in TYPES) return type
        val fm = if (lines.firstOrNull()?.trim() == "---") lines.drop(1).takeWhile { it.trim() != "---" } else emptyList()
        val tagged = fm.any { Regex("^\\s*-\\s*#?claude\\s*$").matches(it) || Regex("^tags:.*\\bclaude\\b").containsMatchIn(it) }
        return if (tagged) "claude" else null
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
