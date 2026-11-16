package uk.tomlowndes.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDate
import java.time.MonthDay

/** Stage 5: people and birthdays, errands by place, meal plan the week. */
class StageFiveTest {
    @get:Rule val tmp = TemporaryFolder()
    private val today = LocalDate.of(2026, 11, 15)

    @Test fun `people notes, birthdays and keep in touch`() {
        val v = FileVault(tmp.root)
        val mum = People.create(v, "Mum", birthday = "1958-11-21")
        People.create(v, "Jamie", every = 21)
        People.create(v, "Sam", birthday = "--03-02")
        var all = People.list(v)
        assertEquals(listOf("Jamie", "Mum", "Sam"), all.map { it.name })
        val m = People.find(all, "mum")!!
        assertEquals(MonthDay.of(11, 21), m.birthday); assertEquals(1958, m.born)
        People.addGift(v, m, "That garden kneeler", LocalDate.of(2026, 10, 3))
        People.addGift(v, m, "Afternoon tea voucher", today)
        People.addNote(v, m, "Knee op in January.")
        People.spoke(v, People.find(all, "Jamie")!!, today.minusDays(35))
        all = People.list(v)
        val mum2 = People.find(all, "Mum")!!
        assertEquals(listOf("That garden kneeler (said 3 Oct)", "Afternoon tea voucher (said 15 Nov)"), mum2.gifts)
        assertEquals(listOf("Knee op in January."), mum2.notes)
        val up = People.comingUp(all, today)
        assertEquals(listOf("Mum", "Jamie"), up.map { it.person.name })
        assertEquals("🎂 Mum · Sat 21 Nov · 6 days · 2 gift ideas", People.deckLine(up[0]))
        assertEquals("Last spoke 5 weeks ago · you said every 3 weeks", up[1].text)
        assertEquals(Pair("Mum", "kneeler"), People.giftIdea("Gift idea for Mum: kneeler"))
        assertNull(People.giftIdea("Buy milk"))
        val w = VaultWriter(v)
        assertEquals(listOf("Mum"), People.addGiftTasks(v, w, all, today))
        assertEquals(emptyList<String>(), People.addGiftTasks(v, w, People.list(v), today))
        assertTrue(w.listQuickTasks().any { it.text.startsWith("Get Mum a gift 🎂 (ideas: That garden kneeler or Afternoon tea voucher)") })
        assertTrue(v.read(mum)!!.contains("gift_task: 2026"))
    }

    @Test fun `errands group by place and nudge once a visit`() {
        val v = FileVault(tmp.root)
        val bq = Place("@b&q", "B&Q Kirkstall", 53.8123, -1.6012, 200)
        Errands.upsert(v, bq)
        Errands.upsert(v, Place("@tesco", "Tesco Headingley", 53.8200, -1.5770))
        assertEquals(listOf("@b&q", "@tesco"), Errands.places(v).map { it.tag })
        val tasks = listOf(TaskItem("- [ ] Shelf brackets ×4 @B&Q", "Shelf brackets ×4 @B&Q", null), TaskItem("- [ ] Sandpaper @b&q", "Sandpaper @b&q", null),
            TaskItem("- [ ] Old paint @tip", "Old paint @tip", null), TaskItem("- [ ] Email hello@example.com", "Email hello@example.com", null))
        val e = Errands.errands(tasks, listOf("Mozzarella", "Onion"), Errands.places(v))
        assertEquals(listOf("@b&q", "@tesco", "@tip"), e.map { it.tag })
        assertEquals("Shelf brackets ×4 · Sandpaper", Errands.summary(e[0]))
        assertEquals("📍 Near B&Q · 2 errands", Errands.title(e[0]))
        val here = Errands.at(Errands.places(v), 53.8125, -1.6010)
        assertEquals(listOf("@b&q"), here.map { it.tag })
        val (first, inside) = Errands.arrivals(here, emptySet(), e)
        assertEquals(1, first.size)
        assertEquals(0, Errands.arrivals(here, inside, e).first.size)
        val (_, left) = Errands.arrivals(emptyList(), inside, e)
        assertEquals(1, Errands.arrivals(here, left, e).first.size)
    }

    @Test fun `meal plan, shopping merge and tonight`() {
        val v = FileVault(tmp.root)
        val g = Recipes.save(v, Recipe("Crispy gnocchi traybake", minutes = 30, ingredients = listOf("1 onion, sliced", "500g gnocchi", "200g cherry tomatoes")), today)
        Recipes.save(v, Recipe("Chicken fajitas", minutes = 25, ingredients = listOf("1 onion", "2 peppers", "100g cherry tomatoes")), today)
        Recipes.setHave(v, g, "500g gnocchi", true)
        val mon = MealPlan.monday(today)
        var days = MealPlan.load(v, today)
        assertEquals(7, days.size); assertEquals(0, MealPlan.planned(days))
        days = MealPlan.set(days, mon, "Crispy gnocchi traybake")
        days = MealPlan.set(days, mon.plusDays(1), null, "Leftovers")
        days = MealPlan.set(days, mon.plusDays(2), "Chicken fajitas")
        MealPlan.save(v, days)
        assertTrue(v.read(MealPlan.path(v, today))!!.contains("- Mon 9 Nov: [[Crispy gnocchi traybake]]"))
        days = MealPlan.load(v, today)
        assertEquals("Leftovers", days[1].text); assertEquals("Chicken fajitas", days[2].recipe)
        days = MealPlan.swap(days, mon, mon.plusDays(1))
        assertEquals("Leftovers", days[0].text); assertEquals("Crispy gnocchi traybake", days[1].recipe)
        val shop = MealPlan.shopping(days, Recipes.list(v))
        assertEquals(listOf("2 onion", "200g cherry tomatoes + 100g cherry tomatoes", "2 peppers"), shop.map { it.text })
        assertEquals("Merged: 2 onion (crispy gnocchi traybake + chicken fajitas)", MealPlan.mergedNote(shop))
        assertEquals("Crispy gnocchi traybake", MealPlan.tonight(days, mon.plusDays(1))?.recipe)
    }
}
