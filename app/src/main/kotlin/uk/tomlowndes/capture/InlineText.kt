package uk.tomlowndes.capture

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle

/**
 * Inline markdown as styled text, from the runs [InlineMd] parses in core.
 * [tags] colours #tags. Links are blue, underlined when [underline], and carry a "url" annotation when [tapLinks].
 * Wikilinks are blue; with [tapWiki] they are underlined too and carry a "wiki" annotation.
 */
internal fun inlineText(
    s: String, tags: Boolean = false, underline: Boolean = true, tapLinks: Boolean = true, tapWiki: Boolean = false,
): AnnotatedString = buildAnnotatedString { appendInline(s, tags, underline, tapLinks, tapWiki) }

internal fun AnnotatedString.Builder.appendInline(
    s: String, tags: Boolean = false, underline: Boolean = true, tapLinks: Boolean = true, tapWiki: Boolean = false,
) {
    for (sp in InlineMd.spans(s, tags)) {
        val link = sp.url != null
        val wiki = sp.wiki != null
        val style = SpanStyle(
            color = if (link || wiki) Primary else sp.tag?.let { tagColour(it).first } ?: Color.Unspecified,
            fontWeight = if (sp.bold) FontWeight.Bold else null,
            fontStyle = if (sp.italic) FontStyle.Italic else null,
            fontFamily = if (sp.code) FontFamily.Monospace else null,
            background = if (sp.code) Surface2 else Color.Unspecified,
            textDecoration = if ((link && underline) || (wiki && tapWiki)) TextDecoration.Underline else null,
        )
        val note = when {
            link && tapLinks -> "url" to sp.url!!
            wiki && tapWiki -> "wiki" to sp.wiki!!
            else -> null
        }
        if (note != null) pushStringAnnotation(note.first, note.second)
        withStyle(style) { append(sp.text) }
        if (note != null) pop()
    }
}
