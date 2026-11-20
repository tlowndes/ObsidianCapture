package uk.tomlowndes.capture

import java.time.LocalDate

/**
 * Morning audio briefing (growth-v1): Claude Fast writes a short, friendly script (about 2 minutes spoken) from the same
 * data as the Deck, and the phone or Windows voice reads it line by line. Saved for the day in [PATH] so the phone and
 * the PC share one script (one Claude call a day).
 */
object Briefing {
    const val PATH = "${FlightNotes.DIR}/Briefing.md"
    const val READY_HOUR = 6

    fun system(name: String = AssistantName.DEFAULT) = """You are ${AssistantName.clean(name)}, reading Tom his morning briefing out loud.
Write 5–8 short lines, one idea per line, each one or two spoken sentences. About 250 words in all.
Always open with the weather: the high and low, the rain story and what to wear or take. If there is no weather line, skip it.
Then: first event and when to leave, the next step, anything due or late, bills, one encouraging line to finish.
Plain spoken UK English: no emoji, no markdown, no lists, say times like "nine forty". Reply with the lines only, one per line."""

    /** The facts Claude writes from: the slip's content, plus the Next step and anything else worth a line. */
    fun prompt(s: SlipContent, next: String?, extra: List<String> = emptyList()): String = buildString {
        append("Today is ${s.date.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }} ${Slip.shortDate(s.date)}.\n")
        s.weather?.let { append("${weatherFacts(it)}\n") }
        (s.allDay + s.events).forEach { e -> append("Event: ${listOfNotNull(e.start, e.title, e.place).joinToString(" ")}\n") }
        next?.let { append("Next step: $it\n") }
        s.overdue.forEach { append("Late: ${it.text}\n") }
        s.today.forEach { append("Due today: ${it.text}\n") }
        s.dontForget.forEach { append("Don't forget: ${it.text} ${it.note}\n") }
        extra.forEach { append("$it\n") }
    }

    /** The whole forecast for Claude, not just the headline: "Weather: 15° high, 7° low. Wet morning… Tip: … Morning 9° 60% rain…". */
    fun weatherFacts(w: WeatherSummary): String = buildString {
        append("Weather: ${w.high}° high, ${w.low}° low. ${w.headline}.")
        if (w.detail.isNotBlank()) append(" ${w.detail.trim().removeSuffix(".")}.")
        w.tip?.takeIf { it.isNotBlank() }?.let { append(" Tip: ${it.trim()}") }
        if (w.parts.isNotEmpty()) append(" " + w.parts.joinToString(", ") { "${it.name.lowercase()} ${it.temp}° ${it.rain}% rain" } + ".")
    }

    fun lines(reply: String): List<String> = reply.replace("\r", "").split("\n")
        .map { it.trim().removePrefix("-").removePrefix("•").trim() }.filter { it.isNotEmpty() }.take(12)

    /**
     * Today's saved script, or null when there isn't one for [day]. With [needWeather], a script written before the
     * forecast had loaded counts as missing, so the next play writes it again with the weather in.
     */
    fun cached(v: Vault, day: LocalDate, needWeather: Boolean = false): List<String>? {
        val t = v.read(PATH) ?: return null
        val ls = t.split("\n")
        if (Md.fmGet(ls, "date")?.trim() != day.toString()) return null
        if (needWeather && Md.fmGet(ls, "weather")?.trim() == "false") return null
        return Md.fmBody(ls).map { it.trim() }.filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }
    }

    /** True once today's briefing has been played to the end (on the phone or the PC), so the Deck hides it till tomorrow. */
    fun heard(v: Vault, day: LocalDate): Boolean {
        val ls = v.read(PATH)?.split("\n") ?: return false
        return Md.fmGet(ls, "date")?.trim() == day.toString() && Md.fmGet(ls, "heard")?.trim() == "true"
    }

    fun markHeard(v: Vault, day: LocalDate) {
        val ls = v.read(PATH)?.split("\n")?.toMutableList() ?: return
        if (Md.fmGet(ls, "date")?.trim() != day.toString()) return
        Md.fmSet(ls, "heard", "true")
        VaultIo.write(v, PATH, ls.joinToString("\n"))
    }

    fun save(v: Vault, day: LocalDate, lines: List<String>, weather: Boolean = true) {
        val body = "---\ndate: $day\nweather: $weather\n---\n\n" + lines.joinToString("\n\n") + "\n"
        if (v.entry(PATH) == null) { v.mkdirs(FlightNotes.DIR); VaultIo.write(v, v.create(FlightNotes.DIR, "Briefing", "md"), body) }
        else VaultIo.write(v, PATH, body)
    }

    /** About how long it takes to say, at 150 words a minute: "2:04". */
    fun length(lines: List<String>): String {
        val s = (lines.sumOf { l -> l.split(Regex("\\s+")).count { it.isNotBlank() } } * 60 / 150).coerceAtLeast(5)
        return "${s / 60}:${"%02d".format(s % 60)}"
    }
}
