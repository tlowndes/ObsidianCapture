package uk.tomlowndes.capture

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress

/** The Jarvis voice client against a local server, so nothing leaves the machine. */
class JarvisTest {
    private class Hit(val method: String, val path: String, val auth: String?, val type: String?, val body: ByteArray)

    private fun server(handle: (Hit) -> Pair<Int, ByteArray>): Pair<HttpServer, String> {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/") { ex ->
            val hit = Hit(ex.requestMethod, ex.requestURI.path, ex.requestHeaders.getFirst("Authorization"),
                ex.requestHeaders.getFirst("Content-Type"), ex.requestBody.readBytes())
            val (code, out) = handle(hit)
            ex.sendResponseHeaders(code, if (out.isEmpty()) -1 else out.size.toLong())
            if (out.isNotEmpty()) ex.responseBody.use { it.write(out) }
            ex.close()
        }
        s.start()
        return s to "http://127.0.0.1:${s.address.port}"
    }

    @Test fun `the address is tidied into an origin`() {
        assertEquals("https://box.ts.net:8443", Jarvis.base("box.ts.net:8443"))
        assertEquals("https://box.ts.net:8443", Jarvis.base(" https://box.ts.net:8443/voice "))
        assertEquals("http://127.0.0.1:9", Jarvis.base("http://127.0.0.1:9/"))
        assertEquals("https://box.ts.net/audio/a1.ogg", Jarvis.resolve("box.ts.net", "/audio/a1.ogg"))
        assertEquals("https://cdn/x.ogg", Jarvis.resolve("box.ts.net", "https://cdn/x.ogg"))
        assertTrue(Jarvis.isDemo(" Demo "))
    }

    @Test fun `a reply is read with or without audio and done jobs`() {
        val r = Jarvis.parseReply("""{"transcript":"hi","reply":"hello","audio_url":"/audio/1.ogg","done":[{"id":"j1","title":"Research finished","detail":"Salaries"},{"detail":"no title"}]}""")
        assertEquals(Jarvis.Reply("hi", "hello", "/audio/1.ogg", listOf(Jarvis.Done("j1", "Research finished", "Salaries"))), r)
        assertNull(Jarvis.parseReply("""{"transcript":"a","reply":"b","audio_url":null}""").audioUrl)
    }

    @Test fun `send posts the audio and worker with the token, then the audio is fetched`() {
        val hits = mutableListOf<Hit>()
        val (s, url) = server { h ->
            hits += h
            when (h.path) {
                "/voice" -> 200 to """{"transcript":"add milk","reply":"Added.","audio_url":"/audio/abc.ogg"}""".toByteArray()
                "/audio/abc.ogg" -> 200 to byteArrayOf(79, 103, 103, 83)
                else -> 404 to ByteArray(0)
            }
        }
        val rec = File.createTempFile("rec", ".ogg").apply { writeBytes("OPUSDATA".toByteArray()) }
        val out = File.createTempFile("reply", ".ogg")
        try {
            val r = Jarvis.send("$url/voice", "tok-1", rec, "obsidian")
            assertEquals("add milk", r.transcript)
            assertEquals("Added.", r.reply)
            Jarvis.fetchAudio(url, "tok-1", r.audioUrl!!, out)
            assertEquals("OggS", out.readText())

            val post = hits[0]
            assertEquals("POST", post.method)
            assertEquals("Bearer tok-1", post.auth)
            assertTrue(post.type!!.startsWith("multipart/form-data; boundary="))
            val body = String(post.body)
            assertTrue(body.contains("name=\"worker\"\r\n\r\nobsidian\r\n"))
            assertTrue(body.contains("name=\"file\"; filename=\"${rec.name}\"\r\nContent-Type: audio/ogg\r\n\r\nOPUSDATA\r\n"))
            assertEquals("Bearer tok-1", hits[1].auth)
        } finally {
            s.stop(0); rec.delete(); out.delete()
        }
    }

    @Test fun `a refused token is an HttpError and ping says so`() {
        val (s, url) = server { 401 to "bad token".toByteArray() }
        val rec = File.createTempFile("rec", ".ogg")
        try {
            try {
                Jarvis.send(url, "nope", rec, "jarvis"); fail("should throw")
            } catch (e: Jarvis.HttpError) {
                assertEquals(401, e.code)
            }
            val p = Jarvis.ping(url, "nope")
            assertFalse(p.ok)
            assertEquals("Token refused (401)", p.message)
        } finally {
            s.stop(0); rec.delete()
        }
    }

    @Test fun `done is empty when the server doesn't offer it`() {
        val (s, url) = server { h -> if (h.path == "/done") 404 to ByteArray(0) else 405 to ByteArray(0) }
        try {
            assertEquals(emptyList<Jarvis.Done>(), Jarvis.done(url, "t"))
            assertTrue(Jarvis.ping(url, "t").ok) // a 405 on GET /voice still means the box answered
        } finally {
            s.stop(0)
        }
        val (s2, url2) = server { """[{"id":"r1","title":"Research finished","detail":"UK salaries"}]""".let { 200 to it.toByteArray() } }
        try {
            assertEquals(listOf(Jarvis.Done("r1", "Research finished", "UK salaries")), Jarvis.done(url2, "t"))
        } finally {
            s2.stop(0)
        }
    }

    @Test fun `an unreachable box pings as not ok and demo needs no server`() {
        assertFalse(Jarvis.ping("http://127.0.0.1:1", "t").ok)
        assertTrue(Jarvis.ping("demo", "").ok)
        assertTrue(Jarvis.demoReply("research", 3.25).reply.contains("Research got your 3.3 second"))
        assertEquals("jarvis", Jarvis.worker("unknown").id)
    }
}
