package uk.tomlowndes.capture

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class PhoneBrainTest {
    private fun t(mine: Boolean, s: String) = PhoneBrain.Turn(mine, s)

    @Test fun historyStartsAndEndsWithTomAndAlternates() {
        val h = PhoneBrain.history(listOf(t(false, "hi"), t(true, "a"), t(true, "b"), t(false, "c"), t(true, "d"), t(false, "")))
        assertEquals(listOf(t(true, "a\n\nb"), t(false, "c"), t(true, "d")), h)
    }

    @Test fun historyDropsATrailingAnswerAndKeepsTheLastFew() {
        val turns = (1..40).map { t(it % 2 == 1, "m$it") } + t(false, "late")
        val h = PhoneBrain.history(turns, keep = 10)
        assertTrue(h.first().mine)
        assertTrue(h.last().mine)
        assertTrue(h.size <= 10)
    }

    @Test fun taskInputBecomesASortedTask() {
        val task = PhoneBrain.parseTask(JSONObject().put("text", "Order stickers").put("tag", "#Personal").put("due", "2026-11-11"))
        assertEquals(SortedTask("Order stickers", "#personal", LocalDate.of(2026, 11, 11)), task)
        assertEquals(SortedTask("Ring Mum", null, null), PhoneBrain.parseTask(JSONObject().put("text", " Ring Mum ").put("tag", "").put("due", "soon")))
        assertNull(PhoneBrain.parseTask(JSONObject().put("text", " ")))
    }

    @Test fun handoffNeedsAKnownWorker() {
        assertEquals(PhoneBrain.Handoff("oddjobs", "Fix the printer"), PhoneBrain.parseHandoff(JSONObject().put("worker", "Odd-jobs").put("request", "Fix the printer")))
        assertNull(PhoneBrain.parseHandoff(JSONObject().put("worker", "jarvis").put("request", "x")))
        assertNull(PhoneBrain.parseHandoff(JSONObject().put("worker", "research").put("request", "")))
    }

    @Test fun spendAddsUpAndResetsEachMonth() {
        val nov = YearMonth.of(2026, 11)
        val usd = PhoneBrain.costUsd(PhoneBrain.CLEVER, 10_000, 2_000) // 2p in + 2p out = $0.04
        assertEquals(0.04, usd, 1e-9)
        val s = PhoneBrain.Spend("", 0, 0).add(usd, nov).add(usd, nov)
        assertEquals(2, s.answers)
        assertEquals("6p · 2 answers", s.label(nov))
        assertEquals("Nothing yet", s.label(YearMonth.of(2026, 12)))
        assertEquals(1, s.add(usd, YearMonth.of(2026, 12)).answers)
        assertEquals("under 1p · 1 answer", PhoneBrain.Spend("", 0, 0).add(PhoneBrain.costUsd(PhoneBrain.FAST, 2000, 200), nov).label(nov))
    }

    @Test fun noteSlipWithoutClaude() {
        val md = "---\ntags: [x]\n---\n# Printer paper\n\nSome words.\n- [ ] Buy 53 mm rolls\n- [x] Done thing\n- Lasts about **200** slips\n- See [[Phomemo M02 Pro|the M02]]\n"
        val s = PhoneBrain.fromNote("Paper", md)
        assertEquals("Printer paper", s.title)
        assertEquals(listOf("Buy 53 mm rolls"), s.todos)
        assertEquals(listOf("Lasts about 200 slips", "See the M02"), s.points)
        assertEquals(listOf("Just a line."), PhoneBrain.fromNote("N", "Just a line.").points)
        assertEquals("N", PhoneBrain.fromNote("N", "Just a line.").title)
    }

    @Test fun summaryJsonRoundTrips() {
        val s = PhoneBrain.Summary("This week", listOf("3 app jobs left"), listOf("Order stickers"))
        assertEquals(s, PhoneBrain.parseSummary(PhoneBrain.summaryToJson(s)))
        assertEquals("Summary", PhoneBrain.parseSummary("{\"points\":[\" \"]}").title)
    }

    @Test fun `a long message is worth sorting and a short one is not`() {
        assertTrue(PhoneBrain.worthSorting("one two three four five six seven eight nine ten ".repeat(4)))
        assertTrue(!PhoneBrain.worthSorting("add milk"))
    }

    @Test fun `sorter json is a save or an answer, not both calls`() {
        val ramble = """{"ramble": true, "reply": "Two bits to keep.", "tasks": [{"text": "Book MOT", "tag": "#home", "due": "2026-11-20"}], "ideas": [], "note": null}"""
        val parsed = PhoneBrain.parseDirect(ramble)!!
        assertEquals("Two bits to keep.", parsed.reply)
        assertEquals("Book MOT", parsed.sorted!!.tasks.single().text)
        val question = """{"ramble": false, "reply": "Thursday."}"""
        val answer = PhoneBrain.parseDirect(question)!!
        assertEquals("Thursday.", answer.reply)
        assertEquals(null, answer.sorted)
        assertEquals(null, PhoneBrain.parseDirect("just a sentence"))
    }

    @Test fun `the same message cannot be in flight twice`() {
        val key = PhoneBrain.flightKey("jarvis", " hello ")
        assertTrue(PhoneBrain.beginFlight(key))
        assertTrue(!PhoneBrain.beginFlight(PhoneBrain.flightKey("jarvis", "hello")))
        PhoneBrain.endFlight(key)
        assertTrue(PhoneBrain.beginFlight(key))
        PhoneBrain.endFlight(key)
    }

    @Test fun systemPromptHasTheDateAndTags() {
        val p = PhoneBrain.system(LocalDate.of(2026, 11, 10), listOf("#work", "#personal"))
        assertTrue(p.contains("Tuesday 10 November 2026"))
        assertTrue(p.contains("#work #personal"))
    }
}
