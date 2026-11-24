package uk.tomlowndes.capture.desktop

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import uk.tomlowndes.capture.PrintAsk
import uk.tomlowndes.capture.Ticket
import uk.tomlowndes.capture.TicketRail
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.RenderingHints
import java.awt.geom.Line2D
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * A Ticket Rail ticket drawn with Java2D: the phone's TicketRender, line for line, so a ticket printed from the PC
 * looks the same on the rail. 576 dots across, as long as its job (ticket-adaptive-v1) like the phone's.
 */
object DesktopTicket {
    private const val W = DesktopSlip.WIDTH
    private const val M = 12f
    /** The shortest ticket: about 60 mm, the same as the phone's. */
    const val MIN_HEIGHT = 720
    private const val FOOT = 22f + 240f + 24f
    private const val STEP_ROW = 46f
    private const val GRIP = 84f
    private const val QR = 240f
    private val HM = DateTimeFormatter.ofPattern("HH:mm")

    private val fonts by lazy { GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toSet() }
    private fun mono(size: Float, bold: Boolean = false): Font {
        val name = listOf("Consolas", "Cascadia Mono", "Courier New").firstOrNull { it in fonts } ?: Font.MONOSPACED
        return Font(name, if (bold) Font.BOLD else Font.PLAIN, 1).deriveFont(size)
    }

    /** How long [t] prints, in dots: its content, or [MIN_HEIGHT] when that's shorter. */
    fun heightOf(t: Ticket): Int {
        val g = BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).createGraphics()
        val end = body(g, t)
        g.dispose()
        return maxOf(MIN_HEIGHT, (end + 20f + FOOT).toInt())
    }

    fun draw(t: Ticket): BufferedImage {
        val h = heightOf(t)
        val img = BufferedImage(W, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = Color.WHITE; g.fillRect(0, 0, W, h)
        g.color = Color.BLACK
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        body(g, t)
        foot(g, t, h.toFloat())
        g.dispose()
        return img
    }

    /** Everything above the footer; returns the y under it. Titles and steps print in full. */
    private fun body(g: Graphics2D, t: Ticket): Float {
        g.color = Color.BLACK
        var y = GRIP
        hatch(g, t.pattern, y, y + 18f)
        y += 18f + 22f + 92f
        g.font = mono(108f, true)
        g.drawString(t.code, M, y)
        y += 20f
        val slot = listOf(t.slot, t.slotNote, t.tag).filter { it.isNotBlank() }.joinToString(" · ")
        y = text(g, slot, mono(30f), y, 1) + 14f
        // Printed ahead of its day (ticket-rail-future-v1): white words on a black bar.
        if (t.early.isNotBlank()) {
            rect(g, M, y, W - M, y + 48f)
            g.font = mono(30f, true)
            g.color = Color.WHITE
            g.drawString(t.early, (W - g.fontMetrics.stringWidth(t.early)) / 2f, y + 35f)
            g.color = Color.BLACK
            y += 62f
        }
        rect(g, M, y, W - M, y + 4f)
        y += 18f
        y = text(g, "TASK", mono(22f), y, 1) + 4f
        val hasSteps = t.steps.isNotEmpty()
        y = text(g, t.text, mono(if (hasSteps) 44f else 50f, true), y, Int.MAX_VALUE) + 10f
        y = text(g, t.source, mono(26f), y, 1) + 16f
        if (hasSteps) {
            rect(g, M, y, W - M, y + 3f)
            y += 14f
            t.steps.forEach { step ->
                g.stroke = BasicStroke(4f)
                g.draw(Rectangle2D.Float(M + 2f, y + 6f, 28f, 28f))
                val end = text(g, step, mono(32f), y + 4f, Int.MAX_VALUE, x = M + 44f)
                y = maxOf(y + STEP_ROW, end + 8f)
            }
        }
        return y
    }

    /** Print time and a CLR box on the left, the QR on the right, ending at [bottom]. */
    private fun foot(g: Graphics2D, t: Ticket, bottom: Float) {
        val qrTop = bottom - 24f - QR
        rect(g, M, qrTop - 22f, W - M, qrTop - 18f)
        qr(g, TicketRail.qr(t.id), W - M - QR, qrTop, QR)
        g.font = mono(28f)
        g.drawString("PRT ${t.printedAt.ifBlank { t.printed.format(HM) }}", M, qrTop + 30f)
        g.stroke = BasicStroke(6f)
        g.draw(Rectangle2D.Float(M + 3f, qrTop + 56f, 100f, 100f))
        g.drawString("CLR", M, qrTop + 196f)
    }

    /** Wrapped text from [top], at most [max] lines (the last one cut with "…"); returns the y under it. */
    private fun text(g: Graphics2D, s: String, f: Font, top: Float, max: Int, x: Float = M): Float {
        g.font = f
        val fm = g.fontMetrics
        val room = W - M - x
        val lines = wrap(s, room) { fm.stringWidth(it).toFloat() }
        val shown = lines.take(max).toMutableList()
        if (lines.size > max) {
            var last = shown.last()
            while (last.isNotEmpty() && fm.stringWidth("$last…") > room) last = last.dropLast(1)
            shown[shown.lastIndex] = last.trimEnd() + "…"
        }
        val lh = fm.height * 1.05f
        var y = top
        shown.forEach { l -> g.drawString(l, x, y + fm.ascent); y += lh }
        return y
    }

    /** Words into lines no wider than [w]; a word too long for a line is broken. */
    internal fun wrap(s: String, w: Float, width: (String) -> Float): List<String> {
        val out = mutableListOf<String>()
        var line = ""
        s.split(Regex("\\s+")).filter { it.isNotEmpty() }.forEach { word ->
            val t = if (line.isEmpty()) word else "$line $word"
            if (width(t) <= w) { line = t; return@forEach }
            if (line.isNotEmpty()) out += line
            var rest = word
            while (width(rest) > w && rest.length > 1) {
                var n = rest.length
                while (n > 1 && width(rest.take(n)) > w) n--
                out += rest.take(n); rest = rest.drop(n)
            }
            line = rest
        }
        if (line.isNotEmpty()) out += line
        return out
    }

    private fun rect(g: Graphics2D, x1: Float, y1: Float, x2: Float, y2: Float) = g.fill(Rectangle2D.Float(x1, y1, x2 - x1, y2 - y1))

    private fun hatch(g: Graphics2D, pattern: Int, y0: Float, y1: Float) {
        when (pattern) {
            1 -> rect(g, M, y0, W - M, y1)
            2 -> { var x = M; while (x < W - M) { rect(g, x, y0, minOf(x + 6f, W - M), y1); x += 12f } }
            else -> {
                val clip = g.clip
                g.clip(Rectangle2D.Float(M, y0, W - 2 * M, y1 - y0))
                g.stroke = BasicStroke(5f)
                var x = M - (y1 - y0)
                while (x < W - M) { g.draw(Line2D.Float(x, y1, x + (y1 - y0), y0)); x += 12f }
                g.clip = clip
            }
        }
    }

    private fun qr(g: Graphics2D, m: Array<BooleanArray>, x: Float, y: Float, size: Float) {
        val cell = size / m.size
        for (r in m.indices) for (k in m[r].indices) if (m[r][k]) {
            g.fill(Rectangle2D.Float(x + k * cell, y + r * cell, cell + 0.5f, cell + 0.5f))
        }
    }
}

/**
 * Print now (next-step-print-v1): another app on this PC (Next Step) opens `flightdeck://print?note=…&task=…`.
 * Flight Deck finds that open task, prints its ticket on the pocket printer, then gives the line its rail id,
 * the same as the phone's Ticket Rail does. Nothing is written until it's on paper, so a failed print leaves
 * the task for the morning run. Runs off the UI thread; says how it went in a Windows notification.
 */
object PrintNow {
    sealed interface Result {
        data class Printed(val code: String, val text: String) : Result
        data class Failed(val title: String, val body: String) : Result
    }

    @Synchronized fun handle(state: AppState, link: String) {
        val ask = TicketRail.parsePrint(link) ?: return
        val r = run(state, ask)
        java.awt.EventQueue.invokeLater {
            when (r) {
                is Result.Printed -> { state.notify("Printed ${r.code}", r.text); state.changed() }
                is Result.Failed -> state.notify(r.title, r.body)
            }
        }
    }

    fun run(state: AppState, ask: PrintAsk, today: LocalDate = LocalDate.now(), now: LocalTime = LocalTime.now()): Result {
        val writer = state.writer() ?: return Result.Failed("Couldn't print", "Choose your Obsidian vault in Flight Deck first.")
        val port = state.prefs.printerAddress
            ?: return Result.Failed("Couldn't print", "Pick your printer in Daily Slip first, then Print now again.")
        val lists = runCatching { writer.listTaskLists() }.getOrElse { return Result.Failed("Couldn't print", "Couldn't read the vault: ${it.message}") }
        val t = TicketRail.findForPrint(lists.open, ask)
            ?: return Result.Failed("Couldn't find that task", "It isn't open in ${ask.note} any more. Was it ticked or edited?")
        // Printed before: print again with the same id, so its old ticket and the new one match.
        val id = TicketRail.railId(t.raw) ?: TicketRail.nextId(TicketRail.prefix(t.text), TicketRail.taken(lists.open, lists.done))
        val ticket = TicketRail.ticket(t, id, today, now)
        val sent = runCatching { DesktopPrinter.send(port, DesktopSlip.job(DesktopTicket.draw(ticket))) }
        if (sent.isFailure) {
            return Result.Failed(
                "Couldn't reach the printer",
                "Is it on? \"${ticket.text}\" stays on your list and prints with tomorrow's morning run.",
            )
        }
        if (TicketRail.railId(t.raw) == null) runCatching { writer.giveTicketId(t, id) }
        return Result.Printed(ticket.code, ticket.text)
    }
}

/**
 * Makes `flightdeck://` links open this app on Windows (HKCU\Software\Classes, no admin needed). Run at each start
 * from the installed app, so it follows the exe when it moves. A link opened while Flight Deck runs reaches the
 * running one through the single-instance socket.
 */
object LinkHandler {
    private const val KEY = "Software\\Classes\\${TicketRail.SCHEME}"
    private val exe: String? get() = System.getProperty("jpackage.app-path")

    fun register() {
        val path = exe ?: return
        runCatching {
            val hk = WinReg.HKEY_CURRENT_USER
            val command = "\"$path\" \"%1\""
            val cmdKey = "$KEY\\shell\\open\\command"
            if (Advapi32Util.registryKeyExists(hk, cmdKey) &&
                Advapi32Util.registryGetStringValue(hk, cmdKey, "") == command
            ) return
            Advapi32Util.registryCreateKey(hk, cmdKey)
            Advapi32Util.registrySetStringValue(hk, KEY, "", "URL:Flight Deck")
            Advapi32Util.registrySetStringValue(hk, KEY, "URL Protocol", "")
            Advapi32Util.registrySetStringValue(hk, cmdKey, "", command)
        }
    }

    /** The `flightdeck:` link among the launch arguments, if Windows started the app for one. */
    fun linkIn(args: Array<String>): String? = args.firstOrNull { it.startsWith("${TicketRail.SCHEME}:", ignoreCase = true) }
}
