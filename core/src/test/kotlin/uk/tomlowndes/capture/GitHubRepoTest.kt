package uk.tomlowndes.capture

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetSocketAddress

/** GitHub link parsing, and [GitHubRepo.fetch] against a local server so nothing calls GitHub. */
class GitHubRepoTest {
    @Test fun `the first github link is the owner and repo`() {
        assertEquals("acme" to "widget", GitHubRepo.repoOf("See https://github.com/acme/widget/pulls for it"))
        assertEquals("acme" to "widget", GitHubRepo.repoOf("git@nope (https://github.com/acme/widget.git)"))
        assertEquals("my.user" to "a.b", GitHubRepo.repoOf("github.com/my.user/a.b"))
        assertNull(GitHubRepo.repoOf("https://gitlab.com/acme/widget"))
        assertNull(GitHubRepo.repoOf("github.com/only-an-owner"))
    }

    @Test fun `fetch reads the push, the open pulls and the latest release`() {
        val seen = mutableListOf<Pair<String, String>>()
        val server = server { path, token ->
            seen += path to token
            when {
                path.endsWith("/pulls") -> """[{"number":1},{"number":2}]"""
                path.endsWith("/releases/latest") -> """{"tag_name":"v1.2"}"""
                else -> """{"pushed_at":"2026-10-25T09:00:00Z"}"""
            }
        }
        try {
            val a = GitHubRepo.fetch("acme", "widget", "tok-123", server.second)
            assertEquals(GitHubRepo.Activity("2026-10-25", 2, "v1.2"), a)
            assertTruePaths(seen.map { it.first }, "/repos/acme/widget", "/repos/acme/widget/pulls", "/repos/acme/widget/releases/latest")
            assertEquals(setOf("tok-123"), seen.map { it.second }.toSet())
        } finally {
            server.first.stop(0)
        }
    }

    @Test fun `a missing release is left blank and a failed repo lookup throws the status`() {
        val server = server { path, _ ->
            if (path.endsWith("/releases/latest")) error("404")
            if (path.contains("/pulls")) "[]" else """{"pushed_at":""}"""
        }
        try {
            assertEquals(GitHubRepo.Activity(null, 0, null), GitHubRepo.fetch("acme", "widget", "t", server.second))
        } finally {
            server.first.stop(0)
        }

        val down = server { _, _ -> error("500") }
        try {
            GitHubRepo.fetch("acme", "widget", "t", down.second)
            fail("fetch should have failed")
        } catch (e: IllegalStateException) {
            assertEquals("GitHub returned 500", e.message)
        } finally {
            down.first.stop(0)
        }
    }

    private fun assertTruePaths(got: List<String>, vararg ends: String) {
        assertEquals(ends.size, got.size)
        ends.forEachIndexed { i, end -> assertEquals(true, got[i].substringBefore('?').endsWith(end)) }
    }

    /** Returns the server and the API origin [GitHubRepo.fetch] should use. A handler that throws "404" or "500" sends that status. */
    private fun server(handler: (path: String, token: String) -> String): Pair<HttpServer, String> {
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/") { ex ->
            val path = ex.requestURI.path
            val token = ex.requestHeaders.getFirst("Authorization")?.removePrefix("Bearer ")?.trim().orEmpty()
            val accept = ex.requestHeaders.getFirst("Accept")
            val version = ex.requestHeaders.getFirst("X-GitHub-Api-Version")
            if (accept != "application/vnd.github+json" || version != "2022-11-28") {
                ex.sendResponseHeaders(400, -1); ex.close(); return@createContext
            }
            val body = try {
                handler(path, token)
            } catch (e: IllegalStateException) {
                val code = e.message?.toIntOrNull() ?: 500
                ex.sendResponseHeaders(code, -1); ex.close(); return@createContext
            }
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        http.start()
        return http to "http://127.0.0.1:${http.address.port}"
    }
}
