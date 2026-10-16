package uk.tomlowndes.capture

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime

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

/**
 * Reads and writes today's daily note, and manages the sections of the Daily Note template.
 * The template is the source of truth for which sections exist; `99 - Meta/capture-config.json`
 * only remembers the type and time slot of sections added from the app. The note edits themselves are in [DailyNote].
 */
class DailyLogStore(private val vault: Vault, private val prefs: VaultPrefs) {
    /** [path] includes the extension. */
    private fun fileAt(path: String, create: Boolean): String? {
        val e = vault.entry(path)
        if (e != null) return path.takeIf { !e.isDir }
        if (!create) return null
        val name = nameOf(path)
        return vault.create(parentOf(path), name.substringBeforeLast('.'), name.substringAfterLast('.', "md"))
    }

    private fun read(path: String) = VaultIo.read(vault, path)
    /** Read-modify-write of [path]; redone on the fresh copy if a sync lands in between. */
    private fun edit(path: String, change: (MutableList<String>) -> Unit) = VaultIo.edit(vault, path) { ls ->
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

    fun todayPath(date: LocalDate = LocalDate.now()): String = cfg().let { DailyNote.notePath(it.folder, it.format, date) }

    /** Name shown in the UI, e.g. 09-10-2026. */
    fun todayName(): String = todayPath().substringAfterLast('/').removeSuffix(".md")

    private fun configFile(create: Boolean): String? = fileAt("99 - Meta/capture-config.json", create)

    private fun customSections(): List<JSONObject> {
        val arr = configFile(false)?.let { runCatching { JSONObject(read(it)).optJSONArray("sections") }.getOrNull() } ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    // ---- fields ----

    fun headings(): List<String> = fileAt(cfg().template, false)?.let { DailyNote.headings(read(it)) } ?: emptyList()

    fun fields(): List<LogField> {
        val custom = customSections().mapNotNull { o ->
            val kind = runCatching { Kind.valueOf(o.getString("kind")) }.getOrNull() ?: return@mapNotNull null
            val title = o.getString("title")
            val id = o.getString("id")
            val choices = o.optJSONArray("choices")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
            val where = if (kind == Kind.CHOICE || kind == Kind.YESNO) Where.Frontmatter(id) else Where.Body(title)
            LogField(id, title, kind, runCatching { Slot.valueOf(o.getString("slot")) }.getOrDefault(Slot.ANYTIME), where, title, choices, true)
        }
        return DailyNote.fields(headings(), custom)
    }

    // ---- reading / writing today's note ----

    fun read(fields: List<LogField>): Map<String, Value> {
        val note = fileAt(todayPath(), false) ?: return emptyMap()
        return DailyNote.read(DailyNote.lines(read(note)), fields)
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
        var skipped = emptyList<LogField>()
        val template = renderTemplate(LocalDate.now())
        edit(note) { ls -> skipped = DailyNote.merge(ls, fields, values, loaded, template) }
        return Saved(skipped, read(fields), created)
    }

    private fun renderTemplate(date: LocalDate): String =
        DailyNote.renderTemplate(fileAt(cfg().template, false)?.let { read(it) }, date, LocalDateTime.now())

    /** Adds `## title` to the template (and today's note) after the section named [after]; remembers its type. */
    fun addSection(title: String, kind: Kind, slot: Slot, choices: List<String>, after: String?) {
        require(Md.norm(title).isNotEmpty()) { "Give the section a name" }
        val existing = customSections()
        val id = DailyNote.newSectionId(title, existing.map { it.getString("id") })
        val apply = { ls: MutableList<String> -> DailyNote.addSection(ls, title, kind, choices, id, after) }
        val template = fileAt(cfg().template, false) ?: error("Daily Note template not found")
        edit(template, apply)
        fileAt(todayPath(), false)?.let { n -> edit(n, apply) }

        val arr = JSONArray().also { a -> existing.forEach { a.put(it) } }
        arr.put(JSONObject().put("id", id).put("title", title).put("kind", kind.name).put("slot", slot.name)
            .put("choices", JSONArray(choices)))
        VaultIo.write(vault, configFile(true) ?: error("Cannot write config"), JSONObject().put("sections", arr).toString(2))
    }
}
