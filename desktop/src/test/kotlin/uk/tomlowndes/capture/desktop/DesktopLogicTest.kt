package uk.tomlowndes.capture.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.time.LocalDate
import java.util.prefs.Preferences
import javax.imageio.ImageIO

/** Pure desktop logic: date labels, the hotkey choice, image shrink, settings, and the Obsidian link. */
class DesktopLogicTest {
    private val today = LocalDate.now()

    @Test fun `due labels use today, tomorrow, or a short date`() {
        assertEquals("today", dueLabel(today))
        assertEquals("tomorrow", dueLabel(today.plusDays(1)))
        assertEquals("7 Oct", dueLabel(LocalDate.of(2020, 10, 7)))
        assertEquals("Today", dayChip(today))
        assertEquals("Tomorrow", dayChip(today.plusDays(1)))
        assertEquals("7 Oct 2020", dayChip(LocalDate.of(2020, 10, 7)))
        val sameYear = if (today.monthValue == 1 && today.dayOfMonth < 3) LocalDate.of(today.year, 6, 15) else LocalDate.of(today.year, 1, 2)
        assertTrue(dayChip(sameYear).contains(sameYear.dayOfMonth.toString()))
        assertFalse(dayChip(sameYear).contains(today.year.toString()))
    }

    @Test fun `an unknown shortcut falls back to the first choice`() {
        assertEquals("Ctrl + Shift + Space", Hotkey.choice("nope").label)
        assertEquals("Ctrl + Alt + N", Hotkey.choice("Ctrl + Alt + N").label)
        assertEquals(5, Hotkey.CHOICES.size)
    }

    @Test fun `only a whole http link counts as a bare url`() {
        assertTrue(isBareUrl(" https://example.com/a "))
        assertTrue(isBareUrl("http://example.com"))
        assertFalse(isBareUrl("https://example.com/a more"))
        assertFalse(isBareUrl("see https://example.com"))
        assertFalse(isBareUrl(""))
    }

    @Test fun `obsidian links encode the vault and drop the md suffix`() {
        assertEquals(
            "obsidian://open?vault=My%20Vault&file=Projects%2FHouse%20move",
            obsidianOpenUrl("My Vault", "Projects/House move.md")
        )
    }

    @Test fun `images shrink only when a side is over the limit, and jpeg bytes round-trip`() {
        val wide = BufferedImage(100, 40, BufferedImage.TYPE_INT_RGB)
        val small = Images.shrink(wide, 50)
        assertEquals(50, small.width)
        assertEquals(20, small.height)
        assertTrue(Images.shrink(wide, 200) === wide)
        val jpeg = Images.toJpeg(wide)
        assertEquals(0xFF.toByte(), jpeg[0])
        assertEquals(0xD8.toByte(), jpeg[1])
        val back = ImageIO.read(ByteArrayInputStream(jpeg))
        assertTrue(back.width <= 1600 && back.height <= 1600)
    }

    @Test fun `settings trim folder paths and clear an empty draft`() {
        val node = Preferences.userRoot().node("uk/tomlowndes/capture-test/${System.nanoTime()}")
        try {
            val p = DesktopPrefs(node)
            assertEquals("03 - Projects", p.ideasDir)
            assertEquals("Ctrl + Shift + Space", p.hotkey)
            p.ideasDir = "/03 - Projects/"
            assertEquals("03 - Projects", p.ideasDir)
            p.vault = java.io.File("/tmp/vault")
            assertEquals("/tmp/vault", p.vault!!.path)
            p.setDraft("capture", "hello")
            assertEquals("hello", p.draft("capture"))
            p.setDraft("capture", "")
            assertEquals("", p.draft("capture"))
        } finally {
            node.removeNode()
        }
    }
}
