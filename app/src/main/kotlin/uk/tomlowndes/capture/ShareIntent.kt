package uk.tomlowndes.capture

/**
 * What a share-sheet send should do, without the Android intent. A link, or a link with only a short
 * caption, is a page for Read It Later. Anything longer is a brain dump for Sort it.
 */
object ShareIntent {
    private val URL = Regex("https?://\\S+")

    /** True when [text] should be saved as a page rather than appended to the Capture box. */
    fun isPage(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return true
        val url = URL.find(t)?.value ?: return false
        return t.replace(url, "").trim().length < 60
    }

    /** The link to fetch, or the trimmed text when there isn't one. */
    fun urlOf(text: String): String {
        val t = text.trim()
        return URL.find(t)?.value ?: t
    }

    /**
     * The note title: the share subject when the sender set one (even if it's blank), otherwise the
     * caption around the link, or null when there's nothing but the link.
     */
    fun pageTitle(text: String, subject: String?): String? {
        if (subject != null) return subject
        val t = text.trim()
        val url = URL.find(t)?.value ?: t
        return t.replace(url, "").trim().ifBlank { null }
    }

    /** [shared] on its own, or under the capture draft that's already there. */
    fun captureDraft(existing: String, shared: String): String {
        val draft = existing.trim()
        val text = shared.trim()
        return if (draft.isEmpty()) text else "$draft\n$text"
    }
}
