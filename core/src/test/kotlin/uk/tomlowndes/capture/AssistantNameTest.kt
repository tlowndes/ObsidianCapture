package uk.tomlowndes.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class AssistantNameTest {
    @Test fun `blank and junk fall back to Tower`() {
        assertEquals("Tower", AssistantName.clean(null))
        assertEquals("Tower", AssistantName.clean("   "))
        assertEquals("Tower", AssistantName.clean("***"))
    }

    @Test fun `a typed name is trimmed and capped`() {
        assertEquals("Tower", AssistantName.clean("  Tower  "))
        assertEquals("My Tower", AssistantName.clean("  My   Tower "))
        assertEquals("A".repeat(24), AssistantName.clean("A".repeat(40)))
    }
}
