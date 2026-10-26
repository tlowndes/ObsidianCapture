package uk.tomlowndes.capture

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate

/** [SortClient] against a local server. No call leaves the machine. */
class SortClientTest {
    private val today = LocalDate.of(2026, 10, 25)
    private val sorted = """{"tasks":[{"text":"Call bins","tag":"home","due":"2026-10-26"}],"ideas":["Monzo"],"note":"Maybe."}"""

    @Test fun `a sorted reply is parsed and the request uses the model from settings`() {
        val server = MockWebServer()
        server.enqueue(message(sorted))
        server.start()
        try {
            val transport = AnthropicSortTransport("test-key", server.url("/").toString(), maxRetries = 0)
            val result = SortClient.sort("test-key", "call the bins tomorrow", listOf("#home"), today, model = "claude-test-model", transport = transport)
            assertEquals(listOf(SortedTask("Call bins", "#home", LocalDate.of(2026, 10, 26))), result.tasks)
            assertEquals(listOf("Monzo"), result.ideas)
            assertEquals("Maybe.", result.note)
            val sent = server.takeRequest()
            val body = JSONObject(sent.body.readUtf8())
            assertEquals("claude-test-model", body.getString("model"))
            assertTrue(body.getJSONArray("messages").toString().contains("call the bins tomorrow"))
            assertTrue(body.toString().contains("\"tasks\""))
            assertEquals("test-key", sent.getHeader("x-api-key"))
        } finally {
            server.shutdown()
        }
    }

    @Test fun `a blank model falls back to the default`() {
        val server = MockWebServer()
        server.enqueue(message("""{"tasks":[],"ideas":[],"note":null}"""))
        server.start()
        try {
            val transport = AnthropicSortTransport("k", server.url("/").toString(), maxRetries = 0)
            val result = SortClient.sort("k", "hi", emptyList(), today, model = "  ", transport = transport)
            assertTrue(result.isEmpty)
            assertEquals(Sorting.MODEL, JSONObject(server.takeRequest().body.readUtf8()).getString("model"))
        } finally {
            server.shutdown()
        }
    }

    @Test fun `a retired model is a clear error, not a quiet failure`() {
        val server = MockWebServer()
        server.enqueue(error(404, "not_found_error", "model: claude-haiku-retired"))
        server.start()
        try {
            val transport = AnthropicSortTransport("k", server.url("/").toString(), maxRetries = 0)
            SortClient.sort("k", "hi", emptyList(), today, model = "claude-haiku-retired", transport = transport)
            fail("retired model should have been rejected")
        } catch (e: ModelRejectedException) {
            assertEquals("claude-haiku-retired", e.model)
            assertTrue(e.message!!.contains("retired"))
            assertTrue(e.message!!.contains("Settings"))
        } finally {
            server.shutdown()
        }
    }

    @Test fun `a 400 that names the model is a rejection and one that doesn't is left alone`() {
        val rejected = MockWebServer()
        rejected.enqueue(error(400, "invalid_request_error", "model: claude-old is not supported"))
        rejected.start()
        try {
            val transport = AnthropicSortTransport("k", rejected.url("/").toString(), maxRetries = 0)
            SortClient.sort("k", "hi", emptyList(), today, model = "claude-old", transport = transport)
            fail("should have been a model rejection")
        } catch (e: ModelRejectedException) {
            assertEquals("claude-old", e.model)
        } finally {
            rejected.shutdown()
        }

        val other = MockWebServer()
        other.enqueue(error(400, "invalid_request_error", "max_tokens: must be greater than 0"))
        other.start()
        try {
            val transport = AnthropicSortTransport("k", other.url("/").toString(), maxRetries = 0)
            SortClient.sort("k", "hi", emptyList(), today, transport = transport)
            fail("should have thrown the api error")
        } catch (e: Exception) {
            assertTrue(e !is ModelRejectedException)
            assertTrue(e !is OfflineException)
        } finally {
            other.shutdown()
        }
    }

    @Test fun `rate limit and a dropped connection stay offline, and a refusal is reported`() {
        val limited = MockWebServer()
        limited.enqueue(error(429, "rate_limit_error", "slow down"))
        limited.start()
        try {
            val transport = AnthropicSortTransport("k", limited.url("/").toString(), maxRetries = 0)
            SortClient.sort("k", "hi", emptyList(), today, transport = transport)
            fail("rate limit should be offline")
        } catch (e: OfflineException) {
            assertTrue(e.cause != null)
        } finally {
            limited.shutdown()
        }

        try {
            val transport = AnthropicSortTransport("k", "http://127.0.0.1:1/", maxRetries = 0)
            SortClient.sort("k", "hi", emptyList(), today, transport = transport)
            fail("connection failure should be offline")
        } catch (e: OfflineException) {
            assertTrue(e.cause != null)
        }

        val refused = MockWebServer()
        refused.enqueue(message("no", stop = "refusal"))
        refused.start()
        try {
            val transport = AnthropicSortTransport("k", refused.url("/").toString(), maxRetries = 0)
            SortClient.sort("k", "hi", emptyList(), today, transport = transport)
            fail("refusal should fail")
        } catch (e: IllegalStateException) {
            assertEquals("Claude couldn't sort this one", e.message)
        } finally {
            refused.shutdown()
        }
    }

    private fun message(text: String, stop: String = "end_turn") = MockResponse().setHeader("Content-Type", "application/json").setBody(
        """
        {"id":"msg_test","type":"message","role":"assistant","model":"claude-test",
         "stop_reason":"$stop","stop_sequence":null,
         "content":[{"type":"text","text":${jsonString(text)}}],
         "usage":{"input_tokens":1,"output_tokens":1}}
        """.trimIndent()
    )

    private fun error(status: Int, type: String, message: String) = MockResponse()
        .setResponseCode(status)
        .setHeader("Content-Type", "application/json")
        .setBody("""{"type":"error","error":{"type":"$type","message":${jsonString(message)}}}""")
}

/** Enough of a JSON string escape for the fixture bodies. */
private fun jsonString(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
