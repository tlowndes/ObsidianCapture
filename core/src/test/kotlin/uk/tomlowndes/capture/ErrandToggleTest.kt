package uk.tomlowndes.capture

import org.junit.Assert.assertEquals
import org.junit.Test

/** Place row on New task and Edit task (task-place-picker-v1). */
class ErrandToggleTest {
    @Test fun `adds the tag at the end`() {
        assertEquals("Shelf brackets @b&q", Errands.toggle("Shelf brackets ", "@b&q"))
        assertEquals("@tesco", Errands.toggle("", "tesco"))
    }

    @Test fun `takes it out wherever it is, any case`() {
        assertEquals("Shelf brackets ×4", Errands.toggle("Shelf brackets @B&Q ×4", "@b&q"))
        assertEquals("Milk", Errands.toggle("Milk @tesco", "@tesco"))
    }

    @Test fun `leaves longer tags and emails alone`() {
        assertEquals("Milk @tescoextra @tesco", Errands.toggle("Milk @tescoextra", "@tesco"))
        assertEquals("Email bob@tesco.com @tesco", Errands.toggle("Email bob@tesco.com", "@tesco"))
    }
}
