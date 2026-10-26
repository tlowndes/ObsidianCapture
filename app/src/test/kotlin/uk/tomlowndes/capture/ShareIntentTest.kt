package uk.tomlowndes.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Share-sheet routing: a link goes to Read It Later, a longer share goes to the Capture box. */
class ShareIntentTest {
    @Test fun `a bare link is a page`() {
        assertTrue(ShareIntent.isPage("https://example.com/story"))
        assertEquals("https://example.com/story", ShareIntent.urlOf("  https://example.com/story  "))
        assertNull(ShareIntent.pageTitle("https://example.com/story", null))
    }

    @Test fun `a short caption stays a page and a long one is a brain dump`() {
        val url = "https://example.com/a"
        val short = "$url " + "x".repeat(59)
        val long = "$url " + "x".repeat(60)
        assertTrue(ShareIntent.isPage(short))
        assertFalse(ShareIntent.isPage(long))
        assertEquals(url, ShareIntent.urlOf("see $url please"))
        assertEquals("A title", ShareIntent.pageTitle(url, "A title"))
        assertEquals("", ShareIntent.pageTitle(url, ""))
        assertEquals("see  please", ShareIntent.pageTitle("see $url please", null))
    }

    @Test fun `text with no link is a brain dump, and an empty share is a page`() {
        assertFalse(ShareIntent.isPage("Remember to call the bins company tomorrow"))
        assertTrue(ShareIntent.isPage("   "))
        assertEquals("nothing", ShareIntent.urlOf("nothing"))
    }

    @Test fun `a brain dump is appended under the capture draft`() {
        assertEquals("shared note", ShareIntent.captureDraft("  ", " shared note "))
        assertEquals("already\nshared note", ShareIntent.captureDraft("already", "shared note"))
    }
}
