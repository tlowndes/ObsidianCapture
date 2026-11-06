package uk.tomlowndes.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TagsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `finds tags lower case, leaves out #task, numbers, links and headings`() {
        assertEquals(
            listOf("#work", "#jobs", "#work/client"),
            Tags.inText("#task Send CV #Work #jobs #2026 #work/client https://x.com/a#frag")
        )
        assertEquals(emptyList<String>(), Tags.inText("# Heading and C# and &#39;"))
    }

    @Test fun `toggle adds at the end and removes any case`() {
        assertEquals("Book MOT #personal", Tags.toggle("Book MOT", "personal"))
        assertEquals("Book MOT", Tags.toggle("Book MOT #Personal", "#personal"))
        assertEquals("Ring Rosie about #jobs today", Tags.toggle("Ring Rosie #work about #jobs today", "#work"))
        assertEquals("#garden", Tags.toggle("", "Garden"))
    }

    @Test fun `add does not repeat a tag`() {
        assertEquals("Prune #Garden", Tags.add("Prune #Garden", "#garden"))
        assertEquals("Prune #garden #work", Tags.withTags("Prune #garden", listOf("#garden", "#work")))
    }

    @Test fun `norm makes a tag Obsidian accepts`() {
        assertEquals("#garden-party", Tags.norm(" Garden Party! "))
        assertEquals("#work/client", Tags.norm("#work/client/"))
        assertNull(Tags.norm("#"))
        assertNull(Tags.norm("2026"))
        assertNull(Tags.norm("task"))
    }

    @Test fun `strip leaves the words`() {
        assertEquals("Send portfolio link", Tags.strip("Send portfolio link #work #jobs"))
    }

    @Test fun `row puts Work and Personal first, then pinned, then extras`() {
        assertEquals(listOf("#work", "#personal", "#jobs", "#garden"), Tags.row(listOf("#jobs", "#work"), listOf("#garden", "#jobs")))
    }

    @Test fun `kind and label`() {
        assertEquals(TagKind.WORK, Tags.kind("#work"))
        assertEquals(TagKind.PERSONAL, Tags.kind("#Personal"))
        assertEquals(TagKind.OTHER, Tags.kind("#jobs"))
        assertEquals("Work", Tags.label("#work"))
        assertEquals("#jobs", Tags.label("#jobs"))
    }

    @Test fun `suggest puts starts-with first`() {
        val known = Tags.counts(listOf("a #garden", "b #garden", "c #yoga", "d #games", "e #gallery"))
        assertEquals(listOf("#garden" to 2, "#gallery" to 1, "#games" to 1, "#yoga" to 1), known)
        assertEquals(listOf("#garden", "#gallery", "#games", "#yoga"), Tags.suggest("#ga", known).map { it.first })
    }

    @Test fun `typing finds the word after a hash at the cursor`() {
        val text = "Prune the tree #ga"
        val (range, part) = Tags.typing(text, text.length)!!
        assertEquals(15..17, range)
        assertEquals("ga", part)
        assertNull(Tags.typing("Prune the tree", 14))
        assertNull(Tags.typing("C#ga", 4))
        val (out, cursor) = Tags.complete(text, range, "#garden")
        assertEquals("Prune the tree #garden ", out)
        assertEquals(out.length, cursor)
    }

    @Test fun `pinned tags round-trip and keep other keys`() {
        val json = Tags.withPinned("""{"cards":["NEXT"]}""", listOf("#jobs", "Garden", "#work"))
        assertEquals(listOf("#jobs", "#garden"), Tags.pinned(json))
        assertTrue(json.contains("\"cards\""))
        assertEquals(emptyList<String>(), Tags.pinned(null))
        assertEquals(emptyList<String>(), Tags.pinned("not json"))
    }

    @Test fun `pinned tags save into the vault config`() {
        val v = FileVault(tmp.root)
        Tags.savePinned(v, listOf("#jobs"))
        assertEquals(listOf("#jobs"), Tags.loadPinned(v))
        assertFalse(Tags.loadPinned(v).contains("#work"))
    }

    @Test fun `a tagged task keeps its tags when edited`() {
        val raw = "- [ ] Reply to recruiter #work ⏱ 5m 📅 2026-11-07"
        val t = TaskNote.parse(raw)!!
        assertEquals(listOf("#work"), Tags.inText(t.text))
        val edited = TaskRules.editedLine(raw, Tags.toggle(t.text, "#jobs"), t.due, null, 5)
        assertEquals("- [ ] Reply to recruiter #work #jobs ⏱ 5m 📅 2026-11-07", edited)
    }

    @Test fun `read later note carries its tags`() {
        val note = ReadLater.note("https://a.com", "A", null, "2026-11-06", listOf("#work", "#design"))
        assertTrue(note.contains("[[ReadItLater]] [[Article]] #work #design\n"))
        assertFalse(ReadLater.note("https://a.com", "A", null, "2026-11-06").contains("#"))
    }

    @Test fun `filter counts use the Work and Personal sides`() {
        val ts = listOf(
            "- [ ] Send CV #work #jobs", "- [ ] Reply to recruiter #jobs", "- [ ] Book MOT #personal", "- [ ] Prune #garden"
        ).map { TaskNote.parse(it)!! }
        assertEquals(listOf("#work" to 2, "#personal" to 2, "#jobs" to 2, "#garden" to 1), Tags.filterCounts(ts))
        assertTrue(Tags.matches(ts[1], "#work"))
        assertFalse(Tags.matches(ts[1], "#personal"))
    }
}
