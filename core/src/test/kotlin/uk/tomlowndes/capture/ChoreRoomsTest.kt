package uk.tomlowndes.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ChoreRoomsTest {
    private val day = LocalDate.of(2026, 11, 17)
    private val note = """
        # Chores

        ## Chores
        - 🌱 Water plants · every 4 · done 2026-11-15

        ## 🛁 Bathroom · reset 2026-11-15
        - Clean the loo · every 7 · done 2026-11-17 · ✓
        - Mop the floor · every 7 · done 2026-11-10

        ## 🍳 Kitchen
        - Mop the floor · every 3 · done 2026-11-16
    """.trimIndent() + "\n"

    @Test fun `rooms read from headings`() {
        val rooms = Chores.rooms(note)
        assertEquals(listOf(Chores.HOUSE, "Bathroom", "Kitchen"), rooms.map { it.name })
        assertEquals("🛁", rooms[1].emoji)
        assertEquals(LocalDate.of(2026, 11, 15), rooms[1].reset)
        assertEquals(1, rooms[1].ticked)
        assertEquals(4, Chores.parse(note).size) // the Deck still sees every chore
    }

    @Test fun `ticking only touches the job in that room`() {
        val k = Chores.rooms(note)[2].chores[0]
        val out = Chores.editedIn(note, "Kitchen", k.name, k.copy(done = day, ticked = true))
        assertTrue(out.contains("- Mop the floor · every 3 · done 2026-11-17 · ✓"))
        assertTrue(out.contains("- Mop the floor · every 7 · done 2026-11-10\n"))
    }

    @Test fun `reset unticks the room and stamps the heading`() {
        val out = Chores.resetIn(note, "Bathroom", day)
        assertTrue(out.contains("## 🛁 Bathroom · reset 2026-11-17"))
        assertEquals(0, Chores.rooms(out)[1].ticked)
        assertEquals(LocalDate.of(2026, 11, 17), Chores.rooms(out)[1].chores[0].done)
    }

    @Test fun `add, edit and remove a room`() {
        val added = Chores.withRoom(note, ChoreRoom("Utility room", "🧺", chores = listOf(Chore("Sort the recycling"))))
        assertEquals("Utility room", Chores.rooms(added).last().name)
        val edited = Chores.withRoom(added, ChoreRoom("Bathroom", "🛁", chores = listOf(Chore("Clean the loo", every = 5), Chore("Change towels", every = 4))), "Bathroom")
        val bath = Chores.rooms(edited).first { it.name == "Bathroom" }
        assertEquals(listOf("Clean the loo", "Change towels"), bath.chores.map { it.name })
        assertEquals(LocalDate.of(2026, 11, 17), bath.chores[0].done)
        assertEquals(5, bath.chores[0].every)
        assertEquals(LocalDate.of(2026, 11, 15), bath.reset)
        val gone = Chores.withoutRoom(edited, "Kitchen")
        assertEquals(listOf(Chores.HOUSE, "Bathroom", "Utility room"), Chores.rooms(gone).map { it.name })
    }

    @Test fun `adding to a new room makes its heading`() {
        val out = Chores.editedIn(note, "Garden", null, Chore("Mow the lawn", "", 14))
        assertEquals("Mow the lawn", Chores.rooms(out).first { it.name == "Garden" }.chores.single().name)
    }
}
