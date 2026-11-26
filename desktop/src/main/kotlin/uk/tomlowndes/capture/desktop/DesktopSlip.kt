package uk.tomlowndes.capture.desktop

import uk.tomlowndes.capture.BillsDoc
import uk.tomlowndes.capture.EscPos
import uk.tomlowndes.capture.ListDoc
import uk.tomlowndes.capture.SlipCanvas
import uk.tomlowndes.capture.SlipCap
import uk.tomlowndes.capture.SlipContent
import uk.tomlowndes.capture.SlipFace
import uk.tomlowndes.capture.SlipFont
import uk.tomlowndes.capture.SlipLayout
import uk.tomlowndes.capture.Summary
import uk.tomlowndes.capture.WeekContent
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.RenderingHints
import java.awt.font.TextAttribute
import java.awt.geom.Area
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDateTime

/**
 * The Daily Slip and the other till-roll slips drawn with Java2D. The layout is core's [SlipLayout], shared with the
 * phone; this draws it at the printer's 576 dots across (48 mm at 300 dpi), and [job] turns it into the same ESC/POS
 * raster the phone sends.
 */
object DesktopSlip {
    const val WIDTH = SlipLayout.WIDTH
    private const val MAX_HEIGHT = SlipLayout.MAX_HEIGHT
    /** Text size Large (printables-v1): about 25% bigger text and tick boxes on every slip. Set before drawing. */
    @Volatile var large = false
    private val LOOK = SlipLayout.Look(dayTitle = 104f)

    fun draw(s: SlipContent, owner: String, now: LocalDateTime = LocalDateTime.now()): BufferedImage =
        page { SlipLayout.daily(it, LOOK, large, s, owner, now) }

    /** The Week slip (slip-week-v2): the week's days one under another, with a tear line between them. */
    fun drawWeek(w: WeekContent, now: LocalDateTime = LocalDateTime.now()): BufferedImage = page { SlipLayout.week(it, LOOK, large, w, now) }

    /** A list (shopping list slip) or shared text (Print clipboard), printables-v1. */
    fun drawList(d: ListDoc, now: LocalDateTime = LocalDateTime.now()): BufferedImage = page { SlipLayout.list(it, LOOK, large, d, now) }

    /** The Bills slip (printables-v1): left to pay, then the month's bills to pay and paid. */
    fun drawBills(b: BillsDoc, now: LocalDateTime = LocalDateTime.now()): BufferedImage = page { SlipLayout.bills(it, LOOK, large, b, now) }

    fun drawSummary(d: Summary.Doc, now: LocalDateTime = LocalDateTime.now()): BufferedImage = page { SlipLayout.summary(it, LOOK, large, d, now) }

    private fun page(draw: (SlipCanvas) -> Int): BufferedImage {
        val tall = BufferedImage(WIDTH, MAX_HEIGHT, BufferedImage.TYPE_INT_RGB)
        val g = tall.createGraphics()
        g.color = Color.WHITE; g.fillRect(0, 0, WIDTH, MAX_HEIGHT)
        g.color = Color.BLACK
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        val h = draw(AwtSlipCanvas(g))
        g.dispose()
        return tall.getSubimage(0, 0, WIDTH, h)
    }

    /** The ESC/POS job for [img]. */
    fun job(img: BufferedImage): ByteArray {
        val grey = IntArray(img.width * img.height)
        for (yy in 0 until img.height) for (xx in 0 until img.width) {
            val v = img.getRGB(xx, yy)
            grey[yy * img.width + xx] = ((v shr 16 and 255) * 299 + (v shr 8 and 255) * 587 + (v and 255) * 114) / 1000
        }
        return EscPos.job(EscPos.bits(grey, img.width, img.height), (img.width + 7) / 8, img.height)
    }
}

private val fonts by lazy { GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toSet() }

/** Java2D as core's [SlipCanvas]: Bahnschrift (or Arial Narrow) headings, Consolas body, Georgia quotes. */
private class AwtSlipCanvas(val g: Graphics2D) : SlipCanvas {
    private fun font(f: SlipFont): Font {
        val base = when (f.face) {
            SlipFace.HEAD -> Font(listOf("Bahnschrift", "Arial Narrow", "Segoe UI").firstOrNull { it in fonts } ?: Font.SANS_SERIF, Font.BOLD, 1)
            SlipFace.MONO -> Font(listOf("Consolas", "Cascadia Mono", "Courier New").firstOrNull { it in fonts } ?: Font.MONOSPACED, if (f.bold) Font.BOLD else Font.PLAIN, 1)
            SlipFace.SERIF -> Font(if ("Georgia" in fonts) "Georgia" else Font.SERIF, Font.ITALIC, 1)
        }
        var out = base.deriveFont(f.size)
        if (f.italic && f.face == SlipFace.MONO) out = out.deriveFont(Font.ITALIC)
        if (f.strike) out = out.deriveFont(mapOf(TextAttribute.STRIKETHROUGH to TextAttribute.STRIKETHROUGH_ON))
        return out
    }

    private fun lines(s: String, f: Font, w: Float): List<String> {
        val fm = g.getFontMetrics(f)
        val out = mutableListOf<String>()
        s.split("\n").forEach { para ->
            var line = ""
            para.split(" ").forEach { word ->
                val t = if (line.isEmpty()) word else "$line $word"
                if (fm.stringWidth(t) <= w || line.isEmpty()) line = t else { out += line; line = word }
            }
            out += line
        }
        return out
    }

    override fun block(s: String, f: SlipFont, x: Float, top: Float, w: Float, center: Boolean, spacing: Float): Float {
        val ft = font(f)
        g.font = ft
        val fm = g.fontMetrics
        val lh = fm.height * spacing
        var yy = top
        lines(s, ft, w).forEach { l ->
            val lx = if (center) x + (w - fm.stringWidth(l)) / 2 else x
            g.drawString(l, lx, yy + fm.ascent)
            yy += lh
        }
        return yy - top
    }

    override fun say(s: String, f: SlipFont, x: Float, base: Float, align: SlipCanvas.Align, white: Boolean) {
        g.font = font(f)
        val w = g.fontMetrics.stringWidth(s)
        if (white) g.color = Color.WHITE
        g.drawString(s, when (align) { SlipCanvas.Align.CENTER -> x - w / 2f; SlipCanvas.Align.RIGHT -> x - w; else -> x }, base)
        if (white) g.color = Color.BLACK
    }

    override fun width(s: String, f: SlipFont) = g.getFontMetrics(font(f)).stringWidth(s).toFloat()

    override fun fill(x1: Float, y1: Float, x2: Float, y2: Float) = g.fill(Rectangle2D.Float(x1, y1, x2 - x1, y2 - y1))

    override fun box(x1: Float, y1: Float, x2: Float, y2: Float, w: Float) {
        g.stroke = BasicStroke(w); g.draw(Rectangle2D.Float(x1, y1, x2 - x1, y2 - y1))
    }

    override fun rule(x1: Float, y1: Float, x2: Float, y2: Float, w: Float, dash: FloatArray?, cap: SlipCap) {
        g.stroke = when {
            dash != null -> BasicStroke(w, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, dash, 0f)
            cap == SlipCap.SQUARE -> BasicStroke(w, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER)
            else -> BasicStroke(w, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        }
        g.draw(Line2D.Float(x1, y1, x2, y2))
    }

    override fun dot(cx: Float, cy: Float, r: Float) = g.fill(Ellipse2D.Float(cx - r, cy - r, 2 * r, 2 * r))

    /** Weather line icons in an [s]-square box, like the phone's. */
    override fun icon(name: String, x: Float, y: Float, s: Float) {
        val saved = g.transform
        g.translate(x.toDouble(), y.toDouble()); g.scale(s / 48.0, s / 48.0)
        g.stroke = BasicStroke(3.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        fun sun(cx: Float, cy: Float, r: Float) {
            g.draw(Ellipse2D.Float(cx - r, cy - r, 2 * r, 2 * r))
            for (i in 0 until 8) {
                val a = Math.toRadians(i * 45.0); val dx = Math.cos(a).toFloat(); val dy = Math.sin(a).toFloat()
                g.draw(Line2D.Float(cx + dx * (r + 4), cy + dy * (r + 4), cx + dx * (r + 9), cy + dy * (r + 9)))
            }
        }
        fun cloud(dy: Float) {
            val a = Area(Ellipse2D.Float(10f, 21f + dy, 14f, 14f))
            a.add(Area(Ellipse2D.Float(17f, 13f + dy, 20f, 20f)))
            a.add(Area(Ellipse2D.Float(29.5f, 22.5f + dy, 13f, 13f)))
            a.add(Area(Rectangle2D.Float(17f, 28f + dy, 19f, 7.5f)))
            g.color = Color.WHITE; g.fill(a); g.color = Color.BLACK; g.draw(a)
        }
        when (name) {
            "sun" -> sun(24f, 24f, 8f)
            "moon" -> g.draw(Ellipse2D.Float(10f, 10f, 28f, 28f))
            "part" -> { sun(17f, 16f, 6f); cloud(6f) }
            "fog" -> listOf(14f, 24f, 34f).forEach { g.draw(Line2D.Float(6f, it, 42f, it)) }
            else -> {
                cloud(if (name == "cloud") 4f else -2f)
                when (name) {
                    "rain", "showers" -> listOf(16f, 24f, 32f).forEach { g.draw(Line2D.Float(it + 2f, 36f, it - 2f, 45f)) }
                    "storm" -> g.draw(Path2D.Float().apply { moveTo(26f, 33f); lineTo(20f, 41f); lineTo(27f, 41f); lineTo(22f, 48f) })
                    "snow" -> listOf(16f, 24f, 32f).forEach { g.fill(Ellipse2D.Float(it - 2.2f, 38.8f, 4.4f, 4.4f)) }
                }
            }
        }
        g.transform = saved
    }
}

/**
 * Sends a job to the receipt printer through the COM port Windows makes when a Bluetooth printer is paired
 * ("Standard Serial over Bluetooth link"). Writing to \\.\COMn is enough; the baud rate doesn't matter over Bluetooth.
 */
object DesktopPrinter {
    data class Port(val name: String, val label: String)

    /** COM ports with their friendly names, Bluetooth ones first. */
    fun ports(): List<Port> {
        val out = runCatching {
            val script = "Get-CimInstance Win32_PnPEntity | Where-Object { \$_.Name -match '\\(COM\\d+\\)' } | ForEach-Object { \$_.Name }"
            val proc = ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", script).redirectErrorStream(true).start()
            proc.inputStream.bufferedReader().readText().also { proc.waitFor() }
        }.getOrDefault("")
        return out.lines().mapNotNull { l ->
            val m = Regex("\\((COM\\d+)\\)").find(l) ?: return@mapNotNull null
            Port(m.groupValues[1], l.trim())
        }.distinctBy { it.name }.sortedByDescending { it.label.contains("Bluetooth", ignoreCase = true) }
    }

    /** Sends [job] in small chunks at about the phone's pace, so the printer doesn't drop the end. [progress] gets 0..1. */
    fun send(port: String, job: ByteArray, progress: (Float) -> Unit = {}) {
        FileOutputStream(File("\\\\.\\$port")).use { out ->
            val chunk = 512
            var i = 0
            while (i < job.size) {
                val n = minOf(chunk, job.size - i)
                out.write(job, i, n); out.flush()
                i += n
                progress(i.toFloat() / job.size)
                Thread.sleep((n * 1000L / 7_000).coerceAtLeast(1))
            }
        }
    }
}
