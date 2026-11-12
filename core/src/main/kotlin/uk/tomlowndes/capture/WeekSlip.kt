package uk.tomlowndes.capture

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.IsoFields
import java.util.Locale

/** What each day of the Week slip shows (slip-week-v2). */
data class WeekOptions(
    val events: Boolean = true,
    val todo: Boolean = true,
    val bills: Boolean = true,
    /** A blank line under each day to write on. */
    val writeLine: Boolean = true,
    /** Leave out days already gone (this week only). */
    val skipPast: Boolean = true,
)

/** One day block: its events (all-day first), to-dos due that day, and bills due that day. */
data class WeekDay(val date: LocalDate, val today: Boolean, val events: List<SlipEvent>, val tasks: List<SlipTask>, val bills: List<SlipReminder>) {
    val empty get() = events.isEmpty() && tasks.isEmpty() && bills.isEmpty()
    /** "THU". */
    val label: String get() = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.UK).uppercase(Locale.UK)
}

/** The Week slip: a separate printout of the week, one day under another. */
data class WeekContent(val monday: LocalDate, val next: Boolean, val week: Int, val days: List<WeekDay>, val show: WeekOptions, val daysLeft: Int) {
    val title: String get() = if (next) "NEXT WEEK" else "THIS WEEK"
    /** "12 – 15 NOVEMBER 2026", or "30 NOVEMBER – 6 DECEMBER 2026" across a month. */
    val range: String get() {
        val a = days.firstOrNull()?.date ?: monday
        val b = days.lastOrNull()?.date ?: monday.plusDays(6)
        val full = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.UK)
        val first = if (a.month == b.month) a.dayOfMonth.toString() else a.format(DateTimeFormatter.ofPattern("d MMMM", Locale.UK))
        return (if (a == b) b.format(full) else "$first – " + b.format(full)).uppercase(Locale.UK)
    }
    /** "Week 46 · 4 days left", or "Week 47" for next week. */
    val sub: String get() = if (next) "Week $week" else "Week $week · $daysLeft day" + (if (daysLeft == 1) "" else "s") + " left"
}

object WeekSlip {
    fun monday(d: LocalDate): LocalDate = d.with(DayOfWeek.MONDAY)

    /** The picker starts on next week on a Sunday, when this week is all but gone. */
    fun startsOnNext(today: LocalDate) = today.dayOfWeek == DayOfWeek.SUNDAY

    /** "9–15 Nov" for the week picker. */
    fun pickLabel(monday: LocalDate): String {
        val sun = monday.plusDays(6)
        val m = DateTimeFormatter.ofPattern("d MMM", Locale.UK)
        return if (monday.month == sun.month) "${monday.dayOfMonth}–" + sun.format(m) else monday.format(m) + " – " + sun.format(m)
    }

    fun build(today: LocalDate, next: Boolean, all: List<TaskItem>, events: List<CalEvent>, o: WeekOptions): WeekContent {
        val mon = monday(today).plusDays(if (next) 7 else 0)
        val tasks = all.distinctBy { Tags.strip(it.text).lowercase() to it.due }
        val dates = (0L..6L).map { mon.plusDays(it) }.filter { next || !o.skipPast || !it.isBefore(today) }
        val days = dates.map { d ->
            val on = events.filter { it.on(d) }
            val ev = if (!o.events) emptyList() else
                on.filter { it.allDay }.map { SlipEvent(it.title, null, null, it.location) } +
                    on.filter { !it.allDay }.sortedBy { it.start }.map {
                        SlipEvent(it.title, if (it.start.toLocalDate() == d) it.start.format(DateTimeFormatter.ofPattern("HH:mm")) else "…", it.end.format(DateTimeFormatter.ofPattern("HH:mm")), it.location)
                    }
            val due = tasks.filter { it.due == d }
            val todo = if (!o.todo) emptyList() else due.filter { !Deck.isBill(it) }.map { SlipTask(Tags.strip(it.text), Tags.inText(it.text).filter { t -> t != "#task" }) }
            val bills = if (!o.bills) emptyList() else due.filter { Deck.isBill(it) }.map { t ->
                val b = TaskViews.bill(t)
                SlipReminder(b.name, b.amount?.let { TaskViews.money(it) } ?: "")
            }
            WeekDay(d, d == today, ev, todo, bills)
        }
        val left = if (next) 7 else (mon.plusDays(6).toEpochDay() - today.toEpochDay() + 1).toInt()
        return WeekContent(mon, next, mon.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR), days, o, left)
    }
}
