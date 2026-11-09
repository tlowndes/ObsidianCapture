package uk.tomlowndes.capture

/** The four PARA doors on the Vault home. */
enum class ParaKind(val label: String, val icon: String, val words: List<String>) {
    PROJECTS("Projects", "🚀", listOf("project")),
    AREAS("Areas", "🌿", listOf("area")),
    RESOURCES("Resources", "📚", listOf("resource", "reference")),
    ARCHIVE("Archive", "🗄", listOf("archive")),
}

/**
 * PARA on the Vault home (paper-hybrid-v1): each kind is found among the vault's top-level folders by name
 * ("03 - Projects", "02 - Areas", "04 - Resources", "05 - Archive"), so nothing has to be renamed.
 */
object Para {
    /** Each kind's top-level folder, or null when the vault has none for it. Hidden (99) folders are skipped. */
    fun folders(topLevel: List<String>): Map<ParaKind, String?> = ParaKind.values().associateWith { k ->
        topLevel.filter { !VaultBrowse.isHidden(it) }.firstOrNull { f -> k.words.any { f.lowercase().contains(it) } }
    }

    const val QUIET_DAYS = 21L

    /**
     * Project folders (direct children of [projectsDir]) where no note has changed for [QUIET_DAYS] days: candidates for
     * "Archive?". Folders already holding the word "archive" are skipped. Newest-quiet last.
     */
    fun quietProjects(notes: List<NoteInfo>, projectsDir: String, now: Long): List<Pair<String, Long>> {
        val cutoff = now - QUIET_DAYS * 86_400_000L
        return notes.filter { it.path.startsWith("$projectsDir/") && it.path.removePrefix("$projectsDir/").contains('/') }
            .groupBy { projectsDir + "/" + it.path.removePrefix("$projectsDir/").substringBefore('/') }
            .mapValues { (_, ns) -> ns.maxOf { it.modified } }
            .filter { (f, last) -> last < cutoff && !f.lowercase().contains("archive") }
            .toList().sortedBy { it.second }
    }
}
