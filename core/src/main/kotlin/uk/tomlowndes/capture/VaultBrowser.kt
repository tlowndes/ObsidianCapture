package uk.tomlowndes.capture

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * One note as the Vault browser sees it: where it is, when it changed, a short preview, its tags, and its text
 * (capped) for full-text search. Built by [NoteIndex], which reads each note once and caches the result.
 */
data class NoteInfo(
    val path: String,
    val modified: Long,
    val preview: String = "",
    val tags: List<String> = emptyList(),
    val text: String = "",
) {
    val title get() = VaultTasks.noteName(path)
    val folder get() = parentOf(path)
}

/** Every note in the vault with its preview, tags and text, read once and kept in a cache file between runs. */
object NoteIndex {
    /** Enough of each note to search; very long notes are only searched this far. */
    const val MAX_TEXT = 40_000

    /** Dot folders (.obsidian, .trash, .git) never show. */
    fun skip(path: String) = nameOf(path).startsWith(".")

    /** Just the paths and dates: quick, so the folders can show before the text is read. */
    fun paths(v: Vault): List<NoteInfo> = v.notes(::skip).map { NoteInfo(it.path, it.modified) }

    /**
     * Every note with its text. Notes whose modified time matches the cache in [cacheFile] aren't read again.
     * [progress] gets (done, total) now and then.
     */
    fun build(v: Vault, cacheFile: File?, progress: (Int, Int) -> Unit = { _, _ -> }): List<NoteInfo> {
        val notes = v.notes(::skip)
        val cached = cacheFile?.let { load(it) }.orEmpty()
        val out = ArrayList<NoteInfo>(notes.size)
        notes.forEachIndexed { i, n ->
            val hit = cached[n.path]?.takeIf { it.modified == n.modified }
            out.add(hit ?: runCatching { info(n.path, n.modified, v.read(n.path, MAX_TEXT).orEmpty()) }
                .getOrElse { NoteInfo(n.path, n.modified) })
            if (i % 50 == 49) progress(i + 1, notes.size)
        }
        progress(notes.size, notes.size)
        cacheFile?.let { f -> runCatching { save(f, out) } }
        return out
    }

    /** The index entry for a note with this [text]. */
    fun info(path: String, modified: Long, text: String): NoteInfo {
        val lines = NoteText.lines(text)
        return NoteInfo(path, modified, preview(lines), NoteTags.of(lines), text)
    }

    /** The first line of real text: not frontmatter, a heading, a rule, an embed or a code fence. Markdown taken out. */
    fun preview(lines: List<String>, max: Int = 160): String {
        var inCode = false
        for (raw in Md.fmBody(lines)) {
            val l = raw.trim()
            if (l.startsWith("```")) { inCode = !inCode; continue }
            if (inCode || l.isEmpty() || HEADING.matches(l) || l.startsWith("![[") ||
                l.startsWith("![") || RULE.matches(l) || l.startsWith("|")) continue
            val plain = plain(l)
            if (Tags.strip(plain).isNotBlank()) return if (plain.length > max) plain.take(max - 1).trimEnd() + "…" else plain
        }
        return ""
    }

    /** [s] without markdown marks: list markers, checkboxes, links, bold, code. Tags are kept. */
    fun plain(s: String): String = s
        .replace(Regex("^\\s*(?:[-*+]|\\d+[.)])\\s+(?:\\[.]\\s+)?"), "")
        .replace(Regex("^>\\s*"), "")
        .replace(Regex("\\[\\[([^\\]|]+)\\|([^\\]]+)]]"), "$2")
        .replace(Regex("\\[\\[([^\\]]+)]]"), "$1")
        .replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")
        .replace(Regex("\\*\\*|__|`|(?<!\\w)\\*(?!\\s)|(?<!\\s)\\*(?!\\w)"), "")
        .trim()

    private val HEADING = Regex("^#{1,6}\\s.*")
    private val RULE = Regex("^([-*_])(\\s*\\1){2,}$")

    private fun load(f: File): Map<String, NoteInfo> {
        if (!f.isFile) return emptyMap()
        return runCatching {
            val a = JSONArray(f.readText())
            (0 until a.length()).associate { i ->
                val o = a.getJSONObject(i)
                val tags = o.optJSONArray("g")?.let { g -> (0 until g.length()).map { g.getString(it) } }.orEmpty()
                o.getString("p") to NoteInfo(o.getString("p"), o.getLong("m"), o.optString("v"), tags, o.optString("t"))
            }
        }.getOrDefault(emptyMap())
    }

    private fun save(f: File, notes: List<NoteInfo>) {
        val a = JSONArray()
        notes.forEach { n ->
            a.put(JSONObject().put("p", n.path).put("m", n.modified).put("v", n.preview).put("g", JSONArray(n.tags)).put("t", n.text))
        }
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(a.toString())
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }
}

/** What's in one folder of the browser: its subfolders (with how many notes are under each) and its own notes. */
data class FolderView(val folders: List<Pair<String, Int>>, val notes: List<NoteInfo>)

object VaultBrowse {
    /** Folders hidden unless "Show hidden folders" is on: the vault's own plumbing. */
    fun isHidden(folder: String) = nameOf(folder).startsWith("99 ")

    /**
     * Folder [dir] ("" for the vault root) from the index: subfolders A–Z, then notes newest first (or A–Z when
     * [byName]). Folders under [isHidden] are left out unless [showHidden].
     */
    fun folder(all: List<NoteInfo>, dir: String, byName: Boolean = false, showHidden: Boolean = false): FolderView {
        val prefix = if (dir.isEmpty()) "" else "$dir/"
        val counts = LinkedHashMap<String, Int>()
        val notes = mutableListOf<NoteInfo>()
        for (n in all) {
            if (!n.path.startsWith(prefix)) continue
            val rest = n.path.substring(prefix.length)
            val slash = rest.indexOf('/')
            if (slash < 0) notes.add(n)
            else {
                val sub = prefix + rest.substring(0, slash)
                if (!showHidden && isHidden(sub)) continue
                counts[sub] = (counts[sub] ?: 0) + 1
            }
        }
        val folders = counts.entries.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { nameOf(it.key) }).map { it.key to it.value }
        val sorted = if (byName) notes.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }) else notes.sortedByDescending { it.modified }
        return FolderView(folders, sorted)
    }

    /** The most recently changed notes, outside hidden folders. */
    fun recent(all: List<NoteInfo>, n: Int = 3, showHidden: Boolean = false): List<NoteInfo> =
        all.asSequence().filter { showHidden || !hiddenPath(it.path) }.sortedByDescending { it.modified }.take(n).toList()

    private fun hiddenPath(path: String) = path.split('/').dropLast(1).any { isHidden(it) }

    /** How many notes use each tag, most used first; for tag suggestions and chips. */
    fun tagCounts(all: List<NoteInfo>): List<Pair<String, Int>> =
        all.flatMap { it.tags }.groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).map { it.key to it.value }

    /**
     * The note a `[[link]]` points at: an exact path, else a note with that name (closest to [from]'s folder first).
     * `#heading` and `|alias` parts are ignored. Null when there's no such note.
     */
    fun resolve(all: List<NoteInfo>, link: String, from: String = ""): NoteInfo? {
        val target = link.substringBefore('|').substringBefore('#').trim().removeSuffix(".md")
        if (target.isEmpty()) return null
        val withMd = "$target.md"
        all.firstOrNull { it.path.equals(withMd, true) }?.let { return it }
        val name = nameOf(target)
        val hits = all.filter { it.title.equals(name, true) && (target == name || it.path.endsWith("/$withMd", true)) }
        val here = parentOf(from)
        return hits.minByOrNull { if (it.folder == here) 0 else if (it.path.startsWith(here)) 1 else 2 }
    }
}

/** Search across the vault browser's index: titles and folders, note text, or open tasks; with tag filters. */
object VaultSearch {
    enum class Mode { ALL, TITLES, TASKS }

    /** A result: the note, and the line to show under it with where the words are in it. */
    data class Hit(val note: NoteInfo, val snippet: String, val marks: List<IntRange>)

    private val OPEN_TASK = Regex("^\\s*[-*+]\\s+\\[ ]\\s")

    /**
     * Notes matching every word of [query] and every tag in [tags], in folder [inFolder] if set. Title hits come
     * first, then text hits; newest first within each. A blank query with tags lists the tagged notes.
     */
    fun search(
        all: List<NoteInfo>, query: String, tags: List<String> = emptyList(), mode: Mode = Mode.ALL,
        inFolder: String? = null, limit: Int = 100,
    ): List<Hit> {
        val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty() && tags.isEmpty()) return emptyList()
        val prefix = inFolder?.takeIf { it.isNotEmpty() }?.let { "$it/" }
        val titleHits = mutableListOf<Hit>()
        val textHits = mutableListOf<Hit>()
        for (n in all) {
            if (prefix != null && !n.path.startsWith(prefix)) continue
            if (tags.any { it !in n.tags }) continue
            val inTitle = words.all { n.path.lowercase().contains(it) }
            when (mode) {
                Mode.TITLES -> if (inTitle || words.isEmpty()) titleHits.add(Hit(n, n.preview, marks(n.preview, words)))
                Mode.TASKS -> {
                    val line = NoteText.lines(n.text).firstOrNull { l -> OPEN_TASK.containsMatchIn(l) && words.all { l.lowercase().contains(it) } }
                    if (line != null) textHits.add(hit(n, NoteIndex.plain(line), words))
                }
                Mode.ALL -> when {
                    inTitle || words.isEmpty() -> titleHits.add(snippetHit(n, words))
                    words.all { n.text.lowercase().contains(it) } -> textHits.add(snippetHit(n, words))
                }
            }
        }
        return (titleHits.sortedByDescending { it.note.modified } + textHits.sortedByDescending { it.note.modified }).take(limit)
    }

    /** The bit of the note round the first word found, or the preview. */
    private fun snippetHit(n: NoteInfo, words: List<String>): Hit {
        val body = NoteText.lines(n.text).let { Md.fmBody(it) }
        val line = words.firstOrNull()?.let { w -> body.firstOrNull { it.lowercase().contains(w) } }
        return if (line == null) Hit(n, n.preview, marks(n.preview, words)) else hit(n, NoteIndex.plain(line.trim()), words)
    }

    private fun hit(n: NoteInfo, line: String, words: List<String>): Hit {
        val s = around(line, words.firstOrNull())
        return Hit(n, s, marks(s, words))
    }

    /** [line] cut to about [width] characters round [word], with … where it was cut. */
    fun around(line: String, word: String?, width: Int = 110): String {
        if (line.length <= width) return line
        val at = word?.let { line.lowercase().indexOf(it) } ?: -1
        val start = if (at < 0) 0 else (at - width / 3).coerceIn(0, line.length - width)
        val end = (start + width).coerceAtMost(line.length)
        return (if (start > 0) "…" else "") + line.substring(start, end).trim() + (if (end < line.length) "…" else "")
    }

    /** Where each of [words] appears in [s] (any case). */
    fun marks(s: String, words: List<String>): List<IntRange> {
        val low = s.lowercase()
        return words.flatMap { w ->
            generateSequence(low.indexOf(w).takeIf { it >= 0 }) { i -> low.indexOf(w, i + w.length).takeIf { it >= 0 } }
                .map { it until it + w.length }.toList()
        }.sortedBy { it.first }
    }

    /**
     * Turns finished `#tag ` words in the search box into tag filters: returns what's left of the text and the
     * tags taken out. A `#word` still being typed (no space after it yet) is left alone.
     */
    fun takeTags(text: String): Pair<String, List<String>> {
        val tags = mutableListOf<String>()
        val left = Regex("(^|\\s)#([\\p{L}\\p{N}_/-]+)\\s").replace(text) { m ->
            val t = Tags.norm(m.groupValues[2])
            if (t == null) m.value else { tags.add(t); m.groupValues[1] }
        }
        return left to tags
    }
}

/** Line handling that keeps a note's own line endings (Windows `\r\n` or `\n`). */
object NoteText {
    fun lines(text: String): List<String> = text.replace("\r\n", "\n").split("\n")
    fun eol(text: String) = if (text.contains("\r\n")) "\r\n" else "\n"
    fun join(lines: List<String>, eol: String) = lines.joinToString(eol)
}

/**
 * A note's tags: the frontmatter `tags:` property (any of Obsidian's forms) and `#tags` in the text, outside code.
 * All as lower-case `#tag`.
 */
object NoteTags {
    private val FM_KEY = Regex("^(tags|tag)\\s*:(.*)$", RegexOption.IGNORE_CASE)
    private val FM_ITEM = Regex("^\\s+-\\s*(.*)$")

    /** Every tag on the note: properties first, then inline ones, no repeats. */
    fun of(lines: List<String>): List<String> = (props(lines) + inline(lines)).distinct()

    /** Tags in the frontmatter `tags:` property. */
    fun props(lines: List<String>): List<String> {
        val r = propRange(lines) ?: return emptyList()
        val first = FM_KEY.matchEntire(lines[r.first].trim())!!.groupValues[2].trim()
        val raw = if (first.isNotEmpty()) first.trim('[', ']').split(Regex("[,\\s]+"))
        else (r.first + 1..r.last).map { FM_ITEM.matchEntire(lines[it])?.groupValues?.get(1).orEmpty() }
        return raw.map { it.trim().trim('"', '\'') }.mapNotNull { Tags.norm(it) }.distinct()
    }

    /** `#tags` written in the note's text (frontmatter and code left out). */
    fun inline(lines: List<String>): List<String> = bodyLines(lines).flatMap { Tags.inText(lines[it]) }.distinct()

    /**
     * Makes the note's tags exactly [wanted]. Tags no longer wanted are taken out wherever they are (property or
     * text). New ones go in the `tags:` property when [toProps], otherwise on a line at the end of the note.
     */
    fun set(lines: MutableList<String>, wanted: List<String>, toProps: Boolean = true) {
        val want = wanted.mapNotNull { Tags.norm(it) }.distinct()
        val props = props(lines)
        val inline = inline(lines)
        val gone = (props + inline).filter { it !in want }
        val added = want.filter { it !in props && it !in inline }
        if (gone.isNotEmpty()) {
            for (i in bodyLines(lines)) {
                var l = lines[i]
                gone.forEach { t -> if (Tags.has(l, t)) l = removeKeepIndent(l, t) }
                lines[i] = l
            }
        }
        val newProps = props.filter { it in want } + if (toProps) added else emptyList()
        if (newProps != props) writeProps(lines, newProps)
        if (!toProps && added.isNotEmpty()) NoteSearch.addLine(lines, added.joinToString(" "))
    }

    /** [line] without [tag], keeping its leading indent and list marker. */
    private fun removeKeepIndent(line: String, tag: String): String {
        val indent = line.takeWhile { it == ' ' || it == '\t' }
        return indent + Tags.remove(line.substring(indent.length), tag)
    }

    /** The `tags:` key line and any `  - item` lines under it, inside the frontmatter. */
    private fun propRange(lines: List<String>): IntRange? {
        val end = fmEnd(lines)
        if (end < 0) return null
        for (i in 1 until end) {
            if (FM_KEY.matches(lines[i].trim()) && !lines[i].startsWith(" ")) {
                var j = i
                while (j + 1 < end && FM_ITEM.matches(lines[j + 1])) j++
                return i..j
            }
        }
        return null
    }

    /** Writes `tags:` as a list (Obsidian's own style), or removes the key when [tags] is empty. */
    private fun writeProps(lines: MutableList<String>, tags: List<String>) {
        val block = if (tags.isEmpty()) emptyList() else listOf("tags:") + tags.map { "  - " + it.removePrefix("#") }
        val r = propRange(lines)
        if (r != null) {
            repeat(r.last - r.first + 1) { lines.removeAt(r.first) }
            lines.addAll(r.first, block)
            return
        }
        if (block.isEmpty()) return
        val end = fmEnd(lines)
        if (end < 0) lines.addAll(0, listOf("---") + block + "---") else lines.addAll(end, block)
    }

    private fun fmEnd(lines: List<String>): Int {
        if (lines.isEmpty() || lines[0].trim() != "---") return -1
        for (i in 1 until lines.size) if (lines[i].trim() == "---") return i
        return -1
    }

    /** Indexes of the lines after the frontmatter that aren't in a code block. */
    private fun bodyLines(lines: List<String>): List<Int> {
        val out = mutableListOf<Int>()
        var inCode = false
        for (i in fmEnd(lines) + 1 until lines.size) {
            if (lines[i].trim().startsWith("```")) { inCode = !inCode; continue }
            if (!inCode) out.add(i)
        }
        return out
    }
}

/**
 * A note cut into the pieces the reader shows and you can tap to edit: the frontmatter, then each heading on its
 * own, and each run of lines between blank lines (a code block stays whole). [lines] are the note's line indexes.
 */
data class NoteChunk(val lines: IntRange, val kind: Kind, val text: String) {
    enum class Kind { FRONTMATTER, HEADING, CODE, TEXT }
}

object NoteChunks {
    private val HEADING = Regex("^#{1,6}\\s.*")
    private val CHECKBOX = Regex("^(\\s*[-*+]\\s+\\[)([ xX])(]\\s)")

    fun split(lines: List<String>): List<NoteChunk> {
        val out = mutableListOf<NoteChunk>()
        fun add(from: Int, to: Int, kind: NoteChunk.Kind) {
            if (to >= from) out.add(NoteChunk(from..to, kind, lines.subList(from, to + 1).joinToString("\n")))
        }
        var i = 0
        if (lines.isNotEmpty() && lines[0].trim() == "---") {
            val end = (1 until lines.size).firstOrNull { lines[it].trim() == "---" }
            if (end != null) { add(0, end, NoteChunk.Kind.FRONTMATTER); i = end + 1 }
        }
        var start = -1
        while (i < lines.size) {
            val l = lines[i].trim()
            when {
                l.startsWith("```") -> {
                    if (start >= 0) { add(start, i - 1, NoteChunk.Kind.TEXT); start = -1 }
                    var j = i + 1
                    while (j < lines.size && !lines[j].trim().startsWith("```")) j++
                    val stop = j.coerceAtMost(lines.size - 1)
                    add(i, stop, NoteChunk.Kind.CODE)
                    i = stop + 1
                    continue
                }
                l.isEmpty() -> if (start >= 0) { add(start, i - 1, NoteChunk.Kind.TEXT); start = -1 }
                HEADING.matches(l) -> {
                    if (start >= 0) { add(start, i - 1, NoteChunk.Kind.TEXT); start = -1 }
                    add(i, i, NoteChunk.Kind.HEADING)
                }
                else -> if (start < 0) start = i
            }
            i++
        }
        if (start >= 0) add(start, lines.size - 1, NoteChunk.Kind.TEXT)
        return out
    }

    /** The note's frontmatter as key/value pairs, without `tags` (shown as chips) and list items joined with commas. */
    fun properties(lines: List<String>): List<Pair<String, String>> {
        if (lines.isEmpty() || lines[0].trim() != "---") return emptyList()
        val end = (1 until lines.size).firstOrNull { lines[it].trim() == "---" } ?: return emptyList()
        val out = mutableListOf<Pair<String, MutableList<String>>>()
        for (i in 1 until end) {
            val l = lines[i]
            val item = Regex("^\\s+-\\s*(.*)$").matchEntire(l)
            if (item != null && out.isNotEmpty()) { out.last().second.add(item.groupValues[1].trim().trim('"')); continue }
            val kv = Regex("^([^:\\s][^:]*):\\s*(.*)$").matchEntire(l) ?: continue
            out.add(kv.groupValues[1].trim() to mutableListOf<String>().apply { kv.groupValues[2].trim().trim('"').takeIf { it.isNotEmpty() }?.let(::add) })
        }
        return out.filter { !it.first.equals("tags", true) && !it.first.equals("tag", true) }
            .map { it.first to it.second.joinToString(", ") }
    }

    /** Line indexes inside [chunk] that are list items, in order (the n-th matches the n-th item MdBlocks finds). */
    fun itemLines(lines: List<String>, chunk: NoteChunk): List<Int> =
        chunk.lines.filter { Regex("^\\s*([-*+]|\\d+[.)])\\s+").containsMatchIn(lines[it]) }

    /** Whether line [i] is a checkbox, and whether it's ticked. */
    fun checkbox(line: String): Boolean? = CHECKBOX.find(line)?.let { it.groupValues[2] != " " }

    /** [line] with its checkbox flipped. Ticking adds Tasks' `✅ date` only when the line is a `#task`. */
    fun toggleCheckbox(line: String, today: java.time.LocalDate): String {
        val m = CHECKBOX.find(line) ?: return line
        val ticked = m.groupValues[2] != " "
        val flipped = line.replaceRange(m.range, m.groupValues[1] + (if (ticked) " " else "x") + m.groupValues[3])
        val isTask = Regex("(^|\\s)#task(\\s|$)").containsMatchIn(line)
        return when {
            !isTask -> flipped
            ticked -> flipped.replace(Regex("\\s*✅\\s*\\d{4}-\\d{2}-\\d{2}"), "")
            else -> "${flipped.trimEnd()} ✅ $today"
        }
    }
}

/** Saving quick edits to a note without losing changes another device made in the meantime. */
object NoteEdit {
    sealed interface Result {
        /** Written; [text] is the note as it now is. */
        data class Saved(val text: String) : Result
        /** The note changed elsewhere and the edit couldn't be placed: [theirs] is what's there now, [mine] the edit applied to the old copy. */
        data class Conflict(val theirs: String, val mine: String) : Result
    }

    /** [text] with lines [range] swapped for [replacement] (which can be several lines, or empty to remove them). */
    fun replace(text: String, range: IntRange, replacement: String): String {
        val eol = NoteText.eol(text)
        val lines = NoteText.lines(text).toMutableList()
        val new = replacement.replace("\r\n", "\n").trimEnd('\n').let { if (it.isEmpty()) emptyList() else it.split("\n") }
        repeat(range.last - range.first + 1) { lines.removeAt(range.first) }
        lines.addAll(range.first, new)
        // Taking a block out shouldn't leave two blank lines where it was.
        if (new.isEmpty() && range.first < lines.size && range.first > 0 && lines[range.first].isBlank() && lines[range.first - 1].isBlank()) {
            lines.removeAt(range.first)
        }
        return NoteText.join(lines, eol)
    }

    /**
     * Swaps lines [range] of [base] (the copy the edit started from) for [replacement] and saves. If the note
     * changed since, the edit is applied to the new copy when the old lines are still there exactly once;
     * otherwise it's a [Result.Conflict] for the person to settle.
     */
    fun saveLines(v: Vault, path: String, base: String, range: IntRange, replacement: String): Result {
        val mine = replace(base, range, replacement)
        val now = v.read(path) ?: error("${nameOf(path)} isn't there any more")
        if (now == base) return write(v, path, mine)
        val old = NoteText.lines(base).subList(range.first, range.last + 1)
        val nowLines = NoteText.lines(now)
        val at = find(nowLines, old)
        return if (at != null) write(v, path, replace(now, at until at + old.size, replacement))
        else Result.Conflict(now, mine)
    }

    /** Saves the whole note [mine] if it's still [base] on disk, else a [Result.Conflict]. */
    fun saveAll(v: Vault, path: String, base: String, mine: String): Result {
        val now = v.read(path) ?: error("${nameOf(path)} isn't there any more")
        return if (now == base) write(v, path, mine) else Result.Conflict(now, mine)
    }

    /** Keep mine: writes [mine] whatever is there. */
    fun force(v: Vault, path: String, mine: String): String = VaultIo.write(v, path, mine)

    /** Keep both: their copy, with my edited lines added at the end under a note saying where they came from. */
    fun both(theirs: String, myLines: String, stamp: String): String {
        val eol = NoteText.eol(theirs)
        val lines = NoteText.lines(theirs).toMutableList()
        while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.size - 1)
        lines.add("")
        lines.add("> [!note] Edited in Flight Deck $stamp while this note changed on another device")
        lines.add("")
        lines.addAll(myLines.replace("\r\n", "\n").trimEnd('\n').split("\n"))
        lines.add("")
        return NoteText.join(lines, eol)
    }

    /** Changes a few lines in place (a checkbox, tags) with [VaultIo.edit], which redoes the change if a sync lands. */
    fun edit(v: Vault, path: String, change: (MutableList<String>) -> Unit): String {
        val text = v.read(path).orEmpty()
        val eol = NoteText.eol(text)
        if (eol == "\n") {
            VaultIo.edit(v, path) { change(it) }
        } else {
            VaultIo.edit(v, path) { ls ->
                val clean = ls.map { it.removeSuffix("\r") }.toMutableList()
                change(clean)
                ls.clear(); ls.addAll(clean.mapIndexed { i, l -> if (i < clean.size - 1) "$l\r" else l })
            }
        }
        return v.read(path).orEmpty()
    }

    private fun write(v: Vault, path: String, text: String): Result {
        val after = VaultIo.write(v, path, text)
        return if (after == text) Result.Saved(after) else Result.Conflict(after, text)
    }

    private fun find(lines: List<String>, block: List<String>): Int? {
        if (block.isEmpty()) return null
        var hit: Int? = null
        for (i in 0..lines.size - block.size) {
            if ((block.indices).all { lines[i + it] == block[it] }) {
                if (hit != null) return null
                hit = i
            }
        }
        return hit
    }
}
