package uk.tomlowndes.capture

import java.time.LocalDate
import java.time.MonthDay
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** One person: a note in People/ with birthday and "keep in touch" in the frontmatter, gift ideas and notes below. */
data class Person(
    val path: String, val name: String, val birthday: MonthDay? = null, val born: Int? = null,
    val every: Int? = null, val lastSpoke: LocalDate? = null,
    val gifts: List<String> = emptyList(), val notes: List<String> = emptyList(),
) {
    fun nextBirthday(today: LocalDate): LocalDate? = birthday?.let { b ->
        val d = b.atYear(today.year)
        if (d.isBefore(today)) b.atYear(today.year + 1) else d
    }
    fun daysToBirthday(today: LocalDate): Long? = nextBirthday(today)?.let { ChronoUnit.DAYS.between(today, it) }
    /** Days past the "keep in touch" gap, or null when it's fine or not set. */
    fun overdue(today: LocalDate): Long? {
        val e = every ?: return null
        val last = lastSpoke ?: return e.toLong()
        return (ChronoUnit.DAYS.between(last, today) - e).takeIf { it >= 0 }
    }
}

/** Something coming up on the People list and the Deck: a birthday, or someone you haven't spoken to in a while. */
data class PersonNudge(val person: Person, val birthday: LocalDate?, val days: Long, val text: String)

/**
 * People and birthdays (body-people-errands-v1). No Claude: notes in People/ with `birthday: 1958-11-21` (or `--11-21`
 * when the year isn't known) and `keep_in_touch: 21` (days). A week before a birthday a gift task is added once.
 */
object People {
    const val DIR = "People"
    const val GIFT_DAYS = 7L
    const val SOON_DAYS = 14L
    private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)
    private val SHORT = DateTimeFormatter.ofPattern("d MMM", Locale.UK)

    /** People/ at the top, or inside an area folder ("02 - Area/People") if that's where it is. */
    fun folder(v: Vault): String {
        if (v.entry(DIR)?.isDir == true) return DIR
        return runCatching { v.list("").filter { it.isDir }.firstOrNull { d -> v.entry("${d.name}/$DIR")?.isDir == true }?.let { "${it.name}/$DIR" } }.getOrNull() ?: DIR
    }

    fun parse(path: String, text: String): Person {
        val lines = text.replace("\r", "").split("\n")
        fun fm(k: String) = Md.fmGet(lines, k)?.trim()?.trim('"', '\'')?.ifEmpty { null }
        val bday = fm("birthday")
        val (md, year) = when {
            bday == null -> null to null
            bday.startsWith("--") -> runCatching { MonthDay.parse(bday) }.getOrNull() to null
            else -> runCatching { LocalDate.parse(bday.take(10)).let { MonthDay.from(it) to it.year } }.getOrDefault(null to null)
        }
        fun section(n: String) = Md.sectionRange(lines, n)?.let { r -> lines.subList(r.first, r.last + 1) }.orEmpty()
            .map { it.trim() }.filter { it.startsWith("- ") || it.startsWith("• ") }.map { it.drop(2).trim() }.filter { it.isNotEmpty() }
        return Person(
            path, fm("name") ?: VaultTasks.noteName(path), md, year,
            fm("keep_in_touch")?.filter { it.isDigit() }?.toIntOrNull()?.takeIf { it > 0 },
            fm("last_spoke")?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() },
            section("Gift ideas"), section("Notes"),
        )
    }

    fun list(v: Vault): List<Person> {
        val dir = folder(v)
        return runCatching { v.list(dir) }.getOrDefault(emptyList()).filter { !it.isDir && it.name.endsWith(".md") }
            .mapNotNull { e -> runCatching { parse("$dir/${e.name}", VaultIo.read(v, "$dir/${e.name}")) }.getOrNull() }
            .sortedBy { it.name.lowercase() }
    }

    fun find(people: List<Person>, name: String): Person? {
        val n = name.trim().lowercase()
        return people.firstOrNull { it.name.lowercase() == n } ?: people.firstOrNull { it.name.lowercase().startsWith(n) }
    }

    fun create(v: Vault, name: String, birthday: String? = null, every: Int? = null): String {
        val dir = folder(v)
        v.mkdirs(dir)
        val path = v.create(dir, name.trim().replace(Regex("[\\\\/:*?\"<>|#^\\[\\]]"), "-"), "md")
        VaultIo.write(v, path, "---\ntype: person\n" + (birthday?.let { "birthday: $it\n" } ?: "") + (every?.let { "keep_in_touch: $it\n" } ?: "") +
            "---\n\n## Gift ideas\n\n## Notes\n")
        return path
    }

    /** Birthdays in the next two weeks, then people past their keep-in-touch gap, most pressing first. */
    fun comingUp(people: List<Person>, today: LocalDate): List<PersonNudge> {
        val birthdays = people.mapNotNull { p ->
            val d = p.nextBirthday(today) ?: return@mapNotNull null
            val days = ChronoUnit.DAYS.between(today, d).takeIf { it <= SOON_DAYS } ?: return@mapNotNull null
            val gifts = p.gifts.size.takeIf { it > 0 }?.let { " · $it gift idea${if (it == 1) "" else "s"}" } ?: ""
            PersonNudge(p, d, days, "${d.format(DAY)} · ${if (days == 0L) "today" else if (days == 1L) "tomorrow" else "$days days"}$gifts")
        }.sortedBy { it.days }
        val touch = people.mapNotNull { p ->
            val late = p.overdue(today) ?: return@mapNotNull null
            val since = p.lastSpoke?.let { ChronoUnit.DAYS.between(it, today) }
            PersonNudge(p, null, -late, "Last spoke " + (since?.let { weeks(it) } ?: "not logged") + " · you said every ${weeks(p.every!!.toLong()).removeSuffix(" ago")}")
        }.sortedBy { it.days }
        return birthdays + touch
    }

    private fun weeks(days: Long): String = when {
        days < 14 -> "$days days ago"
        days < 60 -> "${days / 7} weeks ago"
        else -> "${days / 30} months ago"
    }

    /** Short line for the Deck's Today card, e.g. "🎂 Mum · Sat 21 Nov · 6 days". */
    fun deckLine(n: PersonNudge): String = if (n.birthday != null) "🎂 ${n.person.name} · ${n.text}" else "💬 ${n.person.name} · ${n.text.substringBefore(" ·")}"

    private fun appendTo(v: Vault, path: String, section: String, line: String) = VaultIo.edit(v, path) { lines ->
        val r = Md.sectionRange(lines, section)
        if (r == null) {
            while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.lastIndex)
            lines.addAll(listOf("", "## $section", "", "- $line", ""))
        } else {
            var at = r.last + 1
            while (at > r.first && lines[at - 1].isBlank()) at--
            if (at == r.first) { lines.add(at, ""); at++ }
            lines.add(at, "- $line")
        }
    }

    fun addGift(v: Vault, p: Person, idea: String, today: LocalDate = LocalDate.now()) =
        appendTo(v, p.path, "Gift ideas", "${idea.trim()} (said ${today.format(SHORT)})")

    fun addNote(v: Vault, p: Person, note: String) = appendTo(v, p.path, "Notes", note.trim())

    fun spoke(v: Vault, p: Person, today: LocalDate = LocalDate.now()) = VaultIo.edit(v, p.path) { Md.fmSet(it, "last_spoke", today.toString()) }

    fun setEvery(v: Vault, p: Person, days: Int?) = VaultIo.edit(v, p.path) { Md.fmSet(it, "keep_in_touch", days?.toString() ?: "") }

    fun setBirthday(v: Vault, p: Person, date: String) = VaultIo.edit(v, p.path) { Md.fmSet(it, "birthday", date) }

    /** "gift idea for Mum: kneeler" or "gift for Jamie - socks" → (Mum, kneeler). Capture uses it. */
    fun giftIdea(text: String): Pair<String, String>? =
        Regex("^\\s*gift(?: idea)?s? (?:for|4) ([^:–\\-]+?)\\s*[:–\\-]\\s*(.+)$", RegexOption.IGNORE_CASE).find(text)?.groupValues?.let { it[1].trim() to it[2].trim() }

    /**
     * A week before each birthday, adds "Get <name> a gift 🎂" (due 2 days before) once a year. The year is kept in
     * the note's `gift_task` so it isn't added twice. Returns the names it added.
     */
    fun addGiftTasks(v: Vault, w: VaultWriter, people: List<Person>, today: LocalDate = LocalDate.now()): List<String> = people.mapNotNull { p ->
        val d = p.nextBirthday(today) ?: return@mapNotNull null
        val days = ChronoUnit.DAYS.between(today, d)
        if (days > GIFT_DAYS) return@mapNotNull null
        val lines = VaultIo.read(v, p.path).split("\n")
        if (Md.fmGet(lines, "gift_task")?.trim() == d.year.toString()) return@mapNotNull null
        val ideas = p.gifts.take(2).joinToString(" or ").takeIf { it.isNotEmpty() }?.let { " (ideas: ${it.replace(Regex(" \\(said [^)]*\\)"), "")})" } ?: ""
        w.addTask("Get ${p.name} a gift 🎂$ideas", d.minusDays(2).coerceAtLeast(today).toString())
        VaultIo.edit(v, p.path) { Md.fmSet(it, "gift_task", d.year.toString()) }
        p.name
    }

    private fun LocalDate.coerceAtLeast(o: LocalDate) = if (isBefore(o)) o else this
}
