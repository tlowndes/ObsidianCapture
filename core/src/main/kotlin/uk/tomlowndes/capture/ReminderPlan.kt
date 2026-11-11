package uk.tomlowndes.capture

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** The daily reminders the phone has (Settings › Reminders), plus Windows' heads-up before a timed task. */
enum class ReminderSlot { MORNING, EVENING, DUE, WEEKLY }

/** Which reminders are on, and when (minutes after midnight). */
data class ReminderSettings(
    val beforeOn: Boolean = true, val beforeMinutes: Int = 10,
    val dueOn: Boolean = true, val dueMinute: Int = 9 * 60,
    val morningOn: Boolean = false, val morningMinute: Int = 8 * 60,
    val eveningOn: Boolean = false, val eveningMinute: Int = 18 * 60 + 30,
    val weeklyOn: Boolean = false, val weeklyMinute: Int = 17 * 60,
) {
    fun on(slot: ReminderSlot) = when (slot) {
        ReminderSlot.MORNING -> morningOn
        ReminderSlot.EVENING -> eveningOn
        ReminderSlot.DUE -> dueOn
        ReminderSlot.WEEKLY -> weeklyOn
    }

    fun minute(slot: ReminderSlot) = when (slot) {
        ReminderSlot.MORNING -> morningMinute
        ReminderSlot.EVENING -> eveningMinute
        ReminderSlot.DUE -> dueMinute
        ReminderSlot.WEEKLY -> weeklyMinute
    }
}

/**
 * Works out which reminders are due (windows-reminders-v1). Pure, so the desktop app just asks every so often
 * and remembers what it has shown by [slotKey] / [taskKey].
 */
object ReminderPlan {
    val LEADS = listOf(5, 10, 15, 30)

    fun slotKey(slot: ReminderSlot, day: LocalDate) = "${slot.name}:$day"
    fun taskKey(t: TimedTask, day: LocalDate) = "TASK:$day:${t.start}:${t.task.text.hashCode()}"

    private fun at(slot: ReminderSlot, s: ReminderSettings, day: LocalDate): LocalDateTime? {
        if (!s.on(slot)) return null
        if (slot == ReminderSlot.WEEKLY && day.dayOfWeek != DayOfWeek.SUNDAY) return null
        val m = s.minute(slot).coerceIn(0, 24 * 60 - 1)
        return day.atTime(LocalTime.of(m / 60, m % 60))
    }

    /**
     * The daily reminder to show now, if any, and every key it settles. Only today's count; when several have
     * passed unshown (the PC was off) only the latest shows and the earlier ones are settled with it.
     */
    fun slotDue(s: ReminderSettings, now: LocalDateTime, shown: Set<String>): Pair<ReminderSlot, List<String>>? {
        val day = now.toLocalDate()
        val passed = ReminderSlot.values()
            .mapNotNull { slot -> at(slot, s, day)?.let { slot to it } }
            .filter { (slot, t) -> !t.isAfter(now) && slotKey(slot, day) !in shown }
            .sortedBy { it.second }
        val last = passed.lastOrNull() ?: return null
        return last.first to passed.map { slotKey(it.first, day) }
    }

    /**
     * Timed tasks due today whose heads-up is due: from [ReminderSettings.beforeMinutes] before the start until the
     * start itself. Once a task has started it's too late, so a PC that was off skips them.
     */
    fun timedDue(tasks: List<TaskItem>, s: ReminderSettings, now: LocalDateTime, shown: Set<String>): List<TimedTask> {
        if (!s.beforeOn) return emptyList()
        val day = now.toLocalDate()
        val time = now.toLocalTime()
        return tasks.filter { it.due == day }.mapNotNull { DayPlan.timeOf(it) }
            .filter { t -> taskKey(t, day) !in shown && !time.isBefore(t.start.minusMinutes(s.beforeMinutes.toLong())) && time.isBefore(t.start) }
            .sortedBy { it.start }
    }

    /** Due today and overdue, overdue first, for the Due today card. */
    fun dueToday(tasks: List<TaskItem>, day: LocalDate): List<TaskItem> =
        tasks.filter { it.due != null && !it.due.isAfter(day) }.sortedBy { it.due }

    /** Keys from earlier days are dropped so the shown list doesn't grow forever. */
    fun prune(shown: Set<String>, today: LocalDate): Set<String> = shown.filter { it.contains(":$today") }.toSet()
}
