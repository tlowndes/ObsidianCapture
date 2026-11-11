package uk.tomlowndes.capture

import java.time.LocalDate
import java.time.LocalDateTime

/**
 * What Flight Deck tells Piholegotchi (the e-ink screen on the home network): the day in a few numbers, and the
 * Focus session if one is on. Built here, without Android, so it can be tested. Sent by the app's Piholegotchi.kt.
 */
object PiDeck {
    /** The Deck summary: next step, due count, focus minutes, habits, water and the next job follow-up. */
    fun deck(
        tasks: List<TaskItem>, skipped: Set<String>, maxMinutes: Int?, today: LocalDate, focusMin: Int,
        habits: HabitsNote?, jobs: List<Job>, energy: String, now: LocalDateTime = LocalDateTime.now()
    ): String {
        val next = Deck.nextStep(tasks, skipped, maxMinutes)
        val open = jobs.filter { !it.closed }
        val follow = open.filter { it.next != null }.minByOrNull { it.next!! }
        val (done, total) = habits?.progress(today) ?: (0 to 0)
        val water = habits?.water?.let { w -> habits.day(today).value(w) to habits.waterGoalMl }
        return obj(
            "v" to "1",
            "sent" to str(now.withNano(0).toString()),
            "energy" to str(energy),
            "next" to (next?.let {
                obj("title" to str(title(it.text)), "due" to (it.due?.let { d -> str(d.toString()) } ?: "null"),
                    "mins" to (Deck.minutes(it)?.toString() ?: "null"))
            } ?: "null"),
            "due" to Deck.dueCount(tasks, today).toString(),
            "focus_min" to focusMin.toString(),
            "habits" to obj("done" to done.toString(), "total" to total.toString()),
            "water" to (water?.let { (ml, goal) -> obj("ml" to ml.toString(), "goal" to goal.toString()) } ?: "null"),
            "jobs" to obj(
                "open" to open.size.toString(),
                "follow_up" to (follow?.let {
                    obj("company" to str(it.company), "role" to str(it.role), "date" to str(it.next.toString()))
                } ?: "null")
            ),
        )
    }

    /** The Focus session: what it's on, and when it ends (or how long is left, when paused). Null body ends it. */
    fun focus(task: String?, running: Boolean, endsAtMs: Long?, remainingMs: Long, totalMs: Long): String =
        if (task == null) obj("focus" to "null")
        else obj("focus" to obj(
            "task" to str(task), "running" to running.toString(),
            "ends" to (endsAtMs?.let { (it / 1000).toString() } ?: "null"),
            "remaining_s" to (remainingMs / 1000).toString(), "total_s" to (totalMs / 1000).toString()
        ))

    /** Task text without its #tags, for a small screen. */
    fun title(text: String): String =
        text.replace(Regex("(^|\\s)#[\\w/-]+"), " ").replace(Regex("\\s+"), " ").trim().ifEmpty { text.trim() }

    /** `piholegotchi.local` or `192.168.1.42` -> host and port. */
    fun hostPort(address: String): Pair<String, Int>? {
        val a = address.trim().removePrefix("http://").removePrefix("https://").substringBefore("/")
        if (a.isEmpty()) return null
        val host = a.substringBefore(":")
        val port = a.substringAfter(":", "80").toIntOrNull() ?: return null
        return host to port
    }

    private fun obj(vararg kv: Pair<String, String>) = kv.joinToString(",", "{", "}") { (k, v) -> "${str(k)}:$v" }

    internal fun str(s: String): String = buildString {
        append('"')
        for (c in s) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
        append('"')
    }
}
