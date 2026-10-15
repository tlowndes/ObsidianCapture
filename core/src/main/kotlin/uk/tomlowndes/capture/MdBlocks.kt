package uk.tomlowndes.capture

sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Para(val text: String) : MdBlock
    data class Item(val indent: Int, val marker: String, val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Code(val text: String) : MdBlock
    data class Image(val alt: String, val src: String, val vault: Boolean) : MdBlock
    data object Rule : MdBlock
}

/** Just enough markdown for saved articles: headings, paragraphs, lists, quotes, code, images and rules. */
object MdBlocks {
    private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
    private val ITEM = Regex("^(\\s*)([-*+]|\\d+[.)])\\s+(.*)$")
    private val IMAGE = Regex("^!\\[([^\\]]*)]\\((\\S+?)(?:\\s+\"[^\"]*\")?\\)$")
    private val LINKED_IMAGE = Regex("^\\[!\\[([^\\]]*)]\\((\\S+?)\\)]\\(\\S+\\)$")
    private val EMBED = Regex("^!\\[\\[([^\\]|]+)(?:\\|[^\\]]*)?]]$")
    private val RULE = Regex("^([-*_])(\\s*\\1){2,}$")

    fun parse(lines: List<String>): List<MdBlock> {
        val out = mutableListOf<MdBlock>()
        val para = StringBuilder()
        val quote = mutableListOf<String>()
        fun flush() {
            if (para.isNotBlank()) out.add(MdBlock.Para(para.toString().trim()))
            para.clear()
            if (quote.isNotEmpty()) out.add(MdBlock.Quote(quote.joinToString("\n").trim()))
            quote.clear()
        }
        var i = 0
        while (i < lines.size) {
            val raw = lines[i].trimEnd()
            val line = raw.trim()
            i++
            if (line.startsWith("```")) {
                flush()
                val code = mutableListOf<String>()
                while (i < lines.size && !lines[i].trim().startsWith("```")) code.add(lines[i++].trimEnd())
                i++
                out.add(MdBlock.Code(code.joinToString("\n")))
                continue
            }
            if (line.isEmpty()) { flush(); continue }
            if (line.startsWith(">")) {
                if (para.isNotEmpty()) flush()
                quote.add(line.removePrefix(">").trim())
                continue
            }
            if (quote.isNotEmpty()) flush()
            val heading = HEADING.matchEntire(line)
            val image = IMAGE.matchEntire(line) ?: LINKED_IMAGE.matchEntire(line)
            val embed = EMBED.matchEntire(line)
            val item = ITEM.matchEntire(raw)
            val block: MdBlock? = when {
                heading != null -> MdBlock.Heading(heading.groupValues[1].length, heading.groupValues[2])
                RULE.matches(line) -> MdBlock.Rule
                image != null -> MdBlock.Image(image.groupValues[1], image.groupValues[2], vault = false)
                embed != null -> MdBlock.Image("", embed.groupValues[1].trim(), vault = true)
                item != null -> {
                    val m = item.groupValues[2]
                    val marker = if (m[0].isDigit()) m.trimEnd(')').trimEnd('.') + "." else "•"
                    MdBlock.Item(item.groupValues[1].replace("\t", "  ").length / 2, marker, item.groupValues[3])
                }
                else -> null
            }
            if (block != null) { flush(); out.add(block); continue }
            // A line ending in two spaces is a hard break; otherwise lines of a paragraph join with a space.
            if (para.isNotEmpty() && !para.endsWith("\n")) para.append(' ')
            para.append(line)
            if (lines[i - 1].endsWith("  ")) para.append('\n')
        }
        flush()
        return out
    }

    /** Inline markdown: 1,2 link; 3,4 bold; 5 code; 6 italic; 7,8 wikilink. A match starting with "!" is an image. */
    val INLINE = Regex(
        "!\\[[^\\]]*]\\([^)]*\\)" +                    // inline image (dropped)
            "|\\[([^\\]]+)]\\(([^)\\s]+)[^)]*\\)" +    // 1,2 link
            "|\\*\\*(.+?)\\*\\*|__(.+?)__" +           // 3,4 bold
            "|`([^`]+)`" +                             // 5 code
            "|\\*([^*\\s](?:[^*]*[^*\\s])?)\\*" +      // 6 italic
            "|\\[\\[([^\\]|]+)(?:\\|([^\\]]+))?]]"     // 7,8 wikilink
    )

    /** The text a highlight stores for this block, or null if the block can't be highlighted. */
    fun highlightKey(b: MdBlock): String? = when (b) {
        is MdBlock.Para -> b.text
        is MdBlock.Item -> b.text
        is MdBlock.Quote -> b.text
        else -> null
    }?.replace('\n', ' ')?.trim()
}
