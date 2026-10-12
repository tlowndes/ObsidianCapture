package uk.tomlowndes.capture

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.IsoFields
import java.util.Locale

/**
 * The edits [DailyLogStore] makes to the daily note and its template, as plain functions on lines so they can be
 * tested without a phone.
 */
object DailyNote {
    val BUILTIN = listOf(
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

    fun lines(text: String) = text.split("\n").map { it.trimEnd('\r') }.toMutableList()

    // ---- paths and template ----

    private val MOMENT_TOKEN = Regex("""\[[^\]]*]|YYYY|YY|Y|GGGG|GG|gggg|gg|Qo|Q|MMMM|MMM|Mo|MM|M|DDDD|DDDo|DDD|Do|DD|D|dddd|ddd|do|dd|d|E|e|WW|Wo|W|ww|wo|w""")

    private fun ordinal(n: Int) = "$n" + when {
        n % 100 in 11..13 -> "th"
        n % 10 == 1 -> "st"
        n % 10 == 2 -> "nd"
        n % 10 == 3 -> "rd"
        else -> "th"
    }

    /**
     * Formats [date] with a Moment.js format, as Obsidian does for daily note names. Covers the date tokens;
     * `[text]` is literal and anything else passes through. Week tokens (`w`, `W`, `gggg`, `GGGG`) use ISO weeks.
     */
    fun formatMoment(format: String, date: LocalDate): String = MOMENT_TOKEN.replace(format) { m ->
        val t = m.value
        val week = date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
        val weekYear = date.get(IsoFields.WEEK_BASED_YEAR)
        val dow = date.dayOfWeek
        when (t) {
            "YYYY", "Y" -> "${date.year}"
            "YY" -> "%02d".format(date.year % 100)
            "GGGG", "gggg" -> "$weekYear"
            "GG", "gg" -> "%02d".format(weekYear % 100)
            "Q" -> "${date.get(IsoFields.QUARTER_OF_YEAR)}"
            "Qo" -> ordinal(date.get(IsoFields.QUARTER_OF_YEAR))
            "MMMM" -> date.month.getDisplayName(TextStyle.FULL, Locale.UK)
            "MMM" -> date.month.getDisplayName(TextStyle.SHORT, Locale.UK).take(3)
            "MM" -> "%02d".format(date.monthValue)
            "Mo" -> ordinal(date.monthValue)
            "M" -> "${date.monthValue}"
            "DDDD" -> "%03d".format(date.dayOfYear)
            "DDDo" -> ordinal(date.dayOfYear)
            "DDD" -> "${date.dayOfYear}"
            "DD" -> "%02d".format(date.dayOfMonth)
            "Do" -> ordinal(date.dayOfMonth)
            "D" -> "${date.dayOfMonth}"
            "dddd" -> dow.getDisplayName(TextStyle.FULL, Locale.UK)
            "ddd" -> dow.getDisplayName(TextStyle.SHORT, Locale.UK).take(3)
            "dd" -> dow.getDisplayName(TextStyle.SHORT, Locale.UK).take(2)
            "d" -> "${dow.value % 7}"
            "do" -> ordinal(dow.value % 7)
            "E" -> "${dow.value}"
            "e" -> "${dow.value - 1}"
            "WW", "ww" -> "%02d".format(week)
            "Wo", "wo" -> ordinal(week)
            "W", "w" -> "$week"
            else -> t.removePrefix("[").removeSuffix("]")
        }
    }

    /** Vault path of the daily note for [date], with `.md`. */
    fun notePath(folder: String, format: String, date: LocalDate) = "$folder/${formatMoment(format, date)}.md"

    /** Fills in the Templater date tags of the Daily Note template; with no template, a minimal note. */
    fun renderTemplate(template: String?, date: LocalDate, now: LocalDateTime): String {
        val t = template ?: return "---\ndate: $date\ntype: daily\n---\n\n# $date\n"
        val stamp = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        return t
            .replace(Regex("<%\\s*moment\\(tp\\.file\\.title,\\s*\"([^\"]*)\"\\)\\.format\\(\"([^\"]*)\"\\)\\s*%>")) {
                formatMoment(it.groupValues[2], date)
            }
            .replace(Regex("<%\\s*tp\\.file\\.(creation|last_modified)_date\\(\\)\\s*%>"), stamp)
    }

    /** `##` headings of the template, without the `## `. */
    fun headings(template: String): List<String> =
        lines(template).filter { it.startsWith("## ") }.map { it.removePrefix("## ").trim() }

    /** Built-in fields whose section is in the template, plus [custom] ones, in template order. */
    fun fields(heads: List<String>, custom: List<LogField>): List<LogField> {
        val builtin = if (heads.isEmpty()) BUILTIN else BUILTIN.filter { f -> heads.any { Md.norm(it) == Md.norm(f.section) } }
        return (builtin + custom).withIndex().sortedWith(
            compareBy({ (_, f) -> heads.indexOfFirst { Md.norm(it) == Md.norm(f.section) }.let { if (it < 0) Int.MAX_VALUE else it } }, { it.index })
        ).map { it.value }
    }

    // ---- adding a section ----

    /** A frontmatter-safe id for a new section that doesn't clash with a built-in or [taken] one. */
    fun newSectionId(title: String, taken: List<String>): String {
        var id = Md.norm(title).take(20).ifEmpty { "section" }
        while (BUILTIN.any { it.id == id } || id in taken) id += "_"
        return id
    }

    /** Adds `## title` after the section named [after] (or at the end). Does nothing if it's already there. */
    fun addSection(ls: MutableList<String>, title: String, kind: Kind, choices: List<String>, id: String, after: String?) {
        if (Md.headingIndex(ls, title) >= 0) return
        val body = when (kind) {
            Kind.TEXT -> listOf("")
            Kind.BULLETS -> listOf("- ")
            Kind.NUMBERED -> listOf("1. ")
            Kind.CHECKLIST -> listOf("- [ ] ")
            Kind.CHOICE -> listOf("`INPUT[inlineSelect(${choices.joinToString(", ") { "option($it)" }}):$id]`")
            Kind.YESNO -> listOf("`INPUT[toggle:$id]`")
        }
        val r = after?.let { Md.sectionRange(ls, it) }
        var at = r?.let { it.last + 1 } ?: ls.size
        // The last section also holds the template's hidden Meta Bind buttons: go in front of them.
        if (r != null && at >= ls.size) r.firstOrNull { ls[it].startsWith("```meta-bind-button") }?.let { at = it }
        ls.addAll(at.coerceIn(0, ls.size), listOf("## $title", "") + body + listOf(""))
        if (kind == Kind.CHOICE) Md.fmSet(ls, id, "") else if (kind == Kind.YESNO) Md.fmSet(ls, id, "false")
    }

    // ---- saving the form ----

    /**
     * Writes into [ls] only the fields whose value differs from [loaded] (what the form showed when it was read).
     * A field changed both here and in the note is left alone and returned. A blank note is first filled from [template].
     */
    fun merge(ls: MutableList<String>, fields: List<LogField>, values: Map<String, Value>, loaded: Map<String, Value>, template: String): List<LogField> {
        val skipped = mutableListOf<LogField>()
        if (ls.all { it.isBlank() }) { ls.clear(); ls.addAll(lines(template)) }
        for (f in fields) {
            val v = values[f.id] ?: continue
            if (same(v, loaded[f.id])) continue
            val now = readValue(ls, f)
            if (!same(now, loaded[f.id]) && !same(now, v)) { skipped.add(f); continue }
            writeValue(ls, f, v)
        }
        return skipped
    }

    fun read(ls: List<String>, fields: List<LogField>): Map<String, Value> =
        fields.mapNotNull { f -> readValue(ls, f)?.let { f.id to it } }.toMap()

    /** Equal once blanks and spacing are ignored; a missing value counts as empty. */
    fun same(a: Value?, b: Value?): Boolean = norm(a) == norm(b)

    private fun norm(v: Value?): Any = when (v) {
        null -> ""
        is Value.Text -> v.s.trim()
        is Value.Items -> v.items.map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { "" }
        is Value.Checks -> v.items.filter { it.second.isNotBlank() }.map { it.first to it.second.trim() }.ifEmpty { "" }
        is Value.Flag -> if (v.on) true else ""
    }

    // ---- per-location read/write ----

    private fun section(ls: List<String>, name: String) = Md.sectionRange(ls, name)

    fun readValue(ls: List<String>, f: LogField): Value? = when (val w = f.where) {
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

    fun writeValue(ls: MutableList<String>, f: LogField, v: Value) {
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
