package uk.tomlowndes.capture

import java.time.LocalDate

/** One job application note. [next] is its follow-up date. */
data class Job(val path: String, val company: String, val role: String, val status: String, val next: LocalDate?, val applied: LocalDate?) {
    val closed get() = status.lowercase() in Jobs.CLOSED
}

/**
 * Job applications as the vault already keeps them: one note per application in the Job Hunt folder with
 * frontmatter `type: application`, `company`, `role`, `status` (Shortlisted, Applied, Interview, Offer, Rejected,
 * Withdrawn) and `follow_up` as the next date. New ones are made from the vault's own Application template.
 */
object Jobs {
    const val DIR = "02 - Area/Work/Job Hunt/Applications"
    const val TEMPLATE = "99 - Meta/Templates/(TEMPLATE) Application.md"
    val STATUSES = listOf("Shortlisted", "Applied", "Interview", "Offer", "Rejected", "Withdrawn")
    val CLOSED = setOf("rejected", "withdrawn")

    fun parse(path: String, text: String): Job? {
        val lines = text.split("\n")
        fun get(k: String) = Md.fmGet(lines, k)?.trim()?.trim('"', '\'')?.trim()?.ifEmpty { null }
        if (get("type")?.lowercase() != "application") return null
        fun date(k: String) = get(k)?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }
        return Job(
            path,
            get("company").orEmpty(),
            get("role") ?: VaultTasks.noteName(path),
            get("status") ?: "Shortlisted",
            date("follow_up"), date("applied")
        )
    }

    fun list(v: Vault): List<Job> = v.list(DIR).filter { !it.isDir && it.name.endsWith(".md") }
        .mapNotNull { e -> runCatching { parse(join(DIR, e.name), VaultIo.read(v, join(DIR, e.name))) }.getOrNull() }

    /** Open jobs first by next date (undated last), then closed ones. */
    fun sorted(jobs: List<Job>): Pair<List<Job>, List<Job>> =
        jobs.filter { !it.closed }.sortedWith(compareBy({ it.next == null }, { it.next })) to jobs.filter { it.closed }.sortedBy { it.company }

    fun nextDate(jobs: List<Job>): LocalDate? = jobs.filter { !it.closed }.mapNotNull { it.next }.minOrNull()

    /** A new application note from the vault's template (or a plain one if it's gone), with company and role set. */
    fun create(v: Vault, company: String, role: String, today: LocalDate = LocalDate.now()): String {
        val title = IdeaNote.safeName(listOf(company, role).filter { it.isNotBlank() }.joinToString(" - ")).ifBlank { "New application" }
        val template = v.read(TEMPLATE) ?: PLAIN
        val body = template
            .replace(Regex("<%\\s*tp\\.file\\.title\\s*%>"), title)
            .replace(Regex("<%\\s*tp\\.date\\.now\\([^)]*\\)\\s*%>"), today.toString())
        v.mkdirs(DIR)
        val path = v.create(DIR, title, "md")
        VaultIo.write(v, path, body)
        VaultIo.edit(v, path) { Md.fmSet(it, "company", company.trim()); Md.fmSet(it, "role", role.trim()) }
        return path
    }

    fun setStatus(v: Vault, path: String, status: String) = VaultIo.edit(v, path) { Md.fmSet(it, "status", status) }
    fun setNext(v: Vault, path: String, date: LocalDate?) = VaultIo.edit(v, path) { Md.fmSet(it, "follow_up", date?.toString() ?: "") }

    private val PLAIN = """
        ---
        type: application
        company:
        role:
        status: Shortlisted
        applied:
        follow_up:
        created: <% tp.date.now("YYYY-MM-DD") %>
        tags:
          - job
        ---

        # <% tp.file.title %>

        ## Next step
        - [ ] #job

        ## Notes
        -
    """.trimIndent() + "\n"
}
