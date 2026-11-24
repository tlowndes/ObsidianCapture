package uk.tomlowndes.capture

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A label and its text: a stat ("free time" · "6h"), a Today row ("10:00–10:30" · "Interview prep"), a Coming up day. */
data class BriefRow(val label: String, val text: String)

/** One of the Three things: area ("Job hunt"), energy (low/medium/high), the task, and why plus the first step. */
data class BriefThing(val area: String, val energy: String, val title: String, val why: String)

/** A role from the job hunt: tag ("new", "follow up", "to apply"), "Web Designer · B12", the detail line, its note. */
data class BriefRole(val tag: String, val title: String, val detail: String, val path: String? = null)

/** A TL;DR pick (box only): category (Design, AI, Tech, Security), title, link and one line. */
data class BriefNews(val cat: String, val title: String, val url: String, val line: String)

/** A chapter of the spoken brief: [key] is one of [DailyBrief.CHAPTERS], [lines] are read one after another. */
data class BriefChapter(val key: String, val title: String, val lines: List<String>)

/** Today's brief as the page shows it. Sections with nothing in them are left out of the page. */
data class BriefDoc(
    val date: LocalDate,
    /** "box" or "app": who wrote it. */
    val writer: String = DailyBrief.WRITER_APP,
    /** "06:15", when it was written. */
    val made: String? = null,
    val heard: Boolean = false,
    /** Opened on the phone or PC: once it has, the box doesn't swap in a late version. */
    val opened: Boolean = false,
    val opening: String = "",
    val stats: List<BriefRow> = emptyList(),
    val today: List<BriefRow> = emptyList(),
    /** label "9° / 4° · Dry, then showers", text the detail. */
    val weather: BriefRow? = null,
    val things: List<BriefThing> = emptyList(),
    val comingUp: List<BriefRow> = emptyList(),
    val roles: List<BriefRole> = emptyList(),
    val bills: List<String> = emptyList(),
    val news: List<BriefNews> = emptyList(),
    /** label "16:00", text the reason. */
    val hardStop: BriefRow? = null,
    val audio: List<BriefChapter> = emptyList(),
    val short: List<String> = emptyList(),
) {
    /** The Full read: every chapter in [DailyBrief.CHAPTERS] order. */
    val chapters: List<BriefChapter> get() = audio.filter { it.lines.isNotEmpty() }
        .sortedBy { c -> DailyBrief.CHAPTERS.indexOfFirst { it.first == c.key }.let { if (it < 0) 99 else it } }

    /** The Short read: [short], or the first line of each chapter when Claude didn't write one. */
    val shortLines: List<String> get() = short.ifEmpty { chapters.mapNotNull { it.lines.firstOrNull() } }
}

/**
 * Daily Brief (daily-brief-v1): the morning email as a page in the app, with a longer spoken version.
 *
 * One note, [PATH], written once a day by whoever gets there first: the Oracle box at 06:15 (with the TL;DR news from
 * the TLDR emails), or the app from [APP_FROM] when the box hasn't written one. Phone and PC read the same note, so
 * "heard" and "opened" are shared. Sections are `## Heading`, one item a line, fields split by " | ".
 */
object DailyBrief {
    const val PATH = "${FlightNotes.DIR}/Daily Brief.md"
    const val WRITER_BOX = "box"
    const val WRITER_APP = "app"
    /** The box writes at 06:15; the app writes its own from 06:45 if there's still none for today. */
    val APP_FROM: LocalTime = LocalTime.of(6, 45)
    /** Applications a week the stats strip counts against. */
    const val APPS_WEEK = 3
    /** About 150 spoken words a minute, at 1×. */
    const val WPM = 150

    /** Chapter keys and the names the player shows, in reading order. */
    val CHAPTERS = listOf(
        "opening" to "Opening & today", "weather" to "Weather & travel", "things" to "Three things",
        "coming" to "Coming up", "roles" to "Roles", "bills" to "Bills & inbox", "news" to "The TL;DR", "stop" to "Hard stop",
    )

    fun chapterTitle(key: String) = CHAPTERS.firstOrNull { it.first == key }?.second ?: key

    /** A chapter heading Claude (or the box) wrote, to its key: "### Weather & travel" → "weather". */
    fun chapterKey(title: String): String {
        val t = title.lowercase()
        return when {
            "weather" in t || "travel" in t -> "weather"
            "three" in t || "thing" in t -> "things"
            "coming" in t || "week" in t -> "coming"
            "role" in t || "job" in t -> "roles"
            "bill" in t || "inbox" in t || "email" in t -> "bills"
            "tl;dr" in t || "tldr" in t || "news" in t -> "news"
            "stop" in t -> "stop"
            else -> "opening"
        }
    }

    private val HM = DateTimeFormatter.ofPattern("HH:mm")
    private val DAY = DateTimeFormatter.ofPattern("EEE d", Locale.UK)

    // ---- The note ----

    fun parse(text: String): BriefDoc? {
        val ls = text.replace("\r", "").split("\n")
        val date = Md.fmGet(ls, "date")?.trim()?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() } ?: return null
        fun fm(k: String) = Md.fmGet(ls, k)?.trim()?.trim('"')
        val body = sections(Md.fmBody(ls))
        fun sec(name: String) = body.entries.firstOrNull { it.key.equals(name, true) }?.value.orEmpty()
        fun items(name: String) = sec(name).map { it.trim() }.filter { it.isNotEmpty() }.map { it.removePrefix("-").removePrefix("•").trim() }
        fun fields(l: String) = l.split(" | ").map { it.trim() }
        fun row(l: String) = fields(l).let { BriefRow(it[0], it.drop(1).joinToString(" | ")) }
        val audio = mutableListOf<BriefChapter>()
        var cur: Pair<String, MutableList<String>>? = null
        sec("Audio").forEach { l ->
            val t = l.trim()
            if (t.startsWith("### ")) { cur?.let { audio += BriefChapter(chapterKey(it.first), it.first, it.second) }; cur = t.removePrefix("### ").trim() to mutableListOf() }
            else if (t.isNotEmpty()) { if (cur == null) cur = chapterTitle("opening") to mutableListOf(); cur!!.second += t }
        }
        cur?.let { audio += BriefChapter(chapterKey(it.first), it.first, it.second) }
        return BriefDoc(
            date = date, writer = fm("writer") ?: WRITER_APP, made = fm("made"),
            heard = fm("heard") == "true", opened = fm("opened") == "true",
            opening = items("Opening").joinToString(" "),
            stats = items("Stats").map { row(it) },
            today = items("Today").map { row(it) },
            weather = items("Weather").firstOrNull()?.let { row(it) },
            things = items("Three things").map { fields(it) }.filter { it.size >= 3 }.map {
                BriefThing(it[0], it[1].lowercase(), it[2], it.drop(3).joinToString(" | "))
            },
            comingUp = items("Coming up").map { row(it) },
            roles = items("Roles").map { fields(it) }.filter { it.size >= 2 }.map {
                BriefRole(it[0], it[1], it.getOrElse(2) { "" }, it.getOrNull(3)?.takeIf { p -> p.isNotBlank() })
            },
            bills = items("Bills & inbox"),
            news = items("TL;DR").map { fields(it) }.filter { it.size >= 3 }.map {
                BriefNews(it[0], it[1], it[2], it.drop(3).joinToString(" | "))
            },
            hardStop = items("Hard stop").firstOrNull()?.let { row(it) },
            audio = audio.groupBy { it.key }.map { (k, cs) -> BriefChapter(k, chapterTitle(k), cs.flatMap { it.lines }) },
            short = items("Short"),
        )
    }

    /** `## Heading` → its lines (### sub-headings kept as lines). */
    private fun sections(lines: List<String>): Map<String, List<String>> {
        val out = LinkedHashMap<String, MutableList<String>>()
        var cur: MutableList<String>? = null
        lines.forEach { l ->
            if (l.startsWith("## ")) { cur = mutableListOf<String>().also { out[l.removePrefix("## ").trim()] = it } }
            else cur?.add(l)
        }
        return out
    }

    fun render(d: BriefDoc): String = buildString {
        append("---\ndate: ${d.date}\nwriter: ${d.writer}\n")
        d.made?.let { append("made: \"$it\"\n") }
        append("news: ${d.news.isNotEmpty()}\nheard: ${d.heard}\nopened: ${d.opened}\n---\n")
        fun sec(name: String, ls: List<String>) { if (ls.isNotEmpty()) { append("\n## $name\n"); ls.forEach { append(it).append('\n') } } }
        sec("Opening", listOf(d.opening).filter { it.isNotBlank() })
        sec("Stats", d.stats.map { "- ${it.label} | ${it.text}" })
        sec("Today", d.today.map { "- ${it.label} | ${it.text}" })
        sec("Weather", listOfNotNull(d.weather?.let { "${it.label} | ${it.text}" }))
        sec("Three things", d.things.map { "- ${it.area} | ${it.energy} | ${it.title} | ${it.why}" })
        sec("Coming up", d.comingUp.map { "- ${it.label} | ${it.text}" })
        sec("Roles", d.roles.map { "- ${it.tag} | ${it.title} | ${it.detail}" + (it.path?.let { p -> " | $p" } ?: "") })
        sec("Bills & inbox", d.bills.map { "- $it" })
        sec("TL;DR", d.news.map { "- ${it.cat} | ${it.title} | ${it.url} | ${it.line}" })
        sec("Hard stop", listOfNotNull(d.hardStop?.let { "${it.label} | ${it.text}" }))
        if (d.audio.isNotEmpty()) {
            append("\n## Audio\n")
            d.chapters.forEach { c -> append("### ${c.title}\n"); c.lines.forEach { append(it).append('\n') } }
        }
        sec("Short", d.short)
    }

    /** Today's brief, or null when there isn't one for [day]. */
    fun load(v: Vault, day: LocalDate): BriefDoc? = v.read(PATH)?.let { parse(it) }?.takeIf { it.date == day }

    fun save(v: Vault, d: BriefDoc) {
        val body = render(d)
        if (v.entry(PATH) == null) { v.mkdirs(FlightNotes.DIR); VaultIo.write(v, v.create(FlightNotes.DIR, "Daily Brief", "md"), body) }
        else VaultIo.write(v, PATH, body)
    }

    private fun mark(v: Vault, day: LocalDate, key: String) {
        val ls = v.read(PATH)?.split("\n")?.toMutableList() ?: return
        if (Md.fmGet(ls, "date")?.trim() != day.toString() || Md.fmGet(ls, key)?.trim() == "true") return
        Md.fmSet(ls, key, "true")
        VaultIo.write(v, PATH, ls.joinToString("\n"))
    }

    /** Played to the end, on the phone or the PC: the Deck card shrinks to one row. */
    fun markHeard(v: Vault, day: LocalDate) = mark(v, day, "heard")

    /** Opened on the phone or the PC: the box won't swap in a late version now. */
    fun markOpened(v: Vault, day: LocalDate) = mark(v, day, "opened")

    /** The app writes its own only from [APP_FROM], so the box (with the news) gets first go. */
    fun appMayWrite(now: LocalDateTime) = !now.toLocalTime().isBefore(APP_FROM)

    // ---- Writing it in the app ----

    /** What the app knows at write time. [mail] is the inbox's open emails (needs you, bills, important). */
    data class Input(
        val now: LocalDateTime, val tasks: List<TaskItem>, val events: List<CalEvent>, val weather: WeatherSummary?,
        val jobs: List<Job> = emptyList(), val mail: List<Mail> = emptyList(),
    )

    /** The parts that come straight from the vault, no Claude: stats, Today, weather, Coming up, roles, bills. */
    fun base(i: Input): BriefDoc {
        val day = i.now.toLocalDate()
        val on = i.events.filter { it.on(day) }
        val free = DayPlan.freeMinutes(on, day.atTime(Upcoming.DAY_START))
        val overdue = i.tasks.count { it.due != null && it.due.isBefore(day) && !Deck.isBill(it) }
        val openJobs = i.jobs.filter { !it.closed }
        val monday = day.with(DayOfWeek.MONDAY)
        val applied = i.jobs.count { it.applied != null && !it.applied.isBefore(monday) && !it.applied.isAfter(day) }
        val stats = listOf(
            BriefRow("free time", hours(free)),
            BriefRow("roles to apply", openJobs.count { it.status.equals("Shortlisted", true) }.toString()),
            BriefRow("waiting on you", (i.mail.size + overdue).toString()),
            BriefRow("apps this wk", "$applied/$APPS_WEEK"),
        )
        val timed = on.filter { !it.allDay }.sortedBy { it.start }
        val today = on.filter { it.allDay }.map { BriefRow("All day", it.title) } +
            timed.map { e -> BriefRow("${e.start.format(HM)}–${e.end.format(HM)}", e.title + (e.location?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")) }
                .ifEmpty { listOf(BriefRow("${Upcoming.DAY_START.format(HM)}–${Upcoming.DAY_END.format(HM)}", "Free all day ✨ Nothing in the calendar.")) }
        val weather = i.weather?.takeIf { !it.tomorrow }?.let { w ->
            BriefRow("${w.high}° / ${w.low}° · ${w.headline}", listOfNotNull(w.detail.trim().ifBlank { null }, w.tip?.trim()?.ifBlank { null }).joinToString(" "))
        }
        val coming = (1L..6L).mapNotNull { n ->
            val d = day.plusDays(n)
            val ev = i.events.filter { it.on(d) }.sortedWith(compareBy({ !it.allDay }, { it.start }))
                .map { if (it.allDay) "All day: ${it.title}" else "${it.start.format(HM)} ${it.title}" }
            val ts = i.tasks.filter { it.due == d && !Deck.isBill(it) }.map { TaskViews.plainText(it) }
            (ev + ts).take(3).takeIf { it.isNotEmpty() }?.let { BriefRow(d.format(DAY), it.joinToString(" · ")) }
        }
        val roles = openJobs.sortedBy { it.next ?: LocalDate.MAX }.take(4).map { j ->
            val tag = when {
                j.next != null && !j.next.isAfter(day) -> "follow up"
                j.status.equals("Interview", true) -> "interview"
                j.status.equals("Shortlisted", true) -> "to apply"
                else -> j.status.lowercase()
            }
            val detail = listOfNotNull(
                j.status.takeIf { tag != it.lowercase() },
                j.applied?.let { "applied ${it.format(DAY)}" },
                j.next?.let { if (!it.isAfter(day)) "chase today" else "next ${it.format(DAY)}" },
                j.fit?.let { "fit $it/5" },
            ).joinToString(" · ")
            BriefRole(tag, listOf(j.role, j.company).filter { it.isNotBlank() }.joinToString(" · "), detail, j.path)
        }
        val bills = TaskViews.billsDue(i.tasks, day).take(4).map { b ->
            listOfNotNull(b.name, b.amount?.let { TaskViews.money(it) }, b.task.due?.let { if (it.isBefore(day)) "late" else it.format(DAY) }).joinToString(" · ")
        } + i.mail.take(3).map { m -> "✉ ${m.subject.ifBlank { m.summary }.take(70)} · ${m.from}" }
        return BriefDoc(date = day, writer = WRITER_APP, made = i.now.format(HM), stats = stats, today = today, weather = weather,
            comingUp = coming, roles = roles, bills = bills)
    }

    /** "6h", "6h 30m", "45m". */
    fun hours(min: Int): String = when {
        min < 60 -> "${min}m"
        min % 60 == 0 -> "${min / 60}h"
        else -> "${min / 60}h ${min % 60}m"
    }

    fun system(name: String = AssistantName.DEFAULT) = """You are ${AssistantName.clean(name)}, writing Tom's Daily Brief: a page he reads in his app and a version read out loud.
Tom is a designer job hunting for in-house or remote design roles, building his portfolio. Plain UK English, warm, calm, a bit of humour, no corporate words, no em dashes.
Reply in exactly this format and nothing else:

## Opening
One sentence on the shape of the day.

## Three things
- Area | low or medium or high | Task title, short | Why today, then "First step: ..."
(exactly three lines, picked from the candidate tasks; Area is a short word like Job hunt, Portfolio, Home, Admin)

## Hard stop
HH:MM | One sentence: when to stop work today and why (sunset, evening plans).

## Audio
### Opening & today
### Weather & travel
### Three things
### Coming up
### Roles
### Bills & inbox
### Hard stop
(under each heading, 2 to 5 spoken lines, one or two sentences each; leave out a heading with nothing to say; about 650 words in all.
Spoken style: no emoji, no markdown, no lists, times like "nine forty", money like "eighty-four pounds".)

## Short
5 to 8 spoken lines, about 250 words: the weather first, then the first event, the three things, anything due, a kind line to finish."""

    /** The facts Claude writes from: the base sections, then the tasks to pick the Three things from. */
    fun prompt(i: Input, base: BriefDoc, candidates: List<TaskItem>): String = buildString {
        val day = i.now.toLocalDate()
        append("Today is ${day.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }} ${day.format(DateTimeFormatter.ofPattern("d MMMM", Locale.UK))}. It's ${i.now.format(HM)}.\n")
        append("Stats: " + base.stats.joinToString(", ") { "${it.text} ${it.label}" } + "\n")
        i.weather?.let { append(Briefing.weatherFacts(it)).append('\n') }
        append("Today's calendar:\n"); base.today.forEach { append("- ${it.label} ${it.text}\n") }
        if (base.comingUp.isNotEmpty()) { append("Coming up:\n"); base.comingUp.forEach { append("- ${it.label}: ${it.text}\n") } }
        if (base.roles.isNotEmpty()) { append("Job applications:\n"); base.roles.forEach { append("- ${it.title} (${it.tag}) ${it.detail}\n") } }
        if (base.bills.isNotEmpty()) { append("Bills and email:\n"); base.bills.forEach { append("- $it\n") } }
        append("Candidate tasks (due or late first):\n")
        candidates.forEach { t ->
            val tags = TaskViews.tags(t).joinToString(" ") { "#$it" }
            val due = t.due?.let { if (it.isBefore(day)) " (late since ${it.format(DAY)})" else if (it == day) " (due today)" else " (due ${it.format(DAY)})" } ?: ""
            val min = TaskRules.minutes(t.raw)?.let { " ~${it}m" } ?: ""
            append("- ${TaskViews.plainText(t)}$due$min $tags\n")
        }
    }

    /** Claude's reply laid over the base: the opening, Three things, hard stop and both spoken versions. */
    fun merge(base: BriefDoc, reply: String): BriefDoc {
        val got = parse("---\ndate: ${base.date}\n---\n" + reply.replace("\r", "").trim().removePrefix("```markdown").removePrefix("```").removeSuffix("```"))
            ?: return base
        return base.copy(opening = got.opening, things = got.things.take(3), hardStop = got.hardStop, audio = got.audio, short = got.short)
    }

    /** About how long [lines] take to say at [speed]: "5:40". */
    fun length(lines: List<String>, speed: Float = 1f): String {
        val s = (seconds(lines) / speed).toInt().coerceAtLeast(5)
        return "${s / 60}:${"%02d".format(s % 60)}"
    }

    fun seconds(lines: List<String>): Int = lines.sumOf { l -> l.split(Regex("\\s+")).count { it.isNotBlank() } } * 60 / WPM

    /** Where each chapter starts, in seconds at 1×, for the chapter list. */
    fun starts(chapters: List<BriefChapter>): List<Int> = chapters.runningFold(0) { acc, c -> acc + seconds(c.lines) }.dropLast(1)

    /** The Full read as (chapter index, line) pairs, in order. */
    fun fullQueue(d: BriefDoc): List<Pair<Int, String>> = d.chapters.flatMapIndexed { i, c -> c.lines.map { i to it } }
}
