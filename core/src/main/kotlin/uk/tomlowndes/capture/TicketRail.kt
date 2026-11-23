package uk.tomlowndes.capture

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/** A `flightdeck://print?note=…&task=…` link: print the open task in [note] whose line reads [task] (checkbox and 🆔 aside). */
data class PrintAsk(val note: String, val task: String)

/** One printed ticket: what goes on the paper. [id] is the task's `🆔`, [code] how it reads on the ticket. */
data class Ticket(
    val id: String, val code: String, val slot: String, val slotNote: String, val tag: String,
    val text: String, val source: String, val printed: LocalTime, val pattern: Int,
)

/**
 * Ticket Rail (ticket-rail-v1): tasks printed as tall kitchen-style tickets that hang on a rail by the desk.
 * Each printed task gets a Tasks-plugin id (`🆔 hom042`) on its line, so the QR on its ticket finds it again
 * wherever it lives in the vault. A task "on the rail" is simply an open task with one of these ids; nothing
 * else is stored.
 */
object TicketRail {
    /** Up to this many tickets in the morning run; the rest stay on the Daily Slip. */
    const val MORNING_MAX = 5
    const val SCHEME = "flightdeck"
    private const val CLEAR = "$SCHEME://clear/"
    private const val PRINT = "$SCHEME://print?"

    private val ID = Regex("🆔\\s*([\\w-]+)")
    private val ID_PIECE = Regex("\\s*🆔\\s*[\\w-]+")
    /** Ids this app hands out: three letters and three digits ("hom042"). Other ids are the Tasks plugin's own. */
    private val OURS = Regex("^([a-z]{3})(\\d{3})$")
    private val DM = DateTimeFormatter.ofPattern("d MMM", Locale.UK)

    /** The line's `🆔`, if it has one. */
    fun idOf(line: String): String? = ID.find(line)?.groupValues?.get(1)

    /** The id when it's one Ticket Rail made, else null. */
    fun railId(line: String): String? = idOf(line)?.lowercase()?.takeIf { OURS.matches(it) }

    /** [line] with `🆔 id` on the end (a `\r` stays last). An id already there is left alone. */
    fun withId(line: String, id: String): String {
        if (idOf(line) != null) return line
        val cr = line.endsWith("\r")
        return line.removeSuffix("\r").trimEnd() + " 🆔 $id" + if (cr) "\r" else ""
    }

    /** [line] without its `🆔` (a repeat's next occurrence is a new task, not the ticket on the rail). */
    fun withoutId(line: String): String = line.replace(ID_PIECE, "")

    /** Three letters for the code, from the task's first tag other than #task; "tsk" when it has none. */
    fun prefix(text: String): String {
        val tag = Tags.inText(text).firstOrNull { it != "#task" }?.removePrefix("#")?.substringBefore('/') ?: return "tsk"
        val letters = tag.filter { it in 'a'..'z' }
        return if (letters.length >= 3) letters.take(3) else (letters + "tsk").take(3)
    }

    /** The next free id for [prefix], counting every id already in the vault (open or ticked): 1..999, then round. */
    fun nextId(prefix: String, taken: Collection<String>): String {
        val used = taken.mapNotNull { OURS.matchEntire(it.lowercase()) }.filter { it.groupValues[1] == prefix }
            .map { it.groupValues[2].toInt() }.toSet()
        val start = (used.maxOrNull() ?: 0) % 999 + 1
        val n = (0 until 999).map { (start - 1 + it) % 999 + 1 }.firstOrNull { it !in used } ?: start
        return prefix + "%03d".format(n)
    }

    /** "hom042" as it reads on the ticket: "HOM 042". */
    fun code(id: String): String = OURS.matchEntire(id.lowercase())?.let { "${it.groupValues[1].uppercase()} ${it.groupValues[2]}" } ?: id.uppercase()

    fun link(id: String) = CLEAR + id

    /** The id in a scanned code or opened link, or null when it isn't a Ticket Rail code. */
    fun parseLink(s: String): String? {
        val t = s.trim()
        if (!t.startsWith(CLEAR, ignoreCase = true)) return null
        return t.substring(CLEAR.length).trim('/').lowercase().takeIf { OURS.matches(it) }
    }

    /**
     * A Print now link from another app on this PC (Next Step), or null when [s] isn't one. The task is named by its
     * line, since a task gets its id only when it's printed.
     */
    fun parsePrint(s: String): PrintAsk? {
        val t = s.trim()
        if (!t.startsWith(PRINT, ignoreCase = true)) return null
        val q = t.substring(PRINT.length).split("&").mapNotNull { kv ->
            val i = kv.indexOf('=').takeIf { it > 0 } ?: return@mapNotNull null
            kv.substring(0, i) to runCatching { java.net.URLDecoder.decode(kv.substring(i + 1), Charsets.UTF_8) }.getOrNull()
        }.toMap()
        val note = q["note"]?.trim()?.trim('/')?.takeIf { it.endsWith(".md") && ".." !in it.split("/") } ?: return null
        val task = q["task"]?.let(::body)?.takeIf { it.isNotEmpty() } ?: return null
        return PrintAsk(note, task)
    }

    /** A line without its checkbox, `🆔` or `✅` date, spaces evened out: how a Print now link names it. */
    private fun body(line: String): String =
        line.replace(Regex("^\\s*- \\[.]\\s*"), "").replace(ID_PIECE, "").replace(Regex("\\s*✅\\s*\\d{4}-\\d{2}-\\d{2}"), "")
            .replace(Regex("\\s+"), " ").trim()

    /** The open task [ask] names, wherever its note has it. */
    fun findForPrint(open: List<TaskItem>, ask: PrintAsk): TaskItem? =
        open.firstOrNull { it.path.equals(ask.note, ignoreCase = true) && body(it.raw) == ask.task }

    /** Open tasks already on the rail (printed, not yet cleared), oldest code first. */
    fun onRail(open: List<TaskItem>): List<TaskItem> =
        open.filter { railId(it.raw) != null }.distinctBy { railId(it.raw) }.sortedBy { railId(it.raw) }

    /** Tasks cleared on [day]: ticked tasks that carried a rail id. */
    fun clearedOn(done: List<TickedTask>, day: LocalDate): List<TickedTask> = done.filter { it.done == day && railId(it.raw) != null }

    /** Every id in use, so a new one never repeats an old ticket. */
    fun taken(open: List<TaskItem>, done: List<TickedTask>): Set<String> =
        (open.mapNotNull { railId(it.raw) } + done.mapNotNull { railId(it.raw) }).toSet()

    /** The open task with this id, wherever it lives. */
    fun find(open: List<TaskItem>, id: String): TaskItem? = open.firstOrNull { railId(it.raw) == id.lowercase() }

    /** A ticked task with this id, for "Already cleared". */
    fun findDone(done: List<TickedTask>, id: String): TickedTask? = done.firstOrNull { railId(it.raw) == id.lowercase() }

    /**
     * The morning run: overdue (oldest first), then due today, not already on the rail, at most [max].
     * Quick Task can come back from both the note and the vault scan, so each task counts once.
     */
    fun morningRun(open: List<TaskItem>, today: LocalDate, max: Int = MORNING_MAX): List<TaskItem> =
        open.distinctBy { it.raw }
            .filter { railId(it.raw) == null && it.due != null && !it.due.isAfter(today) }
            .sortedBy { it.due }
            .take(max)

    /** The slot words: "TODAY", "LATE 2D" (with "WAS WED"), a weekday, or "ANY TIME" for no due date. */
    fun slot(due: LocalDate?, today: LocalDate): Pair<String, String> {
        if (due == null) return "ANY TIME" to ""
        val days = ChronoUnit.DAYS.between(due, today)
        val dow = { d: LocalDate -> d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.UK).uppercase(Locale.UK) }
        return when {
            days == 0L -> "TODAY" to "${dow(due)} ${due.format(DM).uppercase(Locale.UK)}"
            days > 0L -> "LATE ${days}D" to "WAS ${dow(due)}"
            days == -1L -> "TOMORROW" to dow(due)
            days > -7L -> dow(due) to due.format(DM).uppercase(Locale.UK)
            else -> due.format(DM).uppercase(Locale.UK) to ""
        }
    }

    /** "01 - Inbox/Quick Task.md" as "Inbox / Quick Task": where the task came from, small on the ticket. */
    fun source(path: String): String =
        path.removeSuffix(".md").split("/").takeLast(2).joinToString(" / ") { it.replace(Regex("^\\d+\\s*-\\s*"), "").replace(Regex("^[^\\p{L}\\p{N}]+"), "") }

    /** What [t] prints as, with [id] already chosen. [pattern] picks the tag's hatch (thermal paper is black only). */
    fun ticket(t: TaskItem, id: String, today: LocalDate, now: LocalTime): Ticket {
        val (slot, note) = slot(t.due, today)
        val tag = Tags.inText(t.text).firstOrNull { it != "#task" }?.removePrefix("#")?.uppercase(Locale.UK) ?: ""
        return Ticket(
            id, code(id), slot, note, tag, Tags.strip(t.text), source(t.path), now,
            pattern = Math.floorMod(id.take(3).hashCode(), 3),
        )
    }

    /** The QR for [id] as rows of dark (true) modules, with no quiet zone. */
    fun qr(id: String): Array<BooleanArray> {
        val m = QRCodeWriter().encode(
            link(id), BarcodeFormat.QR_CODE, 0, 0,
            mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
        )
        return Array(m.height) { y -> BooleanArray(m.width) { x -> m.get(x, y) } }
    }
}
