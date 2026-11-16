package uk.tomlowndes.capture

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/** One pattern: what, how big, on how many days, and one action (habit, counter, focus) where it makes sense. */
data class Pattern(val emoji: String, val title: String, val detail: String, val helps: Boolean, val action: String? = null)

data class MonthPatterns(val month: YearMonth, val summary: String, val patterns: List<Pattern>) {
    val helps get() = patterns.filter { it.helps }
    val drags get() = patterns.filter { !it.helps }
}

/**
 * What helps you (wellbeing-v1): once a month Claude reads the month's mood, habits, sleep, steps, focus sessions and
 * tasks done, and names patterns seen on at least 5 days. Saved as a note in `00 - MOC/Monthly/`.
 */
object Patterns {
    const val DIR = "00 - MOC/Monthly"
    const val MIN_DAYS = 5

    fun path(m: YearMonth) = "$DIR/$m What helps.md"
    fun monthName(m: YearMonth) = m.month.getDisplayName(TextStyle.FULL, Locale.UK)

    /** One line a day for Claude: date, weekday, mood, habits ticked, sleep, steps, focus minutes and their times, tasks done. */
    fun facts(m: YearMonth, habits: HabitsNote?, focus: List<FocusSession>, done: List<TickedTask>): String = buildString {
        append("Month: ${monthName(m)} ${m.year}\nOne line per day: date | mood 1-5 | habits | sleep min | steps | focus (start times, minutes) | tasks done\n")
        val doneBy = done.mapNotNull { it.done }.groupingBy { it }.eachCount()
        for (d in 1..m.lengthOfMonth()) {
            val day = m.atDay(d)
            if (day.isAfter(LocalDate.now())) break
            val h = habits?.day(day)
            val f = focus.filter { it.at.toLocalDate() == day }
            append("$day ${day.dayOfWeek.name.take(3)} | ${h?.let { Mood.of(it) } ?: "-"} | ${h?.done?.joinToString(",").orEmpty()}")
            append(" | ${h?.let { Body.sleep(it) } ?: "-"} | ${h?.let { Body.steps(it) } ?: "-"}")
            append(" | ${f.joinToString(",") { "%02d:%02d %dm".format(it.at.hour, it.at.minute, it.minutes) }} | ${doneBy[day] ?: 0}\n")
        }
    }

    const val SYSTEM = """You look for patterns in a month of Tom's own day logs (he has ADHD; be kind and practical).
Only name a pattern seen on at least 5 days; say how many. Compare days with and without the thing. Never diagnose.
Reply with JSON only, no prose or code fences:
{"summary": "28 mood logs · 3 habits · 41 tasks done", "patterns": [{"emoji": "🚶", "title": "Walks before 10", "detail": "mood +1.4 · 9 of 12 days", "helps": true, "action": "habit"|"counter"|"focus"|null}]}
Up to 6 patterns, helps first. UK English. If the data is too thin, return an empty patterns list."""

    fun parse(reply: String, m: YearMonth): MonthPatterns? {
        val raw = reply.trim()
        val o = runCatching { JSONObject(raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1)) }.getOrNull() ?: return null
        val arr = o.optJSONArray("patterns") ?: JSONArray()
        val ps = (0 until arr.length()).mapNotNull { i ->
            val p = arr.optJSONObject(i) ?: return@mapNotNull null
            val t = p.optString("title").trim().ifEmpty { return@mapNotNull null }
            Pattern(p.optString("emoji").trim(), t, p.optString("detail").trim(), p.optBoolean("helps", true),
                p.opt("action")?.takeIf { it != JSONObject.NULL }?.toString()?.trim()?.ifEmpty { null })
        }
        return MonthPatterns(m, o.optString("summary").trim(), ps.take(6))
    }

    fun markdown(p: MonthPatterns, today: LocalDate): String = buildString {
        append("---\ntype: what-helps\nmonth: ${p.month}\nmade: $today\n---\n\n# What helped in ${monthName(p.month)}\n\n${p.summary}\n\n")
        append("## Helps\n\n"); p.helps.forEach { append(line(it)) }
        append("\n## Drags\n\n"); p.drags.forEach { append(line(it)) }
    }

    private fun line(p: Pattern) = "- ${p.emoji} **${p.title}** · ${p.detail}" + (p.action?.let { " [action:: $it]" } ?: "") + "\n"
    private val LINE = Regex("^- (\\S*)\\s*\\*\\*(.+?)\\*\\*\\s*·\\s*(.*?)(?:\\s*\\[action:: (\\w+)])?\\s*$")

    fun read(text: String, m: YearMonth): MonthPatterns {
        val lines = text.split("\n")
        var helps = true
        val ps = mutableListOf<Pattern>()
        var summary = ""
        for (l in Md.fmBody(lines)) {
            when {
                l.startsWith("## Helps") -> helps = true
                l.startsWith("## Drags") -> helps = false
                l.startsWith("- ") -> LINE.matchEntire(l.trim())?.let { g -> ps.add(Pattern(g.groupValues[1], g.groupValues[2], g.groupValues[3], helps, g.groupValues[4].ifEmpty { null })) }
                l.isNotBlank() && !l.startsWith("#") && summary.isEmpty() -> summary = l.trim()
            }
        }
        return MonthPatterns(m, summary, ps)
    }

    fun load(v: Vault, m: YearMonth): MonthPatterns? = v.read(path(m))?.let { read(it, m) }

    fun save(v: Vault, p: MonthPatterns, today: LocalDate = LocalDate.now()) {
        val text = markdown(p, today)
        if (v.entry(path(p.month)) == null) { v.mkdirs(DIR); VaultIo.write(v, v.create(DIR, "${p.month} What helps", "md"), text) }
        else VaultIo.write(v, path(p.month), text)
    }

    /** The Deck card shows last month's patterns in the first week of a month. */
    fun showCard(today: LocalDate) = today.dayOfMonth <= 7
    fun lastMonth(today: LocalDate): YearMonth = YearMonth.from(today).minusMonths(1)
}
