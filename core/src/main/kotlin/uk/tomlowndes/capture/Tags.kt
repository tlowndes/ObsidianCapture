package uk.tomlowndes.capture

import org.json.JSONArray
import org.json.JSONObject

/** How a tag is coloured: Work amber, Personal green, the rest purple. */
enum class TagKind { WORK, PERSONAL, OTHER }

/**
 * Tags on captures and tasks, written as plain Obsidian tags in the note text (`#work`, `#personal`, `#garden`),
 * so Obsidian's tag pane and search see them too. Nothing is stored anywhere else, apart from the list of
 * pinned tags in `capture-config.json`.
 */
object Tags {
    const val WORK = "#work"
    const val PERSONAL = "#personal"
    /** Always offered on the Tag row, before any pinned tags. */
    val BUILT_IN = listOf(WORK, PERSONAL)

    /** An Obsidian tag: letters, digits, `_`, `-` and `/`, with at least one non-digit. `#task` is the Tasks plugin's, not ours. */
    private val TAG = Regex("(?<![\\w#/&])#(?!task(?![\\w/-]))([\\p{L}\\p{N}_/-]*[\\p{L}_/-][\\p{L}\\p{N}_/-]*)")

    /** Tags in [text], lower-case with their `#`, in the order they appear, no repeats. */
    fun inText(text: String): List<String> = TAG.findAll(text).map { "#" + it.groupValues[1].lowercase() }.distinct().toList()

    fun has(text: String, tag: String) = norm(tag)?.let { it in inText(text) } == true

    /** [text] with every tag taken out, spaces tidied. */
    fun strip(text: String): String = text.replace(TAG, "").replace(Regex("[ \\t]+"), " ").trim()

    /** [text] with [tag] added at the end, or removed (every copy, any case) if it's already there. */
    fun toggle(text: String, tag: String): String {
        val t = norm(tag) ?: return text
        return if (has(text, t)) remove(text, t) else add(text, t)
    }

    fun add(text: String, tag: String): String {
        val t = norm(tag) ?: return text
        if (has(text, t)) return text
        val base = text.trimEnd()
        return if (base.isEmpty()) t else "$base $t"
    }

    fun remove(text: String, tag: String): String {
        val t = norm(tag) ?: return text
        return text.replace(TAG) { m -> if ("#" + m.groupValues[1].lowercase() == t) "" else m.value }
            .replace(Regex("[ \\t]+"), " ").trim()
    }

    /** [text] with [tags] added after it (ones already in it are left alone). */
    fun withTags(text: String, tags: Collection<String>): String = tags.fold(text) { acc, t -> add(acc, t) }

    /**
     * What the person typed, as a tag Obsidian accepts: lower-case, `#` in front, spaces to `-`, other
     * characters dropped. Null when nothing usable is left (empty, or only digits).
     */
    fun norm(input: String): String? {
        val body = input.trim().trimStart('#').lowercase()
            .replace(Regex("\\s+"), "-")
            .replace(Regex("[^\\p{L}\\p{N}_/-]"), "")
            .trim('/')
        if (body.isEmpty() || body.all { it.isDigit() } || body == "task") return null
        return "#$body"
    }

    fun kind(tag: String): TagKind = when (tag.lowercase()) {
        WORK -> TagKind.WORK
        PERSONAL -> TagKind.PERSONAL
        else -> TagKind.OTHER
    }

    /** "Work", "Personal" or the tag itself, for chips. */
    fun label(tag: String): String = when (kind(tag)) {
        TagKind.WORK -> "Work"
        TagKind.PERSONAL -> "Personal"
        TagKind.OTHER -> tag
    }

    /** The Tag row: Work, Personal, then pinned tags, then any [extra] already on the text. */
    fun row(pinned: List<String>, extra: List<String> = emptyList()): List<String> =
        (BUILT_IN + pinned + extra).mapNotNull { norm(it) }.distinct()

    /** How often each tag is used across [texts], most used first, then A–Z. */
    fun counts(texts: List<String>): List<Pair<String, Int>> =
        texts.flatMap { inText(it) }.groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key to it.value }

    /** Known tags matching what's typed so far ([query], with or without `#`): starts-with first, then contains. */
    fun suggest(query: String, known: List<Pair<String, Int>>, max: Int = 6): List<Pair<String, Int>> {
        val q = query.trim().trimStart('#').lowercase()
        if (q.isEmpty()) return known.take(max)
        val starts = known.filter { it.first.removePrefix("#").startsWith(q) }
        val contains = known.filter { it !in starts && it.first.removePrefix("#").contains(q) }
        return (starts + contains).take(max)
    }

    /** The `#word` being typed at [cursor] in [text], as its range and the part after `#`; null when the cursor isn't in one. */
    fun typing(text: String, cursor: Int): Pair<IntRange, String>? {
        val end = cursor.coerceIn(0, text.length)
        var start = end
        while (start > 0 && !text[start - 1].isWhitespace() && text[start - 1] != '#') start--
        if (start == 0 || text[start - 1] != '#') return null
        val hash = start - 1
        if (hash > 0 && !text[hash - 1].isWhitespace()) return null
        var stop = end
        while (stop < text.length && !text[stop].isWhitespace()) stop++
        return (hash until stop) to text.substring(start, end)
    }

    /** [text] with the `#word` at [range] swapped for [tag] and a space after; returns the new text and cursor. */
    fun complete(text: String, range: IntRange, tag: String): Pair<String, Int> {
        val t = norm(tag) ?: return text to range.last + 1
        val after = text.substring(range.last + 1).trimStart()
        val head = text.substring(0, range.first) + t + " "
        return head + after to head.length
    }

    /**
     * Whether [t] shows under [tag] in a tag filter. Work and Personal are the two sides the Tasks tab
     * already has: Work is `#work` or `#jobs`, Personal is everything else. Other tags match exactly.
     */
    fun matches(t: TaskItem, tag: String): Boolean = when (Tags.kind(tag)) {
        TagKind.WORK -> TaskViews.isWork(t)
        TagKind.PERSONAL -> !TaskViews.isWork(t)
        TagKind.OTHER -> tag.lowercase() in TaskViews.tags(t)
    }

    /** Counts for a tag list: Work, Personal, then the most used other tags. */
    fun filterCounts(tasks: List<TaskItem>, max: Int = 8): List<Pair<String, Int>> =
        BUILT_IN.map { b -> b to tasks.count { matches(it, b) } } +
            TaskViews.topTags(tasks, max + BUILT_IN.size).filter { it !in BUILT_IN }.take(max)
                .map { tag -> tag to tasks.count { matches(it, tag) } }

    /** Pinned tags from `capture-config.json` (key `pinnedTags`). */
    fun pinned(json: String?): List<String> {
        val a = parse(json).optJSONArray("pinnedTags") ?: return emptyList()
        return (0 until a.length()).mapNotNull { norm(a.optString(it)) }.filter { it !in BUILT_IN }.distinct()
    }

    /** [json] with its pinned tags set to [tags]; other keys are kept. */
    fun withPinned(json: String?, tags: List<String>): String {
        val o = parse(json)
        o.put("pinnedTags", JSONArray(tags.mapNotNull { norm(it) }.filter { it !in BUILT_IN }.distinct()))
        return o.toString(2)
    }

    fun loadPinned(v: Vault): List<String> = pinned(v.read(DeckConfig.PATH))

    fun savePinned(v: Vault, tags: List<String>) {
        if (v.entry(DeckConfig.PATH) == null) {
            v.mkdirs(parentOf(DeckConfig.PATH))
            v.create(parentOf(DeckConfig.PATH), nameOf(DeckConfig.PATH).removeSuffix(".json"), "json")
        }
        VaultIo.write(v, DeckConfig.PATH, withPinned(v.read(DeckConfig.PATH), tags))
    }

    private fun parse(json: String?): JSONObject =
        json?.takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
}
