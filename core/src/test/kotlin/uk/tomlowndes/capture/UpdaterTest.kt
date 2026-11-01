package uk.tomlowndes.capture

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress

/** Version compare, release notes, and [Updater.latest] / [Updater.download] against a local server. */
class UpdaterTest {
    @Test fun `versions compare by number not text`() {
        assertTrue(Updater.isNewer("v1.8", "1.7"))
        assertTrue(Updater.isNewer("v1.10", "1.9"))
        assertTrue(Updater.isNewer("v2.0", "1.9.3"))
        assertTrue(Updater.isNewer("v1.7.1", "1.7"))
        assertFalse(Updater.isNewer("v1.7", "1.7"))
        assertFalse(Updater.isNewer("v1.7", "1.7.0"))
        assertFalse(Updater.isNewer("v1.6", "1.7"))
        assertFalse(Updater.isNewer("habits-test", "1.7"))
    }

    @Test fun `highlights drop the author and link from generated notes`() {
        val body = """
            ## What's Changed
            * Built-in updater by @tlowndes in https://github.com/tlowndes/ObsidianCapture/pull/27
            * Habit tweaks by @tlowndes in https://github.com/tlowndes/ObsidianCapture/pull/26
            - Plain bullet
            * Fourth
            
            **Full Changelog**: https://github.com/x/y/compare/v1.7...v1.8
        """.trimIndent()
        assertEquals(listOf("Built-in updater", "Habit tweaks", "Plain bullet"), Updater.highlights(body))
    }

    @Test fun `latest reads the tag, notes and assets, and download follows the redirect without the token`() {
        val bytes = ByteArray(300_000) { (it % 251).toByte() }
        val seen = mutableListOf<Pair<String, String?>>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val base = "http://127.0.0.1:${server.address.port}"
        server.createContext("/") { ex ->
            val path = ex.requestURI.path
            seen += path to ex.requestHeaders.getFirst("Authorization")
            when {
                path.endsWith("/releases/latest") -> {
                    val json = """{"tag_name":"v1.8","html_url":"https://x/r","body":"* New thing by @t in https://x/1",
                        "assets":[{"id":11,"name":"VaultCapture-v1.8.apk","size":${bytes.size}},{"id":12,"name":"VaultCapture-v1.8.msi","size":5}]}"""
                    val b = json.toByteArray(); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) }
                }
                path.endsWith("/assets/11") -> {
                    ex.responseHeaders.add("Location", "$base/storage/apk?sig=1"); ex.sendResponseHeaders(302, -1); ex.close()
                }
                path == "/storage/apk" -> { ex.sendResponseHeaders(200, bytes.size.toLong()); ex.responseBody.use { it.write(bytes) } }
                else -> { ex.sendResponseHeaders(404, -1); ex.close() }
            }
        }
        server.start()
        try {
            val r = Updater.latest("tok", base)!!
            assertEquals("1.8", r.version)
            assertEquals(listOf("New thing"), r.notes)
            assertEquals("VaultCapture-v1.8.msi", r.asset(".msi")!!.name)
            val dest = File.createTempFile("upd", ".apk").also { it.delete() }
            var last = 0L
            Updater.download(r.asset(".apk")!!, "tok", dest, base) { done, _ -> last = done }
            assertTrue(bytes.contentEquals(dest.readBytes()))
            assertEquals(bytes.size.toLong(), last)
            assertEquals("Bearer tok", seen.first { it.first.endsWith("/assets/11") }.second)
            assertNull(seen.first { it.first == "/storage/apk" }.second)
            dest.delete()
        } finally {
            server.stop(0)
        }
    }

    @Test fun `a non-version latest release is ignored`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val b = """{"tag_name":"habits-test","assets":[]}""".toByteArray()
            ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) }
        }
        server.start()
        try { assertNull(Updater.latest("tok", "http://127.0.0.1:${server.address.port}")) } finally { server.stop(0) }
    }
}
