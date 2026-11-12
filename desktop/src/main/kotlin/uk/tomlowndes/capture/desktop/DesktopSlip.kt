package uk.tomlowndes.capture.desktop

import uk.tomlowndes.capture.BillsDoc
import uk.tomlowndes.capture.DayPart
import uk.tomlowndes.capture.EscPos
import uk.tomlowndes.capture.ListDoc
import uk.tomlowndes.capture.Slip
import uk.tomlowndes.capture.SlipContent
import uk.tomlowndes.capture.SlipEvent
import uk.tomlowndes.capture.SlipReminder
import uk.tomlowndes.capture.SlipTask
import uk.tomlowndes.capture.Summary
import uk.tomlowndes.capture.TaskViews
import uk.tomlowndes.capture.WeatherSummary
import uk.tomlowndes.capture.WeekContent
import uk.tomlowndes.capture.WeekDay
import uk.tomlowndes.capture.WeekOptions
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.RenderingHints
import java.awt.geom.Area
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * The Daily Slip and Print summary drawn with Java2D, the same layout as the phone's SlipRender, at the printer's
 * 576 dots across (48 mm at 300 dpi). [job] turns it into the same ESC/POS raster the phone sends.
 */
object DesktopSlip {
    const val WIDTH = 576
    private const val MAX_HEIGHT = 7000
    private const val M = 6
    /** Text size Large (printables-v1): about 25% bigger text and tick boxes on every slip. Set before drawing. */
    @Volatile var large = false
    private val scale get() = if (large) 1.25f else 1f
    private val BODY get() = 27f * scale
    private val SMALL get() = 22f * scale
    private val BOX get() = 42f * scale
    private val TEXT_X get() = 60f * scale

    private val fonts by lazy { GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toSet() }
    private fun head(size: Float): Font {
        val name = listOf("Bahnschrift", "Arial Narrow", "Segoe UI").firstOrNull { it in fonts } ?: Font.SANS_SERIF
        return Font(name, Font.BOLD, 1).deriveFont(size)
    }
    private fun mono(size: Float, bold: Boolean = false): Font {
        val name = listOf("Consolas", "Cascadia Mono", "Courier New").firstOrNull { it in fonts } ?: Font.MONOSPACED
        return Font(name, if (bold) Font.BOLD else Font.PLAIN, 1).deriveFont(size)
    }

    fun draw(s: SlipContent, owner: String, now: LocalDateTime = LocalDateTime.now()): BufferedImage = page { p ->
        p.header(s)
        val sections = buildList<() -> Unit> {
            s.brief?.let { b -> add { p.brief(b) } }
            s.weather?.let { w -> add { p.weather(w) } }
            if (s.show.calendar) add { p.calendar(s) }
            if (s.dontForget.isNotEmpty()) add { p.dontForget(s.dontForget) }
            if (s.show.todo) add { p.todo(s) }
            s.sudoku?.let { su -> add { p.sudoku(su) } }
        }
        sections.forEachIndexed { i, f -> if (i > 0) p.dashed(); f() }
        p.footer(owner, now)
    }

    /** The Week slip (slip-week-v2): the week's days one under another, with a tear line between them. */
    fun drawWeek(w: WeekContent, now: LocalDateTime = LocalDateTime.now()): BufferedImage = page { p ->
        p.weekHeader(w)
        w.days.forEachIndexed { i, d -> if (i > 0) p.dashed(); p.weekDay(d, w.show) }
        p.gap(10f); p.solid()
        p.text(Slip.printedAt(now) + " · Flight Deck", mono(SMALL), center = true)
    }

    /** A list (shopping list slip) or shared text (Print clipboard), printables-v1. */
    fun drawList(d: ListDoc, now: LocalDateTime = LocalDateTime.now()): BufferedImage = page { p ->
        p.docHeader(d.title, d.sub)
        d.body?.let { p.text(it, mono(BODY), spacing = 1.15f) }
        d.groups.forEach { g ->
            g.heading?.let { p.gap(10f); p.text(it, mono(SMALL, true)); p.gap(6f) }
            g.items.forEach { p.reminder(SlipReminder(it, "")) }
        }
        p.gap(10f); p.solid()
        p.text(Slip.printedAt(now) + " · Flight Deck", mono(SMALL), center = true)
    }

    /** The Bills slip (printables-v1): left to pay, then the month's bills to pay and paid. */
    fun drawBills(b: BillsDoc, now: LocalDateTime = LocalDateTime.now()): BufferedImage = page { p ->
        p.bills(b)
        p.gap(10f); p.solid()
        p.text(Slip.printedAt(now) + " · Flight Deck", mono(SMALL), center = true)
    }

    fun drawSummary(d: Summary.Doc, now: LocalDateTime = LocalDateTime.now()): BufferedImage = page { p ->
        p.summary(d)
        p.gap(10f); p.solid()
        p.text(Slip.printedAt(now) + " · Flight Deck", mono(SMALL), center = true)
    }

    private fun page(draw: (Pen) -> Unit): BufferedImage {
        val tall = BufferedImage(WIDTH, MAX_HEIGHT, BufferedImage.TYPE_INT_RGB)
        val g = tall.createGraphics()
        g.color = Color.WHITE; g.fillRect(0, 0, WIDTH, MAX_HEIGHT)
        g.color = Color.BLACK
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        val p = Pen(g)
        draw(p)
        g.dispose()
        val h = (p.y + 40).toInt().coerceAtMost(MAX_HEIGHT)
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

    private class Pen(val g: java.awt.Graphics2D) {
        var y = 28f
        val inner = (WIDTH - 2 * M).toFloat()

        fun gap(h: Float) { y += h }

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

        /** Wrapped text; moves [y] past it. */
        fun text(s: String, f: Font, x: Float = M.toFloat(), w: Float = inner, center: Boolean = false, spacing: Float = 1.05f): Float {
            val h = textAt(s, f, x, y, w, center, spacing)
            y += h
            return h
        }

        fun textAt(s: String, f: Font, x: Float, top: Float, w: Float, center: Boolean = false, spacing: Float = 1.05f): Float {
            g.font = f
            val fm = g.fontMetrics
            val lh = fm.height * spacing
            var yy = top
            lines(s, f, w).forEach { l ->
                val lx = if (center) x + (w - fm.stringWidth(l)) / 2 else x
                g.drawString(l, lx, yy + fm.ascent)
                yy += lh
            }
            return yy - top
        }

        private fun at(s: String, f: Font, x: Float, base: Float, align: Int = 0) {
            g.font = f
            val w = g.fontMetrics.stringWidth(s)
            g.drawString(s, when (align) { 1 -> x - w / 2f; 2 -> x - w; else -> x }, base)
        }

        private fun width(s: String, f: Font) = g.getFontMetrics(f).stringWidth(s).toFloat()
        private fun rect(x1: Float, y1: Float, x2: Float, y2: Float) = g.fill(Rectangle2D.Float(x1, y1, x2 - x1, y2 - y1))
        private fun box(x1: Float, y1: Float, x2: Float, y2: Float, w: Float) { g.stroke = BasicStroke(w); g.draw(Rectangle2D.Float(x1, y1, x2 - x1, y2 - y1)) }
        private fun line(x1: Float, y1: Float, x2: Float, y2: Float, w: Float, dash: FloatArray? = null) {
            g.stroke = if (dash != null) BasicStroke(w, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, dash, 0f) else BasicStroke(w, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.draw(java.awt.geom.Line2D.Float(x1, y1, x2, y2))
        }

        fun solid(w: Float = 5f) { gap(10f); rect(M.toFloat(), y, WIDTH - M.toFloat(), y + w); y += w; gap(18f) }

        fun dashed() { gap(22f); line(M.toFloat(), y, WIDTH - M.toFloat(), y, 3f, floatArrayOf(12f, 8f)); gap(26f) }

        fun heading(title: String, right: String? = null) {
            val hf = head(40f)
            val top = y
            val base = top + 34f
            val t = title.uppercase(Locale.UK)
            at(t, hf, M.toFloat(), base)
            val tw = width(t, hf)
            val rf = mono(SMALL)
            val rw = right?.let { width(it, rf) } ?: 0f
            right?.let { at(it, rf, WIDTH - M - rw, base - 4f) }
            val x1 = M + tw + 14f
            val x2 = WIDTH - M - rw - (if (right != null) 14f else 0f)
            if (x2 > x1 + 20) rect(x1, base - 15f, x2, base - 10f)
            y = top + 48f
        }

        fun label(s: String, x: Float, top: Float, size: Float = SMALL): Float {
            val f = mono(size, true)
            val w = width(s, f) + 14f
            rect(x, top, x + w, top + size + 8f)
            g.color = Color.WHITE; at(s, f, x + 7f, top + size); g.color = Color.BLACK
            return w
        }

        fun header(s: SlipContent) {
            val day = s.date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.UK).uppercase(Locale.UK)
            text(day, head(104f), center = true, spacing = 0.9f)
            gap(4f)
            text(s.date.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.UK)).uppercase(Locale.UK), head(44f), center = true)
            gap(8f)
            val left = s.daysInYear - s.dayOfYear
            text("Week ${s.week}   Day ${s.dayOfYear}/${s.daysInYear}   $left days to go", mono(SMALL), center = true)
            if (s.ahead) {
                gap(12f)
                val w = width(Slip.AHEAD_STAMP, mono(SMALL, true)) + 14f
                label(Slip.AHEAD_STAMP, (WIDTH - w) / 2, y)
                y += SMALL + 8f
            }
            solid()
        }

        fun tickBox(top: Float) = box(M + 3f, top + 2f, M + 1f + BOX, top + BOX, 4f)

        fun brief(t: String) { heading("In brief"); gap(4f); text(t, mono(BODY), spacing = 1.15f) }

        fun summary(d: Summary.Doc) {
            text(d.title, head(52f), spacing = 0.95f)
            gap(4f)
            text(d.subtitle, mono(SMALL))
            dashed()
            if (d.bullets.isEmpty()) text(d.text, mono(BODY), spacing = 1.15f)
            else d.bullets.forEach { b ->
                val top = y
                if (d.ticks) tickBox(top) else g.fill(Ellipse2D.Float(M + 8f, top + 11f, 12f, 12f))
                val indent = if (d.ticks) TEXT_X else 44f
                text(b, mono(BODY), M + indent, inner - indent, spacing = 1.1f)
                y = maxOf(y, top + if (d.ticks) BOX + 6f else 34f) + 12f
            }
        }

        fun weather(w: WeatherSummary) {
            heading("Weather", if (w.tomorrow) "Tomorrow" else "Today")
            gap(6f)
            val top = y
            val big = head(96f)
            val hiLo = "${w.high}°"
            at(hiLo, big, M.toFloat(), top + 84f)
            val bw = width(hiLo, big)
            val lo = "/ ${w.low}°"
            at(lo, head(40f), M + bw + 8f, top + 84f)
            val lx = M + bw + 8f + width(lo, head(40f)) + 18f
            val h = textAt(w.headline, mono(BODY, true), lx, top + 8f, WIDTH - M - lx)
            y = top + maxOf(100f, h + 16f)
            text(w.detail, mono(BODY))
            w.tip?.let { gap(6f); text(it, mono(SMALL)) }
            if (w.parts.isNotEmpty()) { gap(14f); parts(w.parts) }
            w.tonight?.let { gap(10f); text(it, mono(SMALL)) }
        }

        fun parts(ps: List<DayPart>) {
            val top = y
            val h = 150f
            val cw = inner / ps.size
            box(M + 2f, top, WIDTH - M - 2f, top + h, 4f)
            ps.forEachIndexed { i, part ->
                val x = M + i * cw
                if (i > 0) line(x, top + 8f, x, top + h - 8f, 2f, floatArrayOf(3f, 5f))
                val mid = x + cw / 2
                at(part.name, mono(SMALL), mid, top + 30f, 1)
                icon(Slip.icon(part.icon), mid - 24f, top + 40f, 48f)
                at("${part.temp}°", mono(BODY + 2, true), mid, top + 120f, 1)
                at(if (part.rain >= 10) "${part.rain}% rain" else "dry", mono(18f), mid, top + 142f, 1)
            }
            y = top + h
        }

        fun calendar(s: SlipContent) {
            val n = s.allDay.size + s.events.size
            val next = s.nextAllDay.size + s.nextEvents.size
            heading("Calendar", if (n == 0) "clear" else "$n event" + if (n == 1) "" else "s")
            gap(6f)
            if (next > 0) { gap(2f); dayLabel(s.dayWord, s.date) }
            events(s.allDay, s.events)
            if (n == 0) { text(s.clearLine, mono(BODY)); gap(8f) }
            if (next > 0) { gap(8f); dayLabel(s.nextWord, s.date.plusDays(1)); events(s.nextAllDay, s.nextEvents) }
            if (s.show.month) { gap(6f); month(s.date) }
        }

        fun events(allDay: List<SlipEvent>, timed: List<SlipEvent>) {
            allDay.forEach { e ->
                val top = y
                gap(8f)
                text("ALL DAY · " + e.title, mono(SMALL + 1), M + 12f, inner - 24f)
                gap(8f)
                box(M + 2f, top, WIDTH - M - 2f, y, 3f)
                gap(10f)
            }
            val tw = 108f
            timed.forEach { e ->
                val top = y
                at(e.start ?: "", mono(BODY, true), M.toFloat(), top + BODY)
                y = top
                text(e.title, mono(BODY), M + tw, inner - tw)
                val sub = listOfNotNull(e.end?.let { "until $it" }, e.place?.takeIf { it.isNotBlank() }).joinToString(" · ")
                if (sub.isNotEmpty()) text(sub, mono(SMALL - 1), M + tw, inner - tw)
                gap(12f)
            }
        }

        fun month(d: LocalDate) {
            text(d.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.UK)).uppercase(Locale.UK), head(30f), center = true)
            gap(8f)
            val cw = inner / 7
            listOf("M", "T", "W", "T", "F", "S", "S").forEachIndexed { i, l -> at(l, mono(SMALL, true), M + cw * i + cw / 2, y + SMALL, 1) }
            y += SMALL + 8f
            rect(M.toFloat(), y, WIDTH - M.toFloat(), y + 3f)
            y += 10f
            val lead = d.withDayOfMonth(1).dayOfWeek.value - 1
            val rows = (lead + d.lengthOfMonth() + 6) / 7
            val rh = 38f
            for (day in 1..d.lengthOfMonth()) {
                val cell = lead + day - 1
                val cx = M + cw * (cell % 7) + cw / 2
                val top = y + (cell / 7) * rh
                if (day == d.dayOfMonth) {
                    rect(cx - cw / 2 + 6f, top, cx + cw / 2 - 6f, top + rh - 4f)
                    g.color = Color.WHITE; at("$day", mono(SMALL + 2, true), cx, top + 27f, 1); g.color = Color.BLACK
                } else {
                    at("$day", mono(SMALL + 2, day >= d.dayOfMonth), cx, top + 27f, 1)
                    if (day < d.dayOfMonth) line(cx - 14f, top + 30f, cx + 14f, top + 6f, 2f)
                }
            }
            y += rows * rh
        }

        fun todo(s: SlipContent) {
            val open = s.overdue.size + s.today.size + s.tomorrow.size
            heading("To-do", "$open open")
            if (s.overdue.isNotEmpty()) { gap(8f); dayLabel(s.lateWord, null); s.overdue.forEach { task(it) } }
            gap(8f); dayLabel(s.dayWord, s.date)
            if (s.today.isEmpty() && s.overdue.isEmpty()) text("Nothing due. Enjoy it.", mono(BODY))
            s.today.forEach { task(it) }
            if (s.show.blank) repeat(3) { blankLine() }
            if (s.tomorrow.isNotEmpty()) { gap(14f); dayLabel(s.nextWord, s.date.plusDays(1)); s.tomorrow.forEach { task(it) } }
            gap(10f)
            text("from Obsidian · Quick Task and your notes", mono(18f))
        }

        /** Don't forget (slip-extras-v1): a tick box, the bill or job, and its note on the right. */
        fun dontForget(items: List<SlipReminder>) {
            heading("Don't forget", "${items.size}")
            gap(8f)
            items.forEach { reminder(it) }
        }

        /** A tick box, the text, and a small note on the right ("£142 · today"). */
        fun reminder(r: SlipReminder) {
            val top = y
            tickBox(top)
            val nf = mono(SMALL)
            val nw = if (r.note.isEmpty()) 0f else width(r.note, nf)
            if (r.note.isNotEmpty()) at(r.note, nf, WIDTH - M - nw, top + BODY)
            text(r.text, mono(BODY), M + TEXT_X, inner - TEXT_X - nw - 16f)
            y = maxOf(y, top + BOX + 6f) + 12f
        }

        fun docHeader(title: String, sub: String) {
            text(title, head(72f), center = true, spacing = 0.9f)
            if (sub.isNotEmpty()) { gap(6f); text(sub, mono(SMALL), center = true) }
            solid()
        }

        fun bills(b: BillsDoc) {
            text("BILLS", head(72f), center = true, spacing = 0.9f)
            gap(4f)
            text(b.title, head(36f), center = true)
            solid()
            val top = y
            val lf = mono(BODY, true)
            at("Left to pay", lf, M.toFloat(), top + BODY)
            val left = TaskViews.money(b.left)
            at(left, lf, WIDTH - M - width(left, lf), top + BODY)
            y = top + BODY + 10f
            val sf = mono(SMALL)
            val total = "of " + TaskViews.money(b.left + b.paidTotal)
            at("Paid " + TaskViews.money(b.paidTotal), sf, M.toFloat(), y + SMALL)
            at(total, sf, WIDTH - M - width(total, sf), y + SMALL)
            y += SMALL + 18f
            if (b.toPay.isNotEmpty()) {
                text("TO PAY", mono(SMALL, true)); gap(6f)
                b.toPay.forEach { reminder(SlipReminder(it.name, listOfNotNull(b.day(it).ifEmpty { null }, it.amount?.let { a -> TaskViews.money(a) }).joinToString(" · "))) }
            } else text("Nothing left to pay.", mono(BODY))
            if (b.paid.isNotEmpty()) {
                gap(8f); text("PAID", mono(SMALL, true)); gap(6f)
                b.paid.forEach { bill ->
                    val t = y
                    rect(M + 3f, t + 2f, M + 1f + BOX, t + BOX)
                    val note = listOfNotNull(b.day(bill).ifEmpty { null }, bill.amount?.let { a -> TaskViews.money(a) }).joinToString(" · ")
                    val nw = width(note, sf)
                    at(note, sf, WIDTH - M - nw, t + BODY)
                    val tf = mono(BODY).deriveFont(mapOf(java.awt.font.TextAttribute.STRIKETHROUGH to java.awt.font.TextAttribute.STRIKETHROUGH_ON))
                    text(bill.name, tf, M + TEXT_X, inner - TEXT_X - nw - 16f)
                    y = maxOf(y, t + BOX + 6f) + 12f
                }
            }
        }

        fun weekHeader(w: WeekContent) {
            text(w.title, head(96f), center = true, spacing = 0.9f)
            gap(4f)
            text(w.range, head(36f), center = true)
            gap(8f)
            text(w.sub, mono(SMALL), center = true)
            solid()
        }

        /** One day block: black label for today, outlined for the rest, then events, to-dos, bills and a line to write on. */
        fun weekDay(d: WeekDay, o: WeekOptions) {
            gap(6f)
            val top = y
            val lw = if (d.today) label(d.label, M.toFloat(), top, BODY) else {
                val lf = mono(BODY, true)
                val w = width(d.label, lf) + 14f
                box(M + 1.5f, top + 1.5f, M + w, top + BODY + 8f, 3f)
                at(d.label, lf, M + 7f, top + BODY)
                w
            }
            val dn = "${d.date.dayOfMonth}"
            val df = mono(BODY + 4, true)
            at(dn, df, M + lw + 14f, top + BODY + 2f)
            if (d.today) at("today", mono(SMALL), M + lw + 28f + width(dn, df), top + BODY)
            y = top + BODY + 30f
            val tw = 108f
            d.events.forEach { e ->
                val t = y
                at(e.start ?: "all day", mono(if (e.start == null) SMALL else BODY, true), M.toFloat(), t + BODY)
                y = t
                text(e.title, mono(BODY), M + tw, inner - tw)
                gap(10f)
            }
            if (d.events.isNotEmpty() && (d.tasks.isNotEmpty() || d.bills.isNotEmpty())) gap(4f)
            d.tasks.forEach { task(it) }
            d.bills.forEach { reminder(it) }
            if (d.empty) { text("Nothing planned.", mono(BODY).deriveFont(Font.ITALIC)); gap(6f) }
            if (o.writeLine) { gap(30f); line(M.toFloat(), y, WIDTH - M.toFloat(), y, 2f); gap(6f) }
        }

        fun dayLabel(l: String, d: LocalDate?) {
            val top = y
            val w = label(l, M.toFloat(), top)
            d?.let { at(Slip.shortDate(it), mono(SMALL), M + w + 12f, top + SMALL) }
            y = top + SMALL + 20f
        }

        fun task(t: SlipTask) {
            val top = y
            tickBox(top)
            text(t.text, mono(BODY), M + TEXT_X, inner - TEXT_X)
            val sub = (t.tags + listOfNotNull(t.late?.let { "was due " + Slip.shortDate(it) })).joinToString(" · ")
            if (sub.isNotEmpty()) text(sub, mono(18f), M + TEXT_X, inner - TEXT_X)
            y = maxOf(y, top + BOX + 6f) + 12f
        }

        fun blankLine() {
            val top = y
            tickBox(top)
            line(M + TEXT_X, top + BOX, WIDTH - M - 4f, top + BOX, 2f)
            y = top + BOX + 18f
        }

        fun sudoku(p: String) {
            heading("Sudoku", "${p.count { it != '0' }} givens")
            gap(12f)
            val cell = 56f
            val size = cell * 9
            val x0 = (WIDTH - size) / 2
            val top = y
            for (i in 0..9) {
                val w = if (i % 3 == 0) 5f else 1.5f
                g.stroke = BasicStroke(w, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER)
                g.draw(java.awt.geom.Line2D.Float(x0 + i * cell, top, x0 + i * cell, top + size))
                g.draw(java.awt.geom.Line2D.Float(x0, top + i * cell, x0 + size, top + i * cell))
            }
            p.forEachIndexed { i, ch ->
                if (ch != '0') at(ch.toString(), mono(34f, true), x0 + (i % 9) * cell + cell / 2, top + (i / 9) * cell + cell / 2 + 12f, 1)
            }
            y = top + size + 14f
            text("A fresh one every day.", mono(18f), center = true)
        }

        fun footer(owner: String, now: LocalDateTime) {
            gap(10f); solid()
            text("Have a good one, $owner", head(40f), center = true)
            gap(6f)
            text(Slip.printedAt(now) + " · Flight Deck", mono(SMALL), center = true)
        }

        /** Weather line icons in an [s]-square box, like the phone's. */
        fun icon(name: String, x: Float, y0: Float, s: Float) {
            val saved = g.transform
            g.translate(x.toDouble(), y0.toDouble()); g.scale(s / 48.0, s / 48.0)
            g.stroke = BasicStroke(3.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            fun sun(cx: Float, cy: Float, r: Float) {
                g.draw(Ellipse2D.Float(cx - r, cy - r, 2 * r, 2 * r))
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0); val dx = Math.cos(a).toFloat(); val dy = Math.sin(a).toFloat()
                    g.draw(java.awt.geom.Line2D.Float(cx + dx * (r + 4), cy + dy * (r + 4), cx + dx * (r + 9), cy + dy * (r + 9)))
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
                "fog" -> listOf(14f, 24f, 34f).forEach { g.draw(java.awt.geom.Line2D.Float(6f, it, 42f, it)) }
                else -> {
                    cloud(if (name == "cloud") 4f else -2f)
                    when (name) {
                        "rain", "showers" -> listOf(16f, 24f, 32f).forEach { g.draw(java.awt.geom.Line2D.Float(it + 2f, 36f, it - 2f, 45f)) }
                        "storm" -> g.draw(Path2D.Float().apply { moveTo(26f, 33f); lineTo(20f, 41f); lineTo(27f, 41f); lineTo(22f, 48f) })
                        "snow" -> listOf(16f, 24f, 32f).forEach { g.fill(Ellipse2D.Float(it - 2.2f, 38.8f, 4.4f, 4.4f)) }
                    }
                }
            }
            g.transform = saved
        }
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
