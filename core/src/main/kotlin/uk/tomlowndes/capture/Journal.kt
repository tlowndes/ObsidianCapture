package uk.tomlowndes.capture

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** What kind of answer a check-in item takes. MOOD is the 1–5 faces, kept in step with the Habits note. */
enum class JournalKind { SCALE, MOOD, TICK, LINE }

/**
 * One check-in item from `99 - Meta/Journal prompts.md`: `- tick 🚶 Moved / went outside | Moved`.
 * [short] is the word used in the journal note; [options] are a scale's choices.
 */
data class JournalPrompt(val kind: JournalKind, val emoji: String, val label: String, val short: String, val options: List<String> = emptyList()) {
    val key get() = short
}

/** Today's numbers the app already knows; null leaves a fact out. */
data class JournalFacts(val tasks: Int? = null, val focusMin: Int? = null, val events: Int? = null) {
    fun parts() = listOfNotNull(
        tasks?.takeIf { it > 0 }?.let { "✓ $it tasks" },
        focusMin?.takeIf { it > 0 }?.let { "⏱ $it min" },
        events?.takeIf { it > 0 }?.let { "📅 $it" },
    )
}

/**
 * One day's check-in. [answers] is keyed by [JournalPrompt.key]: a scale's option, the mood 1–5, "y" for a tick,
 * or a line's text. Missing means not answered (a tick left empty).
 */
data class JournalEntry(val answers: Map<String, String> = emptyMap(), val note: String = "") {
    fun answered(prompts: List<JournalPrompt>) = prompts.count { p ->
        val a = answers[p.key]
        when (p.kind) { JournalKind.TICK -> a == "y"; else -> !a.isNullOrBlank() }
    }
}

/**
 * The evening check-in (journal-prompt-v2): quick scales and ticks plus two optional one-liners, saved to one note a
 * month (`02 - Area/Journal/2026-11.md`) under a heading for each day. Saving again the same day replaces that day.
 */
object Journal {
    const val DIR = "02 - Area/Journal"
    const val PROMPTS = "${FlightNotes.DIR}/Journal prompts.md"
    /** The Deck asks from this hour (24h) until today has an entry. */
    const val ASK_FROM = 19

    val PROMPTS_STARTER = """
        # Journal prompts

        The evening check-in asks these, in this order. One per line:
        `- scale <emoji> <Name> · Option Option Option`, `- mood <emoji> Mood`, `- tick <emoji> <Name>`, `- line <emoji> <Name>`.
        Add `| Word` to set the short word used in the journal. Delete or reorder lines as you like.

        - scale 😴 Slept · Badly OK Well
        - scale ⚡ Energy · Low OK High
        - mood 🙂 Mood
        - tick 🎯 Did the main thing | Main thing
        - tick 💼 Job hunt / portfolio | Job hunt
        - tick 🚶 Moved / went outside | Moved
        - tick 🥗 Ate properly | Ate
        - tick 💬 Talked to someone | Talked
        - tick 📵 Screens under control | Screens
        - tick 💊 Meds / water | Meds
        - line 🏆 One win | Win
        - line ➡️ Tomorrow's one thing | Tomorrow
    """.trimIndent() + "\n"

    private val PROMPT_LINE = Regex("^\\s*[-*]\\s+(scale|mood|tick|line)\\s+(\\S+)\\s+(.+?)\\s*$", RegexOption.IGNORE_CASE)

    fun prompts(text: String): List<JournalPrompt> = text.split("\n").mapNotNull { l ->
        val m = PROMPT_LINE.matchEntire(l) ?: return@mapNotNull null
        val kind = JournalKind.valueOf(m.groupValues[1].uppercase())
        val emoji = m.groupValues[2]
        var rest = m.groupValues[3]
        var short: String? = null
        if ('|' in rest) { short = rest.substringAfter('|').trim().ifBlank { null }; rest = rest.substringBefore('|').trim() }
        var options = emptyList<String>()
        if (kind == JournalKind.SCALE && '·' in rest) {
            options = rest.substringAfter('·').trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            rest = rest.substringBefore('·').trim()
        }
        if (kind == JournalKind.MOOD) options = Mood.FACES
        if (rest.isBlank() || (kind == JournalKind.SCALE && options.size < 2)) return@mapNotNull null
        JournalPrompt(kind, emoji, rest, short ?: rest, options)
    }.distinctBy { it.key }

    fun loadPrompts(v: Vault): List<JournalPrompt> =
        prompts(v.read(PROMPTS) ?: PROMPTS_STARTER).ifEmpty { prompts(PROMPTS_STARTER) }

    /** Makes the prompts note with the starter list if it's missing, so it can be edited in Obsidian. */
    fun ensurePrompts(v: Vault) {
        if (v.entry(PROMPTS) != null) return
        v.mkdirs(FlightNotes.DIR)
        VaultIo.write(v, v.create(FlightNotes.DIR, "Journal prompts", "md"), PROMPTS_STARTER)
    }

    fun path(day: LocalDate) = "$DIR/${day.year}-${"%02d".format(day.monthValue)}.md"

    fun heading(day: LocalDate) = "## " + day.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK))

    fun monthStarter(day: LocalDate): String {
        val month = day.month.getDisplayName(TextStyle.FULL, Locale.UK)
        return "---\ntype: journal\nmonth: ${day.year}-${"%02d".format(day.monthValue)}\nAutoNoteMover: disable\n---\n# Journal · $month ${day.year}\n"
    }

    /** The lines under a day's heading. */
    fun render(prompts: List<JournalPrompt>, e: JournalEntry, facts: JournalFacts): List<String> {
        val out = mutableListOf<String>()
        val meta = prompts.mapNotNull { p ->
            val a = e.answers[p.key]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            when (p.kind) {
                JournalKind.SCALE -> "${p.emoji} $a"
                JournalKind.MOOD -> a.toIntOrNull()?.let { Mood.face(it) }
                else -> null
            }
        } + facts.parts()
        if (meta.isNotEmpty()) out += meta.joinToString(" · ")
        val ticks = prompts.filter { it.kind == JournalKind.TICK }
        if (ticks.isNotEmpty() && ticks.any { e.answers[it.key] == "y" }) {
            out += ticks.joinToString(" · ") { p -> (if (e.answers[p.key] == "y") "✅ " else "⬜ ") + p.short }
        }
        prompts.filter { it.kind == JournalKind.LINE }.forEach { p ->
            e.answers[p.key]?.trim()?.takeIf { it.isNotEmpty() }?.let { out += "**${p.short}:** ${it.replace("\n", " ")}" }
        }
        e.note.trim().takeIf { it.isNotEmpty() }?.let { out += ""; out += it.split("\n") }
        return out
    }

    /** Reads a day's lines back into answers, so the sheet opens with what was saved earlier. */
    fun parse(prompts: List<JournalPrompt>, lines: List<String>): JournalEntry {
        val answers = mutableMapOf<String, String>()
        val note = mutableListOf<String>()
        lines.forEach { raw ->
            val l = raw.trim()
            val tokens = l.split(" · ").map { it.trim() }
            when {
                l.isEmpty() -> if (note.isNotEmpty()) note += ""
                tokens.any { it.startsWith("✅ ") || it.startsWith("⬜ ") } -> tokens.forEach { t ->
                    val p = prompts.firstOrNull { it.kind == JournalKind.TICK && t.drop(2).trim() == it.short }
                    if (p != null && t.startsWith("✅")) answers[p.key] = "y"
                }
                l.startsWith("**") && l.indexOf(":**") > 2 -> {
                    val word = l.substring(2, l.indexOf(":**"))
                    val p = prompts.firstOrNull { it.kind == JournalKind.LINE && it.short == word }
                    if (p != null) answers[p.key] = l.substring(l.indexOf(":**") + 3).trim() else note += raw
                }
                answers.isEmpty() && note.isEmpty() && tokens.any { t -> prompts.any { metaMatch(it, t) != null } } -> tokens.forEach { t ->
                    prompts.forEach { p -> metaMatch(p, t)?.let { answers[p.key] = it } }
                }
                else -> note += raw
            }
        }
        return JournalEntry(answers, note.joinToString("\n").trim())
    }

    private fun metaMatch(p: JournalPrompt, t: String): String? = when (p.kind) {
        JournalKind.SCALE -> if (t.startsWith(p.emoji + " ")) t.removePrefix(p.emoji + " ").trim().takeIf { it in p.options } else null
        JournalKind.MOOD -> Mood.FACES.indexOf(t).takeIf { it >= 0 }?.let { (it + 1).toString() }
        else -> null
    }

    /** The lines of [day]'s section in a month note, without the heading; null if there's no entry for that day. */
    fun section(text: String, day: LocalDate): List<String>? {
        val lines = text.split("\n")
        val h = heading(day)
        val start = lines.indexOfFirst { it.trimEnd() == h }
        if (start < 0) return null
        val end = (start + 1 until lines.size).firstOrNull { lines[it].startsWith("## ") || lines[it].startsWith("# ") } ?: lines.size
        return lines.subList(start + 1, end).dropLastWhile { it.isBlank() }
    }

    fun load(v: Vault, day: LocalDate, prompts: List<JournalPrompt>): JournalEntry? =
        v.read(path(day))?.let { section(it, day) }?.let { parse(prompts, it) }

    fun written(v: Vault, day: LocalDate): Boolean = v.read(path(day))?.let { section(it, day) } != null

    /** [text] (a month note) with [day]'s section set to [body]; days stay in date order. */
    fun withDay(text: String, day: LocalDate, body: List<String>): String {
        val lines = text.trimEnd().split("\n").toMutableList()
        val h = heading(day)
        val start = lines.indexOfFirst { it.trimEnd() == h }
        val block = listOf(h) + body + ""
        if (start >= 0) {
            val end = (start + 1 until lines.size).firstOrNull { lines[it].startsWith("## ") || lines[it].startsWith("# ") } ?: lines.size
            repeat(end - start) { lines.removeAt(start) }
            lines.addAll(start, block)
        } else {
            // Before the first later day, else at the end.
            val later = lines.indices.firstOrNull { i ->
                lines[i].startsWith("## ") && runCatching {
                    val d = java.time.MonthDay.parse(lines[i].removePrefix("## ").substringAfter(' '), DateTimeFormatter.ofPattern("d MMM", Locale.UK))
                    d.atYear(day.year).isAfter(day)
                }.getOrDefault(false)
            }
            if (later != null) lines.addAll(later, block)
            else { if (lines.lastOrNull()?.isNotBlank() == true) lines += ""; lines.addAll(block) }
        }
        return lines.joinToString("\n").trimEnd() + "\n"
    }

    /** Tasks ticked and focus minutes for [day], plus [events] from the calendar the caller already has. */
    fun facts(v: Vault, day: LocalDate, events: Int?): JournalFacts = JournalFacts(
        tasks = runCatching { WeeklyReview.done(v, day).count { it.done == day } }.getOrNull(),
        focusMin = runCatching { FlightNotes.loadToday(v, day).sumOf { it.minutes } }.getOrNull(),
        events = events,
    )

    /** Saves [e] and, if it has a mood, logs that in the Habits note too so the mood card doesn't ask again. */
    fun saveWithMood(v: Vault, day: LocalDate, prompts: List<JournalPrompt>, e: JournalEntry, facts: JournalFacts) {
        ensurePrompts(v)
        save(v, day, prompts, e, facts)
        val mood = prompts.firstOrNull { it.kind == JournalKind.MOOD }?.let { e.answers[it.key]?.toIntOrNull() }
        if (mood != null) runCatching { Habits.update(v, day) { Mood.set(it, mood) } }
    }

    /** What the sheet opens with: today's saved entry, else the mood already logged. */
    fun start(v: Vault, day: LocalDate, prompts: List<JournalPrompt>): JournalEntry {
        load(v, day, prompts)?.let { return it }
        val mood = runCatching { Mood.of(Habits.load(v).day(day)) }.getOrNull()
        val key = prompts.firstOrNull { it.kind == JournalKind.MOOD }?.key
        return if (mood != null && key != null) JournalEntry(mapOf(key to mood.toString())) else JournalEntry()
    }

    fun save(v: Vault, day: LocalDate, prompts: List<JournalPrompt>, e: JournalEntry, facts: JournalFacts) {
        val p = path(day)
        if (v.entry(p) == null) {
            v.mkdirs(DIR)
            VaultIo.write(v, v.create(DIR, p.substringAfterLast('/').removeSuffix(".md"), "md"), monthStarter(day))
        }
        val body = render(prompts, e, facts)
        VaultIo.edit(v, p) { lines ->
            val now = withDay(lines.joinToString("\n"), day, body).trimEnd().split("\n")
            lines.clear(); lines.addAll(now)
        }
    }
}
