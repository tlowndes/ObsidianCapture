package uk.tomlowndes.capture

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

/** One line in an Upcoming day: a task (no time), a timed calendar event, or a free gap between events. */
sealed class AgendaItem {
    data class Task(val task: TaskItem, val late: Boolean = false) : AgendaItem()
    data class Event(val event: CalEvent) : AgendaItem()
    data class Gap(val start: LocalDateTime, val end: LocalDateTime) : AgendaItem() {
        val minutes get() = Duration.between(start, end).toMinutes().toInt()
    }
}

/** A day in Upcoming: all-day events up top, then its lines. [tasks] and [events] count what's due for the week strip. */
data class AgendaDay(val date: LocalDate, val allDay: List<CalEvent>, val items: List<AgendaItem>) {
    val tasks get() = items.count { it is AgendaItem.Task }
    val events get() = allDay.size + items.count { it is AgendaItem.Event }
    val clear get() = allDay.isEmpty() && items.isEmpty()
}

/** The Upcoming view on the Tasks tab: dated tasks and calendar events for the next [DAYS] days. */
object Upcoming {
    const val DAYS = 7
    /** Free gaps are only looked for inside this window, and only on today. */
    val DAY_START: LocalTime = LocalTime.of(9, 0)
    val DAY_END: LocalTime = LocalTime.of(18, 0)
    const val MIN_GAP = 60

    /**
     * The next [DAYS] days from [now]. Today also holds overdue tasks (marked late) and free gaps of an hour or more.
     * Tasks come first (they have no time), then timed events in order. Undated tasks aren't shown.
     */
    fun days(tasks: List<TaskItem>, events: List<CalEvent>, now: LocalDateTime, count: Int = DAYS): List<AgendaDay> {
        val today = now.toLocalDate()
        return (0 until count).map { i ->
            val d = today.plusDays(i.toLong())
            val dayEvents = events.filter { it.on(d) }.sortedBy { it.start }
            val allDay = dayEvents.filter { it.allDay }
            val timed = dayEvents.filter { !it.allDay }
            val late = if (i == 0) tasks.filter { it.due != null && it.due.isBefore(today) }.sortedBy { it.due }
                .map { AgendaItem.Task(it, late = true) } else emptyList()
            val due = tasks.filter { it.due == d }.map { AgendaItem.Task(it) }
            val timeline = if (i == 0) withGaps(timed, now) else timed.map { AgendaItem.Event(it) }
            AgendaDay(d, allDay, late + due + timeline)
        }
    }

    /** Timed events with the free gaps (of [MIN_GAP] minutes or more, after [now], inside the day window) put between them. */
    fun withGaps(timed: List<CalEvent>, now: LocalDateTime): List<AgendaItem> {
        val d = now.toLocalDate()
        val windowEnd = d.atTime(DAY_END)
        var free = maxOf(d.atTime(DAY_START), now.withSecond(0).withNano(0))
        val out = mutableListOf<AgendaItem>()
        for (e in timed.sortedBy { it.start }) {
            val gapEnd = minOf(e.start, windowEnd)
            if (Duration.between(free, gapEnd).toMinutes() >= MIN_GAP) out += AgendaItem.Gap(free, gapEnd)
            out += AgendaItem.Event(e)
            if (e.end.isAfter(free)) free = e.end
        }
        if (Duration.between(free, windowEnd).toMinutes() >= MIN_GAP) out += AgendaItem.Gap(free, windowEnd)
        return out
    }

    /** What's on now, or the next timed event later today. */
    fun nextUp(events: List<CalEvent>, now: LocalDateTime): CalEvent? =
        events.filter { !it.allDay && it.start.toLocalDate() == now.toLocalDate() && it.end.isAfter(now) }.minByOrNull { it.start }

    /** "now", "40m", "1h 20m" or "3h" until [e] starts. */
    fun countdown(e: CalEvent, now: LocalDateTime): String {
        if (!e.start.isAfter(now)) return "now"
        val m = Math.ceil(Duration.between(now, e.start).seconds / 60.0).toInt()
        return when {
            m < 60 -> "${m}m"
            m % 60 == 0 -> "${m / 60}h"
            else -> "${m / 60}h ${m % 60}m"
        }
    }

    /** Open tasks with an estimate that fits a gap of [minutes], longest first, not already due today. */
    fun fits(tasks: List<TaskItem>, minutes: Int, today: LocalDate): List<TaskItem> =
        tasks.mapNotNull { t -> TaskRules.minutes(t.raw)?.takeIf { it <= minutes && t.due != today }?.let { t to it } }
            .sortedByDescending { it.second }.map { it.first }

    /** "2h free", "1h 30m free". */
    fun gapLabel(minutes: Int) = when {
        minutes % 60 == 0 -> "${minutes / 60}h free"
        minutes < 60 -> "${minutes}m free"
        else -> "${minutes / 60}h ${minutes % 60}m free"
    }

    /** The reschedule sheet's quick picks: Today, Tomorrow, This weekend (the next Saturday) and Next week (the next Monday). */
    fun presets(today: LocalDate): List<Pair<String, LocalDate>> = listOf(
        "Today" to today,
        "Tomorrow" to today.plusDays(1),
        "This weekend" to today.plusDays(1).with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY)),
        "Next week" to today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)),
    )
}
