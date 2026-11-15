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
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

/** Stage 1: chores that decay, recipe save, snap anything and highlights on the slip. */
class StageOneTest {
    @get:Rule val tmp = TemporaryFolder()
    private val today = LocalDate.of(2026, 11, 15)

    // ---- Chores ----

    @Test fun `chore lines read and write back`() {
        val c = Chores.parseLine("- 🛁 Clean bathroom · every 7 · done 2026-11-03")!!
        assertEquals(Chore("Clean bathroom", "🛁", 7, LocalDate.of(2026, 11, 3)), c)
        assertEquals("- 🛁 Clean bathroom · every 7 · done 2026-11-03", Chores.line(c))
        assertEquals(Chore("Water plants", "", 7, null), Chores.parseLine("- Water plants"))
        assertNull(Chores.parseLine("- [ ] a task"))
        assertNull(Chores.parseLine("Some prose"))
    }

    @Test fun `bars fill with age and the fullest goes first`() {
        val bath = Chore("Bath", every = 7, done = today.minusDays(12))
        val plants = Chore("Plants", every = 4, done = today.minusDays(1))
        val never = Chore("Windows", every = 30)
        assertEquals(12.0 / 7, bath.fill(today), 0.001)
        assertEquals(1.0, never.fill(today), 0.001)
        assertEquals(listOf(bath, never, plants), Chores.sorted(listOf(plants, never, bath), today))
        assertEquals(listOf(bath, never), Chores.due(listOf(plants, never, bath), today))
        assertEquals("12 days · every 7", bath.ageLabel(today))
        assertEquals("not yet · every 30", never.ageLabel(today))
    }

    @Test fun `chores note is made, marked done, added to and removed from`() {
        val v = FileVault(tmp.root)
        val all = Chores.load(v, today)
        assertEquals(Chores.STARTER.size, all.size)
        assertTrue(all.all { it.done == today })
        val bath = all.first()
        Chores.markDone(v, bath, today.plusDays(3))
        assertEquals(today.plusDays(3), Chores.load(v).first { it.name == bath.name }.done)
        Chores.put(v, null, Chore("Bins out", "🗑", 7))
        assertTrue(Chores.load(v).any { it.name == "Bins out" })
        Chores.remove(v, Chore("Bins out"))
        assertFalse(Chores.load(v).any { it.name == "Bins out" })
        assertTrue(File(tmp.root, Chores.PATH).readText().contains("## Chores"))
    }

    // ---- Recipes ----

    private val page = """
        <html><head><title>Gnocchi | Food</title>
        <script type="application/ld+json">
        {"@context":"https://schema.org","@graph":[{"@type":"WebPage","name":"x"},
          {"@type":["Recipe"],"name":"Crispy gnocchi traybake","totalTime":"PT30M","recipeYield":["2","2 servings"],
           "recipeIngredient":["500g gnocchi","250g cherry tomatoes","1 red onion"],
           "recipeInstructions":[{"@type":"HowToSection","name":"Method","itemListElement":[
              {"@type":"HowToStep","text":"Heat the oven to 220C."},{"@type":"HowToStep","text":"Roast for 25 mins."}]}]}]}
        </script></head><body><p>My life story…</p></body></html>
    """.trimIndent()

    @Test fun `a schema org recipe is read from the page`() {
        val r = Recipes.fromHtml(page, "https://www.bbcgoodfood.com/recipes/gnocchi")!!
        assertEquals("Crispy gnocchi traybake", r.title)
        assertEquals(30, r.minutes)
        assertEquals("2", r.serves)
        assertEquals(3, r.ingredients.size)
        assertEquals(listOf("Heat the oven to 220C.", "Roast for 25 mins."), r.steps)
        assertEquals("bbcgoodfood.com", r.host)
        assertNull(Recipes.fromHtml("<html><body>No recipe</body></html>", null))
        assertEquals(90, Recipes.isoMinutes("PT1H30M"))
    }

    @Test fun `recipe note round trips and ticks`() {
        val r = Recipes.fromHtml(page, "https://example.com/r")!!
        val md = Recipes.markdown(r, today)
        assertTrue(Recipes.isRecipe(md))
        val n = Recipes.parse("Recipes/Gnocchi.md", md)
        assertEquals("Crispy gnocchi traybake", n.title)
        assertEquals(3, n.ingredients.size)
        assertTrue(n.ingredients.none { it.have })
        assertEquals(2, n.steps.size)
        val ticked = Recipes.parse("x", Recipes.withHave(md, "1 red onion", true))
        assertTrue(ticked.ingredients.first { it.text == "1 red onion" }.have)
    }

    @Test fun `shopping list skips what is already on it`() {
        val (text, n) = Recipes.withShopping("# Shopping list\n\n- [ ] Milk\n- [x] Bread\n", listOf("milk", "Bread", "Onion"))
        assertEquals(2, n)
        assertTrue(text.endsWith("- [ ] Bread\n- [ ] Onion\n"))
        val v = FileVault(tmp.root)
        assertEquals(2, Recipes.addToShopping(v, listOf("Eggs", "Flour")))
        assertEquals(0, Recipes.addToShopping(v, listOf("eggs")))
    }

    @Test fun `recipes go in the resources folder when there is one`() {
        val v = FileVault(tmp.root)
        assertEquals("Recipes", Recipes.folder(v))
        File(tmp.root, "05 - Resources").mkdirs()
        assertEquals("05 - Resources/Recipes", Recipes.folder(v))
        val path = Recipes.save(v, Recipes.fromHtml(page, null)!!, today)
        assertEquals(1, Recipes.list(v).size)
        assertTrue(path.startsWith("05 - Resources/Recipes/"))
    }

    @Test fun `claude recipe json is read`() {
        val r = Recipes.fromClaude("```json\n{\"title\":\"Soup\",\"minutes\":20,\"serves\":\"4\",\"ingredients\":[\"1 leek\"],\"steps\":[\"Chop\"]}\n```", "u")!!
        assertEquals("Soup", r.title)
        assertEquals(20, r.minutes)
        assertNull(Recipes.fromClaude("{\"title\":\"\"}", null))
    }

    // ---- Snap ----

    @Test fun `snap reply becomes items`() {
        val r = Snap.parse("""
            {"title":"Looks like a bill","what":"Council Tax letter","steps":false,"items":[
              {"kind":"bill","text":"Council Tax","amount":"142.00","due":"2026-12-01","repeat":"monthly","keep":true},
              {"kind":"task","text":"Pay Council Tax £142","due":"2026-11-30","time":null,"keep":true},
              {"kind":"task","text":"Set up Direct Debit","keep":false}]}
        """.trimIndent())!!
        assertEquals("Looks like a bill", r.title)
        assertEquals(3, r.items.size)
        val bill = r.items[0]
        assertEquals(SnapKind.BILL, bill.kind)
        assertEquals(BigDecimal("142.00"), bill.amount)
        assertEquals(BillRepeat.MONTHLY, bill.repeat)
        assertEquals(2, r.kept.size)
        assertNull(Snap.parse("sorry, no"))
    }

    @Test fun `snap saves bills, timed tasks, steps and notes`() {
        val v = FileVault(tmp.root)
        val w = VaultWriter(v)
        val r = SnapResult("Found", "Booking", listOf(
            SnapItem(SnapKind.BILL, "Council Tax", due = LocalDate.of(2026, 12, 1), amount = BigDecimal("142"), repeat = BillRepeat.MONTHLY),
            SnapItem(SnapKind.TASK, "Dentist", due = LocalDate.of(2026, 12, 3), time = LocalTime.of(9, 40)),
            SnapItem(SnapKind.NOTE, "Tesco £23.40"),
            SnapItem(SnapKind.TASK, "Skip me", keep = false),
        ))
        assertEquals(3, Snap.save(w, r, steps = false, parent = "", today = today))
        val tasks = w.listQuickTasks()
        assertTrue(tasks.any { it.raw.contains("Council Tax £142") && it.raw.contains("#bill 🔁 every month") })
        assertTrue(tasks.any { it.raw.contains("Dentist") && it.raw.contains("09:40") })
        assertFalse(tasks.any { it.raw.contains("Skip me") })
        assertTrue(File(tmp.root, "${VaultWriter.INBOX}/${VaultWriter.INBOX_NOTE}").readText().contains("- Tesco £23.40"))

        val list = SnapResult("2 tasks", "Garage list", listOf(SnapItem(SnapKind.TASK, "Paint to tip"), SnapItem(SnapKind.TASK, "Buy brackets")))
        Snap.save(w, list, steps = true, parent = "Sort the garage", today = today)
        val note = File(tmp.root, "${VaultWriter.INBOX}/${VaultWriter.TASK_FILE}").readText()
        assertTrue(note.contains("Sort the garage"))
        assertTrue(Regex("\\n\\s+- \\[ ] Paint to tip").containsMatchIn(note))
    }

    // ---- Highlights on the slip ----

    @Test fun `long passages are cut at a word`() {
        val s = Highlights.short("word ".repeat(60).trim(), 30)
        assertTrue(s.endsWith("…"))
        assertTrue(s.length <= 31)
        assertEquals("short", Highlights.short("short"))
        assertNotNull(SlipOptions().quote)
    }
}
