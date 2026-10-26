package uk.tomlowndes.capture

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** [modified] is when the idea note or its log file last changed, whichever is later. */
data class IdeaSummary(
    val title: String, val snippet: String, val entries: Int, val images: Int,
    val firstImage: String?, val status: String, val modified: Long,
    val type: String? = null, val area: String? = null, val next: String? = null, val couldContinue: Boolean = false
) {
    val shelved get() = status.lowercase() in setOf("shelved", "done")
}

/** One dated entry in an idea's Log. [heading] is the text after `### `. */
data class IdeaEntry(val heading: String, val text: String, val images: List<String>)

/** [type], [area] come from frontmatter; [next] is the first open checkbox in the note. */
data class IdeaDoc(
    val title: String, val status: String, val summary: String, val entries: List<IdeaEntry>,
    val type: String? = null, val area: String? = null, val next: String? = null, val couldContinue: Boolean = false
)

/**
 * Ideas live as `<ideas folder>/<Title>/<Title>.md` (a folder note) with their entries in `<Title> - Log.md` beside it,
 * and images in the images folder.
 * Every edit is a read-modify-write of just the section it owns, so changes made in Obsidian are kept.
 */
class IdeaStore(private val vault: Vault, private val prefs: VaultPrefs) {
    private fun read(path: String) = VaultIo.read(vault, path)

    private fun folderOf(title: String): String =
        join(prefs.ideasDir, title).takeIf { vault.entry(it)?.isDir == true } ?: error("Idea \"$title\" not found")

    private fun noteOf(title: String): String =
        join(folderOf(title), "$title.md").takeIf { vault.entry(it) != null } ?: error("Idea \"$title\" not found")

    private fun logOf(title: String): String? =
        join(folderOf(title), "${IdeaNote.logName(title)}.md").takeIf { vault.entry(it) != null }

    /** Where the entries are: the log file, or the idea note for an idea from before the split. */
    private fun entriesOf(title: String) = logOf(title) ?: noteOf(title)

    /** The log file, made on first use; entries still in the idea note move across and are swapped for a link. */
    private fun logFile(title: String): String {
        logOf(title)?.let { return it }
        val note = noteOf(title)
        val moved = IdeaNote.logEntries(read(note).split("\n"))
        val log = vault.create(folderOf(title), IdeaNote.logName(title), "md")
        VaultIo.write(vault, log, IdeaNote.newLog(title, moved))
        VaultIo.edit(vault, note) { IdeaNote.moveLogOut(it, title, moved) }
        return log
    }

    /** The idea note's summary and status, with the entries from wherever they live. */
    private fun docOf(title: String, note: String, log: String?): IdeaDoc {
        val doc = IdeaNote.parse(title, read(note))
        return if (log == null) doc else doc.copy(entries = IdeaNote.parse(title, read(log)).entries)
    }

    private fun edit(title: String, change: (MutableList<String>) -> Unit) = VaultIo.edit(vault, noteOf(title), change = change)

    // ---- reading ----

    fun list(): List<IdeaSummary> = vault.list(prefs.ideasDir).filter { it.isDir }.mapNotNull { d ->
        val name = d.name
        val folder = join(prefs.ideasDir, name)
        val files = vault.list(folder).associateBy { it.name }
        val note = files["$name.md"]?.takeIf { !it.isDir } ?: return@mapNotNull null
        val logEntry = files["${IdeaNote.logName(name)}.md"]
        val log = logEntry?.let { join(folder, it.name) }
        val doc = docOf(name, join(folder, note.name), log)
        IdeaSummary(
            name, doc.summary.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty(),
            doc.entries.size, doc.entries.sumOf { it.images.size },
            doc.entries.firstOrNull { it.images.isNotEmpty() }?.images?.first(),
            doc.status, maxOf(note.modified, logEntry?.modified ?: 0L),
            doc.type, doc.area, doc.next, doc.couldContinue
        )
    }.let { sorted(it, byUpdated = true) }

    fun load(title: String): IdeaDoc = docOf(title, noteOf(title), logOf(title))

    /** The idea note as written, for finding its links. */
    fun noteText(title: String): String = read(noteOf(title))

    /** A project's own Changelog or Decisions note, without its frontmatter; null when there isn't one. */
    fun notePart(title: String, name: String): String? =
        join(folderOf(title), "$name.md").takeIf { vault.entry(it) != null }
            ?.let { Md.fmBody(read(it).split("\n")).joinToString("\n").trim() }

    /** Image file name -> what this platform's image loader takes, for everything in the images folder. */
    fun imageIndex(): Map<String, Any> =
        vault.list(prefs.imagesDir).filter { !it.isDir }.associate { it.name to vault.handle(join(prefs.imagesDir, it.name)) }

    // ---- writing ----

    fun create(rawTitle: String, summary: String): String {
        val title = IdeaNote.safeName(rawTitle)
        require(title.isNotBlank()) { "Give the idea a title" }
        val folder = join(prefs.ideasDir, title)
        require(vault.entry(folder) == null) { "\"$title\" already exists" }
        vault.mkdirs(folder)
        try {
            VaultIo.write(vault, vault.create(folder, title, "md"), IdeaNote.newNote(title, summary, java.time.LocalDate.now()))
            VaultIo.write(vault, vault.create(folder, IdeaNote.logName(title), "md"), IdeaNote.newLog(title))
        } catch (e: Exception) {
            runCatching { discard(title) }
            throw e
        }
        return title
    }

    /**
     * The idea project for a Sort it save. [created] is true when this call made it (or finished one a
     * failed save left half-written), so the caller can remove it if the rest of the save fails.
     * A project that was already in the vault is left as it is.
     */
    fun ensureIdea(rawTitle: String, summary: String = ""): Ensured {
        val title = IdeaNote.safeName(rawTitle)
        require(title.isNotBlank()) { "Give the idea a title" }
        val folder = join(prefs.ideasDir, title)
        if (vault.entry(folder) == null) return Ensured(create(rawTitle, summary), created = true)
        if (vault.entry(folder)?.isDir != true) error("\"$title\" already exists")
        val notePath = join(folder, "$title.md")
        val logPath = join(folder, "${IdeaNote.logName(title)}.md")
        val note = if (vault.entry(notePath) != null) VaultIo.read(vault, notePath) else ""
        val log = if (vault.entry(logPath) != null) VaultIo.read(vault, logPath) else ""
        if (note.isBlank()) {
            if (vault.entry(notePath) == null) vault.create(folder, title, "md")
            VaultIo.write(vault, notePath, IdeaNote.newNote(title, summary, java.time.LocalDate.now()))
            writeLog(folder, title, logPath)
            return Ensured(title, created = true)
        }
        // The note landed and the log write didn't (the log file is missing or still empty). Finish it,
        // but don't treat the project as new: a later failure must not delete a note that already survived.
        if (freshCapture(title, summary, note) && log.isBlank()) writeLog(folder, title, logPath)
        return Ensured(title, created = false)
    }

    /** Removes an idea this save just created, files first so the folder can go too. */
    fun discard(title: String) {
        val safe = IdeaNote.safeName(title)
        val folder = join(prefs.ideasDir, safe)
        if (vault.entry(folder) == null) return
        vault.list(folder).filter { !it.isDir }.forEach { vault.delete(join(folder, it.name)) }
        vault.delete(folder)
    }

    private fun writeLog(folder: String, title: String, logPath: String) {
        if (vault.entry(logPath) == null) vault.create(folder, IdeaNote.logName(title), "md")
        VaultIo.write(vault, logPath, IdeaNote.newLog(title))
    }

    /** The empty idea note [create] writes, ignoring the created date so a later retry still matches. */
    private fun freshCapture(title: String, summary: String, text: String): Boolean {
        val created = Regex("created: \\d{4}-\\d{2}-\\d{2}")
        val got = text.trimEnd().replace(created, "created: DATE")
        val want = IdeaNote.newNote(title, summary, java.time.LocalDate.EPOCH).trimEnd().replace(created, "created: DATE")
        return got == want
    }

    /** Result of [ensureIdea]. */
    data class Ensured(val title: String, val created: Boolean)

    /** [was] is the summary as shown when editing started; stops rather than overwrite a change made elsewhere. */
    fun setSummary(title: String, was: String, text: String) = edit(title) { IdeaNote.setSummary(it, was, text) }

    fun setStatus(title: String, status: String) = edit(title) { Md.fmSet(it, "status", status) }

    fun setType(title: String, type: String) = edit(title) { Md.fmSet(it, "type", type) }

    fun setArea(title: String, area: String) = edit(title) { Md.fmSet(it, "area", area) }

    /** Ticks the next step (the note's first open checkbox) with today's date. */
    fun tickNext(title: String) = edit(title) { IdeaNote.tickNext(it, java.time.LocalDate.now()) }

    fun setCouldContinue(title: String, on: Boolean) = edit(title) { Md.fmSet(it, "could_continue", on.toString()) }

    /** New entries go at the top of the log file, newest first. */
    fun addEntry(title: String, text: String, images: List<String>) =
        VaultIo.edit(vault, logFile(title)) { IdeaNote.addEntry(it, LocalDateTime.now().format(STAMP), text, images) }

    /** [entry] is the entry as shown when editing started. */
    fun editEntry(title: String, entry: IdeaEntry, text: String) =
        VaultIo.edit(vault, entriesOf(title)) { IdeaNote.editEntry(it, entry, text) }

    fun deleteEntry(title: String, entry: IdeaEntry) = VaultIo.edit(vault, entriesOf(title)) { IdeaNote.deleteEntry(it, entry) }

    /**
     * Saves an image (already shrunk and encoded by the platform) into the images folder as `<baseName>.<ext>`.
     * Returns its file name and what the image loader takes for it.
     */
    fun saveImage(bytes: ByteArray, baseName: String, ext: String = "jpg"): Pair<String, Any> {
        val path = vault.create(prefs.imagesDir, baseName, ext)
        vault.writeBytes(path, bytes)
        return nameOf(path) to vault.handle(path)
    }

    companion object {
        private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        fun safeName(s: String) = IdeaNote.safeName(s)

        /** The Ideas list order: last updated first, or by title A–Z. */
        fun sorted(ideas: List<IdeaSummary>, byUpdated: Boolean): List<IdeaSummary> =
            if (byUpdated) ideas.sortedByDescending { it.modified }
            else ideas.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
    }
}
