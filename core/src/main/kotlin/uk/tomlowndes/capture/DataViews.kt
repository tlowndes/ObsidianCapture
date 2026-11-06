package uk.tomlowndes.capture

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A task line a query found: where it is, what it says and its Tasks-plugin dates. */
data class QueryTask(
    val path: String, val raw: String, val text: String, val status: Char,
    val due: LocalDate? = null, val scheduled: LocalDate? = null, val start: LocalDate? = null,
    val doneDate: LocalDate? = null, val priority: Int = 3, val tags: List<String> = emptyList(),
    val recurring: Boolean = false,
) {
    /** Done, cancelled or anything else that isn't to-do or in progress, as the Tasks plugin counts it. */
    val done get() = status != ' ' && status != '/'
    val note get() = VaultTasks.noteName(path)
    /** 0 highest (🔺) … 3 none … 5 lowest (⏬). */
    val priorityMark get() = when (priority) { 0 -> "🔺"; 1 -> "⏫"; 2 -> "🔼"; 4 -> "🔽"; 5 -> "⏬"; else -> "" }
}

/** One table cell or list entry: its text, the note it links to (if any) and its date (if it is one). */
data class QueryCell(val text: String, val link: String? = null, val date: LocalDate? = null)

/** What a ```tasks or ```dataview block shows in the reader. */
sealed class QueryView {
    /** TASKS, DATAVIEW or DATAVIEWJS: the label on the card. */
    abstract val kind: String
    /** A few words on what the query asks for. */
    abstract val title: String

    data class Tasks(override val kind: String, override val title: String, val tasks: List<QueryTask>, val total: Int, val footer: String) : QueryView()
    data class Table(override val kind: String, override val title: String, val headers: List<String>, val rows: List<List<QueryCell>>) : QueryView()
    data class Items(override val kind: String, override val title: String, val items: List<Pair<QueryCell, QueryCell?>>) : QueryView()
    /** A block the app can't run; [script] when it's dataviewjs, which only ever runs in Obsidian. */
    data class Unsupported(override val kind: String, override val title: String, val reason: String, val script: Boolean = false) : QueryView()
}

/**
 * Runs the common Tasks-plugin and Dataview queries against the Vault browser's index, so query blocks show
 * results in the app. Anything outside the supported subset comes back as [QueryView.Unsupported].
 */
object DataViews {
    private class Unsup(val why: String) : Exception(why)

    /** The query language and body of a fenced code chunk, or null when it isn't a query block. */
    fun of(chunk: String): Pair<String, String>? {
        val ls = chunk.split("\n")
        val lang = ls.firstOrNull()?.trim()?.removePrefix("```")?.removePrefix("~~~")?.trim()?.lowercase() ?: return null
        if (lang !in setOf("tasks", "dataview", "dataviewjs")) return null
        return lang to ls.drop(1).dropLastWhile { it.trim().startsWith("```") || it.trim().startsWith("~~~") }.joinToString("\n")
    }

    /** The view for a query block in note [here]. Never throws. */
    fun run(lang: String, query: String, all: List<NoteInfo>, here: String, today: LocalDate = LocalDate.now()): QueryView = when (lang) {
        "dataviewjs" -> QueryView.Unsupported("DATAVIEWJS", "Script", "This block is a script, so the app can't show its results.", script = true)
        "tasks" -> try { tasks(query, all, here, today) } catch (e: Unsup) {
            QueryView.Unsupported("TASKS", "Tasks query", "Not supported yet (${e.why}).")
        } catch (e: Exception) { QueryView.Unsupported("TASKS", "Tasks query", "Couldn't read this query.") }
        else -> try { Dql(query, all, here, today).run() } catch (e: Unsup) {
            QueryView.Unsupported("DATAVIEW", "Dataview query", "Not supported yet (${e.why}).")
        } catch (e: Exception) { QueryView.Unsupported("DATAVIEW", "Dataview query", "Couldn't read this query.") }
    }

    /**
     * Ticks or unticks [t] in its own note, the way the Tasks tab does: a `#task` gets its ✅ date (and its next
     * occurrence when it repeats; Quick Task moves it to Archive). Returns the note's new text.
     */
    fun toggle(v: Vault, t: QueryTask, today: LocalDate = LocalDate.now()): String = NoteEdit.edit(v, t.path) { ls ->
        val i = ls.indexOf(t.raw)
        if (i < 0) error("that task changed on another device")
        if (!t.done && GLOBAL.containsMatchIn(t.raw)) {
            val c = TaskNote.completion(t.raw, today, true)
            if (t.path == VaultTasks.QUICK_TASK) TaskNote.complete(ls, t.raw, c) else VaultTasks.completeInPlace(ls, t.raw, c)
        } else ls[i] = NoteChunks.toggleCheckbox(ls[i], today)
    }

    /** "5 Nov", or "5 Nov 2027" outside this year. */
    fun shortDate(d: LocalDate, today: LocalDate = LocalDate.now()): String =
        d.format(DateTimeFormatter.ofPattern(if (d.year == today.year) "d MMM" else "d MMM yyyy", Locale.UK))

    // ------------------------------------------------------------------ notes, parsed once per change

    private class Page(val modified: Long, val sig: Int, val props: Map<String, Any?>, val tasks: List<QueryTask>, val links: Set<String>)
    private val pages = HashMap<String, Page>()

    private fun page(n: NoteInfo): Page = synchronized(pages) {
        pages[n.path]?.takeIf { it.modified == n.modified && it.sig == n.text.hashCode() && n.text.isNotEmpty() }?.let { return it }
        val p = parse(n)
        pages[n.path] = p
        p
    }

    private val TASK_LINE = Regex("^\\s*[-*+]\\s+\\[(.)]\\s+(.*)$")
    private val WIKI = Regex("\\[\\[([^\\]|#]*)")
    private val INLINE_FIELD = Regex("^\\s*(?:[-*+]\\s+)?([\\p{L}\\p{N}_][\\p{L}\\p{N}_ -]*)::\\s*(.*)$")

    private fun parse(n: NoteInfo): Page {
        val lines = NoteText.lines(n.text)
        val props = LinkedHashMap<String, Any?>()
        var i = 0
        if (lines.firstOrNull()?.trim() == "---") {
            val end = (1 until lines.size).firstOrNull { lines[it].trim() == "---" }
            if (end != null) {
                var key: String? = null
                for (j in 1 until end) {
                    val l = lines[j]
                    val item = Regex("^\\s+-\\s*(.*)$").matchEntire(l)
                    if (item != null && key != null) {
                        @Suppress("UNCHECKED_CAST")
                        val list = (props[key] as? MutableList<Any?>) ?: mutableListOf<Any?>().also { props[key!!] = it }
                        list.add(value(item.groupValues[1]))
                        continue
                    }
                    val kv = Regex("^([^:\\s#][^:]*):\\s*(.*)$").matchEntire(l) ?: continue
                    key = kv.groupValues[1].trim().lowercase()
                    val raw = kv.groupValues[2].trim()
                    props[key] = if (raw.isEmpty()) null else value(raw)
                }
                i = end + 1
            }
        }
        val tasks = mutableListOf<QueryTask>()
        val links = HashSet<String>()
        var fence = false
        while (i < lines.size) {
            val l = lines[i++]
            val t = l.trimStart()
            if (t.startsWith("```") || t.startsWith("~~~")) { fence = !fence; continue }
            if (fence) continue
            WIKI.findAll(l).forEach { m -> m.groupValues[1].trim().takeIf { it.isNotEmpty() }?.let { links.add(it.substringAfterLast('/').removeSuffix(".md").lowercase()) } }
            TASK_LINE.matchEntire(l)?.let { m -> tasks.add(task(n.path, l, m.groupValues[1][0], m.groupValues[2])) }
            INLINE_FIELD.matchEntire(l)?.let { m ->
                val k = m.groupValues[1].trim().lowercase()
                if (k !in props) props[k] = value(m.groupValues[2])
            }
        }
        return Page(n.modified, n.text.hashCode(), props, tasks, links)
    }

    private fun date(re: String, body: String): LocalDate? =
        Regex("$re\\s*(\\d{4}-\\d{2}-\\d{2})").find(body)?.groupValues?.get(1)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    private fun task(path: String, raw: String, status: Char, body: String) = QueryTask(
        path, raw, TaskRules.cleanText(body).ifBlank { body.trim() }, status,
        due = date("📅", body), scheduled = date("⏳", body), start = date("🛫", body), doneDate = date("✅", body),
        priority = when {
            "🔺" in body -> 0; "⏫" in body -> 1; "🔼" in body -> 2; "🔽" in body -> 4; "⏬" in body -> 5; else -> 3
        },
        tags = Tags.inText(body), recurring = "🔁" in body,
    )

    /** A frontmatter or inline-field value: link, date, number, boolean, list or text. */
    private fun value(raw: String): Any? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        if (s.startsWith("[") && s.endsWith("]") && !s.startsWith("[[")) {
            return s.substring(1, s.length - 1).split(",").map { value(it) }.filter { it != null }
        }
        val q = if (s.length >= 2 && (s.startsWith("\"") && s.endsWith("\"") || s.startsWith("'") && s.endsWith("'"))) s.substring(1, s.length - 1) else s
        Regex("^\\[\\[([^\\]|]+)(?:\\|([^\\]]+))?]]$").matchEntire(q)?.let { return Link(it.groupValues[1], it.groupValues[2].ifEmpty { null }) }
        if (q != s) return q
        Regex("^(\\d{4}-\\d{2}-\\d{2})").find(q)?.let { m -> runCatching { LocalDate.parse(m.groupValues[1]) }.getOrNull()?.let { return it } }
        q.toDoubleOrNull()?.let { return it }
        if (q.equals("true", true)) return true
        if (q.equals("false", true)) return false
        if (q == "null" || q == "~") return null
        return q
    }

    /** A [[wikilink]] value. [path] is set once resolved against the index. */
    private data class Link(val target: String, val display: String? = null, val path: String? = null) {
        val name get() = display ?: target.substringAfterLast('/').removeSuffix(".md")
    }

    // ------------------------------------------------------------------ Tasks plugin queries

    private fun dateWord(s: String, today: LocalDate): LocalDate {
        val w = s.trim().lowercase()
        return when (w) {
            "today" -> today
            "tomorrow" -> today.plusDays(1)
            "yesterday" -> today.minusDays(1)
            else -> runCatching { LocalDate.parse(w) }.getOrNull() ?: throw Unsup("\"$s\"")
        }
    }

    private fun prio(s: String): Int = when (s.trim().lowercase()) {
        "highest" -> 0; "high" -> 1; "medium" -> 2; "none", "normal" -> 3; "low" -> 4; "lowest" -> 5
        else -> throw Unsup("priority $s")
    }

    private val DATE_FILTER = Regex("^(due|scheduled|starts|start|done|happens)(?: (before|after|on or before|on or after|on|in))? (.+)$", RegexOption.IGNORE_CASE)
    private val TEXT_FILTER = Regex("^(path|folder|filename|description|heading)( does not include| includes)(?: \\(?)?(.+?)\\)?$", RegexOption.IGNORE_CASE)
    private val TAG_FILTER = Regex("^tags? (include|includes|do not include|does not include) (.+)$", RegexOption.IGNORE_CASE)
    private val HAS_DATE = Regex("^(no|has) (due|scheduled|start|done|happens) date$", RegexOption.IGNORE_CASE)
    private val PRIORITY = Regex("^priority is(?: (above|below|not))? (\\w+)$", RegexOption.IGNORE_CASE)

    private fun atom(src: String, today: LocalDate): (QueryTask) -> Boolean {
        val s = src.trim()
        val low = s.lowercase()
        when (low) {
            "done" -> return { it.done }
            "not done" -> return { !it.done }
            "is recurring" -> return { it.recurring }
            "is not recurring" -> return { !it.recurring }
            "exclude sub-items" -> return { true }
        }
        HAS_DATE.matchEntire(s)?.let { m ->
            val pick = picker(m.groupValues[2])
            val has = m.groupValues[1].equals("has", true)
            return { t -> pick(t).isNotEmpty() == has }
        }
        DATE_FILTER.matchEntire(s)?.let { m ->
            val pick = picker(m.groupValues[1])
            val d = dateWord(m.groupValues[3], today)
            val test: (LocalDate) -> Boolean = when (m.groupValues[2].lowercase()) {
                "before" -> { x -> x < d }
                "after" -> { x -> x > d }
                "on or before" -> { x -> x <= d }
                "on or after" -> { x -> x >= d }
                else -> { x -> x == d }
            }
            return { t -> pick(t).any(test) }
        }
        TAG_FILTER.matchEntire(s)?.let { m ->
            val want = m.groupValues[2].trim().removePrefix("#").lowercase()
            val yes = !m.groupValues[1].contains("not", true)
            return { t -> t.tags.any { it.removePrefix("#").contains(want) } == yes }
        }
        TEXT_FILTER.matchEntire(s)?.let { m ->
            val field = m.groupValues[1].lowercase()
            if (field == "heading") throw Unsup("heading")
            val want = m.groupValues[3].trim().lowercase()
            val yes = !m.groupValues[2].contains("not", true)
            return { t ->
                val hay = when (field) {
                    "path" -> t.path
                    "folder" -> t.path.substringBeforeLast('/', "") + "/"
                    "filename" -> t.path.substringAfterLast('/')
                    else -> t.text
                }.lowercase()
                hay.contains(want) == yes
            }
        }
        PRIORITY.matchEntire(s)?.let { m ->
            val p = prio(m.groupValues[2])
            return when (m.groupValues[1].lowercase()) {
                "above" -> { t -> t.priority < p }
                "below" -> { t -> t.priority > p }
                "not" -> { t -> t.priority != p }
                else -> { t -> t.priority == p }
            }
        }
        throw Unsup(s.take(30))
    }

    private fun picker(field: String): (QueryTask) -> List<LocalDate> = when (field.lowercase()) {
        "due" -> { t -> listOfNotNull(t.due) }
        "scheduled" -> { t -> listOfNotNull(t.scheduled) }
        "starts", "start" -> { t -> listOfNotNull(t.start) }
        "done" -> { t -> listOfNotNull(t.doneDate) }
        else -> { t -> listOfNotNull(t.due, t.scheduled, t.start) }
    }

    /** A filter line with `(a) OR (b)`, `NOT (a)`, AND, XOR and brackets. */
    private fun filter(src: String, today: LocalDate): (QueryTask) -> Boolean {
        val s = src.trim()
        if (s.startsWith("NOT (") || s.startsWith("NOT(")) {
            val open = s.indexOf('(')
            val close = matching(s, open)
            if (close == s.length - 1) { val f = filter(s.substring(open + 1, close), today); return { !f(it) } }
            return combine({ t -> !filter(s.substring(open + 1, close), today)(t) }, s.substring(close + 1), today)
        }
        if (s.startsWith("(")) {
            val close = matching(s, 0)
            val left = filter(s.substring(1, close), today)
            if (close == s.length - 1) return left
            return combine(left, s.substring(close + 1), today)
        }
        return atom(s, today)
    }

    private fun combine(left: (QueryTask) -> Boolean, rest: String, today: LocalDate): (QueryTask) -> Boolean {
        val r = rest.trim()
        val m = Regex("^(AND NOT|OR NOT|AND|OR|XOR)\\s+(.*)$").matchEntire(r) ?: throw Unsup(r.take(30))
        val right = filter(m.groupValues[2], today)
        return when (m.groupValues[1]) {
            "AND" -> { t -> left(t) && right(t) }
            "OR" -> { t -> left(t) || right(t) }
            "XOR" -> { t -> left(t) != right(t) }
            "AND NOT" -> { t -> left(t) && !right(t) }
            else -> { t -> left(t) || !right(t) }
        }
    }

    private fun matching(s: String, open: Int): Int {
        var depth = 0
        for (i in open until s.length) {
            if (s[i] == '(') depth++
            if (s[i] == ')') { depth--; if (depth == 0) return i }
        }
        throw Unsup("brackets")
    }

    private fun tasks(query: String, all: List<NoteInfo>, here: String, today: LocalDate): QueryView {
        val placeholders = mapOf(
            "{{query.file.path}}" to here, "{{query.file.pathWithoutExtension}}" to here.removeSuffix(".md"),
            "{{query.file.folder}}" to here.substringBeforeLast('/', "") + "/",
            "{{query.file.filename}}" to here.substringAfterLast('/'),
        )
        var q = query
        placeholders.forEach { (k, v) -> q = q.replace(k, v) }
        if ("{{" in q) throw Unsup("placeholders")
        val lines = q.replace(Regex("\\\\\\s*\\n\\s*"), " ").split("\n").map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        val filters = mutableListOf<(QueryTask) -> Boolean>()
        val words = mutableListOf<String>()
        val sorts = mutableListOf<Pair<String, Boolean>>()
        var limit: Int? = null
        for (l in lines) {
            val low = l.lowercase()
            when {
                low.startsWith("sort by ") -> {
                    val parts = low.removePrefix("sort by ").trim().split(Regex("\\s+"))
                    sorts.add(parts[0] to (parts.getOrNull(1) == "reverse"))
                }
                low.startsWith("limit groups") -> Unit
                low.startsWith("limit") -> limit = Regex("\\d+").find(low)?.value?.toInt()
                low.startsWith("hide ") || low.startsWith("show ") || low == "short mode" || low == "short" || low == "full mode" ||
                    low == "explain" || low.startsWith("group by") || low == "ignore global query" -> Unit
                low.startsWith("filter by function") || low.startsWith("sort by function") -> throw Unsup("functions")
                else -> { filters.add(filter(l, today)); words.add(l) }
            }
        }
        val found = all.asSequence().flatMap { page(it).tasks.asSequence() }
            .filter { t -> GLOBAL.containsMatchIn(t.raw) && filters.all { it(t) } }.toList()
        val sorted = found.sortedWith(order(sorts.ifEmpty { listOf("status" to false, "due" to false, "priority" to false, "path" to false) }))
        val shown = limit?.let { sorted.take(it) } ?: sorted
        val foot = buildList {
            limit?.let { add("Limit $it") }
            if (sorts.isNotEmpty()) add("sorted by " + sorts.joinToString(", ") { it.first + if (it.second) " ↓" else "" })
        }.joinToString(" · ")
        return QueryView.Tasks("TASKS", summary(words), shown, found.size, foot)
    }

    /** The Tasks plugin's global filter in this vault: only `#task` lines are tasks. */
    private val GLOBAL = Regex("(^|\\s)#task(/|\\s|$)")

    private fun order(sorts: List<Pair<String, Boolean>>): Comparator<QueryTask> {
        var c: Comparator<QueryTask> = Comparator { _, _ -> 0 }
        val far = LocalDate.MAX
        for ((key, rev) in sorts) {
            val one: Comparator<QueryTask> = when (key) {
                "due" -> compareBy { it.due ?: far }
                "scheduled" -> compareBy { it.scheduled ?: far }
                "start" -> compareBy { it.start ?: far }
                "done" -> compareBy { it.doneDate ?: far }
                "happens" -> compareBy { listOfNotNull(it.due, it.scheduled, it.start).minOrNull() ?: far }
                "priority" -> compareBy { it.priority }
                "status" -> compareBy { it.done }
                "urgency" -> compareBy<QueryTask> { it.due ?: far }.thenBy { it.priority }
                "path", "filename" -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.path }
                "description" -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.text }
                "tag" -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.tags.firstOrNull() ?: "￿" }
                "recurring" -> compareBy { !it.recurring }
                else -> throw Unsup("sort by $key")
            }
            c = c.then(if (rev) one.reversed() else one)
        }
        return c
    }

    /** "(due on or before today) OR (scheduled on or before today)" → "Due or scheduled by today". */
    private fun summary(filters: List<String>): String {
        val parts = filters.mapNotNull { f ->
            val low = f.lowercase().trim()
            when {
                low == "not done" -> null
                Regex("^(not )?\\(?\\s*(path|folder|filename)\\b").containsMatchIn(low) -> null
                else -> low.replace("(", "").replace(")", "")
                    .replace("on or before", "by").replace("on or after", "from")
                    .replace(Regex("\\s+"), " ").trim()
            }
        }
        var s = parts.joinToString(" and ")
        s = s.replace(Regex("(\\w+) (by|before|after|from|on) (\\S+) or (\\w+) \\2 \\3"), "$1 or $4 $2 $3")
        if (s.isEmpty()) s = if (filters.any { it.trim().equals("not done", true) }) "Open tasks" else "Tasks"
        return s.replaceFirstChar { it.uppercase() }.let { if (it.length > 48) it.take(47).trimEnd() + "…" else it }
    }

    // ------------------------------------------------------------------ Dataview queries

    private enum class T { STR, NUM, ID, TAG, LINK, OP }
    private data class Tok(val t: T, val s: String)

    private fun lex(q: String): List<Tok> {
        val out = mutableListOf<Tok>()
        var i = 0
        while (i < q.length) {
            val c = q[i]
            when {
                c.isWhitespace() -> i++
                c == '/' && q.startsWith("//", i) -> { while (i < q.length && q[i] != '\n') i++ }
                c == '"' || c == '\'' -> {
                    val sb = StringBuilder(); i++
                    while (i < q.length && q[i] != c) { if (q[i] == '\\' && i + 1 < q.length) i++; sb.append(q[i]); i++ }
                    i++; out.add(Tok(T.STR, sb.toString()))
                }
                q.startsWith("[[", i) -> {
                    val end = q.indexOf("]]", i).takeIf { it > 0 } ?: throw Unsup("link")
                    out.add(Tok(T.LINK, q.substring(i + 2, end))); i = end + 2
                }
                c == '#' -> {
                    var j = i + 1
                    while (j < q.length && (q[j].isLetterOrDigit() || q[j] in "_-/")) j++
                    out.add(Tok(T.TAG, q.substring(i, j).lowercase())); i = j
                }
                c.isDigit() -> {
                    var j = i
                    while (j < q.length && (q[j].isDigit() || q[j] == '.')) j++
                    out.add(Tok(T.NUM, q.substring(i, j))); i = j
                }
                c.isLetter() || c == '_' -> {
                    var j = i
                    while (j < q.length && (q[j].isLetterOrDigit() || q[j] in "_.-")) j++
                    out.add(Tok(T.ID, q.substring(i, j).trimEnd('.', '-'))); i += out.last().s.length
                }
                else -> {
                    val two = q.substring(i, minOf(i + 2, q.length))
                    if (two in setOf("!=", "<=", ">=", "&&", "||")) { out.add(Tok(T.OP, two)); i += 2 }
                    else { out.add(Tok(T.OP, c.toString())); i++ }
                }
            }
        }
        return out
    }

    private class Expr(val src: String, val eval: (Row) -> Any?)

    /** What an expression is evaluated against: a note, and for TASK queries one of its tasks. */
    private class Row(val note: NoteInfo, val task: QueryTask? = null)

    private val CLAUSES = setOf("FROM", "WHERE", "SORT", "LIMIT", "GROUP", "FLATTEN")

    private class Dql(query: String, val all: List<NoteInfo>, val here: String, val today: LocalDate) {
        val toks = lex(query)
        var p = 0

        fun peek(): Tok? = toks.getOrNull(p)
        fun kw(): String? = peek()?.takeIf { it.t == T.ID }?.s?.uppercase()
        fun next(): Tok = toks.getOrNull(p++) ?: throw Unsup("query ends early")
        fun op(s: String) = peek()?.let { it.t == T.OP && it.s == s } == true
        fun expect(s: String) { if (!op(s)) throw Unsup("expected $s"); p++ }
        fun atClause() = kw() in CLAUSES || peek() == null

        fun run(): QueryView {
            val type = kw() ?: throw Unsup("no query type")
            p++
            if (type == "CALENDAR") throw Unsup("CALENDAR")
            if (type !in setOf("LIST", "TABLE", "TASK")) throw Unsup(type)
            var withoutId = false
            if (kw() == "WITHOUT" && toks.getOrNull(p + 1)?.s?.uppercase() == "ID") { withoutId = true; p += 2 }
            val cols = mutableListOf<Pair<String, Expr>>()
            if (type != "TASK") while (!atClause()) {
                val e = expr()
                var name = e.src
                if (kw() == "AS") { p++; name = next().s }
                cols.add(name to e)
                if (op(",")) p++ else break
            }
            var source: ((NoteInfo) -> Boolean)? = null
            var sourceLabel = ""
            val wheres = mutableListOf<Expr>()
            val sorts = mutableListOf<Pair<Expr, Boolean>>()
            var limit: Int? = null
            while (peek() != null) {
                when (kw()) {
                    "FROM" -> { p++; val start = p; source = src(); sourceLabel = label(start, p) }
                    "WHERE" -> { p++; wheres.add(expr()) }
                    "SORT" -> {
                        p++
                        while (true) {
                            val e = expr()
                            var desc = false
                            when (kw()) { "DESC", "DESCENDING" -> { desc = true; p++ }; "ASC", "ASCENDING" -> p++ }
                            sorts.add(e to desc)
                            if (op(",")) p++ else break
                        }
                    }
                    "LIMIT" -> { p++; limit = next().s.toDoubleOrNull()?.toInt() }
                    "GROUP" -> throw Unsup("GROUP BY")
                    "FLATTEN" -> throw Unsup("FLATTEN")
                    else -> throw Unsup(peek()!!.s)
                }
            }
            val title = (listOf(sourceLabel.ifEmpty { "Whole vault" }) + wheres.flatMap { hints(it.src) }).joinToString(" · ")
            val notes = all.filter { !NoteIndex.skip(it.path) && (source?.invoke(it) ?: true) }

            if (type == "TASK") {
                var rows = notes.flatMap { n -> page(n).tasks.map { Row(n, it) } }.filter { r -> wheres.all { truthy(it.eval(r)) } }
                if (sorts.isNotEmpty()) rows = rows.sortedWith(rowOrder(sorts))
                val total = rows.size
                limit?.let { rows = rows.take(it) }
                return QueryView.Tasks("DATAVIEW", title, rows.map { it.task!! }, total, limit?.let { "Limit $it" }.orEmpty())
            }
            var rows = notes.map { Row(it) }.filter { r -> wheres.all { truthy(it.eval(r)) } }
            rows = if (sorts.isNotEmpty()) rows.sortedWith(rowOrder(sorts)) else rows.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.note.path })
            limit?.let { rows = rows.take(it) }
            fun linkCell(n: NoteInfo) = QueryCell(n.title, n.path)
            if (type == "LIST") {
                val e = cols.firstOrNull()?.second
                return QueryView.Items("DATAVIEW", title, rows.map { r ->
                    val v = e?.let { cell(it.eval(r)) }
                    if (withoutId && v != null) v to null else linkCell(r.note) to v
                })
            }
            val headers = (if (withoutId) emptyList() else listOf("File")) + cols.map { it.first }
            return QueryView.Table("DATAVIEW", title, headers, rows.map { r ->
                (if (withoutId) emptyList() else listOf(linkCell(r.note))) + cols.map { cell(it.second.eval(r)) }
            })
        }

        fun label(from: Int, to: Int) = toks.subList(from, to).joinToString(" ") { if (it.t == T.LINK) "[[${it.s}]]" else it.s }

        /** `status = "active"` → "active", shown in the card's title. `type` is left out. */
        fun hints(src: String): List<String> = Regex("([\\w.]+) = \"([^\"]+)\"").findAll(src)
            .filter { !it.groupValues[1].equals("type", true) }.map { it.groupValues[2] }.toList()

        fun rowOrder(sorts: List<Pair<Expr, Boolean>>): Comparator<Row> = Comparator { a, b ->
            for ((e, desc) in sorts) {
                val x = e.eval(a); val y = e.eval(b)
                val c = when {
                    x == null && y == null -> 0
                    x == null -> 1
                    y == null -> -1
                    else -> compare(x, y).let { if (desc) -it else it }
                }
                if (c != 0) return@Comparator c
            }
            0
        }

        // FROM: "folder", #tag, [[link]], outgoing([[link]]), with and / or / - and brackets.
        fun src(): (NoteInfo) -> Boolean {
            var l = srcAnd()
            while (kw() == "OR" || op("||")) { p++; val a = l; val b = srcAnd(); l = { a(it) || b(it) } }
            return l
        }
        fun srcAnd(): (NoteInfo) -> Boolean {
            var l = srcOne()
            while (kw() == "AND" || op("&&")) { p++; val a = l; val b = srcOne(); l = { a(it) && b(it) } }
            return l
        }
        fun srcOne(): (NoteInfo) -> Boolean {
            if (op("-") || op("!")) { p++; val f = srcOne(); return { !f(it) } }
            if (op("(")) { p++; val f = src(); expect(")"); return f }
            val t = next()
            return when {
                t.t == T.STR -> {
                    val folder = t.s.trim('/')
                    val q: (NoteInfo) -> Boolean = { it.path.startsWith("$folder/") || it.path.equals("$folder.md", true) || it.path == folder }
                    q
                }
                t.t == T.TAG -> { n -> n.tags.any { it == t.s || it.startsWith(t.s + "/") } }
                t.t == T.LINK -> {
                    val target = target(t.s)
                    val name = target?.title?.lowercase() ?: t.s.substringAfterLast('/').lowercase()
                    val q: (NoteInfo) -> Boolean = { n -> name in page(n).links }
                    q
                }
                t.t == T.ID && t.s.equals("outgoing", true) -> {
                    expect("("); val l = next(); expect(")")
                    val links = target(l.s)?.let { page(it).links }.orEmpty()
                    val q: (NoteInfo) -> Boolean = { n -> n.title.lowercase() in links }
                    q
                }
                else -> throw Unsup("FROM ${t.s}")
            }
        }

        fun target(link: String): NoteInfo? = if (link.isBlank()) all.firstOrNull { it.path == here } else VaultBrowse.resolve(all, link, here)

        // Expressions.
        fun expr(): Expr = or()
        fun bin(l: Expr, opS: String, r: Expr, f: (Any?, Any?) -> Any?) = Expr("${l.src} $opS ${r.src}") { row -> f(l.eval(row), r.eval(row)) }

        fun or(): Expr {
            var l = and()
            while (kw() == "OR" || op("||")) { p++; val a = l; val b = and(); l = Expr("${a.src} OR ${b.src}") { r -> truthy(a.eval(r)) || truthy(b.eval(r)) } }
            return l
        }
        fun and(): Expr {
            var l = cmp()
            while (kw() == "AND" || op("&&")) { p++; val a = l; val b = cmp(); l = Expr("${a.src} AND ${b.src}") { r -> truthy(a.eval(r)) && truthy(b.eval(r)) } }
            return l
        }
        fun cmp(): Expr {
            val l = add()
            val o = peek()?.takeIf { it.t == T.OP && it.s in setOf("=", "!=", "<", ">", "<=", ">=") }?.s ?: return l
            p++
            val r = add()
            return bin(l, o, r) { a, b ->
                when (o) {
                    "=" -> same(a, b)
                    "!=" -> !same(a, b)
                    else -> if (a == null || b == null) false else compare(a, b).let {
                        when (o) { "<" -> it < 0; ">" -> it > 0; "<=" -> it <= 0; else -> it >= 0 }
                    }
                }
            }
        }
        fun add(): Expr {
            var l = unary()
            while (op("+") || (op("-") && toks.getOrNull(p + 1)?.t == T.NUM)) {
                val o = next().s
                val a = l; val b = unary()
                l = bin(a, o, b) { x, y ->
                    when {
                        x is Double && y is Double -> if (o == "+") x + y else x - y
                        x is LocalDate && y is Double -> if (o == "+") x.plusDays(y.toLong()) else x.minusDays(y.toLong())
                        o == "+" -> text(x) + text(y)
                        else -> null
                    }
                }
            }
            return l
        }
        fun unary(): Expr {
            if (op("!")) { p++; val e = unary(); return Expr("!${e.src}") { r -> !truthy(e.eval(r)) } }
            if (op("-")) { p++; val e = unary(); return Expr("-${e.src}") { r -> (e.eval(r) as? Double)?.let { -it } } }
            return primary()
        }
        fun primary(): Expr {
            val t = next()
            return when (t.t) {
                T.STR -> Expr("\"${t.s}\"") { t.s }
                T.NUM -> { val d = t.s.toDouble(); Expr(t.s) { d } }
                T.LINK -> { val l = Link(t.s); Expr("[[${t.s}]]") { l } }
                T.TAG -> Expr(t.s) { t.s }
                T.OP -> if (t.s == "(") { val e = expr(); expect(")"); Expr("(${e.src})", e.eval) } else throw Unsup(t.s)
                T.ID -> if (op("(")) call(t.s) else field(t.s)
            }
        }

        fun call(name: String): Expr {
            expect("(")
            val args = mutableListOf<Expr>()
            if (name.equals("date", true) && peek()?.t == T.ID && !op(")")) {
                val w = peek()!!.s.lowercase()
                if (w in setOf("today", "now", "tomorrow", "yesterday")) {
                    p++; expect(")")
                    val d = when (w) { "tomorrow" -> today.plusDays(1); "yesterday" -> today.minusDays(1); else -> today }
                    return Expr("date($w)") { d }
                }
            }
            while (!op(")")) { args.add(expr()); if (op(",")) p++ else break }
            expect(")")
            val src = "$name(${args.joinToString(", ") { it.src }})"
            fun a(r: Row, i: Int) = args.getOrNull(i)?.eval?.invoke(r)
            return when (name.lowercase()) {
                "contains", "icontains", "econtains" -> Expr(src) { r -> contains(a(r, 0), a(r, 1), name.equals("icontains", true)) }
                "date" -> Expr(src) { r -> asDate(a(r, 0)) }
                "dateformat" -> Expr(src) { r ->
                    val d = asDate(a(r, 0)); val f = a(r, 1) as? String
                    if (d == null || f == null) null else runCatching { d.format(DateTimeFormatter.ofPattern(f, Locale.UK)) }.getOrNull()
                }
                "length" -> Expr(src) { r -> when (val v = a(r, 0)) { is List<*> -> v.size.toDouble(); null -> 0.0; else -> text(v).length.toDouble() } }
                "lower" -> Expr(src) { r -> a(r, 0)?.let { text(it).lowercase() } }
                "upper" -> Expr(src) { r -> a(r, 0)?.let { text(it).uppercase() } }
                "default" -> Expr(src) { r -> a(r, 0) ?: a(r, 1) }
                "choice" -> Expr(src) { r -> if (truthy(a(r, 0))) a(r, 1) else a(r, 2) }
                "startswith" -> Expr(src) { r -> text(a(r, 0)).startsWith(text(a(r, 1))) }
                "endswith" -> Expr(src) { r -> text(a(r, 0)).endsWith(text(a(r, 1))) }
                "link" -> Expr(src) { r -> a(r, 0)?.let { Link(text(it)) } }
                else -> throw Unsup("$name()")
            }
        }

        fun field(name: String): Expr {
            val low = name.lowercase()
            when (low) {
                "true" -> return Expr(name) { true }
                "false" -> return Expr(name) { false }
                "null" -> return Expr(name) { null }
            }
            if (low.startsWith("this.")) {
                val me = all.firstOrNull { it.path == here }
                val inner = low.removePrefix("this.")
                return Expr(name) { me?.let { n -> lookup(Row(n), inner) } }
            }
            return Expr(name) { r -> lookup(r, low) }
        }

        fun lookup(r: Row, key: String): Any? {
            val n = r.note
            val pg = page(n)
            r.task?.let { t ->
                when (key) {
                    "completed", "fullycompleted" -> return t.status == 'x' || t.status == 'X'
                    "checked" -> return t.status != ' '
                    "text" -> return t.text
                    "status" -> return t.status.toString()
                    "due" -> return t.due
                    "scheduled" -> return t.scheduled
                    "start" -> return t.start
                    "completion" -> return t.doneDate
                    "tags" -> return t.tags
                    "path" -> return t.path
                }
                if (!key.startsWith("file.")) return null
            }
            when (key) {
                "file.name" -> return n.title
                "file.link", "file" -> return Link(n.path.removeSuffix(".md"), n.title, n.path)
                "file.path" -> return n.path
                "file.folder" -> return n.folder
                "file.ext" -> return "md"
                "file.mtime", "file.mday" -> return day(n.modified)
                "file.ctime", "file.cday" -> return asDate(pg.props["created"]) ?: day(n.modified)
                "file.day" -> return Regex("\\d{4}-\\d{2}-\\d{2}").find(n.title)?.let { runCatching { LocalDate.parse(it.value) }.getOrNull() }
                "file.tags", "file.etags" -> return n.tags
                "tags" -> return n.tags.map { it.removePrefix("#") }
            }
            if (key.startsWith("file.")) throw Unsup(key)
            if (key in pg.props) return pg.props[key]
            return pg.props[key.replace(' ', '-')] ?: pg.props.entries.firstOrNull { it.key.replace(' ', '-') == key }?.value
        }

        fun day(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()

        fun cell(v: Any?): QueryCell = when (v) {
            null -> QueryCell("—")
            is Link -> {
                val path = v.path ?: target(v.target)?.path
                QueryCell(v.name, path)
            }
            is LocalDate -> QueryCell(shortDate(v, today), date = v)
            is List<*> -> {
                val cs = v.map { cell(it) }
                QueryCell(cs.joinToString(", ") { it.text }.ifEmpty { "—" }, cs.singleOrNull()?.link, cs.singleOrNull()?.date)
            }
            else -> QueryCell(text(v))
        }
    }

    private fun text(v: Any?): String = when (v) {
        null -> ""
        is Double -> if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()
        is Link -> v.name
        is List<*> -> v.joinToString(", ") { text(it) }
        else -> v.toString()
    }

    private fun asDate(v: Any?): LocalDate? = when (v) {
        is LocalDate -> v
        is String -> Regex("^(\\d{4}-\\d{2}-\\d{2})").find(v.trim())?.let { runCatching { LocalDate.parse(it.groupValues[1]) }.getOrNull() }
        else -> null
    }

    private fun truthy(v: Any?): Boolean = when (v) {
        null -> false
        is Boolean -> v
        is Double -> v != 0.0
        is String -> v.isNotEmpty()
        is List<*> -> v.isNotEmpty()
        else -> true
    }

    private fun same(a: Any?, b: Any?): Boolean {
        if (a == null || b == null) return a == null && b == null
        if (a is Link || b is Link) return linkName(a).equals(linkName(b), true)
        if (a is LocalDate || b is LocalDate) return asDate(a) != null && asDate(a) == asDate(b)
        if (a is List<*> && b !is List<*>) return a.any { same(it, b) }
        return a == b || (a is Double && b is Double && a == b)
    }

    private fun linkName(v: Any?): String = when (v) {
        is Link -> v.target.substringAfterLast('/').removeSuffix(".md")
        else -> text(v).substringAfterLast('/').removeSuffix(".md")
    }

    private fun compare(a: Any, b: Any): Int = when {
        a is Double && b is Double -> a.compareTo(b)
        asDate(a) != null && asDate(b) != null && (a is LocalDate || b is LocalDate) -> asDate(a)!!.compareTo(asDate(b)!!)
        a is Boolean && b is Boolean -> a.compareTo(b)
        else -> text(a).compareTo(text(b), ignoreCase = true)
    }

    private fun contains(hay: Any?, needle: Any?, ignoreCase: Boolean): Boolean = when (hay) {
        null -> false
        is List<*> -> hay.any { h -> if (h is String && needle is String) (if (ignoreCase) h.equals(needle, true) else h == needle) || h.removePrefix("#") == needle.removePrefix("#") else same(h, needle) }
        is Link -> linkName(hay).contains(text(needle), ignoreCase)
        else -> text(hay).contains(text(needle), ignoreCase)
    }
}
