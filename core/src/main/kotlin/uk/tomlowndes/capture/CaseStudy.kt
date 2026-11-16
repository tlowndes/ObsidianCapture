package uk.tomlowndes.capture

import java.time.LocalDate

/**
 * Portfolio case study builder (growth-v1): Claude Clever turns a project's summary, Log entries and images into a
 * case study draft (problem, process, outcome), saved as its own note next to the "Portfolio & CV" note, so the
 * Job co-pilot can point to it.
 */
object CaseStudy {
    enum class Tone(val ask: String) { FIRST(""), SHORTER("Make it shorter: about 500 words."), RECRUITER("Rewrite it for a recruiter skimming in a minute: lead with the outcome, plain words.") }

    const val SYSTEM = """You write portfolio case studies for Tom, a creative designer (app design and AI) job hunting for in-house or remote design roles.
Voice: plain, confident, specific, a bit of humour, UK spelling, no buzzwords or sales talk.
Markdown with exactly these sections: "## The problem", "## Process", "## Outcome", then "## What I'd do next".
About 900–1,100 words unless asked otherwise. Use real numbers from the notes (entries, mockups, releases). Don't invent clients or metrics.
Where an image would help, add a line like "![[file name.png]]" using only image names from the notes. Reply with the case study only."""

    /** What Claude reads: the project's note, its Log and its images, cut to fit. */
    fun prompt(d: IdeaDoc, note: String, extra: String = ""): String = buildString {
        append("Project: ${d.title}\nStatus: ${d.status}\n")
        d.type?.let { append("Type: $it\n") }
        append("Entries in the Log: ${d.entries.size}. Images: ${d.entries.sumOf { it.images.size }}.\n\n")
        append("The project note:\n").append(note.take(6000)).append("\n\nThe Log, newest first:\n")
        var left = 14_000
        for (e in d.entries) {
            val chunk = "### ${e.heading}\n${e.text}\n" + e.images.joinToString("") { "![[$it]]\n" }
            if (chunk.length > left) break
            append(chunk); left -= chunk.length
        }
        if (extra.isNotBlank()) append("\n").append(extra)
    }

    fun redo(previous: String, tone: Tone) = "The last draft:\n$previous\n\n${tone.ask}"

    /** Where case studies go: beside the CV note, else a Portfolio folder in Projects. */
    fun folder(v: Vault): String = JobCopilot.cvPath(v)?.substringBeforeLast('/', "")?.ifEmpty { null } ?: "03 - Projects/Portfolio"

    fun pathFor(v: Vault, project: String) = "${folder(v)}/${IdeaNote.safeName(project)} - Case study.md"

    /** Saves (or replaces) the draft. Returns its path. */
    fun save(v: Vault, project: String, draft: String, today: LocalDate = LocalDate.now()): String {
        val path = pathFor(v, project)
        val body = "---\ntype: case-study\nproject: \"[[${IdeaNote.safeName(project)}]]\"\nupdated: $today\ntags: [portfolio]\n---\n\n# ${project.trim()}\n\n${draft.trim()}\n"
        if (v.entry(path) == null) {
            v.mkdirs(folder(v))
            return v.create(folder(v), path.substringAfterLast('/').removeSuffix(".md"), "md").also { VaultIo.write(v, it, body) }
        }
        VaultIo.write(v, path, body)
        return path
    }

    fun words(text: String) = text.split(Regex("\\s+")).count { it.isNotBlank() }
}
