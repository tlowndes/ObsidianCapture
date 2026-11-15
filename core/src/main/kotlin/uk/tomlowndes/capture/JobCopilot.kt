package uk.tomlowndes.capture

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What Claude read from a job ad, and how well Tom fits it against his CV note. */
data class JobAd(
    val role: String, val company: String, val where: String? = null, val salary: String? = null,
    val closes: LocalDate? = null, val fit: Int? = null, val good: List<String> = emptyList(), val gaps: List<String> = emptyList(),
    val url: String? = null,
)

/**
 * Job hunt co-pilot (job-copilot-v1): a shared or pasted job ad is read by Claude, which fills the Jobs note, scores
 * the fit against the "Portfolio & CV" note, and can draft a cover letter in Tom's voice. The ad is kept in the note,
 * so it survives the posting coming down. The letter is a note next to the job.
 */
object JobCopilot {
    private val HOSTS = listOf("linkedin.com/jobs", "linkedin.com/comm/jobs", "indeed.", "reed.co.uk", "totaljobs.com", "glassdoor.",
        "otta.com", "welcometothejungle.com", "workable.com", "greenhouse.io", "lever.co", "ashbyhq.com", "cv-library.co.uk",
        "dribbble.com/jobs", "workinstartups.com", "smartrecruiters.com", "teamtailor.com", "/careers/", "/jobs/", "/job/")
    private val DAY = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.UK)

    /** A link that is probably a job posting. */
    fun looksLikeJob(url: String): Boolean = url.lowercase().let { u -> HOSTS.any { it in u } }

    /** The CV note: the newest note whose name starts "Portfolio & CV", else one with "CV" in the name. */
    fun cvPath(v: Vault): String? {
        val notes = runCatching { NoteIndex.paths(v) }.getOrDefault(emptyList())
        fun name(p: String) = p.substringAfterLast('/').removeSuffix(".md").lowercase()
        return notes.filter { name(it.path).startsWith("portfolio & cv") || name(it.path).startsWith("portfolio and cv") }.maxByOrNull { it.modified }?.path
            ?: notes.filter { Regex("\\bcv\\b").containsMatchIn(name(it.path)) }.maxByOrNull { it.modified }?.path
    }

    fun cvText(v: Vault, max: Int = 8000): String = cvPath(v)?.let { runCatching { VaultIo.read(v, it) }.getOrNull() }.orEmpty().take(max)

    fun readSystem(today: LocalDate, cv: String): String = """
        You read job ads for Tom, a creative designer (app design and AI) looking for in-house or remote design roles in the UK. Today is ${today.format(DAY)}.
        Reply with JSON only, no prose and no code fences:
        {"role": string, "company": string, "where": string or null, "salary": string or null, "closes": "YYYY-MM-DD" or null, "fit": 0-100, "good": [string], "gaps": [string]}
        - good: 2–4 short reasons Tom fits, from his CV below. gaps: 1–3 short things the ad wants that the CV doesn't show (gaps to cover, not deal-breakers).
        - fit is honest: 80+ strong, 60–79 decent, under 60 a stretch. UK English.
        Tom's CV and portfolio note:
        ${cv.ifBlank { "(no CV note found, judge from the ad alone and say so in gaps)" }}
    """.trimIndent()

    fun parse(reply: String, url: String?): JobAd? {
        val raw = reply.trim()
        val o = runCatching { JSONObject(raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1)) }.getOrNull() ?: return null
        fun s(k: String) = o.opt(k)?.takeIf { it != JSONObject.NULL }?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
        fun list(k: String) = (o.optJSONArray(k) ?: JSONArray()).let { a -> (0 until a.length()).mapNotNull { a.optString(it).trim().ifEmpty { null } } }
        val role = s("role") ?: return null
        return JobAd(
            role, s("company").orEmpty(), s("where"), s("salary"),
            s("closes")?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() },
            o.optInt("fit", -1).takeIf { it in 0..100 }, list("good"), list("gaps"), url,
        )
    }

    /** Saves the job as an application note (Shortlisted), with the fit and the ad kept. Returns its path. */
    fun save(v: Vault, ad: JobAd, adText: String, today: LocalDate = LocalDate.now()): String {
        val path = Jobs.create(v, ad.company, ad.role, today)
        VaultIo.edit(v, path) { lines ->
            ad.fit?.let { Md.fmSet(lines, "fit", it.toString()) }
            ad.where?.let { Md.fmSet(lines, "where", it) }
            ad.salary?.let { Md.fmSet(lines, "salary", it) }
            ad.closes?.let { Md.fmSet(lines, "closes", it.toString()) }
            ad.url?.let { Md.fmSet(lines, "url", it) }
            Md.fmSet(lines, "follow_up", (ad.closes?.minusDays(2)?.takeIf { it.isAfter(today) } ?: today.plusDays(7)).toString())
            while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.lastIndex)
            lines.addAll(listOf("", "## Fit${ad.fit?.let { " · $it" } ?: ""}") + ad.good.map { "- ✓ $it" } + ad.gaps.map { "- △ $it" })
            if (adText.isNotBlank()) lines.addAll(listOf("", "## Ad", "") + adText.trim().take(6000).split("\n"))
            lines.add("")
        }
        return path
    }

    fun fit(v: Vault, path: String): Int? = v.read(path)?.let { Md.fmGet(it.split("\n"), "fit")?.trim()?.toIntOrNull() }

    enum class Tone(val ask: String) {
        FIRST(""), SHORTER("Make it shorter: about 150 words."), WARMER("Make it warmer and more personal."),
        RESEARCH("Say more about user research experience."),
    }

    fun letterSystem(name: String = "Tom"): String = """
        You write cover letters for $name, a creative designer (app design and AI). His voice: friendly, plain, confident, a bit of humour, never salesy or buzzwordy.
        UK spelling. About 240 words unless asked otherwise. No address block or date. Start "Hi <company> team,". End "Thanks, Tom".
        Lead with something real he built (Flight Deck, an Android and Windows app for planning a day with a busy head, if nothing fits better).
        Cover the gaps honestly. Reply with the letter only.
    """.trimIndent()

    fun letterPrompt(jobNote: String, cv: String, previous: String?, tone: Tone): String = buildString {
        append("The job (my saved note):\n").append(jobNote.take(7000)).append("\n\nMy CV and portfolio note:\n").append(cv.ifBlank { "(none)" })
        if (previous != null && tone != Tone.FIRST) append("\n\nThe last draft:\n").append(previous).append("\n\n").append(tone.ask)
    }

    fun letterPath(jobPath: String) = jobPath.removeSuffix(".md") + " - Cover letter.md"

    /** Writes [letter] as the job's cover letter note (replacing an older draft) and links it from the job. */
    fun saveLetter(v: Vault, jobPath: String, letter: String, draft: Int, today: LocalDate = LocalDate.now()): String {
        val path = letterPath(jobPath)
        val name = VaultTasks.noteName(jobPath)
        val body = "---\ntype: cover-letter\njob: \"[[$name]]\"\ndraft: $draft\nupdated: $today\n---\n\n${letter.trim()}\n"
        if (v.entry(path) == null) {
            val dir = path.substringBeforeLast('/', "")
            v.mkdirs(dir)
            VaultIo.write(v, v.create(dir, path.substringAfterLast('/').removeSuffix(".md"), "md"), body)
            VaultIo.edit(v, jobPath) { lines -> if (lines.none { "Cover letter]]" in it }) lines.addAll(listOf("", "Cover letter: [[$name - Cover letter]]")) }
        } else VaultIo.write(v, path, body)
        return path
    }

    fun letter(v: Vault, jobPath: String): String? = v.read(letterPath(jobPath))?.split("\n")?.let { Md.fmBody(it).joinToString("\n").trim() }?.ifEmpty { null }

    fun draftNumber(v: Vault, jobPath: String): Int = v.read(letterPath(jobPath))?.let { Md.fmGet(it.split("\n"), "draft")?.trim()?.toIntOrNull() } ?: 0

    /** "This week" line for the Jobs list: applied, interviews and ones to chase in the next 7 days. */
    fun weekLine(jobs: List<Job>, today: LocalDate): String {
        val open = jobs.filter { !it.closed }
        val applied = open.count { it.applied?.let { d -> !d.isBefore(today.minusDays(6)) } == true }
        val interview = open.count { it.status.equals("Interview", true) }
        val chase = open.count { j -> j.next?.let { !it.isAfter(today.plusDays(6)) } == true }
        return "$applied applied · $interview interview · $chase to chase"
    }
}
