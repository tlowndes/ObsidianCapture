package uk.tomlowndes.capture

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.time.LocalDateTime

/**
 * Draws the till-roll slips on the phone. The layout lives in core ([SlipLayout]), shared with Windows; this file
 * only turns it into an Android bitmap. [WIDTH] dots across: the Phomemo M02 Pro's 48 mm head at 300 dpi.
 */
object SlipRender {
    const val WIDTH = SlipLayout.WIDTH
    private const val MAX_HEIGHT = SlipLayout.MAX_HEIGHT
    /** Text size Large (printables-v1): about 25% bigger text and tick boxes on every slip. Set before drawing. */
    @Volatile var large = false
    private val LOOK = SlipLayout.Look(dayTitle = 112f)

    /** The slip as a bitmap, cropped to its length. */
    fun draw(s: SlipContent, owner: String, now: LocalDateTime = LocalDateTime.now()): Bitmap =
        page { SlipLayout.daily(it, LOOK, large, s, owner, now) }

    /** The Week slip (slip-week-v2): the week's days one under another, with a tear line between them. */
    fun drawWeek(w: WeekContent, now: LocalDateTime = LocalDateTime.now()): Bitmap = page { SlipLayout.week(it, LOOK, large, w, now) }

    /** A list (shopping list slip) or shared text (Print from share), printables-v1. */
    fun drawList(d: ListDoc, now: LocalDateTime = LocalDateTime.now()): Bitmap = page { SlipLayout.list(it, LOOK, large, d, now) }

    /** The Bills slip (printables-v1): left to pay, then the month's bills to pay and paid. */
    fun drawBills(b: BillsDoc, now: LocalDateTime = LocalDateTime.now()): Bitmap = page { SlipLayout.bills(it, LOOK, large, b, now) }

    /** A Print summary slip (print-summary-v1): title, where it came from, then the points or paragraph. */
    fun drawSummary(d: Summary.Doc, now: LocalDateTime = LocalDateTime.now()): Bitmap = page { SlipLayout.summary(it, LOOK, large, d, now) }

    private fun page(draw: (SlipCanvas) -> Int): Bitmap {
        val tall = Bitmap.createBitmap(WIDTH, MAX_HEIGHT, Bitmap.Config.ARGB_8888)
        val c = Canvas(tall)
        c.drawColor(0xFFFFFFFF.toInt())
        val h = draw(AndroidSlipCanvas(c))
        return Bitmap.createBitmap(tall, 0, 0, WIDTH, h).also { if (it !== tall) tall.recycle() }
    }

    /** Grey levels (0 black..255 white) for [EscPos.bits]. */
    fun grey(b: Bitmap): IntArray {
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        for (i in px.indices) {
            val v = px[i]
            px[i] = ((v shr 16 and 255) * 299 + (v shr 8 and 255) * 587 + (v and 255) * 114) / 1000
        }
        return px
    }

    /** The ESC/POS job for [b]. */
    fun job(b: Bitmap): ByteArray = EscPos.job(EscPos.bits(grey(b), b.width, b.height), (b.width + 7) / 8, b.height)
}

/** [SlipCanvas] on an Android [Canvas]. */
private class AndroidSlipCanvas(val c: Canvas) : SlipCanvas {
    private fun face(f: SlipFont): Typeface = when (f.face) {
        SlipFace.HEAD -> headFace
        SlipFace.SERIF -> serifItalic
        SlipFace.MONO -> when { f.bold -> monoBold; f.italic -> monoItalic; else -> mono }
    }

    private fun paint(f: SlipFont, align: Paint.Align = Paint.Align.LEFT) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = f.size; typeface = face(f); color = 0xFF000000.toInt(); textAlign = align
        letterSpacing = f.letterSpacing; isStrikeThruText = f.strike
    }

    private fun stroke(w: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF000000.toInt(); style = Paint.Style.STROKE; strokeWidth = w; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }

    override fun block(s: String, f: SlipFont, x: Float, top: Float, w: Float, center: Boolean, spacing: Float): Float {
        val align = if (center) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL
        val l = StaticLayout.Builder.obtain(s, 0, s.length, paint(f), w.toInt()).setAlignment(align).setLineSpacing(0f, spacing).setIncludePad(false).build()
        c.save(); c.translate(x, top); l.draw(c); c.restore()
        return l.height.toFloat()
    }

    override fun say(s: String, f: SlipFont, x: Float, base: Float, align: SlipCanvas.Align, white: Boolean) {
        val a = when (align) { SlipCanvas.Align.LEFT -> Paint.Align.LEFT; SlipCanvas.Align.CENTER -> Paint.Align.CENTER; SlipCanvas.Align.RIGHT -> Paint.Align.RIGHT }
        c.drawText(s, x, base, paint(f, a).apply { if (white) color = 0xFFFFFFFF.toInt() })
    }

    override fun width(s: String, f: SlipFont) = paint(f).measureText(s)

    override fun fill(x1: Float, y1: Float, x2: Float, y2: Float) = c.drawRect(x1, y1, x2, y2, ink)

    override fun box(x1: Float, y1: Float, x2: Float, y2: Float, w: Float) = c.drawRect(x1, y1, x2, y2, stroke(w))

    override fun rule(x1: Float, y1: Float, x2: Float, y2: Float, w: Float, dash: FloatArray?, cap: SlipCap) {
        val p = stroke(w).apply {
            strokeCap = when (cap) { SlipCap.BUTT -> Paint.Cap.BUTT; SlipCap.SQUARE -> Paint.Cap.SQUARE; SlipCap.ROUND -> Paint.Cap.ROUND }
            if (dash != null) pathEffect = DashPathEffect(dash, 0f)
        }
        c.drawLine(x1, y1, x2, y2, p)
    }

    override fun dot(cx: Float, cy: Float, r: Float) = c.drawCircle(cx, cy, r, ink)

    override fun icon(name: String, x: Float, y: Float, s: Float) = Icons.draw(c, name, x, y, s)

    private companion object {
        val headFace: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        val mono: Typeface = Typeface.MONOSPACE
        val monoBold: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        val monoItalic: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.ITALIC)
        val serifItalic: Typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt(); style = Paint.Style.FILL }
    }
}

/** Line icons for the weather strip, drawn in a [s]-square box at ([x], [y]). */
private object Icons {
    private fun st(w: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt(); style = Paint.Style.STROKE; strokeWidth = w; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); style = Paint.Style.FILL }

    fun draw(c: Canvas, name: String, x: Float, y: Float, s: Float) {
        val u = s / 48f
        c.save(); c.translate(x, y); c.scale(u, u)
        val line = st(3.2f)
        when (name) {
            "sun" -> sun(c, 24f, 24f, 8f, line)
            "moon" -> { c.drawCircle(24f, 24f, 14f, line); c.drawCircle(33f, 17f, 12f, white); c.drawArc(RectF(21f, 5f, 45f, 29f), 95f, 170f, false, line) }
            "part" -> { sun(c, 17f, 16f, 6f, line); cloud(c, 6f, line) }
            "fog" -> listOf(14f, 24f, 34f).forEach { c.drawLine(6f, it, 42f, it, line) }
            else -> {
                cloud(c, if (name == "cloud") 4f else -2f, line)
                when (name) {
                    "rain", "showers" -> listOf(16f, 24f, 32f).forEach { c.drawLine(it + 2f, 36f, it - 2f, 45f, line) }
                    "storm" -> { val p = Path().apply { moveTo(26f, 33f); lineTo(20f, 41f); lineTo(27f, 41f); lineTo(22f, 48f) }; c.drawPath(p, line) }
                    "snow" -> listOf(16f, 24f, 32f).forEach { c.drawCircle(it, 41f, 2.2f, Paint(line).apply { style = Paint.Style.FILL }) }
                }
            }
        }
        c.restore()
    }

    private fun sun(c: Canvas, cx: Float, cy: Float, r: Float, p: Paint) {
        c.drawCircle(cx, cy, r, p)
        for (i in 0 until 8) {
            val a = Math.toRadians(i * 45.0)
            val (dx, dy) = Math.cos(a).toFloat() to Math.sin(a).toFloat()
            c.drawLine(cx + dx * (r + 4), cy + dy * (r + 4), cx + dx * (r + 9), cy + dy * (r + 9), p)
        }
    }

    /** A cloud outline with a white fill, so it can sit over a sun. [dy] nudges it up or down. */
    private fun cloud(c: Canvas, dy: Float, p: Paint) {
        val path = Path().apply {
            addCircle(17f, 28f + dy, 7f, Path.Direction.CW)
            addCircle(27f, 23f + dy, 10f, Path.Direction.CW)
            addCircle(36f, 29f + dy, 6.5f, Path.Direction.CW)
            addRect(17f, 28f + dy, 36f, 35.5f + dy, Path.Direction.CW)
        }
        val merged = Path().apply { op(path, Path.Op.UNION) }
        c.drawPath(merged, white)
        c.drawPath(merged, p)
    }
}
