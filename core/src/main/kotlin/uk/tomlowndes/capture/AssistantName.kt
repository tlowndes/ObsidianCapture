package uk.tomlowndes.capture

/**
 * The assistant's name, chosen in Settings. Blank or odd input falls back to [DEFAULT].
 * Stored preference keys stay as they are; this only cleans what is shown and spoken.
 */
object AssistantName {
    const val DEFAULT = "Tower"
    const val MAX = 24

    /** Trim, keep a short readable name, and use [DEFAULT] when nothing is left. */
    fun clean(raw: String?): String {
        val t = raw.orEmpty().trim().replace(Regex("\\s+"), " ")
            .filter { it.isLetterOrDigit() || it == ' ' || it == '-' || it == '\'' }
            .trim().take(MAX).trim()
        return t.ifEmpty { DEFAULT }
    }
}
