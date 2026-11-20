package uk.tomlowndes.capture

import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale

/** One thing to take: `- 🔑 Keys`. [reason] says why it was added today ("rain", "gym"), null for Always. */
data class KitItem(val emoji: String, val name: String, val reason: String? = null)

/** A "When" rule: `- rain · ☂️ Umbrella` or `- event gym · 🎒 Gym bag, 🧴 Towel`. */
data class KitRule(val kind: Kind, val arg: String, val items: List<KitItem>) {
    enum class Kind { RAIN, COLD, EVENT, LONG }
}

data class Kit(val always: List<KitItem>, val rules: List<KitRule>)

/** What Launch Pad knows about the trip: the next event today, the travel line if there is one, and the weather. */
data class Trip(
    val title: String?, val start: LocalDateTime?, val end: LocalDateTime?,
    val leaveBy: LocalDateTime?, val driveMinutes: Int?, val rainChance: Int, val low: Int?,
)

/** Where you stand against the leave-by time. */
data class Timing(val minutesLeft: Long?, val lateBy: Long, val arriveAt: LocalTime?)

/**
 * Launch Pad (Hangar, phase 1): the leave-the-house pass. The kit is a plain note: an Always list, plus "When"
 * rules that add items for today's rain, cold, a long trip or an event whose title has a word in it.
 */
object LaunchPad {
    const val KIT = "${FlightNotes.DIR}/Launch Pad kit.md"
    const val RAIN_AT = 50

    val STARTER = """
        # Launch Pad kit

        Always takes these. Under When, each line is a rule, a ·, then what to add.
        Rules: rain, cold, event <word in the title>, long <hours>.

        ## Always
        - 🔑 Keys
        - 👛 Wallet
        - 📱 Phone
        - 🎧 Headphones

        ## When
        - rain · ☂️ Umbrella
        - cold · 🧣 Scarf
        - event gym · 🎒 Gym bag, 🧴 Towel
        - event interview · 📁 Portfolio, 📄 CV
        - long 4 · 🔌 Charger, 💊 Meds
    """.trimIndent() + "\n"

    private val ITEM = Regex("^\\s*[-*]\\s+(?!\\[)(.+?)\\s*$")

    fun item(text: String, reason: String? = null): KitItem? {
        val t = text.trim()
        if (t.isEmpty()) return null
        val first = t.substringBefore(" ")
        val hasEmoji = t.contains(" ") && !first.first().isLetterOrDigit()
        return KitItem(if (hasEmoji) first else "•", (if (hasEmoji) t.substringAfter(" ") else t).trim(), reason)
    }

    fun kit(text: String): Kit {
        val always = mutableListOf<KitItem>()
        val rules = mutableListOf<KitRule>()
        var section = ""
        text.split("\n").forEach { line ->
            if (line.startsWith("## ")) { section = line.removePrefix("## ").trim().lowercase(Locale.UK); return@forEach }
            val body = ITEM.matchEntire(line)?.groupValues?.get(1) ?: return@forEach
            when (section) {
                "always" -> item(body)?.let(always::add)
                "when" -> rule(body)?.let(rules::add)
            }
        }
        return Kit(always, rules)
    }

    fun rule(body: String): KitRule? {
        if (!body.contains("·")) return null
        val head = body.substringBefore("·").trim().lowercase(Locale.UK)
        val word = head.substringBefore(" ")
        val arg = head.substringAfter(" ", "").trim()
        val kind = when (word) {
            "rain" -> KitRule.Kind.RAIN
            "cold" -> KitRule.Kind.COLD
            "event" -> KitRule.Kind.EVENT
            "long" -> KitRule.Kind.LONG
            else -> return null
        }
        if (kind == KitRule.Kind.EVENT && arg.isEmpty()) return null
        val reason = when (kind) { KitRule.Kind.RAIN -> "rain"; KitRule.Kind.COLD -> "cold"; KitRule.Kind.EVENT -> arg; KitRule.Kind.LONG -> "long trip" }
        val items = body.substringAfter("·").split(",").mapNotNull { item(it, reason) }
        return if (items.isEmpty()) null else KitRule(kind, arg, items)
    }

    private fun applies(r: KitRule, t: Trip): Boolean = when (r.kind) {
        KitRule.Kind.RAIN -> t.rainChance >= RAIN_AT
        KitRule.Kind.COLD -> (t.low ?: 99) <= (r.arg.toIntOrNull() ?: 5)
        KitRule.Kind.EVENT -> t.title?.lowercase(Locale.UK)?.contains(r.arg) == true
        KitRule.Kind.LONG -> {
            val hours = r.arg.toLongOrNull() ?: 4
            t.start != null && t.end != null && Duration.between(t.start, t.end).toMinutes() >= hours * 60
        }
    }

    /** Today's list: Always first, then whatever the rules add. No item appears twice. */
    fun itemsFor(kit: Kit, trip: Trip): List<KitItem> =
        (kit.always + kit.rules.filter { applies(it, trip) }.flatMap { it.items }).distinctBy { it.name.lowercase(Locale.UK) }

    /** Running late keeps only the Always items, so you can get out the door. */
    fun essentials(items: List<KitItem>) = items.filter { it.reason == null }

    /** The time to leave: the travel leave-by when there's a route, otherwise the event's start. */
    fun leaveBy(trip: Trip): LocalDateTime? = trip.leaveBy ?: trip.start

    fun timing(trip: Trip, now: LocalDateTime): Timing {
        val by = leaveBy(trip) ?: return Timing(null, 0, null)
        val left = Duration.between(now, by).toMinutes()
        val late = if (now.isAfter(by)) Duration.between(by, now).toMinutes().coerceAtLeast(1) else 0
        val arrive = trip.start?.let { s -> if (late > 0) now.plusMinutes((trip.driveMinutes ?: 0).toLong()).toLocalTime().let { maxOf(it, s.toLocalTime()) } else s.toLocalTime() }
        return Timing(if (late > 0) 0 else left, late, arrive)
    }

    fun load(v: Vault): Kit {
        if (v.entry(KIT) == null) {
            v.mkdirs(FlightNotes.DIR)
            VaultIo.write(v, v.create(FlightNotes.DIR, nameOf(KIT).removeSuffix(".md"), "md"), STARTER)
        }
        return kit(VaultIo.read(v, KIT))
    }

    /** The next timed event today that hasn't started, preferring one with a place (it has a leave-by). */
    fun nextEvent(events: List<CalEvent>, now: LocalDateTime): CalEvent? =
        events.filter { !it.allDay && it.start.toLocalDate() == now.toLocalDate() && it.start.isAfter(now.minusMinutes(30)) && !it.isNow(now) }
            .minByOrNull { it.start }
}

/** Something Heads Up warns you about. */
data class HeadsTarget(val kind: Kind, val label: String, val at: LocalDateTime) {
    enum class Kind { LEAVE, EVENT, FOCUS }
}

/** One buzz: [minutes] before [target]. */
data class HeadsAlert(val at: LocalDateTime, val minutes: Int, val target: HeadsTarget)

/**
 * Heads Up (Hangar, phase 1): gentle buzzes at 10, 5 and 1 minute before a switch (leave-by, an event starting,
 * Focus ending), so time blindness doesn't win. Nothing buzzes in quiet hours.
 */
object HeadsUp {
    val STAGES = listOf(15, 10, 5, 1)
    val DEFAULT = listOf(10, 5, 1)

    fun parseStages(saved: String?): List<Int> =
        saved?.split(",")?.mapNotNull { it.trim().toIntOrNull() }?.filter { it in STAGES }?.distinct()?.sortedDescending()
            ?.takeIf { it.isNotEmpty() } ?: DEFAULT

    fun formatStages(s: List<Int>) = s.distinct().sortedDescending().joinToString(",")

    fun quiet(t: LocalTime, from: LocalTime, to: LocalTime): Boolean =
        if (from <= to) !t.isBefore(from) && t.isBefore(to) else !t.isBefore(from) || t.isBefore(to)

    /** Every buzz still to come, soonest first. A leave-by and an event at the same moment only buzz once. */
    fun plan(
        targets: List<HeadsTarget>, now: LocalDateTime, stages: List<Int> = DEFAULT,
        quietFrom: LocalTime = LocalTime.of(21, 0), quietTo: LocalTime = LocalTime.of(7, 0),
    ): List<HeadsAlert> {
        val ts = targets.sortedBy { it.kind.ordinal }.distinctBy { it.at.withSecond(0).withNano(0) }
        return ts.flatMap { t -> stages.map { m -> HeadsAlert(t.at.minusMinutes(m.toLong()), m, t) } }
            .filter { it.at.isAfter(now) && !quiet(it.at.toLocalTime(), quietFrom, quietTo) }
            .sortedBy { it.at }
    }

    /** The notification's words for a buzz. */
    fun words(a: HeadsAlert): Pair<String, String> {
        val m = a.minutes
        val t = a.target
        return when (t.kind) {
            HeadsTarget.Kind.LEAVE -> when {
                m >= 10 -> "$m min until you leave" to "${t.label} · start wrapping up"
                m > 1 -> "$m min · shoes and coat" to "Tap to open Launch Pad"
                else -> "1 min · time to go" to t.label
            }
            HeadsTarget.Kind.EVENT -> (if (m > 1) "$m min until ${t.label}" else "${t.label} starts in 1 min") to
                (if (m >= 10) "Start wrapping up" else "Time to switch")
            HeadsTarget.Kind.FOCUS -> (if (m > 1) "$m min left in Focus" else "Focus ends in 1 min") to
                (if (m >= 5) "Note where you are, so it's easy to pick up" else t.label)
        }
    }
}
