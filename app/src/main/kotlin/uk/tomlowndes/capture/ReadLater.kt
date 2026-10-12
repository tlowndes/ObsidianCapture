package uk.tomlowndes.capture

import android.content.Context
import android.net.Uri
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A fetched page, ready to be written as a note. */
data class Article(val title: String, val markdown: String, val video: Boolean)

/**
 * Saves a link into the Read It Later inbox the same way the Obsidian read-it-later plugin does
 * when you paste from the clipboard: fetch the page, keep the readable article, write it as markdown.
 * If the page can't be fetched or parsed, it falls back to a note holding just the link.
 */
object ReadLater {
    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    private val WS = Regex("\\s+")

    /** Blocking (network + file IO): call from a background dispatcher. Returns true if the full page was saved. */
    fun save(ctx: Context, vault: Uri, url: String, sharedTitle: String?): Boolean {
        val article = runCatching { fetch(url) }.getOrNull()
        val name = noteName(article, sharedTitle)
            ?: "Article " + SimpleDateFormat("yyyy-MM-dd HHmmss", Locale.UK).format(Date())
        VaultWriter(ctx, vault).addNote(name, note(url, sharedTitle, article, ReadStore.stamp()))
        return article != null
    }

    /** The whole note: frontmatter the Read tab uses, then the same layout as the read-it-later plugin. */
    fun note(url: String, sharedTitle: String?, article: Article?, saved: String): String {
        val label = article?.title?.takeIf { it.isNotBlank() } ?: sharedTitle?.takeIf { it.isNotBlank() && it != url } ?: url
        val body = when {
            article == null -> "[[ReadItLater]] [[Article]]\n\n[$label]($url)\n"
            article.video -> "[[ReadItLater]] [[Youtube]]\n\n# [${article.title}]($url)\n\n![]($url)\n"
            else -> "[[ReadItLater]] [[Article]]\n\n# [${article.title}]($url)\n\n${article.markdown}\n"
        }
        return "---\nstatus: unread\nsaved: $saved\n---\n\n" + body
    }

    /** File name for the note, or null if neither the page nor the share gave a usable title. */
    fun noteName(article: Article?, sharedTitle: String?): String? =
        article?.title?.let(::safeName)?.takeIf { it.isNotBlank() }
            ?: sharedTitle?.let(::safeName)?.takeIf { it.isNotBlank() }

    private fun safeName(s: String) =
        s.replace(Regex("[\\\\/:*?\"<>|#^\\[\\]]"), "-").replace(WS, " ").trim().take(100).trim()

    private fun fetch(url: String): Article? =
        fromPage(Jsoup.connect(url).userAgent(UA).timeout(15_000).followRedirects(true).maxBodySize(4_000_000).get(), url)

    /** Turns a downloaded page into an [Article]; null if there isn't enough readable text. */
    fun fromHtml(html: String, url: String): Article? = fromPage(Jsoup.parse(html, url), url)

    private fun fromPage(doc: Document, url: String): Article? {
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: doc.title()
        val host = runCatching { URI(url).host.orEmpty() }.getOrDefault("")
        if (host.endsWith("youtube.com") || host == "youtu.be") return Article(title.trim(), "", video = true)

        doc.select("script,style,noscript,nav,footer,header,aside,form,iframe,svg,button,[role=navigation],[aria-hidden=true]").remove()
        val root = doc.selectFirst("article") ?: doc.selectFirst("main") ?: bestBlock(doc)
        val sb = StringBuilder()
        render(root, sb, 0)
        val md = sb.toString()
            .replace(Regex("[ \\t]+\\n"), "\n").replace(Regex("\\n{3,}"), "\n\n").trim()
        if (md.length < 80) return null
        return Article(title.trim(), md, video = false)
    }

    /** The div/section whose direct paragraphs hold the most text; falls back to <body>. */
    private fun bestBlock(doc: Document): Element {
        val body = doc.body()
        val best = body.select("div, section").maxByOrNull { el ->
            el.children().toList().filter { it.normalName() == "p" }.sumOf { it.text().length }
        }
        val score = best?.children()?.toList()?.filter { it.normalName() == "p" }?.sumOf { it.text().length } ?: 0
        return if (best != null && score > 200) best else body
    }

    private fun block(sb: StringBuilder, body: () -> Unit) { sb.append("\n\n"); body(); sb.append("\n\n") }

    private fun inner(n: Element, depth: Int): String {
        val s = StringBuilder()
        n.childNodes().forEach { render(it, s, depth) }
        return s.toString()
    }

    private fun render(n: Node, sb: StringBuilder, depth: Int) {
        if (n is TextNode) {
            val t = n.wholeText.replace(WS, " ")
            sb.append(if (sb.isEmpty() || sb.last() == '\n') t.trimStart() else t)
            return
        }
        if (n !is Element) return
        when (val tag = n.normalName()) {
            "h1", "h2", "h3", "h4", "h5", "h6" -> {
                val text = inner(n, depth).trim()
                if (text.isNotEmpty()) block(sb) { sb.append("#".repeat(tag[1] - '0')).append(' ').append(text) }
            }
            "p" -> block(sb) { sb.append(inner(n, depth).trim()) }
            "br" -> sb.append("  \n")
            "hr" -> block(sb) { sb.append("---") }
            "strong", "b" -> inner(n, depth).trim().takeIf { it.isNotEmpty() }?.let { sb.append("**").append(it).append("**") }
            "em", "i" -> inner(n, depth).trim().takeIf { it.isNotEmpty() }?.let { sb.append("*").append(it).append("*") }
            "code" -> sb.append('`').append(n.text()).append('`')
            "pre" -> block(sb) { sb.append("```\n").append(n.wholeText().trim('\n')).append("\n```") }
            "blockquote" -> block(sb) {
                sb.append(inner(n, depth).trim().lines().joinToString("\n") { "> $it" })
            }
            "a" -> {
                val text = inner(n, depth).trim()
                val href = n.absUrl("href")
                if (text.isEmpty()) return
                if (href.isEmpty() || href.startsWith("javascript")) sb.append(text)
                else sb.append('[').append(text).append("](").append(href.replace(" ", "%20").replace(")", "%29")).append(')')
            }
            "img" -> {
                val src = listOf("src", "data-src", "data-lazy-src").map { n.absUrl(it) }.firstOrNull { it.isNotEmpty() }
                if (src != null && !src.startsWith("data:")) {
                    sb.append("\n\n![").append(n.attr("alt").replace("\n", " ")).append("](")
                        .append(src.replace(" ", "%20").replace(")", "%29")).append(")\n\n")
                }
            }
            "ul", "ol" -> {
                var i = 1
                sb.append('\n')
                for (li in n.children().toList().filter { it.normalName() == "li" }) {
                    sb.append('\n').append("  ".repeat(depth))
                        .append(if (tag == "ol") "${i++}. " else "- ")
                    sb.append(inner(li, depth + 1).trim().replace(Regex("\\n{2,}"), "\n"))
                }
                sb.append("\n\n")
            }
            "tr" -> block(sb) { sb.append(n.children().joinToString(" | ") { it.text() }) }
            "div", "section", "article", "main", "figure", "figcaption", "table", "tbody", "thead", "dl", "dt", "dd" -> {
                sb.append('\n'); n.childNodes().forEach { render(it, sb, depth) }; sb.append('\n')
            }
            else -> n.childNodes().forEach { render(it, sb, depth) }
        }
    }
}
