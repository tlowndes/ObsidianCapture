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
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Draws the Daily Slip as a black-and-white till roll, [WIDTH] dots across: the Phomemo M02 Pro's 48 mm head
 * at 300 dpi. Sizes are in printer dots (about 12 per mm).
 */
object SlipRender {
    const val WIDTH = 576
    private const val MAX_HEIGHT = 7000
    private const val M = 6           // side margin
    private const val BODY = 27f
    private const val SMALL = 22f

    private val headFace = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    private val mono = Typeface.MONOSPACE
    private val monoBold = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)

    private fun paint(size: Float, face: Typeface = mono, align: Paint.Align = Paint.Align.LEFT) =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; typeface = face; color = 0xFF000000.toInt(); textAlign = align }

    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt(); style = Paint.Style.FILL }
    private fun stroke(w: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF000000.toInt(); style = Paint.Style.STROKE; strokeWidth = w; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val paper = Paint().apply { color = 0xFFFFFFFF.toInt(); style = Paint.Style.FILL }

    /** The slip as a bitmap, cropped to its length. */
    fun draw(s: SlipContent, owner: String, now: LocalDateTime = LocalDateTime.now()): Bitmap {
        val tall = Bitmap.createBitmap(WIDTH, MAX_HEIGHT, Bitmap.Config.ARGB_8888)
        val c = Canvas(tall)
        c.drawColor(0xFFFFFFFF.toInt())
        val p = Pen(c)
        p.header(s)
        val sections = buildList<() -> Unit> {
            if (s.weather != null) add { p.weather(s.weather!!) }
            if (s.show.calendar) add { p.calendar(s) }
            if (s.show.todo) add { p.todo(s) }
            if (s.sudoku != null) add { p.sudoku(s.sudoku!!) }
        }
        sections.forEachIndexed { i, f -> if (i > 0) p.dashed(); f() }
        p.footer(owner, now)
        val h = (p.y + 40).toInt().coerceAtMost(MAX_HEIGHT)
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

    /** Draws top to bottom; [y] is where the next thing goes. */
    private class Pen(val c: Canvas) {
        var y = 28f
        val inner = (WIDTH - 2 * M).toFloat()

        fun gap(h: Float) { y += h }

        /** Wrapped text at [x], [w] wide; moves [y] down past it. */
        fun text(s: String, p: TextPaint, x: Float = M.toFloat(), w: Float = inner, align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL, spacing: Float = 1.05f): Float {
            val l = StaticLayout.Builder.obtain(s, 0, s.length, p, w.toInt()).setAlignment(align).setLineSpacing(0f, spacing).setIncludePad(false).build()
            c.save(); c.translate(x, y); l.draw(c); c.restore()
            y += l.height
            return l.height.toFloat()
        }

        /** Text that doesn't move [y]; returns its height. */
        fun textAt(s: String, p: TextPaint, x: Float, top: Float, w: Float, align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL): Float {
            val l = StaticLayout.Builder.obtain(s, 0, s.length, p, w.toInt()).setAlignment(align).setLineSpacing(0f, 1.05f).setIncludePad(false).build()
            c.save(); c.translate(x, top); l.draw(c); c.restore()
            return l.height.toFloat()
        }

        fun solid(w: Float = 5f) { gap(10f); c.drawRect(M.toFloat(), y, WIDTH - M.toFloat(), y + w, ink); y += w; gap(18f) }

        fun dashed() {
            gap(22f)
            val p = stroke(3f).apply { strokeCap = Paint.Cap.BUTT; pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f) }
            c.drawLine(M.toFloat(), y, WIDTH - M.toFloat(), y, p)
            gap(26f)
        }

        /** "WEATHER ───────── Today". */
        fun heading(title: String, right: String? = null) {
            val hp = paint(40f, headFace)
            val top = y
            val base = top + 34f
            c.drawText(title.uppercase(Locale.UK), M.toFloat(), base, hp)
            val tw = hp.measureText(title.uppercase(Locale.UK))
            val rp = paint(SMALL)
            val rw = right?.let { rp.measureText(it) } ?: 0f
            right?.let { c.drawText(it, WIDTH - M - rw, base - 4f, rp) }
            val x1 = M + tw + 14f
            val x2 = WIDTH - M - rw - (if (right != null) 14f else 0f)
            if (x2 > x1 + 20) c.drawRect(x1, base - 15f, x2, base - 10f, ink)
            y = top + 48f
        }

        /** A black label with white letters, e.g. "OVERDUE". Returns its width. */
        fun label(s: String, x: Float, top: Float, size: Float = SMALL): Float {
            val p = paint(size, monoBold).apply { color = 0xFFFFFFFF.toInt() }
            val w = p.measureText(s) + 14f
            c.drawRect(x, top, x + w, top + size + 8f, ink)
            c.drawText(s, x + 7f, top + size, p)
            return w
        }

        fun header(s: SlipContent) {
            val day = s.date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.UK).uppercase(Locale.UK)
            text(day, paint(112f, headFace).apply { letterSpacing = -0.01f }, align = Layout.Alignment.ALIGN_CENTER, spacing = 0.9f)
            gap(4f)
            text(s.date.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.UK)).uppercase(Locale.UK),
                paint(44f, headFace).apply { letterSpacing = 0.06f }, align = Layout.Alignment.ALIGN_CENTER)
            gap(8f)
            val left = s.daysInYear - s.dayOfYear
            text("Week ${s.week}   Day ${s.dayOfYear}/${s.daysInYear}   $left days to go", paint(SMALL), align = Layout.Alignment.ALIGN_CENTER)
            solid()
        }

        fun weather(w: WeatherSummary) {
            heading("Weather", if (w.tomorrow) "Tomorrow" else "Today")
            gap(6f)
            val top = y
            val big = paint(96f, headFace)
            val hiLo = "${w.high}°"
            c.drawText(hiLo, M.toFloat(), top + 84f, big)
            val bw = big.measureText(hiLo)
            c.drawText("/ ${w.low}°", M + bw + 8f, top + 84f, paint(40f, headFace))
            val lx = M + bw + 8f + paint(40f, headFace).measureText("/ ${w.low}°") + 18f
            val h = textAt(w.headline, paint(BODY, monoBold), lx, top + 8f, WIDTH - M - lx)
            y = top + maxOf(100f, h + 16f)
            text(w.detail, paint(BODY))
            w.tip?.let { gap(6f); text(it, paint(SMALL)) }
            if (w.parts.isNotEmpty()) { gap(14f); parts(w.parts) }
            w.tonight?.let { gap(10f); text(it, paint(SMALL)) }
        }

        /** Morning / Afternoon / Evening boxes. */
        fun parts(ps: List<DayPart>) {
            val top = y
            val h = 150f
            val cw = inner / ps.size
            val box = stroke(4f)
            c.drawRect(M + 2f, top, WIDTH - M - 2f, top + h, box)
            ps.forEachIndexed { i, part ->
                val x = M + i * cw
                if (i > 0) c.drawLine(x, top + 8f, x, top + h - 8f, stroke(2f).apply { pathEffect = DashPathEffect(floatArrayOf(3f, 5f), 0f) })
                val mid = x + cw / 2
                c.drawText(part.name, mid, top + 30f, paint(SMALL, mono, Paint.Align.CENTER))
                Icons.draw(c, Slip.icon(part.icon), mid - 24f, top + 40f, 48f)
                c.drawText("${part.temp}°", mid, top + 120f, paint(BODY + 2, monoBold, Paint.Align.CENTER))
                c.drawText(if (part.rain >= 10) "${part.rain}% rain" else "dry", mid, top + 142f, paint(18f, mono, Paint.Align.CENTER))
            }
            y = top + h
        }

        fun calendar(s: SlipContent) {
            val n = s.allDay.size + s.events.size
            heading("Calendar", if (n == 0) "clear" else "$n event" + if (n == 1) "" else "s")
            gap(6f)
            s.allDay.forEach { e ->
                val top = y
                gap(8f)
                text("ALL DAY · " + e.title, paint(SMALL + 1), M + 12f, inner - 24f)
                gap(8f)
                c.drawRect(M + 2f, top, WIDTH - M - 2f, y, stroke(3f))
                gap(10f)
            }
            val tw = 108f
            s.events.forEach { e ->
                val top = y
                c.drawText(e.start ?: "", M.toFloat(), top + BODY, paint(BODY, monoBold))
                y = top
                text(e.title, paint(BODY), M + tw, inner - tw)
                val sub = listOfNotNull(e.end?.let { "until $it" }, e.place?.takeIf { it.isNotBlank() }).joinToString(" · ")
                if (sub.isNotEmpty()) text(sub, paint(SMALL - 1), M + tw, inner - tw)
                gap(12f)
            }
            if (n == 0) { text("Nothing in the calendar today.", paint(BODY)); gap(8f) }
            if (s.show.month) { gap(6f); month(s.date) }
        }

        fun month(d: LocalDate) {
            text(d.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.UK)).uppercase(Locale.UK), paint(30f, headFace), align = Layout.Alignment.ALIGN_CENTER)
            gap(8f)
            val cw = inner / 7
            val hp = paint(SMALL, monoBold, Paint.Align.CENTER)
            listOf("M", "T", "W", "T", "F", "S", "S").forEachIndexed { i, l -> c.drawText(l, M + cw * i + cw / 2, y + SMALL, hp) }
            y += SMALL + 8f
            c.drawRect(M.toFloat(), y, WIDTH - M.toFloat(), y + 3f, ink)
            y += 10f
            val first = d.withDayOfMonth(1)
            val lead = first.dayOfWeek.value - 1
            val rows = (lead + d.lengthOfMonth() + 6) / 7
            val rh = 38f
            for (day in 1..d.lengthOfMonth()) {
                val cell = lead + day - 1
                val cx = M + cw * (cell % 7) + cw / 2
                val top = y + (cell / 7) * rh
                if (day == d.dayOfMonth) {
                    c.drawRect(cx - cw / 2 + 6f, top, cx + cw / 2 - 6f, top + rh - 4f, ink)
                    c.drawText("$day", cx, top + 27f, paint(SMALL + 2, monoBold, Paint.Align.CENTER).apply { color = 0xFFFFFFFF.toInt() })
                } else {
                    c.drawText("$day", cx, top + 27f, paint(SMALL + 2, if (day < d.dayOfMonth) mono else monoBold, Paint.Align.CENTER))
                    if (day < d.dayOfMonth) c.drawLine(cx - 14f, top + 30f, cx + 14f, top + 6f, stroke(2f))
                }
            }
            y += rows * rh
        }

        fun todo(s: SlipContent) {
            val open = s.overdue.size + s.today.size + s.tomorrow.size
            heading("To-do", "$open open")
            if (s.overdue.isNotEmpty()) { gap(8f); dayLabel("OVERDUE", null); s.overdue.forEach { task(it) } }
            gap(8f); dayLabel("TODAY", s.date)
            if (s.today.isEmpty() && s.overdue.isEmpty()) text("Nothing due. Enjoy it.", paint(BODY))
            s.today.forEach { task(it) }
            if (s.show.blank) repeat(3) { blankLine() }
            if (s.tomorrow.isNotEmpty()) { gap(14f); dayLabel("TOMORROW", s.date.plusDays(1)); s.tomorrow.forEach { task(it) } }
            gap(10f)
            text("from Obsidian · Quick Task and your notes", paint(18f))
        }

        fun dayLabel(l: String, d: LocalDate?) {
            val top = y
            val w = label(l, M.toFloat(), top)
            d?.let { c.drawText(Slip.shortDate(it), M + w + 12f, top + SMALL, paint(SMALL)) }
            y = top + SMALL + 20f
        }

        fun task(t: SlipTask) {
            val top = y
            c.drawRect(M + 3f, top + 4f, M + 29f, top + 30f, stroke(3.5f))
            text(t.text, paint(BODY), M + 44f, inner - 44f)
            val sub = (t.tags + listOfNotNull(t.late?.let { "was due " + Slip.shortDate(it) })).joinToString(" · ")
            if (sub.isNotEmpty()) text(sub, paint(18f), M + 44f, inner - 44f)
            y = maxOf(y, top + 34f) + 12f
        }

        fun blankLine() {
            val top = y
            c.drawRect(M + 3f, top + 4f, M + 29f, top + 30f, stroke(3.5f))
            c.drawLine(M + 44f, top + 30f, WIDTH - M - 4f, top + 30f, stroke(2f))
            y = top + 46f
        }

        fun sudoku(p: String) {
            heading("Sudoku", "${p.count { it != '0' }} givens")
            gap(12f)
            val cell = 56f
            val size = cell * 9
            val x0 = (WIDTH - size) / 2
            val top = y
            val np = paint(34f, monoBold, Paint.Align.CENTER)
            for (i in 0..9) {
                val w = if (i % 3 == 0) 5f else 1.5f
                c.drawLine(x0 + i * cell, top, x0 + i * cell, top + size, stroke(w).apply { strokeCap = Paint.Cap.SQUARE })
                c.drawLine(x0, top + i * cell, x0 + size, top + i * cell, stroke(w).apply { strokeCap = Paint.Cap.SQUARE })
            }
            p.forEachIndexed { i, ch ->
                if (ch != '0') c.drawText(ch.toString(), x0 + (i % 9) * cell + cell / 2, top + (i / 9) * cell + cell / 2 + 12f, np)
            }
            y = top + size + 14f
            text("A fresh one every day.", paint(18f), align = Layout.Alignment.ALIGN_CENTER)
        }

        fun footer(owner: String, now: LocalDateTime) {
            gap(10f); solid()
            text("Have a good one, $owner", paint(40f, headFace), align = Layout.Alignment.ALIGN_CENTER)
            gap(6f)
            text(Slip.printedAt(now) + " · Flight Deck", paint(SMALL), align = Layout.Alignment.ALIGN_CENTER)
        }
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
