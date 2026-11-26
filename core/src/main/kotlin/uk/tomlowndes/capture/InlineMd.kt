package uk.tomlowndes.capture

/**
 * Inline markdown (links, bold, code, italic, [[wikilinks]], #tags) split into flat runs of text with their styles.
 * Each app turns the runs into its own styled text; the parsing lives here once.
 */
object InlineMd {
    /** One run of text. [url] is a markdown link's target, [wiki] a wikilink's note, [tag] a #tag in normal form. */
    data class Span(
        val text: String,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val code: Boolean = false,
        val url: String? = null,
        val wiki: String? = null,
        val tag: String? = null,
    )

    /** A #tag in running text (not inside a word, a URL or an HTML entity). */
    val TAG_IN_TEXT = Regex("(?<![\\w#/&])#[\\p{L}\\p{N}_/-]*[\\p{L}_/-][\\p{L}\\p{N}_/-]*")

    /** [s] as runs. With [tags], #tags in plain text get their own runs (#task is left plain). Images are dropped. */
    fun spans(s: String, tags: Boolean = false): List<Span> {
        val out = mutableListOf<Span>()
        fun add(sp: Span) { if (sp.text.isNotEmpty()) out.add(sp) }
        fun plain(p: String, base: Span) {
            if (!tags) { add(base.copy(text = p)); return }
            var at = 0
            for (m in TAG_IN_TEXT.findAll(p)) {
                add(base.copy(text = p.substring(at, m.range.first)))
                val tag = Tags.norm(m.value)?.takeIf { it != "#task" }
                add(base.copy(text = m.value, tag = tag))
                at = m.range.last + 1
            }
            add(base.copy(text = p.substring(at)))
        }
        fun inline(p: String, base: Span) {
            var at = 0
            for (m in MdBlocks.INLINE.findAll(p)) {
                plain(p.substring(at, m.range.first), base)
                val g = m.groups
                when {
                    m.value.startsWith("!") -> Unit
                    g[1] != null -> inline(g[1]!!.value, base.copy(url = g[2]!!.value))
                    g[3] != null || g[4] != null -> inline((g[3] ?: g[4])!!.value, base.copy(bold = true))
                    g[5] != null -> add(base.copy(text = g[5]!!.value, code = true))
                    g[6] != null -> inline(g[6]!!.value, base.copy(italic = true))
                    g[7] != null -> add(base.copy(text = (g[8] ?: g[7])!!.value, wiki = g[7]!!.value))
                }
                at = m.range.last + 1
            }
            plain(p.substring(at), base)
        }
        inline(s, Span(""))
        return out
    }
}
