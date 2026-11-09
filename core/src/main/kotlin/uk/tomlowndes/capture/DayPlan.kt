package uk.tomlowndes.capture

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** A task with a time today: `- [ ] 11:00 - 11:45 Apply to Monzo …` (the Day Planner plugin's way of writing it). */
data class TimedTask(val task: TaskItem, val start: LocalTime, val end: LocalTime)

/** A block on the Visual day timeline: a calendar event or a timed task. */
sealed class DayBlock(open val start: LocalTime, open val end: LocalTime) {
    data class Event(val event: CalEvent, override val start: LocalTime, override val end: LocalTime) : DayBlock(start, end)
    data class Task(val timed: TimedTask) : DayBlock(timed.start, timed.end)
}

/**
 * Plan today (build-queue T1) and Visual day (T2).
 * A task gets a time as `HH:mm - HH:mm` at the start of its text, which Obsidian's Day Planner reads too.
 */
object DayPlan {
    private val TIME = Regex("^(\\s*- \\[[^\\]]*\\]\\s*)(\\d{1,2}):(\\d{2})(?:\\s*-\\s*(\\d{1,2}):(\\d{2}))?\\s+")
    private val TEXT_TIME = Regex("^(\\d{1,2}):(\\d{2})(?:\\s*-\\s*(\\d{1,2}):(\\d{2}))?\\s+")
    const val PICK_MAX = 3
    const val DEFAULT_MIN = 15
    /** The timeline runs from 07:00 to 22:00. */
    val FIRST_HOUR = 7
    val LAST_HOUR = 22

    private fun time(h: String, m: String) = runCatching { LocalTime.of(h.toInt(), m.toInt()) }.getOrNull()

    /** The time a task line starts with, and its end (start + estimate, or 15 min) when it has none. */
    fun timeOf(t: TaskItem): TimedTask? {
        val m = TIME.find(t.raw) ?: return null
        val start = time(m.groupValues[2], m.groupValues[3]) ?: return null
        val end = if (m.groupValues[4].isNotEmpty()) time(m.groupValues[4], m.groupValues[5]) else null
        return TimedTask(t, start, end?.takeIf { it.isAfter(start) } ?: start.plusMinutes((TaskRules.minutes(t.raw) ?: DEFAULT_MIN).toLong()))
    }

    /** Task text without a leading time, for the timeline. */
    fun bareText(text: String) = text.replace(TEXT_TIME, "")

    /** [raw] with its time set to [start]–[start]+[minutes] (or removed for null). */
    fun withTime(raw: String, start: LocalTime?, minutes: Int): String {
        val box = Regex("^(\\s*- \\[[^\\]]*\\]\\s*)").find(raw) ?: return raw
        val stripped = TIME.find(raw)?.let { raw.replaceRange(it.range, it.groupValues[1]) } ?: raw
        if (start == null) return stripped
        val end = start.plusMinutes(minutes.toLong())
        val head = box.groupValues[1].trimEnd() + " "
        val f = { t: LocalTime -> "%02d:%02d".format(t.hour, t.minute) }
        return head + "${f(start)} - ${f(end)} " + stripped.substring(box.groupValues[1].length).trimStart()
    }

    /** Today's timeline: timed events and timed tasks due today, by start. */
    fun blocks(tasks: List<TaskItem>, events: List<CalEvent>, day: LocalDate): List<DayBlock> {
        val ev = events.filter { !it.allDay && it.on(day) }.map {
            DayBlock.Event(it, if (it.start.toLocalDate().isBefore(day)) LocalTime.MIN else it.start.toLocalTime(),
                if (it.end.toLocalDate().isAfter(day)) LocalTime.of(23, 59) else it.end.toLocalTime())
        }
        val tk = tasks.filter { it.due == day }.mapNotNull { timeOf(it) }.map { DayBlock.Task(it) }
        return (ev + tk).sortedBy { it.start }
    }

    /** Tasks due today (or late) with no time yet, for "Give it a time". */
    fun untimed(tasks: List<TaskItem>, day: LocalDate): List<TaskItem> =
        tasks.filter { it.due != null && !it.due.isAfter(day) && !Deck.isBill(it) && timeOf(it) == null }

    // ---- Plan today ----

    /** What the morning card offers: due today or late first, then this week, then undated. Bills stay out. */
    fun candidates(tasks: List<TaskItem>, today: LocalDate, max: Int = 8): List<TaskItem> {
        val open = tasks.filter { !Deck.isBill(it) }
        val due = open.filter { it.due != null && !it.due.isAfter(today) }.sortedBy { it.due }
        val week = open.filter { it.due != null && it.due.isAfter(today) && !it.due.isAfter(today.plusDays(6)) }.sortedBy { it.due }
        val undated = open.filter { it.due == null }
        return (due + week + undated).take(max)
    }

    fun minutes(t: TaskItem) = TaskRules.minutes(t.raw) ?: DEFAULT_MIN

    fun planned(picked: List<TaskItem>) = picked.sumOf { minutes(it) }

    /**
     * Free minutes left today between [from] (or 09:00) and 18:00, after timed events. Overlapping events count once.
     */
    fun freeMinutes(events: List<CalEvent>, now: LocalDateTime): Int {
        val d = now.toLocalDate()
        val start = maxOf(d.atTime(Upcoming.DAY_START), now)
        val end = d.atTime(Upcoming.DAY_END)
        if (!start.isBefore(end)) return 0
        var busy = 0L
        var cursor = start
        for (e in events.filter { !it.allDay && it.end.isAfter(start) && it.start.isBefore(end) }.sortedBy { it.start }) {
            val s = maxOf(e.start, cursor)
            val f = minOf(e.end, end)
            if (f.isAfter(s)) { busy += Duration.between(s, f).toMinutes(); cursor = f }
        }
        return (Duration.between(start, end).toMinutes() - busy).toInt().coerceAtLeast(0)
    }

    /** Evening shut down: what's still open for today (due today or late), bills left out. */
    fun leftToday(tasks: List<TaskItem>, today: LocalDate): List<TaskItem> =
        tasks.filter { it.due != null && !it.due.isAfter(today) && !Deck.isBill(it) }.sortedBy { it.due }

    /** Morning card until noon, Shut down from 17:00. */
    fun isMorning(now: LocalDateTime) = now.hour in 5..11
    fun isEvening(now: LocalDateTime) = now.hour >= 17
}
