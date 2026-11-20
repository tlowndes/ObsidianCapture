package uk.tomlowndes.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class KeyFindingsTest {
    private val date = LocalDate.of(2026, 11, 12)
    private val knife = KeyFindings.Link("Benchmade", "https://amzn.to/knife")

    /** A ReadItLater note: frontmatter, tags, a linked title, a summary callout, then the article. */
    private val withSummary = """
        ---
        status: unread
        saved: 2026-01-01
        ---

        [[ReadItLater]] [[Article]]

        # [What's in your bag, Adam Savage](https://example.com/bag)

        > [!summary] Summary
        > A short existing summary of the bag.

        The body starts here and keeps going so there is a real article underneath the title.

        ![knife](https://cdn.example.com/knife.jpg)

        The [Benchmade](https://amzn.to/knife) lives in the front pocket. See https://amzn.to/light too.

        As an Amazon Associate I earn from qualifying purchases.

        ## Related
        [Another bag](https://example.com/other)
    """.trimIndent() + "\n"

    private val noSummary = withSummary.replace(
        "> [!summary] Summary\n> A short existing summary of the bag.\n\n",
        ""
    )

    private fun stamp(note: String, eol: String = "\n") = note.replace(
        "---${eol}status: unread${eol}saved: 2026-01-01${eol}---${eol}",
        "---${eol}status: unread${eol}saved: 2026-01-01${eol}key_findings: 2026-11-12${eol}---${eol}"
    )

    @Test fun `short findings are inserted after the summary and the rest of the note is unchanged`() {
        val findings = KeyFindings.Findings(KeyFindings.Length.SHORT, listOf("A small knife is enough", "Keep the light handy"), "Lay the bag out once")
        val callout = KeyFindings.callout(findings, "\n")
        val marked = "> A short existing summary of the bag.\n"
        val base = stamp(withSummary)
        val at = base.indexOf(marked) + marked.length
        assertEquals(base.substring(0, at) + callout + base.substring(at), KeyFindings.apply(withSummary, findings, date))
        assertTrue(callout.contains("> - A small knife is enough"))
        assertFalse(callout.lines().any { it == ">" })
    }

    @Test fun `with no summary the callout goes after the title`() {
        val findings = KeyFindings.Findings(KeyFindings.Length.LONG, listOf("A small knife covers most days.", "The light belongs where the hand already goes."), null)
        val callout = KeyFindings.callout(findings, "\n")
        val marked = "# [What's in your bag, Adam Savage](https://example.com/bag)\n"
        val base = stamp(noSummary)
        val at = base.indexOf(marked) + marked.length
        val out = KeyFindings.apply(noSummary, findings, date)
        assertEquals(base.substring(0, at) + callout + base.substring(at), out)
        assertTrue(out.contains("> A small knife covers most days.\n>\n> The light belongs where the hand already goes."))
        assertEquals(1, Regex("\\[!key-findings]").findAll(out).count())
    }

    @Test fun `an existing key findings callout is replaced`() {
        val old = "> [!key-findings] Key findings\n> - An old point\n"
        val note = withSummary.replace("The body starts here", old + "The body starts here")
        val findings = KeyFindings.Findings(KeyFindings.Length.SHORT, listOf("A small knife is enough"), null)
        val out = KeyFindings.apply(note, findings, date)
        assertEquals(stamp(note).replace(old, KeyFindings.callout(findings, "\n")), out)
        assertEquals(1, Regex("\\[!key-findings]").findAll(out).count())
        assertFalse(out.contains("An old point"))
    }

    @Test fun `a long callout replaces a short one in the same place`() {
        val short = KeyFindings.Findings(KeyFindings.Length.SHORT, listOf("An old point"), null)
        val once = KeyFindings.apply(withSummary, short, date)
        val long = KeyFindings.Findings(KeyFindings.Length.LONG, listOf("First paragraph.", "Second paragraph."), "Try it")
        val twice = KeyFindings.apply(once, long, date)
        assertEquals(once.replace(KeyFindings.callout(short, "\n"), KeyFindings.callout(long, "\n")), twice)
        assertEquals(1, Regex("\\[!key-findings]").findAll(twice).count())
    }

    @Test fun `line endings and a note without frontmatter stay as they were`() {
        val findings = KeyFindings.Findings(KeyFindings.Length.SHORT, listOf("A small knife is enough"), null)
        val crlf = withSummary.replace("\n", "\r\n")
        val out = KeyFindings.apply(crlf, findings, date)
        assertEquals(crlf, out.replace(KeyFindings.callout(findings, "\r\n"), "").replace("key_findings: 2026-11-12\r\n", ""))

        val bare = "# [Title](https://example.com)\n\nThe body of this saved article is long enough to stand on its own.\n"
        val written = KeyFindings.apply(bare, findings, date)
        assertFalse(written.contains("key_findings"))
        assertTrue(written.startsWith("# [Title](https://example.com)\n" + KeyFindings.callout(findings, "\n")))
        assertTrue(written.endsWith("The body of this saved article is long enough to stand on its own.\n"))
    }

    @Test fun `short is a few one line bullets and long is a few paragraphs`() {
        val extra = (1..6).joinToString("\n") { "- Point $it" }
        val short = KeyFindings.parse(extra, KeyFindings.Length.SHORT, emptyList())!!
        assertEquals(KeyFindings.Length.SHORT, short.length)
        assertEquals(listOf("Point 1", "Point 2", "Point 3", "Point 4", "Point 5"), short.parts)
        assertTrue(KeyFindings.callout(short, "\n").contains("> - Point 1"))

        val long = KeyFindings.parse(
            "One.\n\nTwo.\n\nThree.\n\nFour.\n\nFive.",
            KeyFindings.Length.LONG, emptyList()
        )!!
        assertEquals(KeyFindings.Length.LONG, long.length)
        assertEquals(listOf("One.", "Two.", "Three.", "Four."), long.parts)
        assertFalse(KeyFindings.callout(long, "\n").contains("> - "))
    }

    @Test fun `long findings get a blank callout line between paragraphs and before worth trying`() {
        val messy = """
            > [!key-findings] Key findings
            > First paragraph about the knife.
            >
            >
            > Second paragraph.
            >
            > Worth trying: Lay the bag out once
        """.trimIndent()
        val findings = KeyFindings.parse(messy, KeyFindings.Length.LONG, emptyList())!!
        assertEquals(listOf("First paragraph about the knife.", "Second paragraph."), findings.parts)
        assertEquals("Lay the bag out once", findings.worthTrying)
        assertEquals(
            """
            > [!key-findings] Key findings
            > First paragraph about the knife.
            >
            > Second paragraph.
            >
            > Worth trying: Lay the bag out once
            """.trimIndent() + "\n",
            KeyFindings.callout(findings, "\n")
        )

        val flat = KeyFindings.parse("First paragraph.\nSecond paragraph.\nWorth trying: Try it", KeyFindings.Length.LONG, emptyList())!!
        assertEquals(
            "> First paragraph.\n>\n> Second paragraph.\n>\n> Worth trying: Try it\n",
            KeyFindings.callout(flat, "\n").substringAfter("> [!key-findings] Key findings\n")
        )

        val noTry = KeyFindings.callout(KeyFindings.Findings(KeyFindings.Length.LONG, listOf("First.", "Second."), null), "\n")
        assertEquals("> First.\n>\n> Second.\n", noTry.substringAfter("> [!key-findings] Key findings\n"))
        assertFalse(noTry.endsWith(">\n"))
    }

    @Test fun `urls that are not in the article are dropped`() {
        val allowed = KeyFindings.links(withSummary)
        assertTrue(allowed.any { it == knife })
        assertTrue(allowed.any { it.url == "https://amzn.to/light" })
        assertTrue(allowed.any { it.url == "https://example.com/other" && it.text == "Another bag" })
        assertTrue(allowed.none { it.url.contains("knife.jpg") || it.url.contains("cdn.example") })

        val reply = """
            - Carry a [Benchmade](https://amzn.to/knife)
            - Skip this [Fake](https://evil.example/buy)
            - See https://not-in-the-article.example/page for more
            Worth trying: Try the [Benchmade](https://amzn.to/knife) not https://invented.example
        """.trimIndent()
        val short = KeyFindings.parse(reply, KeyFindings.Length.SHORT, allowed)!!
        assertEquals("Carry a [Benchmade](https://amzn.to/knife)", short.parts[0])
        assertEquals("Skip this Fake", short.parts[1])
        assertEquals("See for more", short.parts[2])
        assertEquals("Try the [Benchmade](https://amzn.to/knife) not", short.worthTrying)
        assertFalse(short.parts.joinToString().contains("evil.example"))
        assertFalse(short.worthTrying!!.contains("invented"))

        val long = KeyFindings.parse(
            "Carry a [Benchmade](https://amzn.to/knife) and ignore https://evil.example/buy.\n\nPack a [[Leatherman]] and a [[Missing]].",
            KeyFindings.Length.LONG, allowed, listOf("Leatherman")
        )!!
        assertEquals("Carry a [Benchmade](https://amzn.to/knife) and ignore.", long.parts[0])
        assertEquals("Pack a [[Leatherman]] and a Missing.", long.parts[1])
        assertFalse(long.parts.joinToString().contains("evil.example"))
    }

    @Test fun `the article sent to the model has no images or leftover urls`() {
        val prepared = KeyFindings.prepare(withSummary)
        assertEquals("What's in your bag, Adam Savage", prepared.title)
        assertFalse(prepared.article.contains("cdn.example"))
        assertFalse(prepared.article.contains("http"))
        assertFalse(prepared.article.contains("Amazon Associate"))
        assertFalse(prepared.article.contains("Another bag"))
        assertTrue(prepared.article.contains("Benchmade"))
        assertTrue(prepared.article.contains("front pocket"))
        val longPrompt = KeyFindings.prompt(prepared, KeyFindings.Length.LONG, listOf("Leatherman"))
        val shortPrompt = KeyFindings.prompt(prepared, KeyFindings.Length.SHORT)
        assertTrue(longPrompt.contains("2 to 4"))
        assertTrue(shortPrompt.contains("3 to 5"))
        assertTrue(longPrompt.contains("https://amzn.to/knife"))
        assertFalse(longPrompt.contains("knife.jpg"))
        assertTrue(longPrompt.contains("[[Leatherman]]"))
    }

    @Test fun `a named note is found and a blank request uses the newest article`() {
        assertEquals("Adam Savage", KeyFindings.query("key findings for Adam Savage"))
        assertEquals("", KeyFindings.query("Please summarise this article"))
        assertNull(KeyFindings.query("summarise my week"))
        val notes = listOf(VaultNote("EDC/Other.md", 1), VaultNote("EDC/Adam Savage.md", 9), VaultNote("Inbox/Today.md", 20))
        assertEquals("EDC/Adam Savage.md", KeyFindings.find(notes, "adam savage") { "[[Article]]" })
        assertEquals("EDC/Adam Savage.md", KeyFindings.find(notes, "") { path -> if (path.startsWith("EDC/Adam")) "[[ReadItLater]]" else "hello" })
        assertEquals(listOf("Other"), KeyFindings.related(notes, "EDC/Adam Savage.md"))
        assertEquals(KeyFindings.Length.LONG, KeyFindings.Length.fromStored(null))
        assertEquals(KeyFindings.Length.SHORT, KeyFindings.Length.fromStored("short"))
    }
}
