package uk.tomlowndes.capture

import java.time.LocalDate
import java.time.LocalTime

/** A change made to the vault's tasks: what to tell the user, and how to take it back. */
class TaskChange(val message: String, val undo: (VaultWriter) -> Unit)

/**
 * The task actions both apps offer (tick, move, flag, tag, remove, plan, and the same on many at once), each
 * written once here and returning a [TaskChange] for the app's Undo bar. Each throws if the write fails.
 */
object TaskOps {
    /** None of a bulk change's tasks could be written; [message] is ready to show as it is. */
    class NoneChanged(message: String) : Exception(message)

    fun plural(n: Int) = "$n task${if (n == 1) "" else "s"}"

    /** "today", "tomorrow" or "Tue 7 Oct": how a move names its day. */
    fun dayWord(d: LocalDate, today: LocalDate = LocalDate.now()): String =
        if (d == today || d == today.plusDays(1)) dueLabel(d, today) else dayChip(d, today)

    fun complete(w: VaultWriter, t: TaskItem, recurring: Boolean, today: LocalDate = LocalDate.now()): TaskChange {
        val c = w.completeTask(t, recurring)
        val next = c.nextLine?.let { TaskRules.due(it) }
        return TaskChange(if (next != null) "Done · next: ${dueLabel(next, today)}" else "Task completed") { it.restoreTask(t, c) }
    }

    /** A new due date for [t]; null clears it. Only the 📅 date changes. */
    fun move(w: VaultWriter, t: TaskItem, due: LocalDate?, today: LocalDate = LocalDate.now()): TaskChange {
        val raw = w.rescheduleTask(t, due)
        return TaskChange(due?.let { "Moved to " + dayWord(it, today) } ?: "Date cleared") { it.restoreTask(t, null, raw) }
    }

    fun edit(w: VaultWriter, t: TaskItem, text: String, due: LocalDate?, recurrence: String?, minutes: Int?, priority: Priority? = null): TaskChange {
        val raw = w.editTask(t, text, due, recurrence, minutes, priority)
        return TaskChange("Task updated") { it.restoreTask(t, null, raw) }
    }

    fun priority(w: VaultWriter, t: TaskItem, p: Priority): TaskChange = edit(w, t, t.text, t.due, t.recurrence, Deck.minutes(t), p)

    fun remove(w: VaultWriter, t: TaskItem): TaskChange {
        val at = w.deleteTask(t)
        return TaskChange("Task removed") { it.restoreTask(t, null, at = at) }
    }

    /** Marks [t] cancelled where it is. */
    fun drop(w: VaultWriter, t: TaskItem, today: LocalDate = LocalDate.now()): TaskChange {
        w.cancelTask(t, today)
        return TaskChange("Dropped") { it.restoreTask(t, null, WeeklyReview.cancelled(t.raw, today)) }
    }

    /** Due [date] at [start] for its estimate (drag-to-slot-v1). */
    fun plan(w: VaultWriter, t: TaskItem, date: LocalDate, start: LocalTime, today: LocalDate = LocalDate.now()): TaskChange {
        val raw = w.planAt(t, date, start)
        return TaskChange("Planned for ${"%02d:%02d".format(start.hour, start.minute)} ${dayWord(date, today)}") { it.restoreTask(t, null, raw) }
    }

    /**
     * Runs [op] on each of [items], then one Undo for all that worked; the undos run newest first so line numbers
     * hold. [done] words the message from how many changed. Throws [NoneChanged] if none did.
     */
    fun bulk(w: VaultWriter, items: List<TaskItem>, done: (Int) -> String, op: (VaultWriter, TaskItem) -> TaskChange): TaskChange {
        val changes = items.mapNotNull { t -> runCatching { op(w, t) }.getOrNull() }
        if (changes.isEmpty()) throw NoneChanged("Couldn't change ${plural(items.size)}")
        val failed = items.size - changes.size
        return TaskChange(done(changes.size) + if (failed > 0) " · $failed failed" else "") { u ->
            changes.asReversed().forEach { c -> runCatching { c.undo(u) } }
        }
    }

    fun bulkMove(w: VaultWriter, items: List<TaskItem>, due: LocalDate, today: LocalDate = LocalDate.now()) =
        bulk(w, items, { "${plural(it)} moved to ${dayWord(due, today)}" }) { u, t -> move(u, t, due, today) }

    fun bulkPriority(w: VaultWriter, items: List<TaskItem>, p: Priority) =
        bulk(w, items, { if (p == Priority.NONE) "Flag cleared on ${plural(it)}" else "${plural(it)} flagged ${p.title.lowercase()}" }) { u, t -> priority(u, t, p) }

    fun bulkTag(w: VaultWriter, items: List<TaskItem>, tag: String) =
        bulk(w, items, { "$tag added to ${plural(it)}" }) { u, t -> edit(u, t, Tags.add(t.text, tag), t.due, t.recurrence, Deck.minutes(t)) }

    fun bulkDone(w: VaultWriter, items: List<TaskItem>, recurring: Boolean) =
        bulk(w, items, { "${plural(it)} done" }) { u, t -> complete(u, t, recurring) }

    fun bulkRemove(w: VaultWriter, items: List<TaskItem>) = bulk(w, items, { "${plural(it)} removed" }) { u, t -> remove(u, t) }

    /** Energy reshuffle (energy-reshuffle-v1): each task to its own new day. */
    fun reshuffle(w: VaultWriter, moves: List<Reshuffle.Move>): TaskChange {
        val to = moves.associate { it.task to it.to }
        return bulk(w, moves.map { it.task }, { "Moved ${plural(it)} to later days" }) { u, t -> move(u, t, to.getValue(t)) }
    }
}
