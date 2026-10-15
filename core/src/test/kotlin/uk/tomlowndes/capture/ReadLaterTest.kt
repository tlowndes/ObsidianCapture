package uk.tomlowndes.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** Saving a link (ReadLater) and reading the note back in the Read tab (ReadNote). */
class ReadLaterTest {
    private val url = "https://example.com/posts/slow-mornings"
    private val ref = ReadRef("Slow mornings.md", archived = false)
    private val article = Article("Slow mornings", "First paragraph of the article, long enough to be a snippet on its own.\n\nSecond.", video = false)

    private fun epoch(s: String) = LocalDateTime.parse(s).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test fun `a saved article note has the plugin's layout and unread frontmatter`() {
        assertEquals(
            "---\nstatus: unread\nsaved: 2026-10-12T09:30\n---\n\n[[ReadItLater]] [[Article]]\n\n# [Slow mornings]($url)\n\n${article.markdown}\n",
            ReadLater.note(url, null, article, "2026-10-12T09:30")
        )
    }

    @Test fun `the Read tab reads back what was saved`() {
        val doc = ReadNote.parse(ref, ReadLater.note(url, null, article, "2026-10-12T09:30"), modified = 0)
        assertEquals("Slow mornings", doc.title)
        assertEquals(url, doc.url)
        assertFalse(doc.read)
        assertEquals(epoch("2026-10-12T09:30"), doc.saved)
        assertEquals(listOf("", "First paragraph of the article, long enough to be a snippet on its own.", "", "Second.", ""), doc.body)
        assertEquals("First paragraph of the article, long enough to be a snippet on its own.", ReadNote.snippet(doc.body))
    }

    @Test fun `a page that couldn't be fetched is saved as a link`() {
        val text = ReadLater.note(url, "Shared title", null, "2026-10-12T09:30")
        assertTrue(text.endsWith("[[ReadItLater]] [[Article]]\n\n[Shared title]($url)\n"))
        val doc = ReadNote.parse(ReadRef("Shared title.md", false), text, 0)
        assertEquals("Shared title", doc.title)
        assertEquals(url, doc.url)
    }

    @Test fun `a shared url with no title uses the url as the label`() {
        assertTrue(ReadLater.note(url, url, null, "x").contains("[$url]($url)"))
    }

    @Test fun `videos get an embed`() {
        val text = ReadLater.note("https://youtu.be/abc", null, Article("A talk", "", video = true), "x")
        assertTrue(text.endsWith("[[ReadItLater]] [[Youtube]]\n\n# [A talk](https://youtu.be/abc)\n\n![](https://youtu.be/abc)\n"))
    }

    @Test fun `file names come from the page title, then the shared title`() {
        assertEquals("Slow mornings", ReadLater.noteName(article, "Shared"))
        assertEquals("Why- A -guide- -2026-", ReadLater.noteName(null, "Why? A [guide] #2026|"))
        assertNull(ReadLater.noteName(null, null))
        assertNull(ReadLater.noteName(Article(" ", "md", false), "  "))
    }

    @Test fun `read state comes from status or the archive folder`() {
        val unread = "---\nstatus: unread\n---\n\n# T\n"
        assertFalse(ReadNote.parse(ref, unread, 0).read)
        assertTrue(ReadNote.parse(ref, unread.replace("unread", "read"), 0).read)
        assertTrue(ReadNote.parse(ref, unread.replace("unread", "Archived"), 0).read)
        assertTrue(ReadNote.parse(ReadRef("T.md", archived = true), unread, 0).read)
        assertFalse(ReadNote.parse(ref, "# No frontmatter\n", 0).read)
    }

    @Test fun `saved time falls back to the file time`() {
        assertEquals(123L, ReadNote.parse(ref, "# T\n", 123L).saved)
        assertEquals(epoch("2026-10-12T00:00"), ReadNote.parse(ref, "---\nsaved: 2026-10-12\n---\n", 1L).saved)
        assertEquals(epoch("2026-10-12T09:30"), ReadNote.parse(ref, "---\nsaved: \"2026-10-12 09:30\"\n---\n", 1L).saved)
        assertEquals(5L, ReadNote.parse(ref, "---\nsaved: rubbish\n---\n", 5L).saved)
    }

    @Test fun `highlights are added, listed and removed`() {
        val ls = ReadLater.note(url, null, article, "2026-10-12T09:30").split("\n").toMutableList()
        val original = ls.joinToString("\n")
        ReadNote.toggleHighlight(ls, "First paragraph")
        ReadNote.toggleHighlight(ls, "Second.\nwith a line break")
        assertTrue(ls.joinToString("\n").endsWith("Second.\n\n## Highlights\n\n> First paragraph\n\n> Second. with a line break\n"))

        val doc = ReadNote.parse(ref, ls.joinToString("\n"), 0)
        assertEquals(listOf("First paragraph", "Second. with a line break"), doc.highlights)
        assertFalse(doc.body.any { it.startsWith(">") || it.startsWith("## Highlights") })

        ReadNote.toggleHighlight(ls, "First paragraph")
        assertEquals(listOf("Second. with a line break"), ReadNote.parse(ref, ls.joinToString("\n"), 0).highlights)
        assertTrue(original.length < ls.joinToString("\n").length)
    }

    @Test fun `plain text for previews`() {
        assertEquals("Bold link code", ReadNote.plainText("**Bold** [link](https://x.y) `code`"))
        assertEquals("Quote", ReadNote.plainText("> Quote"))
        assertEquals("Item", ReadNote.plainText("1. Item"))
        assertEquals("", ReadNote.plainText("![alt](https://x.y/i.png)"))
    }

    @Test fun `snippet skips headings and images`() {
        assertEquals("Short", ReadNote.snippet(listOf("# Heading", "![](i.png)", "", "Short")))
    }

    @Test fun `html pages become markdown`() {
        val html = """
            <html><head><title>Fallback</title><meta property="og:title" content="Slow mornings"></head>
            <body>
              <nav>Home | About</nav>
              <script>track()</script>
              <article>
                <h2>Why slow</h2>
                <p>Mornings go better with <strong>less</strong> rush and <em>more</em> tea, says <a href="/about">me</a>.</p>
                <ul><li>Kettle</li><li>Toast</li></ul>
                <ol><li>Wake</li><li>Stretch</li></ol>
                <blockquote>Slow is smooth</blockquote>
                <img src="/img/tea.png" alt="Tea">
              </article>
            </body></html>
        """.trimIndent()
        val a = ReadLater.fromHtml(html, url)!!
        assertEquals("Slow mornings", a.title)
        assertFalse(a.video)
        val md = a.markdown
        assertTrue(md, md.startsWith("## Why slow"))
        assertTrue(md, md.contains("Mornings go better with **less** rush and *more* tea, says [me](https://example.com/about)."))
        assertTrue(md, md.contains("- Kettle\n- Toast"))
        assertTrue(md, md.contains("1. Wake\n2. Stretch"))
        assertTrue(md, md.contains("> Slow is smooth"))
        assertTrue(md, md.contains("![Tea](https://example.com/img/tea.png)"))
        assertFalse(md, md.contains("Home | About"))
        assertFalse(md, md.contains("track()"))
    }

    @Test fun `too little text is not an article`() {
        assertNull(ReadLater.fromHtml("<html><body><p>Hi</p></body></html>", url))
    }

    @Test fun `youtube links are videos`() {
        val a = ReadLater.fromHtml("<html><head><title>A talk</title></head><body></body></html>", "https://www.youtube.com/watch?v=abc")!!
        assertTrue(a.video)
        assertEquals("A talk", a.title)
    }

    @Test fun `the Read widget counts unread articles and offers the newest`() {
        fun saved(file: String, read: Boolean, at: String, archived: Boolean = false) =
            SavedArticle(ReadRef(file, archived), file.removeSuffix(".md"), null, "", read, epoch(at), 100)
        val items = listOf(
            saved("Old.md", read = false, at = "2026-10-01T08:00"),
            saved("Newest.md", read = false, at = "2026-10-12T09:00"),
            saved("Done.md", read = true, at = "2026-10-12T10:00"),
            saved("Filed.md", read = false, at = "2026-10-12T11:00", archived = true)
        )
        val (count, next) = ReadNote.unread(items)
        assertEquals(2, count)
        assertEquals("Newest.md", next?.ref?.file)
        assertEquals(0 to null, ReadNote.unread(items.filter { it.read }))
    }
}
