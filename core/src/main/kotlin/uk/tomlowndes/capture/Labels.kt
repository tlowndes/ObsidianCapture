package uk.tomlowndes.capture

import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

// Date and time words the phone and Windows apps both show, kept in one place so they read the same.

private fun fmt(d: LocalDate, pattern: String) = d.format(DateTimeFormatter.ofPattern(pattern, Locale.UK))

/** "today", "tomorrow" or "5 Nov". */
fun dueLabel(d: LocalDate, today: LocalDate = LocalDate.now()): String = when (d) {
    today -> "today"
    today.plusDays(1) -> "tomorrow"
    else -> fmt(d, "d MMM")
}

/** "Today", "Tomorrow", "Tue 7 Oct", or "7 Oct 2027" outside this year. Used on due chips. */
fun dayChip(d: LocalDate, today: LocalDate = LocalDate.now()): String = when (d) {
    today -> "Today"
    today.plusDays(1) -> "Tomorrow"
    else -> if (d.year == today.year) fmt(d, "EEE d MMM") else fmt(d, "d MMM yyyy")
}

/** "overdue", "due today", "due tomorrow" or "due Tue 7". Used under the next step on Flight Deck. */
fun duePhrase(d: LocalDate, today: LocalDate = LocalDate.now()): String = when {
    d.isBefore(today) -> "overdue"
    d == today -> "due today"
    d == today.plusDays(1) -> "due tomorrow"
    else -> "due " + fmt(d, "EEE d")
}

/** "today", "yesterday", "tomorrow" or "5 Nov" (with the year outside this one). Used on query-block tasks. */
fun relativeDay(d: LocalDate, today: LocalDate = LocalDate.now()): String = when (d) {
    today -> "today"
    today.minusDays(1) -> "yesterday"
    today.plusDays(1) -> "tomorrow"
    else -> DataViews.shortDate(d, today)
}

/** "good morning", "good afternoon" or "good evening". */
fun greeting(now: LocalTime = LocalTime.now()): String = when {
    now.hour < 12 -> "good morning"
    now.hour < 18 -> "good afternoon"
    else -> "good evening"
}

/** Seconds as "m:ss", e.g. 75 → "1:15". */
fun mmss(seconds: Int): String = "${seconds / 60}:${"%02d".format(seconds % 60)}"
