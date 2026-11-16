package uk.tomlowndes.capture

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Talk me through it (wellbeing-v1): when a task has waited 3 days or more, offer a short chat (3–4 turns, in the
 * assistant's voice) that ends by adding small steps under the task. The first step becomes the Next step.
 */
object TalkThrough {
    const val STUCK_DAYS = 3L
    const val SNOOZE_DAYS = 3L
    const val MARK = "STEPS:"
    private val ADDED = Regex("➕\\s*(\\d{4}-\\d{2}-\\d{2})")

    /** How long [t] has waited: days overdue, or days since its ➕ created date when it has no due date. */
    fun waited(t: TaskItem, today: LocalDate): Long? {
        t.due?.let { d -> return ChronoUnit.DAYS.between(d, today).takeIf { it > 0 } }
        val made = ADDED.find(t.raw)?.groupValues?.get(1)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
        return ChronoUnit.DAYS.between(made, today).takeIf { it > 0 }
    }

    /** The task to offer today: the one that has waited longest (3+ days), top-level, not snoozed. */
    fun stuck(tasks: List<TaskItem>, today: LocalDate, snoozed: Map<String, LocalDate>): TaskItem? = tasks
        .filter { !it.raw.startsWith(" ") && !it.raw.startsWith("\t") && it.recurrence == null }
        .filter { t -> snoozed[key(t)]?.let { !today.isBefore(it) } ?: true }
        .mapNotNull { t -> waited(t, today)?.takeIf { it >= STUCK_DAYS }?.let { t to it } }
        .maxByOrNull { it.second }?.first

    fun key(t: TaskItem) = "%08x".format(TaskRules.cleanText(t.text).lowercase().hashCode())

    /** Snoozes kept as "key=yyyy-mm-dd,…" in the app's settings. */
    fun readSnoozed(s: String?): Map<String, LocalDate> = s.orEmpty().split(",").mapNotNull { p ->
        val (k, d) = p.split("=").takeIf { it.size == 2 } ?: return@mapNotNull null
        runCatching { k to LocalDate.parse(d) }.getOrNull()
    }.toMap()

    fun snooze(s: String?, t: TaskItem, today: LocalDate): String =
        (readSnoozed(s).filterValues { !it.isBefore(today) } + (key(t) to today.plusDays(SNOOZE_DAYS)))
            .entries.joinToString(",") { "${it.key}=${it.value}" }

    fun opener(task: String) = "What's making \"${TaskRules.cleanText(task)}\" hard to start?"

    fun system(task: String, days: Long, name: String = AssistantName.DEFAULT): String = """
        You are ${AssistantName.clean(name)}, helping Tom (ADHD, a designer) get unstuck on one task that has waited $days days: "${TaskRules.cleanText(task)}".
        Be warm, brief and practical, UK English, no lists until the end. 1–3 short sentences a turn.
        Find what makes it hard, then offer the smallest possible first step (about 5 minutes) and ask if that works.
        Once Tom agrees (or after 3 of his replies at most), end your reply with a line "$MARK" and then 2–4 steps, one per line,
        like "- Change the top line · 5m". The first step must be the tiny one you agreed.
    """.trimIndent()

    /** The steps when the reply has the [MARK] line, else null. */
    fun steps(reply: String): List<Step>? {
        val at = reply.indexOf(MARK).takeIf { it >= 0 } ?: return null
        return Breakdown.parse(reply.substring(at + MARK.length)).take(6).takeIf { it.isNotEmpty() }
    }

    /** What to show in the chat: the reply without the steps block. */
    fun visible(reply: String): String = reply.substringBefore(MARK).trim()
}
