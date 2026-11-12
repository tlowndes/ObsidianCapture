package uk.tomlowndes.capture

import java.time.LocalDate

/**
 * Key findings for a saved article. Long is a few short paragraphs; Short is a handful of one-line bullets.
 * Shown for confirmation, then written back as one callout. Everything outside that callout (and one
 * frontmatter line) stays as it was.
 */
object KeyFindings {
    enum class Length(val label: String, val stored: String) {
        LONG("Long", "long"),
        SHORT("Short", "short");

        companion object {
            /** Missing or unknown values stay on [LONG], which is the default. */
            fun fromStored(raw: String?) = entries.firstOrNull { it.stored.equals(raw?.trim(), ignoreCase = true) } ?: LONG
        }
    }

    data class Link(val text: String, val url: String)

    /** [parts] are paragraphs for [Length.LONG] and bullets for [Length.SHORT]. */
    data class Findings(val length: Length, val parts: List<String>, val worthTrying: String?) {
        val isEmpty get() = parts.isEmpty()
    }

    data class Prepared(val title: String, val article: String, val links: List<Link>)

    /** The note was too short to be an article. */
    class Thin : Trouble("This note doesn't have enough article text to pull findings from.")

    /** The reply had no findings left after checks. */
    class Empty : Trouble("No findings came back. Try again.")

    open class Trouble(message: String) : Exception(message)

    /** How much cleaned article text is sent. Long pages are cut here so the request stays small. */
    const val MAX_CHARS = 12_000

    /** Below this, the note is not worth sending. */
    const val MIN_CHARS = 80

    /** The message is a request for key findings. The result is the note name to look for, or blank for the newest article. */
    fun query(message: String): String? {
        val m = REQUEST.matchEntire(message.trim()) ?: return null
        return m.groupValues[1].trim().trim('"', '\'', '“', '”', '?', '.')
    }

    fun looksLikeArticle(text: String): Boolean {
        val low = text.lowercase()
        return low.contains("[[readitlater]]") || low.contains("[[article]]") || H1_LINK.containsMatchIn(text)
    }

    /** Title, cleaned body, and the links the model is allowed to use. Images are not links. */
    fun prepare(note: String): Prepared {
        val body = Md.fmBody(NoteText.lines(note)).joinToString("\n")
        val title = NoteText.lines(body).firstNotNullOfOrNull { line ->
            H1.matchEntire(line.trim())?.groupValues?.get(1)?.let(::plainTitle)
        } ?: "Article"
        return Prepared(title, clean(body).take(MAX_CHARS), links(note))
    }

    /** Product and article links already in the note: link text plus URL, images left out, duplicates dropped. */
    fun links(note: String): List<Link> {
        val body = Md.fmBody(NoteText.lines(note)).joinToString("\n")
        val noImages = EMBED.replace(MD_IMAGE.replace(body, " "), " ")
        val source = noImages.lineSequence().map { if (AFFILIATE.containsMatchIn(it)) "" else it }.joinToString("\n")
        val found = mutableListOf<Link>()
        val seen = HashSet<String>()
        fun add(text: String, url: String) {
            val u = cleanUrl(url)
            if (!u.startsWith("http://") && !u.startsWith("https://")) return
            if (isImage(u)) return
            if (!seen.add(normUrl(u))) return
            val label = text.trim().replace(WHITESPACE, " ").take(80).ifBlank { u }
            found += Link(label, u)
        }
        val rest = StringBuilder()
        var i = 0
        MD_LINK.findAll(source).forEach { m ->
            rest.append(source, i, m.range.first).append(' ')
            add(m.groupValues[1], m.groupValues[2])
            i = m.range.last + 1
        }
        rest.append(source.substring(i))
        BARE.findAll(rest).forEach { add(it.value, it.value) }
        return found.take(24)
    }

    /** Other notes in the same folder, by title. The model may mention these as wikilinks and no others. */
    fun related(notes: List<VaultNote>, path: String): List<String> {
        val folder = parentOf(path)
        return notes.asSequence()
            .filter { parentOf(it.path) == folder && it.path != path && !nameOf(it.path).startsWith(".") }
            .map { VaultTasks.noteName(it.path) }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
            .take(8)
            .toList()
    }

    fun prompt(prepared: Prepared, length: Length, notes: List<String> = emptyList(), name: String = AssistantName.DEFAULT): String {
        val shape = when (length) {
            Length.LONG -> """
                Return 2 to 4 short paragraphs. Each paragraph is one takeaway in plain English, not a retelling.
                Put a blank line between paragraphs. Do not use bullets.
            """.trimIndent()
            Length.SHORT -> """
                Return 3 to 5 bullets. Each bullet is one short line: a takeaway or an idea worth acting on, not a retelling.
                One bullet per line, starting with "- ".
            """.trimIndent()
        }
        val linkBlock = if (prepared.links.isEmpty()) "Links you may use: none. Do not write any URL."
        else "Links you may use (only these; never invent a URL):\n" + prepared.links.joinToString("\n") { "- [${it.text}](${it.url})" }
        val noteBlock = if (notes.isEmpty()) ""
        else "\nNotes already in this folder. Mention one only as [[Name]], and only if it is relevant:\n" + notes.joinToString("\n") { "- [[$it]]" }
        return """
            You are ${AssistantName.clean(name)}. Pull key findings from this article for Tom. British English.
            $shape
            Where a product or another article in the list is relevant, include it as [text](url) using that exact URL.
            Do not invent anything that is not in the article, and do not invent a URL.
            No introduction and no headings.
            If the article suggests something practical to try, add one last line exactly like: Worth trying: one short line
            Leave that line out when there is nothing practical.

            Title: ${prepared.title}

            $linkBlock
            $noteBlock

            ${prepared.article}
        """.trimIndent()
    }

    /** A stand-in reply when the address is "demo", using only [links] and [notes] that are allowed. */
    fun demo(length: Length, links: List<Link>, notes: List<String> = emptyList()): String {
        val link = links.firstOrNull()?.let { " Look at [${it.text}](${it.url})." } ?: ""
        val note = notes.firstOrNull()?.let { " It sits near [[$it]]." } ?: ""
        return when (length) {
            Length.LONG -> "One takeaway from the article, in plain English.$link\n\nA second takeaway worth acting on.$note\nWorth trying: Try the simplest version this week"
            Length.SHORT -> "- One short takeaway from the article.$link\n- One idea worth acting on.$note\n- Leave out what you will not use\nWorth trying: Try the simplest version this week"
        }
    }

    /**
     * Turns a reply into findings. URLs that are not in [links] are removed. Wikilinks that are not in [notes]
     * are left as plain text. Long replies are normalised into paragraphs; the callout gaps are added later.
     */
    fun parse(reply: String, length: Length, links: List<Link>, notes: List<String> = emptyList()): Findings? {
        var worth: String? = null
        val body = mutableListOf<String>()
        reply.replace("\r\n", "\n").lineSequence().forEach { raw ->
            var line = raw.trim()
            while (line.startsWith(">")) line = line.removePrefix(">").trim()
            if (line.isEmpty()) { body += ""; return@forEach }
            val worthText = worthOf(line)
            if (worthText != null) {
                worth = scrub(worthText, links, notes).ifEmpty { null }
                return@forEach
            }
            if (line.equals("Key findings", ignoreCase = true) || line.startsWith("[!key-findings]", ignoreCase = true)) return@forEach
            body += line
        }
        val parts = when (length) {
            Length.SHORT -> bullets(body, links, notes)
            Length.LONG -> paragraphs(body, links, notes)
        }
        if (parts.isEmpty()) return null
        return Findings(length, parts, worth)
    }

    fun fromReply(note: String, reply: String, length: Length, related: List<String>, date: LocalDate): Pair<Findings, String> {
        val prepared = prepare(note)
        if (prepared.article.length < MIN_CHARS) throw Thin()
        val findings = parse(reply, length, prepared.links, related) ?: throw Empty()
        return findings to apply(note, findings, date)
    }

    /**
     * The callout as it will be written, using [eol] so the note's line endings stay put.
     * Long findings get a line that is only `>` between paragraphs and before Worth trying.
     */
    fun callout(findings: Findings, eol: String): String {
        val lines = mutableListOf("> [!key-findings] Key findings")
        when (findings.length) {
            Length.SHORT -> findings.parts.forEach { lines += "> - ${one(it)}" }
            Length.LONG -> findings.parts.forEachIndexed { i, part ->
                if (i > 0) lines += ">"
                lines += "> ${one(part)}"
            }
        }
        findings.worthTrying?.let { worth ->
            if (findings.length == Length.LONG) lines += ">"
            lines += "> Worth trying: ${one(worth)}"
        }
        return lines.joinToString(eol) + eol
    }

    /**
     * [note] with the findings callout put after an existing summary callout, or after the first # heading.
     * An existing key-findings callout is replaced. One `key_findings` date is set in the frontmatter when
     * the note already has frontmatter. Every other byte stays as it was.
     */
    fun apply(note: String, findings: Findings, date: LocalDate): String {
        val eol = NoteText.eol(note)
        return stamp(place(note, callout(findings, eol), eol), date, eol)
    }

    /** The note whose path best matches [query], preferring an article when no name was given. */
    fun find(notes: List<VaultNote>, query: String, read: (String) -> String?): String? {
        if (notes.isEmpty()) return null
        val words = query.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 2 }
        val ranked = notes.map { n -> n to words.count { n.path.lowercase().contains(it) } }
            .filter { words.isEmpty() || it.second > 0 }
            .sortedWith(compareByDescending<Pair<VaultNote, Int>> { it.second }.thenByDescending { it.first.modified })
        if (words.isNotEmpty() && ranked.isEmpty()) return null
        val pool = (if (ranked.isEmpty()) notes.sortedByDescending { it.modified } else ranked.map { it.first }).take(12)
        if (words.isNotEmpty()) return pool.firstOrNull()?.path
        val article = pool.firstOrNull { read(it.path)?.let(::looksLikeArticle) == true }
        return (article ?: pool.firstOrNull())?.path
    }

    private fun bullets(lines: List<String>, links: List<Link>, notes: List<String>): List<String> {
        val marked = lines.mapNotNull { line ->
            if (line.isEmpty()) null else BULLET.matchEntire(line)?.groupValues?.get(1)?.let { scrub(it, links, notes).ifEmpty { null } }
        }
        val chosen = if (marked.isNotEmpty()) marked else lines.filter { it.isNotEmpty() }.map { scrub(unbullet(it), links, notes) }.filter { it.isNotEmpty() }
        return chosen.distinct().take(5)
    }

    /** Blank lines group a wrapped paragraph. A reply with no blank lines is one paragraph per line. */
    private fun paragraphs(lines: List<String>, links: List<Link>, notes: List<String>): List<String> {
        val sawBlank = lines.any { it.isEmpty() }
        val groups = mutableListOf<MutableList<String>>()
        var current = mutableListOf<String>()
        fun flush() { if (current.isNotEmpty()) { groups += current; current = mutableListOf() } }
        for (line in lines) if (line.isEmpty()) flush() else current += line
        flush()
        val raw = if (sawBlank) groups.map { it.joinToString(" ") } else groups.flatten()
        return raw.map { scrub(unbullet(it), links, notes) }.filter { it.isNotEmpty() }.distinct().take(4)
    }

    private fun worthOf(line: String): String? {
        val stripped = unbullet(line)
        return WORTH.matchEntire(stripped)?.groupValues?.get(1)?.trim()
    }

    /** Drops URLs that are not in [links], and wikilinks that are not in [notes]. Allowed links are rewritten to the original URL. */
    private fun scrub(text: String, links: List<Link>, notes: List<String>): String {
        val allowed = HashMap<String, String>()
        links.forEach { allowed.putIfAbsent(normUrl(it.url), it.url) }
        val names = notes.map { VaultTasks.noteName(it).ifBlank { it } }
        val out = StringBuilder()
        var i = 0
        TOKEN.findAll(text).forEach { m ->
            out.append(text, i, m.range.first)
            out.append(when {
                m.value.startsWith("[[") -> {
                    val target = m.groupValues[3].trim()
                    val shown = m.groupValues[4].ifBlank { target }
                    val hit = names.firstOrNull { it.equals(target, ignoreCase = true) || it.equals(target.removeSuffix(".md"), ignoreCase = true) }
                    if (hit != null) "[[$hit]]" else shown
                }
                m.value.startsWith("[") -> {
                    val canon = allowed[normUrl(cleanUrl(m.groupValues[2]))]
                    if (canon != null) "[${m.groupValues[1].trim()}]($canon)" else m.groupValues[1].trim()
                }
                else -> {
                    val cleaned = cleanUrl(m.value)
                    val tail = m.value.substring(cleaned.length)
                    (allowed[normUrl(cleaned)] ?: "") + tail
                }
            })
            i = m.range.last + 1
        }
        out.append(text.substring(i))
        return one(out.toString())
    }

    private fun place(note: String, block: String, eol: String): String {
        val lines = lineSpans(note)
        val key = lines.indexOfFirst { line(note, it).trim().startsWith("> [!key-findings]", ignoreCase = true) }
        if (key >= 0) {
            val end = calloutEnd(note, lines, key)
            return note.substring(0, lines[key].start) + block + note.substring(lines[end - 1].end)
        }
        val summary = lines.indexOfFirst { SUMMARY.containsMatchIn(line(note, it).trim()) }
        val at = when {
            summary >= 0 -> lines[calloutEnd(note, lines, summary) - 1].end
            else -> lines.indexOfFirst { H1.matches(line(note, it).trim()) }.takeIf { it >= 0 }?.let { lines[it].end }
                ?: lines.indexOfFirst { line(note, it).trim() == "---" }.takeIf { it > 0 }?.let { lines[it].end }
                ?: 0
        }
        val gap = if (at < note.length && note[at] == '>') eol else ""
        return note.substring(0, at) + block + gap + note.substring(at)
    }

    /** Lines of a callout run on while each one starts with `>`. [from] is the opening line. */
    private fun calloutEnd(note: String, lines: List<Span>, from: Int): Int {
        var i = from + 1
        while (i < lines.size && line(note, lines[i]).trimStart().startsWith(">")) i++
        return i
    }

    private fun stamp(note: String, date: LocalDate, eol: String): String {
        val lines = lineSpans(note)
        if (lines.isEmpty() || line(note, lines[0]).trim() != "---") return note
        val close = (1 until lines.size).firstOrNull { line(note, lines[it]).trim() == "---" } ?: return note
        val existing = (1 until close).firstOrNull { line(note, lines[it]).trim().startsWith("key_findings:") }
        val fresh = "key_findings: $date"
        return if (existing != null) {
            val span = lines[existing]
            note.substring(0, span.start) + fresh + note.substring(contentEnd(note, span))
        } else note.substring(0, lines[close].start) + fresh + eol + note.substring(lines[close].start)
    }

    private fun clean(body: String): String {
        val kept = mutableListOf<String>()
        var stop = false
        var inFindings = false
        for (raw in body.replace("\r\n", "\n").lineSequence()) {
            if (stop) break
            val trimmed = raw.trim()
            if (inFindings) {
                if (trimmed.startsWith(">")) continue
                inFindings = false
            }
            if (trimmed.startsWith("> [!key-findings]", ignoreCase = true)) { inFindings = true; continue }
            if (FOOTER.containsMatchIn(trimmed)) { stop = true; continue }
            if (AFFILIATE.containsMatchIn(trimmed)) continue
            if (TAGS_ONLY.matches(trimmed) || H1.matches(trimmed) || SUMMARY.containsMatchIn(trimmed)) continue
            var line = trimmed
            line = MD_IMAGE.replace(line, "")
            line = EMBED.replace(line, "")
            line = MD_LINK.replace(line) { it.groupValues[1] }
            line = WIKI.replace(line) { m -> m.groupValues[2].ifEmpty { m.groupValues[1] } }
            line = BARE.replace(line, "")
            line = line.replace(WHITESPACE, " ").trim().trim(',', '.', ';', ':')
            if (line.isEmpty()) continue
            if (trimmed.startsWith(">")) {
                val inner = line.removePrefix(">").trim()
                if (inner.isNotEmpty() && !inner.startsWith("[!")) kept += inner
            } else kept += line
        }
        return kept.joinToString("\n").trim()
    }

    private fun plainTitle(raw: String): String =
        MD_LINK.replace(raw) { it.groupValues[1] }.replace(Regex("[*_`]"), "").trim()

    private fun unbullet(s: String) = s.replace(Regex("""^(?:[-*•]|\d+[.)])\s+"""), "")

    private fun one(s: String) = s.replace(WHITESPACE, " ").replace(Regex("""\s+([.,;:!?])"""), "$1").trim()

    private fun cleanUrl(url: String): String {
        var u = url.trim().trim('<', '>')
        while (u.isNotEmpty() && u.last() in ".,;:)]>") u = u.dropLast(1)
        return u
    }

    private fun normUrl(url: String) = cleanUrl(url).trimEnd('/').lowercase()

    private fun isImage(url: String) = IMAGE_EXT.containsMatchIn(url.substringBefore('?').substringBefore('#'))

    private data class Span(val start: Int, val end: Int)

    private fun lineSpans(text: String): List<Span> {
        if (text.isEmpty()) return emptyList()
        val out = mutableListOf<Span>()
        var i = 0
        while (i < text.length) {
            val nl = text.indexOf('\n', i)
            if (nl < 0) { out += Span(i, text.length); break }
            out += Span(i, nl + 1)
            i = nl + 1
        }
        return out
    }

    private fun line(text: String, span: Span) = text.substring(span.start, contentEnd(text, span))

    private fun contentEnd(text: String, span: Span): Int {
        var e = span.end
        if (e > span.start && text[e - 1] == '\n') e--
        if (e > span.start && text[e - 1] == '\r') e--
        return e
    }

    private val REQUEST = Regex(
        """(?is)^\s*(?:please\s+|can you\s+)?(?:key\s+findings(?:\s+(?:for|on|from|of|about))?|summari[sz]e(?:\s+the)?\s+(?:key\s+findings(?:\s+(?:for|of|on))?|this\s+article|the\s+article))\s*[:\-]?\s*(.*)$"""
    )
    private val H1 = Regex("""^#\s+(?!#)(.*)$""")
    private val H1_LINK = Regex("""(?m)^#\s+\[.+]\(https?://""")
    private val SUMMARY = Regex("""(?i)^>\s*\[!summary]""")
    private val BULLET = Regex("""^(?:[-*•]|\d+[.)])\s+(.+)$""")
    private val WORTH = Regex("""(?i)^worth trying:\s*(.+)$""")
    private val MD_IMAGE = Regex("""!\[[^\]]*]\([^)]*\)""")
    private val EMBED = Regex("""!\[\[[^\]]*]]""")
    private val MD_LINK = Regex("""\[([^\]]+)]\(([^)]+)\)""")
    private val WIKI = Regex("""\[\[([^\]|]+)(?:\|([^\]]+))?]]""")
    private val TOKEN = Regex("""\[([^\]]+)]\(([^)]+)\)|\[\[([^\]|#]+)(?:#[^\]|]*)?(?:\|([^\]]+))?]]|https?://[^\s<>)\]]+""")
    private val TAGS_ONLY = Regex("""(?i)^(?:\[\[(?:readitlater|article|youtube)]]\s*)+$""")
    private val BARE = Regex("""https?://[^\s<>)\]]+""")
    private val AFFILIATE = Regex("""(?i).*(as an amazon associate|affiliate links?|contains affiliate|this post contains|paid link).*""")
    private val FOOTER = Regex("""(?i)^(#{1,3}\s*)?(related(\s+(posts|articles|reading))?|you (may|might) also like|subscribe|newsletter|disclosure|advertisement|about the author|share this)\b.*$""")
    private val IMAGE_EXT = Regex("""(?i)\.(png|jpe?g|gif|webp|svg|avif|bmp|ico)$""")
    private val WHITESPACE = Regex("""\s+""")
}
