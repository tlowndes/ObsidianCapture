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

    @Test fun `with nothing dated left, next step is the first undated task`() {
        val later = task("- [ ] #task Another someday thing")
        assertEquals(undated, Deck.nextStep(listOf(bill, undated, later)))
        assertEquals(later, Deck.nextStep(listOf(undated, later), skipped = setOf(undated.raw)))
        assertEquals(listOf(cv, undated), Deck.topTasks(all, 4).drop(2))
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
        assertEquals(HomeCard.values().size - 1, l.onCount)
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
        // The saved two keep their order; cards added since (v1.16's follow Jobs) slot in around them.
        assertEquals(listOf(HomeCard.JOBS, HomeCard.NEXT_STEP), l.order.filter { it == HomeCard.JOBS || it == HomeCard.NEXT_STEP })
        assertEquals(HomeCard.JOBS, l.order.first())
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

    @Test fun `Plan today and Shut down done are shared through the config`() {
        val v = FileVault(tmp.root)
        DeckConfig.save(v, DeckLayout().toggled(HomeCard.BILLS))
        assertEquals(DayDone(), DeckConfig.loadDayDone(v))
        DeckConfig.markPlanned(v, today)
        DeckConfig.markShutDown(v, today)
        val d = DeckConfig.loadDayDone(v)
        assertEquals(true, d.planned(today))
        assertEquals(true, d.shutDown(today))
        assertEquals(false, d.planned(today.plusDays(1)))
        // Other keys are kept.
        assertEquals(DeckLayout().toggled(HomeCard.BILLS), DeckConfig.load(v))
    }

    @Test fun `an older day never overwrites a later one`() {
        val json = DeckConfig.withDone(null, "plannedOn", today)
        assertEquals(today.toString(), DeckConfig.dayDone(DeckConfig.withDone(json, "plannedOn", today.minusDays(1))).plannedOn)
        assertEquals(DayDone(), DeckConfig.dayDone("not json"))
    }

    @Test fun `dragging a card puts it before the card it was dropped on`() {
        val l = DeckLayout(listOf(HomeCard.NEXT_STEP, HomeCard.TODAY, HomeCard.WEATHER, HomeCard.TOP_TASKS), hidden = setOf(HomeCard.TODAY))
        assertEquals(listOf(HomeCard.WEATHER, HomeCard.NEXT_STEP, HomeCard.TODAY, HomeCard.TOP_TASKS), l.placed(HomeCard.WEATHER, HomeCard.NEXT_STEP).order)
        assertEquals(setOf(HomeCard.TODAY), l.placed(HomeCard.WEATHER, HomeCard.NEXT_STEP).hidden)
        assertEquals(l, l.placed(HomeCard.WEATHER, HomeCard.WEATHER))
    }

    @Test fun `dropping at the end of a column goes after its last card`() {
        val l = DeckLayout(listOf(HomeCard.NEXT_STEP, HomeCard.TODAY, HomeCard.WEATHER, HomeCard.TOP_TASKS))
        assertEquals(listOf(HomeCard.TODAY, HomeCard.NEXT_STEP, HomeCard.WEATHER, HomeCard.TOP_TASKS),
            l.placed(HomeCard.NEXT_STEP, null, listOf(HomeCard.TODAY)).order)
        assertEquals(listOf(HomeCard.TODAY, HomeCard.WEATHER, HomeCard.TOP_TASKS, HomeCard.NEXT_STEP),
            l.placed(HomeCard.NEXT_STEP, null, listOf(HomeCard.TOP_TASKS, HomeCard.WEATHER)).order)
        assertEquals(l, l.placed(HomeCard.NEXT_STEP, null, emptyList()))
    }

    @Test fun `phone sections put the agenda after the weather and they move apart`() {
        val l = DeckLayout()
        assertEquals(listOf(DeckSection.WEATHER, DeckSection.AGENDA, DeckSection.TOP_TASKS, DeckSection.MORE), DeckSection.inOrder(l))
        val agendaFirst = DeckSection.moved(l, DeckSection.AGENDA, DeckSection.WEATHER)
        assertEquals(listOf(DeckSection.AGENDA, DeckSection.WEATHER, DeckSection.TOP_TASKS, DeckSection.MORE), DeckSection.inOrder(agendaFirst))
        assertEquals(true, agendaFirst.order.indexOf(HomeCard.TODAY) < agendaFirst.order.indexOf(HomeCard.WEATHER))
        val weatherLast = DeckSection.moved(l, DeckSection.WEATHER, null)
        assertEquals(DeckSection.AGENDA, DeckSection.inOrder(weatherLast).first())
        assertEquals(false, weatherLast.order.indexOf(HomeCard.WEATHER) < weatherLast.order.indexOf(HomeCard.TODAY))
        assertEquals(HomeCard.values().toSet(), weatherLast.order.toSet())
        assertEquals(HomeCard.values().size, weatherLast.order.size)
        val more = DeckSection.moved(l, DeckSection.MORE, DeckSection.WEATHER)
        assertEquals(listOf(DeckSection.MORE, DeckSection.WEATHER, DeckSection.AGENDA, DeckSection.TOP_TASKS), DeckSection.inOrder(more))
    }

    @Test fun `a saved order gains the calendar straight after the weather and drops nothing`() {
        val json = "{\"cards\":[\"NEXT_STEP\",\"WEATHER\",\"BILLS\",\"TOP_TASKS\"],\"hiddenCards\":[\"BILLS\"]}"
        val l = DeckConfig.layout(json)
        assertEquals(HomeCard.TODAY, l.order[l.order.indexOf(HomeCard.WEATHER) + 1])
        assertEquals(true, HomeCard.BILLS in l.hidden)
        assertEquals(HomeCard.values().size, l.order.distinct().size)
        assertEquals(true, l.order.containsAll(HomeCard.values().toList()))
    }

    @Test fun `a saved order that already placed the calendar keeps that place`() {
        val json = "{\"cards\":[\"TODAY\",\"NEXT_STEP\",\"WEATHER\"],\"hiddenCards\":[]}"
        val l = DeckConfig.layout(json)
        assertEquals(HomeCard.TODAY, l.order.first())
        assertEquals(HomeCard.values().size, l.order.size)
    }
}
