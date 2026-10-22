package uk.tomlowndes.capture

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** [modified] is when the idea note or its log file last changed, whichever is later. */
data class IdeaSummary(
    val title: String, val snippet: String, val entries: Int, val images: Int,
    val firstImage: String?, val status: String, val modified: Long
)

/** One dated entry in an idea's Log. [heading] is the text after `### `. */
data class IdeaEntry(val heading: String, val text: String, val images: List<String>)

data class IdeaDoc(val title: String, val status: String, val summary: String, val entries: List<IdeaEntry>)

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

    private fun edit(title: String, change: (MutableList<String>) -> Unit) = VaultIo.edit(vault, noteOf(title), change)

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
            doc.status, maxOf(note.modified, logEntry?.modified ?: 0L)
        )
    }.let { sorted(it, byUpdated = true) }

    fun load(title: String): IdeaDoc = docOf(title, noteOf(title), logOf(title))

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
        VaultIo.write(vault, vault.create(folder, title, "md"), IdeaNote.newNote(title, summary, java.time.LocalDate.now()))
        VaultIo.write(vault, vault.create(folder, IdeaNote.logName(title), "md"), IdeaNote.newLog(title))
        return title
    }

    /** [was] is the summary as shown when editing started; stops rather than overwrite a change made elsewhere. */
    fun setSummary(title: String, was: String, text: String) = edit(title) { IdeaNote.setSummary(it, was, text) }

    fun setStatus(title: String, status: String) = edit(title) { Md.fmSet(it, "status", status) }

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
