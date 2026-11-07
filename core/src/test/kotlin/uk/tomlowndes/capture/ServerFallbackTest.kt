package uk.tomlowndes.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerFallbackTest {
    private val min = 60_000L
    private val now = 1_000_000_000L
    private val serverText = "- [ ] Order dog ramp #task\n- [ ] Reply to Rhea #task\n"
    private val status = ServerFallback.Status("01 - Inbox/⚡ Quick Task.md", now - 21 * min, ServerFallback.sha1(serverText.toByteArray()))

    @Test fun `phone has an older copy for long enough - use the server`() {
        val v = ServerFallback.judge(status, "- [ ] Order dog ramp #task\n", now - 60 * min, now, staleMin = 5)
        assertTrue(v.useServer)
        assertEquals(21, v.behindMin)
    }

    @Test fun `same content - stay on the phone`() {
        val v = ServerFallback.judge(status, serverText, now - 60 * min, now, staleMin = 5)
        assertFalse(v.useServer)
        assertEquals(0, v.behindMin)
    }

    @Test fun `a change younger than the threshold gets time to sync`() {
        val fresh = status.copy(modified = now - 2 * min)
        assertFalse(ServerFallback.judge(fresh, "old", now - 60 * min, now, staleMin = 5).useServer)
    }

    @Test fun `the phone's copy is newer - the phone is ahead, not behind`() {
        assertFalse(ServerFallback.judge(status, "edited on the phone", now - 1 * min, now, staleMin = 5).useServer)
    }

    @Test fun `the phone doesn't have the note yet`() {
        assertTrue(ServerFallback.judge(status, null, null, now, staleMin = 5).useServer)
    }
}
