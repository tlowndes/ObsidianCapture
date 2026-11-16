package uk.tomlowndes.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDateTime

/** 1.38: Email and Shopping list live in 99 - Meta; Check now reaches either box script. */
class MetaPathsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun put(rel: String, text: String) = File(tmp.root, rel).apply { parentFile.mkdirs(); writeText(text) }

    @Test fun `old inbox shopping list moves to meta`() {
        put(Recipes.OLD_SHOPPING, "# Shopping list\n\n- [ ] Milk\n")
        val v = FileVault(tmp.root)
        assertEquals(listOf("Milk"), Recipes.shoppingOpen(Recipes.shopping(v)!!))
        assertNull(v.entry(Recipes.OLD_SHOPPING))
        assertEquals(1, Recipes.addToShopping(v, listOf("Eggs")))
        assertEquals(listOf("Milk", "Eggs"), Recipes.shoppingOpen(v.read(Recipes.SHOPPING)!!))
    }

    @Test fun `new shopping list is made in meta`() {
        val v = FileVault(tmp.root)
        Recipes.addToShopping(v, listOf("Bread"))
        assertNotNull(v.entry("99 - Meta/Shopping list.md"))
    }

    @Test fun `digest is read from meta then the old inbox path`() {
        put(MailDigest.OLD_PATH, "---\ntype: email-digest\nchecked: 2026-11-16T09:00\n---\n")
        val v = FileVault(tmp.root)
        assertEquals(LocalDateTime.of(2026, 11, 16, 9, 0), MailDigest.load(v)!!.checked)
        put(MailDigest.PATH, "---\ntype: email-digest\nchecked: 2026-11-16T09:30\n---\n")
        assertEquals(LocalDateTime.of(2026, 11, 16, 9, 30), MailDigest.load(v)!!.checked)
    }

    @Test fun `inbox json from the other box script counts as checked`() {
        put(MailDigest.OLD_PATH, "---\nAutoNoteMover: disable\n---\n\n## Needs you\n- Hannah: interview\n")
        put(MailDigest.INBOX_JSON, """{"version":1,"checked":"2026-11-16T09:40:00","status":"ok","counts":{"needs_you":1,"handled":2,"skipped":5},
            "items":[{"id":"a1","from":"Hannah","subject":"Re: Designer","date":"2026-11-16T09:12:00","kind":"job","needs_you":true,
            "summary":"Wants a call Tuesday","detail":"Asks for a call on Tuesday at 2pm.","task":{"text":"Reply to Hannah","due":"2026-11-17","tag":"#work"},
            "job":{"company":"Monzo","role":"Designer","status":"Interview"}},{"id":"s","kind":"skip"}]}""")
        val d = MailDigest.load(FileVault(tmp.root))!!
        assertEquals(LocalDateTime.of(2026, 11, 16, 9, 40), d.checked)
        val m = d.items.single()
        assertEquals(MailKind.JOB, m.kind)
        assertEquals("Wants a call Tuesday", m.gist)
        assertEquals("Reply to Hannah", m.task)
        assertEquals("Interview", m.jobStatus)
        assertFalse(MailDigest.paused(d, LocalDateTime.of(2026, 11, 16, 10, 0)))
    }

    @Test fun `check now also drops the file the other box watches`() {
        val v = FileVault(tmp.root)
        MailDigest.checkNow(v, MailDigest.BoxSettings(), LocalDateTime.of(2026, 11, 16, 10, 0))
        assertEquals(LocalDateTime.of(2026, 11, 16, 10, 0), MailDigest.settings(v).checkNow)
        assertNull(v.entry(MailDigest.CHECK_NOW))  // no Inbox folder: box/mailbox only
        File(tmp.root, "99 - Meta/Inbox").mkdirs()
        MailDigest.checkNow(v, MailDigest.BoxSettings())
        assertTrue(File(tmp.root, MailDigest.CHECK_NOW).exists())
    }
}
