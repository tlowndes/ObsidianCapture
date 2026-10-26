package uk.tomlowndes.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDate

class DeckTest {
    @get:Rule val tmp = TemporaryFolder()

    private val today = LocalDate.of(2026, 10, 25)
    private fun task(line: String) = TaskNote.parse(line)!!

    private val overdue = task("- [ ] #task Boiler service booked? 📅 2026-10-22")
    private val wol = task("- [ ] #task Setup WOL #learning ⏱ 25m 📅 2026-10-25")
    private val reply = task("- [ ] #task Reply to Sam ⏱ 5m 📅 2026-10-25")
    private val cv = task("- [ ] #task Tailor CV #jobs 📅 2026-10-27")
    private val bill = task("- [ ] Council tax £128 #bill #task 📅 2026-10-20")
    private val undated = task("- [ ] #task Someday thing")
    private val all = listOf(cv, undated, wol, bill, reply, overdue)

    @Test fun `next step is the earliest due, overdue first, never a bill`() {
        assertEquals(overdue, Deck.nextStep(all))
    }

    @Test fun `Later skips a task until it is cleared`() {
        assertEquals(wol, Deck.nextStep(all, skipped = setOf(overdue.raw)))
    }

    @Test fun `top tasks come after the next step`() {
        assertEquals(listOf(wol, reply), Deck.topTasks(all, 2))
        assertEquals(listOf(wol, reply, cv), Deck.topTasks(all, 3))
    }

    @Test fun `low energy keeps tasks of 15 minutes or less`() {
        assertEquals(reply, Deck.nextStep(all, maxMinutes = 15))
        assertEquals(emptyList<TaskItem>(), Deck.topTasks(all, 2, maxMinutes = 15))
    }

    @Test fun `minutes are read from the timer field`() {
        assertEquals(25, Deck.minutes(wol))
        assertNull(Deck.minutes(cv))
    }

    @Test fun `tasks due counts today and overdue, bills included`() {
        assertEquals(4, Deck.dueCount(all, today))
    }

    @Test fun `low energy moves Stuck to the top`() {
        val l = DeckLayout()
        assertEquals(HomeCard.STUCK, l.visible(lowEnergy = true).first())
        assertEquals(HomeCard.NEXT_STEP, l.visible(lowEnergy = false).first())
    }

    @Test fun `cards reorder and switch off`() {
        val l = DeckLayout().moved(9, 0).toggled(HomeCard.WEATHER)
        assertEquals(HomeCard.JOBS, l.order.first())
        assertEquals(9, l.onCount)
        assertEquals(false, HomeCard.WEATHER in l.visible(false))
    }

    @Test fun `layout round-trips through the config and keeps other keys`() {
        val l = DeckLayout().moved(0, 3).toggled(HomeCard.JOBS)
        val json = DeckConfig.withLayout("{\"reminders\":{\"morning\":\"08:00\"}}", l)
        assertEquals(l, DeckConfig.layout(json))
        assertEquals("08:00", org.json.JSONObject(json).getJSONObject("reminders").getString("morning"))
    }

    @Test fun `a missing or broken config gives the default layout`() {
        assertEquals(DeckLayout(), DeckConfig.layout(null))
        assertEquals(DeckLayout(), DeckConfig.layout("not json"))
    }

    @Test fun `a card added later is appended to a saved order`() {
        val json = "{\"cards\":[\"JOBS\",\"NEXT_STEP\"],\"hiddenCards\":[]}"
        val l = DeckConfig.layout(json)
        assertEquals(listOf(HomeCard.JOBS, HomeCard.NEXT_STEP), l.order.take(2))
        assertEquals(HomeCard.values().size, l.order.size)
    }

    @Test fun `Habits added later lands under Top tasks in a saved order`() {
        val saved = DeckLayout().order.filter { it != HomeCard.HABITS }.reversed().map { "\"${it.name}\"" }
        val l = DeckConfig.layout("{\"cards\":[${saved.joinToString(",")}],\"hiddenCards\":[]}")
        assertEquals(HomeCard.HABITS, l.order[l.order.indexOf(HomeCard.TOP_TASKS) + 1])
        assertEquals(HomeCard.values().size, l.order.size)
    }

    @Test fun `layout is saved into the vault, creating the config`() {
        val v = FileVault(tmp.root)
        val l = DeckLayout().toggled(HomeCard.BILLS)
        DeckConfig.save(v, l)
        assertEquals(l, DeckConfig.load(v))
    }
}
