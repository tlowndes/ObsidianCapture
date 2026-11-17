package uk.tomlowndes.capture

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters

/** A dot under a day in Month view (calendar-view-v1). */
enum class Dot { EVENT, TASK, BILL, BIRTHDAY }

/** A block in Week view: a timed event or a timed task, on day [day] (0 = Monday) from [start] to [end]. */
data class WeekBlock(val day: Int, val start: LocalTime, val end: LocalTime, val title: String, val event: CalEvent? = null, val task: TaskItem? = null)

/** What one day holds, for the list under Month view: all-day events, then timed events and tasks by time. */
data class DayItems(val allDay: List<CalEvent>, val timed: List<CalEvent>, val tasks: List<TaskItem>, val birthdays: List<String>) {
    val size get() = allDay.size + timed.size + tasks.size + birthdays.size
}

/** Month and Week views on Upcoming (calendar-view-v1). Weeks start on Monday. */
object CalendarGrid {
    const val MAX_DOTS = 3
    /** Week view opens at 08:00 and scrolls for earlier or later. */
    const val WEEK_SCROLL_TO = 8

    fun weekStart(d: LocalDate): LocalDate = d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    /** Every day on the month's grid: from the Monday on or before the 1st to the Sunday on or after the last day. */
    fun monthDays(month: YearMonth): List<LocalDate> {
        val from = weekStart(month.atDay(1))
        val to = month.atEndOfMonth().with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
        return generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.toList()
    }

    /** Up to [MAX_DOTS] dots for [day]: birthdays, events, bills, then tasks. */
    fun dots(day: LocalDate, tasks: List<TaskItem>, events: List<CalEvent>, birthdays: Map<LocalDate, List<String>> = emptyMap()): List<Dot> {
        val out = mutableListOf<Dot>()
        repeat(birthdays[day]?.size ?: 0) { out += Dot.BIRTHDAY }
        repeat(events.count { it.on(day) }) { out += Dot.EVENT }
        val due = tasks.filter { it.due == day }
        repeat(due.count { Deck.isBill(it) }) { out += Dot.BILL }
        repeat(due.count { !Deck.isBill(it) }) { out += Dot.TASK }
        return out.take(MAX_DOTS)
    }

    fun day(day: LocalDate, tasks: List<TaskItem>, events: List<CalEvent>, birthdays: Map<LocalDate, List<String>> = emptyMap()): DayItems {
        val on = events.filter { it.on(day) }
        return DayItems(
            on.filter { it.allDay }, on.filter { !it.allDay }.sortedBy { it.start },
            tasks.filter { it.due == day }.sortedBy { DayPlan.timeOf(it)?.start ?: LocalTime.MAX },
            birthdays[day].orEmpty(),
        )
    }

    /** Timed events and timed tasks in the week from [monday], as blocks. An event over midnight is cut at each day's edge. */
    fun weekBlocks(monday: LocalDate, tasks: List<TaskItem>, events: List<CalEvent>): List<WeekBlock> = (0 until 7).flatMap { i ->
        val d = monday.plusDays(i.toLong())
        DayPlan.blocks(tasks, events, d).map { b ->
            when (b) {
                is DayBlock.Event -> WeekBlock(i, b.start, b.end, b.event.title, event = b.event)
                is DayBlock.Task -> WeekBlock(i, b.start, b.end, DayPlan.bareText(TaskViews.plainText(b.timed.task)), task = b.timed.task)
            }
        }
    }

    /** All-day events per day of the week from [monday]. */
    fun weekAllDay(monday: LocalDate, events: List<CalEvent>): List<List<CalEvent>> =
        (0 until 7).map { i -> events.filter { it.allDay && it.on(monday.plusDays(i.toLong())) } }

    /** Tasks due on each day of the week that have no time (shown as a count under the day). */
    fun weekUntimed(monday: LocalDate, tasks: List<TaskItem>): List<Int> =
        (0 until 7).map { i -> val d = monday.plusDays(i.toLong()); tasks.count { it.due == d && DayPlan.timeOf(it) == null } }

    /** Birthdays falling between [from] and [to], from People notes. */
    fun birthdays(people: List<Person>, from: LocalDate, to: LocalDate): Map<LocalDate, List<String>> =
        people.flatMap { p ->
            val b = p.birthday ?: return@flatMap emptyList()
            (from.year..to.year).mapNotNull { y -> runCatching { b.atYear(y) }.getOrNull()?.takeIf { !it.isBefore(from) && !it.isAfter(to) }?.let { it to p.name } }
        }.groupBy({ it.first }, { it.second })
}
