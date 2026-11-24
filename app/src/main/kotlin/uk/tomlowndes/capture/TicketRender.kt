package uk.tomlowndes.capture

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import java.time.format.DateTimeFormatter

/**
 * Draws Ticket Rail tickets (ticket-rail-v1): tall kitchen-style tickets, one per task, straight down the roll at
 * the same [SlipRender.WIDTH] as the Daily Slip (about 12 dots per mm). Each is as long as its job
 * (ticket-adaptive-v1), never shorter than [MIN_HEIGHT]; tops still hang level since the rail grips the top.
 * A dashed tear line sits between them, since the pocket printer has no cutter.
 */
object TicketRender {
    private const val W = SlipRender.WIDTH
    private const val M = 12f
    /** The shortest ticket: about 60 mm, so the code, QR and CLR box stay easy to grab and scan. */
    const val MIN_HEIGHT = 720
    /** The rail grips the top: about 7 mm left blank. */
    private const val GRIP = 84f
    private const val TEAR = 70
    private const val QR = 240f
    private const val STEP_ROW = 46f
    /** The rule, QR and CLR box at the bottom, with the margin under it. */
    private const val FOOT = 22f + QR + 24f
    private val HM = DateTimeFormatter.ofPattern("HH:mm")

    private val mono = Typeface.MONOSPACE
    private val monoBold = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    private fun paint(size: Float, face: Typeface = mono) =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; typeface = face; color = 0xFF000000.toInt() }
    private val ink = Paint().apply { color = 0xFF000000.toInt(); style = Paint.Style.FILL }
    private fun stroke(w: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt(); style = Paint.Style.STROKE; strokeWidth = w }

    /** How long [t] prints, in dots: its content, or [MIN_HEIGHT] when that's shorter. */
    fun heightOf(t: Ticket): Int {
        val scratch = Canvas(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
        val end = body(scratch, t, 0f)
        return maxOf(MIN_HEIGHT, (end + 20f + FOOT).toInt())
    }

    /** The paper [tickets] take, tear gaps included, in dots. */
    fun lengthOf(tickets: List<Ticket>): Int =
        if (tickets.isEmpty()) 0 else tickets.sumOf { heightOf(it) } + (tickets.size - 1) * TEAR

    /** [tickets] one under another, with a tear line between each. */
    fun draw(tickets: List<Ticket>): Bitmap {
        require(tickets.isNotEmpty())
        val heights = tickets.map { heightOf(it) }
        val h = heights.sum() + (tickets.size - 1) * TEAR
        val b = Bitmap.createBitmap(W, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(0xFFFFFFFF.toInt())
        var top = 0f
        tickets.forEachIndexed { i, t ->
            if (i > 0) { tear(c, top + TEAR / 2f); top += TEAR }
            body(c, t, top)
            foot(c, t, top + heights[i])
            top += heights[i]
        }
        return b
    }

    /** [a] then [b] underneath with a tear line between: the Daily Slip and the morning's tickets in one print. */
    fun stack(a: Bitmap, b: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(W, a.height + TEAR + b.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(0xFFFFFFFF.toInt())
        c.drawBitmap(a, 0f, 0f, null)
        tear(c, a.height + TEAR / 2f)
        c.drawBitmap(b, 0f, (a.height + TEAR).toFloat(), null)
        return out
    }

    private fun tear(c: Canvas, y: Float) {
        val p = stroke(4f).apply { pathEffect = DashPathEffect(floatArrayOf(18f, 12f), 0f) }
        c.drawLine(M, y, W - M, y, p)
    }

    /** Everything above the footer, from [top]; returns the y under it. Titles and steps print in full. */
    private fun body(c: Canvas, t: Ticket, top: Float): Float {
        var y = top + GRIP
        // The tag as a hatch, since thermal paper is black only.
        hatch(c, t.pattern, y, y + 18f)
        y += 18f + 22f
        val code = paint(108f, monoBold)
        y += 92f
        c.drawText(t.code, M, y, code)
        y += 20f
        val slot = listOf(t.slot, t.slotNote, t.tag).filter { it.isNotBlank() }.joinToString(" · ")
        y = text(c, slot, paint(30f), y, 1) + 14f
        // Printed ahead of its day (ticket-rail-future-v1): white words on a black bar, so it can't pass for today's.
        if (t.early.isNotBlank()) {
            c.drawRect(M, y, W - M, y + 48f, ink)
            val bar = paint(30f, monoBold).apply { color = 0xFFFFFFFF.toInt(); textAlign = Paint.Align.CENTER }
            c.drawText(t.early, W / 2f, y + 35f, bar)
            y += 62f
        }
        c.drawRect(M, y, W - M, y + 4f, ink)
        y += 18f
        y = text(c, "TASK", paint(22f), y, 1) + 4f
        val hasSteps = t.steps.isNotEmpty()
        y = text(c, t.text, paint(if (hasSteps) 44f else 50f, monoBold), y, Int.MAX_VALUE) + 10f
        y = text(c, t.source, paint(26f), y, 1) + 16f
        // Sub-tasks as tick boxes (four-fixes-v1), every one of them.
        if (hasSteps) {
            c.drawRect(M, y, W - M, y + 3f, ink)
            y += 14f
            t.steps.forEach { step ->
                c.drawRect(M + 2f, y + 6f, M + 30f, y + 34f, stroke(4f))
                val end = text(c, step, paint(32f), y + 4f, Int.MAX_VALUE, x = M + 44f)
                y = maxOf(y + STEP_ROW, end + 8f)
            }
        }
        return y
    }

    /** Print time and a CLR box on the left, the QR on the right, ending at [bottom]. It's what your hand is on when you tear. */
    private fun foot(c: Canvas, t: Ticket, bottom: Float) {
        val qrTop = bottom - 24f - QR
        c.drawRect(M, qrTop - 22f, W - M, qrTop - 18f, ink)
        qr(c, TicketRail.qr(t.id), W - M - QR, qrTop, QR)
        val small = paint(28f)
        c.drawText("PRT ${t.printedAt.ifBlank { t.printed.format(HM) }}", M, qrTop + 30f, small)
        c.drawRect(M + 3f, qrTop + 56f, M + 103f, qrTop + 156f, stroke(6f))
        c.drawText("CLR", M, qrTop + 196f, small)
    }

    /** Wrapped text from [y], at most [lines] lines; returns the y under it. */
    private fun text(c: Canvas, s: String, p: TextPaint, y: Float, lines: Int, x: Float = M): Float {
        val w = (W - M - x).toInt()
        val l = StaticLayout.Builder.obtain(s, 0, s.length, p, w).setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.05f).setMaxLines(lines).setEllipsize(TextUtils.TruncateAt.END).setIncludePad(false).build()
        c.save(); c.translate(x, y); l.draw(c); c.restore()
        return y + l.height
    }

    private fun hatch(c: Canvas, pattern: Int, y0: Float, y1: Float) {
        when (pattern) {
            1 -> c.drawRect(M, y0, W - M, y1, ink)
            2 -> { var x = M; while (x < W - M) { c.drawRect(x, y0, minOf(x + 6f, W - M), y1, ink); x += 12f } }
            else -> {
                c.save(); c.clipRect(M, y0, W - M, y1)
                val p = stroke(5f)
                var x = M - (y1 - y0)
                while (x < W - M) { c.drawLine(x, y1, x + (y1 - y0), y0, p); x += 12f }
                c.restore()
            }
        }
    }

    private fun qr(c: Canvas, m: Array<BooleanArray>, x: Float, y: Float, size: Float) {
        val cell = size / m.size
        for (r in m.indices) for (k in m[r].indices) if (m[r][k]) {
            c.drawRect(x + k * cell, y + r * cell, x + (k + 1) * cell + 0.5f, y + (r + 1) * cell + 0.5f, ink)
        }
    }
}
