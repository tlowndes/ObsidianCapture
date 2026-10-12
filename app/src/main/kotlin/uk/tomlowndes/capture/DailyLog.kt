package uk.tomlowndes.capture

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

enum class Kind { TEXT, BULLETS, NUMBERED, CHECKLIST, CHOICE, YESNO }
enum class Slot { MORNING, EVENING, ANYTIME }

/** Where a field's value lives inside the daily note. */
sealed interface Where {
    /** A frontmatter property (Meta Bind inputs in the template are bound to these). */
    data class Frontmatter(val key: String) : Where
    /** The first plain `- ` bullet under a `##` section. */
    data class FirstBullet(val section: String) : Where
    /** A `- **Label:** value` line (extra bullets go on indented lines below it). */
    data class Labelled(val section: String, val label: String) : Where
    /** `1. 2. 3.` lines under a section. */
    data class Numbered(val section: String) : Where
    /** The list or text body of a whole custom section. */
    data class Body(val section: String) : Where
}

data class LogField(
    val id: String, val label: String, val kind: Kind, val slot: Slot, val where: Where, val section: String,
    val choices: List<String> = emptyList(), val custom: Boolean = false
)

sealed interface Value {
    data class Text(val s: String) : Value
    data class Items(val items: List<String>) : Value
    data class Checks(val items: List<Pair<Boolean, String>>) : Value
    data class Flag(val on: Boolean) : Value

    fun isEmpty(): Boolean = when (this) {
        is Text -> s.isBlank()
        is Items -> items.all { it.isBlank() }
        is Checks -> items.all { it.second.isBlank() }
        is Flag -> !on
    }
}

private val BUILTIN = listOf(
    LogField("do_first", "🎯 Do this first", Kind.TEXT, Slot.MORNING, Where.FirstBullet("Do this first"), "Do this first"),
    LogField("energy", "Energy", Kind.CHOICE, Slot.MORNING, Where.Frontmatter("energy"), "Do this first", listOf("Low", "Medium", "High")),
    LogField("overwhelmed", "Overwhelmed?", Kind.YESNO, Slot.MORNING, Where.Frontmatter("overwhelmed"), "Do this first"),
    LogField("hard_day", "If today is hard, the one thing that counts", Kind.TEXT, Slot.MORNING,
        Where.Labelled("Do this first", "If today is hard, the one thing that counts"), "Do this first"),
    LogField("top3", "🌤️ Top 3", Kind.NUMBERED, Slot.MORNING, Where.Numbered("Top 3"), "Top 3"),
    LogField("went_well", "Went well", Kind.BULLETS, Slot.EVENING, Where.Labelled("Wind down", "Went well"), "Wind down"),
    LogField("leave", "Leave for tomorrow", Kind.BULLETS, Slot.EVENING, Where.Labelled("Wind down", "Leave for tomorrow"), "Wind down"),
    LogField("first_step", "First step tomorrow", Kind.TEXT, Slot.EVENING, Where.Labelled("Wind down", "First step tomorrow"), "Wind down"),
)

private val LIST_ITEM = Regex("^\\s*-\\s?(?!\\*\\*)(?!\\[[ xX]]\\s)(.*)$")
private val CHECK_ITEM = Regex("^\\s*-\\s+\\[([ xX])]\\s?(.*)$")
private val NUM_ITEM = Regex("^\\s*\\d+\\.\\s?(.*)$")
private val SUB_ITEM = Regex("^\\s{2,}-\\s+(.*)$")

/**
 * Reads and writes today's daily note, and manages the sections of the Daily Note template.
 * The template is the source of truth for which sections exist; `99 - Meta/capture-config.json`
 * only remembers the type and time slot of sections added from the app.
 */
class DailyLogStore(private val ctx: Context, treeUri: Uri, private val prefs: AppPrefs) {
    private val root = DocumentFile.fromTreeUri(ctx, treeUri) ?: error("Vault folder unavailable")

    private fun dir(path: String, create: Boolean): DocumentFile? {
        var cur = root
        for (part in path.split("/").filter { it.isNotBlank() }) {
            cur = cur.findFile(part)?.takeIf { it.isDirectory }
                ?: (if (create) cur.createDirectory(part) else null) ?: return null
        }
        return cur
    }

    /** [path] includes the `.md` extension. */
    private fun fileAt(path: String, create: Boolean): DocumentFile? {
        val parent = path.substringBeforeLast('/', "")
        val name = path.substringAfterLast('/')
        val d = dir(parent, create) ?: return null
        return d.findFile(name) ?: if (create) d.createFile("text/markdown", name.removeSuffix(".md")) else null
    }

    private fun read(f: DocumentFile) = VaultIo.read(ctx, f)
    private fun lines(text: String) = text.split("\n").map { it.trimEnd('\r') }.toMutableList()
    /** Read-modify-write of [f]; redone on the fresh copy if a sync lands in between. */
    private fun edit(f: DocumentFile, change: (MutableList<String>) -> Unit) = VaultIo.edit(ctx, f) { ls ->
        ls.replaceAll { it.trimEnd('\r') }
        change(ls)
    }

    // ---- configuration ----

    private class Cfg(val folder: String, val format: String, val template: String)

    private fun cfg(): Cfg {
        val json = fileAt(".obsidian/daily-notes.json", false)?.let { runCatching { JSONObject(read(it)) }.getOrNull() }
        val folder = prefs.dailyDir.ifBlank { json?.optString("folder")?.ifBlank { null } ?: "04 - Journal/01 Daily" }
        val format = json?.optString("format")?.ifBlank { null } ?: "YYYY/MM/DD-MM-YYYY"
        val template = json?.optString("template")?.ifBlank { null }?.let { if (it.endsWith(".md")) it else "$it.md" }
            ?: "99 - Meta/Templates/(TEMPLATE) Daily Note.md"
        return Cfg(folder, format, template)
    }

    private fun momentToJava(f: String) = f.replace("YYYY", "yyyy").replace("DD", "dd").replace("dddd", "EEEE")

    fun todayPath(date: LocalDate = LocalDate.now()): String {
        val c = cfg()
        return "${c.folder}/${date.format(DateTimeFormatter.ofPattern(momentToJava(c.format)))}.md"
    }

    /** Name shown in the UI, e.g. 09-10-2026. */
    fun todayName(): String = todayPath().substringAfterLast('/').removeSuffix(".md")

    private fun configFile(create: Boolean): DocumentFile? {
        val dir = dir("99 - Meta", create) ?: return null
        return dir.findFile("capture-config.json") ?: if (create) dir.createFile("application/json", "capture-config.json") else null
    }

    private fun customSections(): List<JSONObject> {
        val arr = configFile(false)?.let { runCatching { JSONObject(read(it)).optJSONArray("sections") }.getOrNull() } ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    // ---- fields ----

    fun headings(): List<String> {
        val t = fileAt(cfg().template, false) ?: return emptyList()
        return lines(read(t)).filter { it.startsWith("## ") }.map { it.removePrefix("## ").trim() }
    }

    fun fields(): List<LogField> {
        val heads = headings()
        val custom = customSections().mapNotNull { o ->
            val kind = runCatching { Kind.valueOf(o.getString("kind")) }.getOrNull() ?: return@mapNotNull null
            val title = o.getString("title")
            val id = o.getString("id")
            val choices = o.optJSONArray("choices")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
            val where = if (kind == Kind.CHOICE || kind == Kind.YESNO) Where.Frontmatter(id) else Where.Body(title)
            LogField(id, title, kind, runCatching { Slot.valueOf(o.getString("slot")) }.getOrDefault(Slot.ANYTIME), where, title, choices, true)
        }
        val builtin = if (heads.isEmpty()) BUILTIN else BUILTIN.filter { f -> heads.any { Md.norm(it) == Md.norm(f.section) } }
        return (builtin + custom).withIndex().sortedWith(
            compareBy({ (_, f) -> heads.indexOfFirst { Md.norm(it) == Md.norm(f.section) }.let { if (it < 0) Int.MAX_VALUE else it } }, { it.index })
        ).map { it.value }
    }

    // ---- reading / writing today's note ----

    fun read(fields: List<LogField>): Map<String, Value> {
        val note = fileAt(todayPath(), false) ?: return emptyMap()
        val ls = lines(read(note))
        return fields.mapNotNull { f -> readValue(ls, f)?.let { f.id to it } }.toMap()
    }

    /** True if any field in [slot] already has something written in today's note. */
    fun isFilled(slot: Slot): Boolean {
        val fs = fields().filter { it.slot == slot }
        return read(fs).values.any { !it.isEmpty() }
    }

    /**
     * Fields left alone by [save] because they changed in the note since the form was loaded. [created] is true when
     * today's note wasn't on the phone yet: if Obsidian Sync later brings the desktop's copy, it will merge the two.
     */
    class Saved(val skipped: List<LogField>, val inNote: Map<String, Value>, val created: Boolean)

    /**
     * Writes only the fields whose value differs from [loaded] (what the form showed when it was read), so sections
     * filled in on another device meanwhile are kept. A field changed both here and in the note is skipped, and
     * reported in [Saved.skipped]; [Saved.inNote] is what the note holds now, to use as the next [loaded].
     */
    fun save(fields: List<LogField>, values: Map<String, Value>, loaded: Map<String, Value>): Saved {
        val created = fileAt(todayPath(), false) == null
        val note = fileAt(todayPath(), true) ?: error("Cannot create today's note")
        val skipped = mutableListOf<LogField>()
        val template = renderTemplate(LocalDate.now())
        edit(note) { ls ->
            skipped.clear()
            if (ls.all { it.isBlank() }) { ls.clear(); ls.addAll(lines(template)) }
            for (f in fields) {
                val v = values[f.id] ?: continue
                if (same(v, loaded[f.id])) continue
                val now = readValue(ls, f)
                if (!same(now, loaded[f.id]) && !same(now, v)) { skipped.add(f); continue }
                writeValue(ls, f, v)
            }
        }
        return Saved(skipped, read(fields), created)
    }

    /** Equal once blanks and spacing are ignored; a missing value counts as empty. */
    private fun same(a: Value?, b: Value?): Boolean = norm(a) == norm(b)

    private fun norm(v: Value?): Any = when (v) {
        null -> ""
        is Value.Text -> v.s.trim()
        is Value.Items -> v.items.map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { "" }
        is Value.Checks -> v.items.filter { it.second.isNotBlank() }.map { it.first to it.second.trim() }.ifEmpty { "" }
        is Value.Flag -> if (v.on) true else ""
    }

    private fun renderTemplate(date: LocalDate): String {
        val t = fileAt(cfg().template, false)?.let { read(it) } ?: return "---\ndate: $date\ntype: daily\n---\n\n# $date\n"
        val now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        return t
            .replace(Regex("<%\\s*moment\\(tp\\.file\\.title,\\s*\"([^\"]*)\"\\)\\.format\\(\"([^\"]*)\"\\)\\s*%>")) {
                date.format(DateTimeFormatter.ofPattern(momentToJava(it.groupValues[2])))
            }
            .replace(Regex("<%\\s*tp\\.file\\.(creation|last_modified)_date\\(\\)\\s*%>"), now)
    }

    /** Adds `## title` to the template (and today's note) after the section named [after]; remembers its type. */
    fun addSection(title: String, kind: Kind, slot: Slot, choices: List<String>, after: String?) {
        require(Md.norm(title).isNotEmpty()) { "Give the section a name" }
        val existing = customSections()
        var id = Md.norm(title).take(20).ifEmpty { "section" }
        while (BUILTIN.any { it.id == id } || existing.any { it.getString("id") == id }) id += "_"
        val body = when (kind) {
            Kind.TEXT -> listOf("")
            Kind.BULLETS -> listOf("- ")
            Kind.NUMBERED -> listOf("1. ")
            Kind.CHECKLIST -> listOf("- [ ] ")
            Kind.CHOICE -> listOf("`INPUT[inlineSelect(${choices.joinToString(", ") { "option($it)" }}):$id]`")
            Kind.YESNO -> listOf("`INPUT[toggle:$id]`")
        }
        fun apply(ls: MutableList<String>) {
            if (Md.headingIndex(ls, title) >= 0) return
            val r = after?.let { Md.sectionRange(ls, it) }
            var at = r?.let { it.last + 1 } ?: ls.size
            // The last section also holds the template's hidden Meta Bind buttons: go in front of them.
            if (r != null && at >= ls.size) r.firstOrNull { ls[it].startsWith("```meta-bind-button") }?.let { at = it }
            ls.addAll(at.coerceIn(0, ls.size), listOf("## $title", "") + body + listOf(""))
            if (kind == Kind.CHOICE) Md.fmSet(ls, id, "") else if (kind == Kind.YESNO) Md.fmSet(ls, id, "false")
        }
        val template = fileAt(cfg().template, false) ?: error("Daily Note template not found")
        edit(template) { apply(it) }
        fileAt(todayPath(), false)?.let { n -> edit(n) { apply(it) } }

        val arr = JSONArray().also { a -> existing.forEach { a.put(it) } }
        arr.put(JSONObject().put("id", id).put("title", title).put("kind", kind.name).put("slot", slot.name)
            .put("choices", JSONArray(choices)))
        VaultIo.write(ctx, configFile(true) ?: error("Cannot write config"), JSONObject().put("sections", arr).toString(2))
    }

    // ---- per-location read/write ----

    private fun section(ls: List<String>, name: String) = Md.sectionRange(ls, name)

    private fun readValue(ls: List<String>, f: LogField): Value? = when (val w = f.where) {
        is Where.Frontmatter -> Md.fmGet(ls, w.key)?.trim('"', '\'')?.let {
            if (f.kind == Kind.YESNO) Value.Flag(it.equals("true", true)) else Value.Text(it)
        }
        is Where.FirstBullet -> section(ls, w.section)?.let { r ->
            r.firstNotNullOfOrNull { LIST_ITEM.find(ls[it])?.groupValues?.get(1) }?.let { Value.Text(it.trim()) }
        }
        is Where.Labelled -> section(ls, w.section)?.let { r ->
            val re = labelRegex(w.label)
            val i = r.firstOrNull { re.containsMatchIn(ls[it]) } ?: return@let null
            val first = re.find(ls[i])!!.groupValues[1].trim()
            val extras = mutableListOf<String>()
            var j = i + 1
            while (j < ls.size && SUB_ITEM.matches(ls[j])) { extras.add(SUB_ITEM.find(ls[j])!!.groupValues[1].trim()); j++ }
            if (f.kind == Kind.TEXT) Value.Text(first) else Value.Items((listOf(first) + extras).filter { it.isNotBlank() })
        }
        is Where.Numbered -> section(ls, w.section)?.let { r ->
            Value.Items(r.mapNotNull { NUM_ITEM.find(ls[it])?.groupValues?.get(1)?.trim() })
        }
        is Where.Body -> section(ls, w.section)?.let { r ->
            when (f.kind) {
                Kind.CHECKLIST -> Value.Checks(r.mapNotNull { CHECK_ITEM.find(ls[it]) }.map { (it.groupValues[1] != " ") to it.groupValues[2].trim() })
                Kind.BULLETS -> Value.Items(r.mapNotNull { LIST_ITEM.find(ls[it])?.groupValues?.get(1)?.trim() })
                Kind.NUMBERED -> Value.Items(r.mapNotNull { NUM_ITEM.find(ls[it])?.groupValues?.get(1)?.trim() })
                else -> Value.Text(r.map { ls[it] }.filter { it.isNotBlank() }.joinToString("\n").trim())
            }
        }
    }

    private fun labelRegex(label: String) = Regex("\\*\\*${Regex.escape(label)}:?\\*\\*:?\\s?(.*)$")

    private fun writeValue(ls: MutableList<String>, f: LogField, v: Value) {
        when (val w = f.where) {
            is Where.Frontmatter -> when (v) {
                is Value.Flag -> Md.fmSet(ls, w.key, v.on.toString())
                is Value.Text -> Md.fmSet(ls, w.key, v.s)
                else -> {}
            }
            is Where.FirstBullet -> {
                val r = section(ls, w.section) ?: return
                val i = r.firstOrNull { LIST_ITEM.containsMatchIn(ls[it]) }
                val text = ((v as? Value.Text)?.s ?: "").trim().replace("\n", " ")
                if (i != null) ls[i] = "- $text".trimEnd().let { if (text.isEmpty()) "- " else it }
                else {
                    var at = r.first
                    while (at <= r.last && (ls[at].isBlank() || ls[at].startsWith(">"))) at++
                    ls.add(at.coerceAtMost(ls.size), "- $text")
                }
            }
            is Where.Labelled -> {
                val r = section(ls, w.section) ?: return
                val re = labelRegex(w.label)
                val i = r.firstOrNull { re.containsMatchIn(ls[it]) }
                val items = when (v) {
                    is Value.Text -> listOf(v.s.trim().replace("\n", " "))
                    is Value.Items -> v.items.map { it.trim() }.filter { it.isNotEmpty() }
                    else -> return
                }
                val first = items.firstOrNull().orEmpty()
                val extras = items.drop(1).map { "  - $it" }
                if (i == null) {
                    var at = r.last + 1
                    while (at > r.first && ls[at - 1].isBlank()) at--
                    ls.addAll(at, listOf("- **${w.label}:** $first".trimEnd()) + extras)
                } else {
                    val m = re.find(ls[i])!!
                    val prefix = ls[i].substring(0, ls[i].length - m.groupValues[1].length)
                    ls[i] = (prefix.trimEnd() + " " + first).trimEnd()
                    var j = i + 1
                    while (j < ls.size && SUB_ITEM.matches(ls[j])) ls.removeAt(j)
                    ls.addAll(i + 1, extras)
                }
            }
            is Where.Numbered -> {
                val r = section(ls, w.section) ?: return
                val items = ((v as? Value.Items)?.items ?: return).map { it.trim() }.filter { it.isNotEmpty() }
                val padded = items + List((3 - items.size).coerceAtLeast(0)) { "" }
                val newLines = padded.mapIndexed { n, s -> "${n + 1}. $s".trimEnd().let { if (s.isEmpty()) "${n + 1}. " else it } }
                val old = r.filter { NUM_ITEM.containsMatchIn(ls[it]) }
                val at = old.firstOrNull() ?: r.first.let { s -> var a = s; while (a <= r.last && ls[a].isBlank()) a++; a }
                old.reversed().forEach { ls.removeAt(it) }
                ls.addAll(at.coerceAtMost(ls.size), newLines)
            }
            is Where.Body -> {
                val r = section(ls, w.section) ?: return
                val newLines: List<String> = when (v) {
                    is Value.Checks -> v.items.filter { it.second.isNotBlank() }.map { "- [${if (it.first) "x" else " "}] ${it.second.trim()}" }
                    is Value.Items -> v.items.filter { it.isNotBlank() }.mapIndexed { n, s ->
                        if (f.kind == Kind.NUMBERED) "${n + 1}. ${s.trim()}" else "- ${s.trim()}"
                    }
                    is Value.Text -> v.s.trim().split("\n")
                    else -> return
                }
                if (f.kind == Kind.TEXT) {
                    repeat(r.last - r.first + 1) { ls.removeAt(r.first) }
                    ls.addAll(r.first, listOf("") + newLines + listOf(""))
                } else {
                    val isItem = { s: String -> LIST_ITEM.containsMatchIn(s) || CHECK_ITEM.containsMatchIn(s) || NUM_ITEM.containsMatchIn(s) }
                    val old = r.filter { isItem(ls[it]) }
                    val at = old.firstOrNull() ?: (r.first + 1).coerceAtMost(ls.size)
                    old.reversed().forEach { ls.removeAt(it) }
                    ls.addAll(at.coerceAtMost(ls.size), newLines.ifEmpty { listOf(if (f.kind == Kind.CHECKLIST) "- [ ] " else "- ") })
                }
            }
        }
    }
}
