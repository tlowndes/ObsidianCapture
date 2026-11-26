package uk.tomlowndes.capture

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** A slip's typefaces: the condensed heading face, the monospace body, and the serif used for quotes. */
enum class SlipFace { HEAD, MONO, SERIF }

/** A font on the slip. [letterSpacing] is in ems; a platform that can't space letters ignores it. */
data class SlipFont(
    val size: Float,
    val face: SlipFace = SlipFace.MONO,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val letterSpacing: Float = 0f,
    val strike: Boolean = false,
)

enum class SlipCap { BUTT, ROUND, SQUARE }

/**
 * What a slip is drawn on: Android's Canvas or Java2D. Each app wraps and measures text its own way (so line
 * breaks match what it printed before); everything else about the layout lives in [SlipLayout]. Black ink on white.
 */
interface SlipCanvas {
    /** Wraps [s] to [w] at ([x], [top]), centred when [center], lines [spacing] apart. Returns its height. */
    fun block(s: String, f: SlipFont, x: Float, top: Float, w: Float, center: Boolean, spacing: Float): Float

    /** One line with its baseline at [base]: left of [x], centred on it, or right of it. White text when [white]. */
    fun say(s: String, f: SlipFont, x: Float, base: Float, align: Align = Align.LEFT, white: Boolean = false)

    fun width(s: String, f: SlipFont): Float
    fun fill(x1: Float, y1: Float, x2: Float, y2: Float)
    fun box(x1: Float, y1: Float, x2: Float, y2: Float, w: Float)
    fun rule(x1: Float, y1: Float, x2: Float, y2: Float, w: Float, dash: FloatArray? = null, cap: SlipCap = SlipCap.ROUND)
    fun dot(cx: Float, cy: Float, r: Float)

    /** A weather line icon ([Slip.icon] names) in an [s]-square box at ([x], [y]). */
    fun icon(name: String, x: Float, y: Float, s: Float)

    enum class Align { LEFT, CENTER, RIGHT }
}

/**
 * The till-roll slips (Daily Slip, Week slip, lists, Bills, Print summary) laid out once for both apps, [WIDTH] dots
 * across: the Phomemo M02 Pro's 48 mm head at 300 dpi. Sizes are in printer dots (about 12 per mm). Each draw
 * returns the height to crop the page to.
 */
object SlipLayout {
    const val WIDTH = 576
    const val MAX_HEIGHT = 7000

    /** How one screen's slips differ: the phone's weekday title is a touch bigger. */
    data class Look(val dayTitle: Float)

    fun daily(c: SlipCanvas, look: Look, large: Boolean, s: SlipContent, owner: String, now: LocalDateTime): Int = page(c, look, large) { p ->
        p.header(s)
        val sections = buildList<() -> Unit> {
            s.brief?.let { b -> add { p.brief(b) } }
            s.weather?.let { w -> add { p.weather(w) } }
            if (s.show.calendar) add { p.calendar(s) }
            if (s.dontForget.isNotEmpty()) add { p.dontForget(s.dontForget) }
            if (s.show.todo) add { p.todo(s) }
            s.sudoku?.let { su -> add { p.sudoku(su) } }
            s.quote?.let { q -> add { p.quote(q) } }
        }
        sections.forEachIndexed { i, f -> if (i > 0) p.dashed(); f() }
        p.footer(owner, now)
    }

    /** The Week slip (slip-week-v2): the week's days one under another, with a tear line between them. */
    fun week(c: SlipCanvas, look: Look, large: Boolean, w: WeekContent, now: LocalDateTime): Int = page(c, look, large) { p ->
        p.weekHeader(w)
        w.days.forEachIndexed { i, d -> if (i > 0) p.dashed(); p.weekDay(d, w.show) }
        p.printedLine(now)
    }

    /** A list (shopping list slip) or shared text (Print from share or clipboard), printables-v1. */
    fun list(c: SlipCanvas, look: Look, large: Boolean, d: ListDoc, now: LocalDateTime): Int = page(c, look, large) { p ->
        p.docHeader(d.title, d.sub)
        d.body?.let { p.text(it, p.mono(p.BODY), spacing = 1.15f) }
        d.groups.forEach { g ->
            g.heading?.let { p.gap(10f); p.text(it, p.mono(p.SMALL, true)); p.gap(6f) }
            g.items.forEach { p.reminder(SlipReminder(it, "")) }
        }
        p.printedLine(now)
    }

    /** The Bills slip (printables-v1): left to pay, then the month's bills to pay and paid. */
    fun bills(c: SlipCanvas, look: Look, large: Boolean, b: BillsDoc, now: LocalDateTime): Int = page(c, look, large) { p ->
        p.bills(b)
        p.printedLine(now)
    }

    /** A Print summary slip (print-summary-v1): title, where it came from, then the points or paragraph. */
    fun summary(c: SlipCanvas, look: Look, large: Boolean, d: Summary.Doc, now: LocalDateTime): Int = page(c, look, large) { p ->
        p.summary(d)
        p.printedLine(now)
    }

    private fun page(c: SlipCanvas, look: Look, large: Boolean, draw: (Pen) -> Unit): Int {
        val p = Pen(c, look, large)
        draw(p)
        return (p.y + 40).toInt().coerceAtMost(MAX_HEIGHT)
    }

    /** Draws top to bottom; [y] is where the next thing goes. */
    private class Pen(val c: SlipCanvas, val look: Look, large: Boolean) {
        private val M = 6f                 // side margin
        private val scale = if (large) 1.25f else 1f
        val BODY = 27f * scale
        val SMALL = 22f * scale
        /** Tick box size and where the text after it starts (Tom asked for bigger boxes, 2026-11-11). */
        val BOX = 42f * scale
        val TEXT_X = 60f * scale
        var y = 28f
        val inner = WIDTH - 2 * M

        fun mono(size: Float, bold: Boolean = false) = SlipFont(size, SlipFace.MONO, bold)
        fun head(size: Float, spacing: Float = 0f) = SlipFont(size, SlipFace.HEAD, bold = true, letterSpacing = spacing)

        fun gap(h: Float) { y += h }

        /** Wrapped text at [x], [w] wide; moves [y] down past it. */
        fun text(s: String, f: SlipFont, x: Float = M, w: Float = inner, center: Boolean = false, spacing: Float = 1.05f): Float {
            val h = c.block(s, f, x, y, w, center, spacing)
            y += h
            return h
        }

        /** Text that doesn't move [y]; returns its height. */
        fun textAt(s: String, f: SlipFont, x: Float, top: Float, w: Float): Float = c.block(s, f, x, top, w, false, 1.05f)

        private fun at(s: String, f: SlipFont, x: Float, base: Float, align: SlipCanvas.Align = SlipCanvas.Align.LEFT, white: Boolean = false) =
            c.say(s, f, x, base, align, white)
        private fun mid(s: String, f: SlipFont, x: Float, base: Float, white: Boolean = false) = at(s, f, x, base, SlipCanvas.Align.CENTER, white)

        fun solid(w: Float = 5f) { gap(10f); c.fill(M, y, WIDTH - M, y + w); y += w; gap(18f) }

        fun dashed() { gap(22f); c.rule(M, y, WIDTH - M, y, 3f, floatArrayOf(12f, 8f), SlipCap.BUTT); gap(26f) }

        fun printedLine(now: LocalDateTime) {
            gap(10f); solid()
            text(Slip.printedAt(now) + " · Flight Deck", mono(SMALL), center = true)
        }

        /** "WEATHER ───────── Today". */
        fun heading(title: String, right: String? = null) {
            val hf = head(40f)
            val top = y
            val base = top + 34f
            val t = title.uppercase(Locale.UK)
            at(t, hf, M, base)
            val tw = c.width(t, hf)
            val rf = mono(SMALL)
            val rw = right?.let { c.width(it, rf) } ?: 0f
            right?.let { at(it, rf, WIDTH - M - rw, base - 4f) }
            val x1 = M + tw + 14f
            val x2 = WIDTH - M - rw - (if (right != null) 14f else 0f)
            if (x2 > x1 + 20) c.fill(x1, base - 15f, x2, base - 10f)
            y = top + 48f
        }

        /** A black label with white letters, e.g. "OVERDUE". Returns its width. */
        fun label(s: String, x: Float, top: Float, size: Float = SMALL): Float {
            val f = mono(size, true)
            val w = c.width(s, f) + 14f
            c.fill(x, top, x + w, top + size + 8f)
            at(s, f, x + 7f, top + size, white = true)
            return w
        }

        fun header(s: SlipContent) {
            val day = s.date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.UK).uppercase(Locale.UK)
            text(day, head(look.dayTitle, -0.01f), center = true, spacing = 0.9f)
            gap(4f)
            text(s.date.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.UK)).uppercase(Locale.UK), head(44f, 0.06f), center = true)
            gap(8f)
            val left = s.daysInYear - s.dayOfYear
            text("Week ${s.week}   Day ${s.dayOfYear}/${s.daysInYear}   $left days to go", mono(SMALL), center = true)
            if (s.ahead) {
                gap(12f)
                val w = c.width(Slip.AHEAD_STAMP, mono(SMALL, true)) + 14f
                label(Slip.AHEAD_STAMP, (WIDTH - w) / 2, y)
                y += SMALL + 8f
            }
            solid()
        }

        /** A tick box to fill in with a pen: [BOX] dots square (about 3.5 mm), its top at [top]. */
        fun tickBox(top: Float) = c.box(M + 3f, top + 2f, M + 1f + BOX, top + BOX, 4f)

        fun brief(t: String) { heading("In brief"); gap(4f); text(t, mono(BODY), spacing = 1.15f) }

        /** From your reading (home-life-v1): one passage in italic and where it's from. */
        fun quote(q: Pair<String, String>) {
            heading("From your reading"); gap(4f)
            text("“${q.first}”", SlipFont(BODY, SlipFace.SERIF, italic = true), spacing = 1.15f)
            gap(4f); text("— ${q.second}", mono(SMALL))
        }

        fun summary(d: Summary.Doc) {
            text(d.title, head(52f), spacing = 0.95f)
            gap(4f)
            text(d.subtitle, mono(SMALL))
            dashed()
            if (d.bullets.isEmpty()) text(d.text, mono(BODY), spacing = 1.15f)
            else d.bullets.forEach { b ->
                val top = y
                if (d.ticks) tickBox(top) else c.dot(M + 14f, top + 17f, 6f)
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
            at(hiLo, big, M, top + 84f)
            val bw = c.width(hiLo, big)
            val lo = "/ ${w.low}°"
            at(lo, head(40f), M + bw + 8f, top + 84f)
            val lx = M + bw + 8f + c.width(lo, head(40f)) + 18f
            val h = textAt(w.headline, mono(BODY, true), lx, top + 8f, WIDTH - M - lx)
            y = top + maxOf(100f, h + 16f)
            text(w.detail, mono(BODY))
            w.tip?.let { gap(6f); text(it, mono(SMALL)) }
            if (w.parts.isNotEmpty()) { gap(14f); parts(w.parts) }
            w.tonight?.let { gap(10f); text(it, mono(SMALL)) }
        }

        /** Morning / Afternoon / Evening boxes. */
        fun parts(ps: List<DayPart>) {
            val top = y
            val h = 150f
            val cw = inner / ps.size
            c.box(M + 2f, top, WIDTH - M - 2f, top + h, 4f)
            ps.forEachIndexed { i, part ->
                val x = M + i * cw
                if (i > 0) c.rule(x, top + 8f, x, top + h - 8f, 2f, floatArrayOf(3f, 5f))
                val m = x + cw / 2
                mid(part.name, mono(SMALL), m, top + 30f)
                c.icon(Slip.icon(part.icon), m - 24f, top + 40f, 48f)
                mid("${part.temp}°", mono(BODY + 2, true), m, top + 120f)
                mid(if (part.rain >= 10) "${part.rain}% rain" else "dry", mono(18f), m, top + 142f)
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
                c.box(M + 2f, top, WIDTH - M - 2f, y, 3f)
                gap(10f)
            }
            val tw = 108f
            timed.forEach { e ->
                val top = y
                at(e.start ?: "", mono(BODY, true), M, top + BODY)
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
            listOf("M", "T", "W", "T", "F", "S", "S").forEachIndexed { i, l -> mid(l, mono(SMALL, true), M + cw * i + cw / 2, y + SMALL) }
            y += SMALL + 8f
            c.fill(M, y, WIDTH - M, y + 3f)
            y += 10f
            val lead = d.withDayOfMonth(1).dayOfWeek.value - 1
            val rows = (lead + d.lengthOfMonth() + 6) / 7
            val rh = 38f
            for (day in 1..d.lengthOfMonth()) {
                val cell = lead + day - 1
                val cx = M + cw * (cell % 7) + cw / 2
                val top = y + (cell / 7) * rh
                if (day == d.dayOfMonth) {
                    c.fill(cx - cw / 2 + 6f, top, cx + cw / 2 - 6f, top + rh - 4f)
                    mid("$day", mono(SMALL + 2, true), cx, top + 27f, white = true)
                } else {
                    mid("$day", mono(SMALL + 2, day >= d.dayOfMonth), cx, top + 27f)
                    if (day < d.dayOfMonth) c.rule(cx - 14f, top + 30f, cx + 14f, top + 6f, 2f)
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
            val nw = if (r.note.isEmpty()) 0f else c.width(r.note, nf)
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
            at("Left to pay", lf, M, top + BODY)
            val left = TaskViews.money(b.left)
            at(left, lf, WIDTH - M - c.width(left, lf), top + BODY)
            y = top + BODY + 10f
            val sf = mono(SMALL)
            val total = "of " + TaskViews.money(b.left + b.paidTotal)
            at("Paid " + TaskViews.money(b.paidTotal), sf, M, y + SMALL)
            at(total, sf, WIDTH - M - c.width(total, sf), y + SMALL)
            y += SMALL + 18f
            if (b.toPay.isNotEmpty()) {
                text("TO PAY", mono(SMALL, true)); gap(6f)
                b.toPay.forEach { reminder(SlipReminder(it.name, listOfNotNull(b.day(it).ifEmpty { null }, it.amount?.let { a -> TaskViews.money(a) }).joinToString(" · "))) }
            } else text("Nothing left to pay.", mono(BODY))
            if (b.paid.isNotEmpty()) {
                gap(8f); text("PAID", mono(SMALL, true)); gap(6f)
                b.paid.forEach { bill ->
                    val t = y
                    c.fill(M + 3f, t + 2f, M + 1f + BOX, t + BOX)
                    val note = listOfNotNull(b.day(bill).ifEmpty { null }, bill.amount?.let { a -> TaskViews.money(a) }).joinToString(" · ")
                    val nw = c.width(note, sf)
                    at(note, sf, WIDTH - M - nw, t + BODY)
                    text(bill.name, mono(BODY).copy(strike = true), M + TEXT_X, inner - TEXT_X - nw - 16f)
                    y = maxOf(y, t + BOX + 6f) + 12f
                }
            }
        }

        fun weekHeader(w: WeekContent) {
            text(w.title, head(96f), center = true, spacing = 0.9f)
            gap(4f)
            text(w.range, head(36f, 0.04f), center = true)
            gap(8f)
            text(w.sub, mono(SMALL), center = true)
            solid()
        }

        /** One day block: black label for today, outlined for the rest, then events, to-dos, bills and a line to write on. */
        fun weekDay(d: WeekDay, o: WeekOptions) {
            gap(6f)
            val top = y
            val lw = if (d.today) label(d.label, M, top, BODY) else {
                val lf = mono(BODY, true)
                val w = c.width(d.label, lf) + 14f
                c.box(M + 1.5f, top + 1.5f, M + w, top + BODY + 8f, 3f)
                at(d.label, lf, M + 7f, top + BODY)
                w
            }
            val dn = "${d.date.dayOfMonth}"
            val df = mono(BODY + 4, true)
            at(dn, df, M + lw + 14f, top + BODY + 2f)
            if (d.today) at("today", mono(SMALL), M + lw + 28f + c.width(dn, df), top + BODY)
            y = top + BODY + 30f
            val tw = 108f
            d.events.forEach { e ->
                val t = y
                at(e.start ?: "all day", mono(if (e.start == null) SMALL else BODY, true), M, t + BODY)
                y = t
                text(e.title, mono(BODY), M + tw, inner - tw)
                gap(10f)
            }
            if (d.events.isNotEmpty() && (d.tasks.isNotEmpty() || d.bills.isNotEmpty())) gap(4f)
            d.tasks.forEach { task(it) }
            d.bills.forEach { reminder(it) }
            if (d.empty) { text("Nothing planned.", mono(BODY).copy(italic = true)); gap(6f) }
            if (o.writeLine) { gap(30f); c.rule(M, y, WIDTH - M, y, 2f); gap(6f) }
        }

        fun dayLabel(l: String, d: LocalDate?) {
            val top = y
            val w = label(l, M, top)
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
            c.rule(M + TEXT_X, top + BOX, WIDTH - M - 4f, top + BOX, 2f)
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
                c.rule(x0 + i * cell, top, x0 + i * cell, top + size, w, cap = SlipCap.SQUARE)
                c.rule(x0, top + i * cell, x0 + size, top + i * cell, w, cap = SlipCap.SQUARE)
            }
            p.forEachIndexed { i, ch ->
                if (ch != '0') mid(ch.toString(), mono(34f, true), x0 + (i % 9) * cell + cell / 2, top + (i / 9) * cell + cell / 2 + 12f)
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
    }
}
