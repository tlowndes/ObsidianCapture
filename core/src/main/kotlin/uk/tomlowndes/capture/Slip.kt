package uk.tomlowndes.capture

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.IsoFields
import java.util.Locale

/** What goes on the Daily Slip. Saved on the phone, so the next print uses the same choices. */
data class SlipOptions(
    val weather: Boolean = true,
    val calendar: Boolean = true,
    val month: Boolean = true,
    val todo: Boolean = true,
    val tomorrow: Boolean = true,
    val blank: Boolean = true,
    val sudoku: Boolean = true,
    val tomorrowEvents: Boolean = true,
    /** "In brief": a few lines from Jarvis about the day, at the top (print-summary-v1). */
    val brief: Boolean = true,
)

/** One to-do on the slip. [late] is the date it was due when that's before today. */
data class SlipTask(val text: String, val tags: List<String>, val late: LocalDate? = null)

/** One timed event on the slip, or an all-day one when [start] is null. */
data class SlipEvent(val title: String, val start: String?, val end: String?, val place: String?)

/** Everything the printout shows, worked out from the vault, the calendar cache and the forecast. */
data class SlipContent(
    val date: LocalDate,
    val week: Int, val dayOfYear: Int, val daysInYear: Int,
    val weather: WeatherSummary?,
    val allDay: List<SlipEvent>, val events: List<SlipEvent>,
    val overdue: List<SlipTask>, val today: List<SlipTask>, val tomorrow: List<SlipTask>,
    val sudoku: String?,
    /** The choices it was built with, for the parts that aren't lists (month grid, blank lines). */
    val show: SlipOptions = SlipOptions(),
    /** Tomorrow's calendar, shown under today's. */
    val nextAllDay: List<SlipEvent> = emptyList(), val nextEvents: List<SlipEvent> = emptyList(),
    /** Jarvis's In brief lines, filled in by the app after [Slip.build] when [SlipOptions.brief] is on. */
    val brief: String? = null,
)

/** The Daily Slip: a till-roll printout of today's weather, calendar and to-dos, for a pocket thermal printer. */
object Slip {
    private val HM = DateTimeFormatter.ofPattern("HH:mm")
    private val SHORT = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)

    /** Undated tasks only appear when there are fewer dated ones than this, so the slip stays short. */
    const val UNDATED_FILL = 6

    fun build(
        date: LocalDate, all: List<TaskItem>, events: List<CalEvent>, weather: WeatherSummary?, o: SlipOptions,
    ): SlipContent {
        // Quick Task can come back from both the note and the vault scan: keep one of each.
        val tasks = all.distinctBy { Tags.strip(it.text).lowercase() to it.due }
        fun day(d: LocalDate): Pair<List<SlipEvent>, List<SlipEvent>> {
            val on = events.filter { it.on(d) }
            return on.filter { it.allDay }.map { SlipEvent(it.title, null, null, it.location) } to
                on.filter { !it.allDay }.sortedBy { it.start }.map {
                    SlipEvent(it.title, if (it.start.toLocalDate() == d) it.start.format(HM) else "…", it.end.format(HM), it.location)
                }
        }
        val (allDay, timed) = day(date)
        val (nextAllDay, nextTimed) = if (o.calendar && o.tomorrowEvents) day(date.plusDays(1)) else emptyList<SlipEvent>() to emptyList()
        fun slip(t: TaskItem, late: Boolean = false) = SlipTask(
            Tags.strip(t.text), Tags.inText(t.text).filter { it != "#task" }, if (late) t.due else null
        )
        val overdue = tasks.filter { it.due != null && it.due.isBefore(date) }.sortedBy { it.due }.map { slip(it, true) }
        val dueToday = tasks.filter { it.due == date }
        val undated = if (overdue.size + dueToday.size < UNDATED_FILL) tasks.filter { it.due == null }.take(UNDATED_FILL - overdue.size - dueToday.size) else emptyList()
        val tomorrow = tasks.filter { it.due == date.plusDays(1) }.map { slip(it) }
        return SlipContent(
            date, date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR), date.dayOfYear, date.lengthOfYear(),
            weather.takeIf { o.weather },
            if (o.calendar) allDay else emptyList(), if (o.calendar) timed else emptyList(),
            if (o.todo) overdue else emptyList(), if (o.todo) (dueToday + undated).map { slip(it) } else emptyList(),
            if (o.todo && o.tomorrow) tomorrow else emptyList(),
            if (o.sudoku) Sudoku.forDay(date) else null,
            o,
            nextAllDay, nextTimed,
        )
    }

    fun shortDate(d: LocalDate): String = d.format(SHORT)

    /** "Printed 07:02". */
    fun printedAt(now: LocalDateTime): String = "Printed " + now.format(HM)

    /** Weather emoji from [Weather] to the slip's line icons. */
    fun icon(emoji: String): String = when (emoji) {
        "☀️" -> "sun"
        "🌙" -> "moon"
        "⛅" -> "part"
        "🌦️" -> "showers"
        "🌧️" -> "rain"
        "⛈️" -> "storm"
        "🌨️" -> "snow"
        "🌫️" -> "fog"
        else -> "cloud"
    }
}

/** A sudoku a day: a few hand-checked puzzles, picked by the date so reprints match. 0 is a blank. */
object Sudoku {
    val PUZZLES = listOf(
        "530070000600195000098000060800060003400803001700020006060000280000419005000080079",
        "003020600900305001001806400008102900700000008006708200002609500800203009005010300",
        "200080300060070084030500209000105408000000000402706000301007040720040060004010003",
        "000000907000420180000705026100904000050000040000507009920108000034059000507000000",
        "030050040008010500460000012070502080000603000040109030250000098001020600080060020",
        "020810740700003100090002805009040087400208003160030200302700060005600008076051090",
        "100920000524010000000000070050008102000000000402700090060000000000030945000071006",
    )

    fun forDay(d: LocalDate): String = PUZZLES[(d.toEpochDay() % PUZZLES.size).toInt()]
}

/** ESC/POS raster printing (GS v 0), which Phomemo's M02 Pro and most receipt printers take. */
object EscPos {
    /** Rows per GS v 0 block: small enough for a pocket printer's buffer. */
    const val BAND = 128

    /**
     * Turns grey levels (0 black..255 white, row by row, [width] wide) into 1-bit rows, MSB first.
     * Text is a plain threshold, which keeps letters crisp; [dither] spreads the error (Floyd–Steinberg) for photos.
     */
    fun bits(grey: IntArray, width: Int, height: Int, dither: Boolean = false, threshold: Int = 150): ByteArray {
        val bpr = (width + 7) / 8
        val out = ByteArray(bpr * height)
        val err = FloatArray(grey.size) { grey[it].toFloat() }
        for (y in 0 until height) for (x in 0 until width) {
            val i = y * width + x
            val v = err[i]
            val black = v < threshold
            if (!dither) { if (black) out[y * bpr + (x shr 3)] = (out[y * bpr + (x shr 3)].toInt() or (0x80 ushr (x and 7))).toByte(); continue }
            if (black) out[y * bpr + (x shr 3)] = (out[y * bpr + (x shr 3)].toInt() or (0x80 ushr (x and 7))).toByte()
            val e = v - if (black) 0f else 255f
            if (x + 1 < width) err[i + 1] += e * 7 / 16
            if (y + 1 < height) {
                if (x > 0) err[i + width - 1] += e * 3 / 16
                err[i + width] += e * 5 / 16
                if (x + 1 < width) err[i + width + 1] += e / 16
            }
        }
        return out
    }

    /** Reset, left align, the image in [BAND]-row blocks, then feed [feed] lines so the end clears the tear bar. */
    fun job(bits: ByteArray, bytesPerRow: Int, height: Int, feed: Int = 4): ByteArray {
        val out = java.io.ByteArrayOutputStream(bits.size + height / BAND * 8 + 16)
        out.write(byteArrayOf(0x1b, 0x40, 0x1b, 0x61, 0x00))
        var y = 0
        while (y < height) {
            val n = minOf(BAND, height - y)
            out.write(byteArrayOf(0x1d, 0x76, 0x30, 0x00, (bytesPerRow and 255).toByte(), (bytesPerRow shr 8).toByte(), (n and 255).toByte(), (n shr 8).toByte()))
            out.write(bits, y * bytesPerRow, n * bytesPerRow)
            y += n
        }
        out.write(byteArrayOf(0x1b, 0x64, feed.toByte()))
        return out.toByteArray()
    }
}
