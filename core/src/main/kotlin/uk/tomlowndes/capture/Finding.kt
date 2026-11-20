package uk.tomlowndes.capture

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/** Makes a note from [starter] the first time it's needed. */
private fun ensure(v: Vault, path: String, starter: String) {
    if (v.entry(path) != null) return
    v.mkdirs(FlightNotes.DIR)
    VaultIo.write(v, v.create(FlightNotes.DIR, nameOf(path).removeSuffix(".md"), "md"), starter)
}

/** Keeps the note's header (everything before the first list line) and replaces the list with [body]. */
private fun rewrite(v: Vault, path: String, body: List<String>) = VaultIo.edit(v, path) { lines ->
    val head = lines.takeWhile { !it.trimStart().startsWith("- ") }.toMutableList()
    while (head.isNotEmpty() && head.last().isBlank()) head.removeAt(head.lastIndex)
    lines.clear(); lines.addAll(head); lines.add(""); lines.addAll(body)
}

/**
 * One Pocket Pass code: `- Home · 🔒 Wi-Fi Lowndes-5G · hunter2`, or a booking that goes after its day:
 * `- Bookings · Train to Leeds · TRL-8KQ2M · 📅 2026-11-21`.
 */
data class PassCode(val group: String, val name: String, val value: String, val hidden: Boolean = false, val until: LocalDate? = null) {
    val isWifi get() = name.lowercase(Locale.UK).let { it.startsWith("wi-fi") || it.startsWith("wifi") }
    /** The network name of a Wi-Fi code: whatever follows "Wi-Fi". */
    val ssid get() = name.substringAfter(' ', "").trim()
}

/**
 * Pocket Pass (hangar-phase2): the codes you're always hunting for, one tap from copied. Kept in a vault note; it's
 * left out of vault search so codes don't turn up there or reach Claude.
 */
object PocketPass {
    const val NOTE = "${FlightNotes.DIR}/Pocket Pass.md"
    val GROUPS = listOf("Me", "Home", "Bookings", "Other")

    val STARTER = """
        # Pocket Pass

        One code per line: group · name · code. Put 🔒 before the name to hide it until tapped, and
        add · 📅 YYYY-MM-DD to a booking so it goes after that day. A Wi-Fi code is named "Wi-Fi <network>".
        This note is left out of Flight Deck's search and never sent to Claude.
    """.trimIndent() + "\n"

    fun parse(text: String): List<PassCode> = text.lines().mapNotNull { line(it) }

    fun line(raw: String): PassCode? {
        val body = raw.trim().takeIf { it.startsWith("- ") && !it.startsWith("- [") }?.removePrefix("- ") ?: return null
        val parts = body.split(" · ").map { it.trim() }
        if (parts.size < 3) return null
        val until = parts.drop(3).firstOrNull { it.startsWith("📅") }?.removePrefix("📅")?.trim()?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val hidden = parts[1].startsWith("🔒")
        val name = parts[1].removePrefix("🔒").trim()
        if (name.isEmpty() || parts[2].isEmpty()) return null
        return PassCode(parts[0].ifEmpty { "Other" }, name, parts[2], hidden, until)
    }

    fun format(c: PassCode) = "- ${c.group} · ${if (c.hidden) "🔒 " else ""}${c.name} · ${c.value}" + (c.until?.let { " · 📅 $it" } ?: "")

    /** What shows today, grouped in [GROUPS] order: bookings past their day drop off. */
    fun visible(codes: List<PassCode>, today: LocalDate): List<PassCode> =
        codes.filter { it.until == null || !it.until.isBefore(today) }
            .sortedBy { GROUPS.indexOf(it.group).let { i -> if (i < 0) GROUPS.size else i } }

    /** All but the last four characters dotted out, keeping the spaces: "485 777 4821" → "••• ••• 4821". */
    fun masked(value: String): String {
        var keep = 4
        return value.reversed().map { ch -> if (ch == ' ') ' ' else if (keep-- > 0) ch else '•' }.joinToString("").reversed()
            .let { if (value.count { it != ' ' } <= 4) "•".repeat(value.length) else it }
    }

    /** The text a phone camera reads to join a Wi-Fi network. */
    fun wifiText(ssid: String, password: String): String {
        fun esc(s: String) = s.replace(Regex("""([\\;,:"])"""), "\\\\$1")
        return "WIFI:T:WPA;S:${esc(ssid)};P:${esc(password)};;"
    }

    fun qr(text: String): Array<BooleanArray> {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0,
            mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M))
        return Array(m.height) { y -> BooleanArray(m.width) { x -> m.get(x, y) } }
    }

    fun load(v: Vault): List<PassCode> { ensure(v, NOTE, STARTER); return parse(VaultIo.read(v, NOTE)) }

    fun add(v: Vault, c: PassCode) { ensure(v, NOTE, STARTER); VaultIo.edit(v, NOTE) { l -> while (l.isNotEmpty() && l.last().isBlank()) l.removeAt(l.lastIndex); l.add(format(c)) } }

    fun remove(v: Vault, c: PassCode) = VaultIo.edit(v, NOTE) { l -> l.indexOfFirst { line(it) == c }.takeIf { it >= 0 }?.let(l::removeAt) }

    /** Clears bookings whose day has gone, so the note doesn't fill up. */
    fun tidy(v: Vault, today: LocalDate) = VaultIo.edit(v, NOTE) { l -> l.removeAll { line(it)?.until?.isBefore(today.minusDays(7)) == true } }
}

/**
 * One Last Seen entry: `- [ ] 2026-11-20 10:02 · 🚗 Car · Bay 41 · 📍 NCP Broad St · geo:52.48,-1.9 · ![[20261120-100200.jpg]]`.
 * Ticked with `· found 2026-11-22` when it turns up.
 */
data class Seen(
    val at: LocalDateTime, val emoji: String, val what: String, val note: String = "", val place: String? = null,
    val lat: Double? = null, val lon: Double? = null, val photo: String? = null, val found: LocalDate? = null,
)

/** Last Seen (hangar-phase2): snap where you put something, then find it with "where's my…". */
object LastSeen {
    const val NOTE = "${FlightNotes.DIR}/Last Seen.md"
    const val PHOTOS = "${FlightNotes.DIR}/Last Seen"
    /** Found things stay this many days, then go. */
    const val KEEP_DAYS = 30L
    val KINDS = listOf("🚗" to "Car", "🔑" to "Keys", "📦" to "Parcel", "🔌" to "Charger", "🎒" to "Bag")

    val STARTER = """
        # Last Seen

        Where you put things, newest first. Photos are in the Last Seen folder next to this note.
    """.trimIndent() + "\n"

    fun parse(text: String): List<Seen> = text.lines().mapNotNull { line(it) }

    fun line(raw: String): Seen? {
        val t = raw.trim()
        val done = t.startsWith("- [x] ")
        if (!done && !t.startsWith("- [ ] ")) return null
        val parts = t.drop(6).split(" · ").map { it.trim() }
        val at = runCatching { LocalDateTime.parse(parts[0], STAMP) }.getOrNull() ?: return null
        val thing = parts.getOrNull(1) ?: return null
        val emoji = thing.substringBefore(" ").takeIf { thing.contains(" ") && !it.first().isLetterOrDigit() } ?: "•"
        val what = if (emoji == "•") thing else thing.substringAfter(" ")
        var note = ""; var place: String? = null; var lat: Double? = null; var lon: Double? = null; var photo: String? = null; var found: LocalDate? = null
        parts.drop(2).forEach { p ->
            when {
                p.startsWith("📍") -> place = p.removePrefix("📍").trim()
                p.startsWith("geo:") -> p.removePrefix("geo:").split(",").let { lat = it.getOrNull(0)?.toDoubleOrNull(); lon = it.getOrNull(1)?.toDoubleOrNull() }
                p.startsWith("![[") -> photo = p.removePrefix("![[").removeSuffix("]]")
                p.startsWith("found ") -> found = runCatching { LocalDate.parse(p.removePrefix("found ")) }.getOrNull()
                else -> note = if (note.isEmpty()) p else "$note · $p"
            }
        }
        return Seen(at, emoji, what, note, place, lat, lon, photo, if (done) found ?: at.toLocalDate() else null)
    }

    fun format(s: Seen): String = buildString {
        append(if (s.found != null) "- [x] " else "- [ ] ")
        append(s.at.format(STAMP)).append(" · ").append(if (s.emoji == "•") s.what else "${s.emoji} ${s.what}")
        if (s.note.isNotBlank()) append(" · ").append(s.note.replace(" · ", ", ").trim())
        s.place?.let { append(" · 📍 ").append(it) }
        if (s.lat != null && s.lon != null) append(" · geo:").append("%.5f,%.5f".format(Locale.UK, s.lat, s.lon))
        s.photo?.let { append(" · ![[").append(it).append("]]") }
        s.found?.let { append(" · found ").append(it) }
    }

    /** Still out first, newest first; found ones after, until they're [KEEP_DAYS] old. */
    fun ordered(items: List<Seen>, today: LocalDate): List<Seen> =
        items.filter { it.found == null }.sortedByDescending { it.at } +
            items.filter { it.found != null && ChronoUnit.DAYS.between(it.found, today) <= KEEP_DAYS }.sortedByDescending { it.found }

    /** "Where's my…": every word of [q] in the name, note or place. */
    fun search(items: List<Seen>, q: String): List<Seen> {
        val words = q.lowercase(Locale.UK).removePrefix("where's my").removePrefix("wheres my").split(" ").filter { it.isNotBlank() }
        if (words.isEmpty()) return items
        return items.filter { s -> val hay = "${s.what} ${s.note} ${s.place.orEmpty()}".lowercase(Locale.UK); words.all { it in hay } }
    }

    /** "2h ago", "Tue", "3 Nov". */
    fun ago(at: LocalDateTime, now: LocalDateTime): String {
        val mins = Duration.between(at, now).toMinutes()
        return when {
            mins < 1 -> "just now"
            mins < 60 -> "${mins}m ago"
            at.toLocalDate() == now.toLocalDate() -> "${mins / 60}h ago"
            ChronoUnit.DAYS.between(at.toLocalDate(), now.toLocalDate()) < 7 -> at.format(DateTimeFormatter.ofPattern("EEE", Locale.UK))
            else -> at.format(DateTimeFormatter.ofPattern("d MMM", Locale.UK))
        }
    }

    fun photoName(at: LocalDateTime) = at.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".jpg"

    fun load(v: Vault): List<Seen> { ensure(v, NOTE, STARTER); return parse(VaultIo.read(v, NOTE)) }

    /** Saves [s] on top and drops found ones past [KEEP_DAYS] (their photos too). */
    fun add(v: Vault, s: Seen, today: LocalDate = s.at.toLocalDate()) = save(v, listOf(s) + load(v), today)

    fun markFound(v: Vault, s: Seen, on: LocalDate) = save(v, load(v).map { if (it == s) it.copy(found = on) else it }, on)

    fun unfound(v: Vault, s: Seen) = save(v, load(v).map { if (it == s) it.copy(found = null) else it }, LocalDate.now())

    private fun save(v: Vault, items: List<Seen>, today: LocalDate) {
        val (old, keep) = items.partition { it.found != null && ChronoUnit.DAYS.between(it.found, today) > KEEP_DAYS }
        old.forEach { o -> o.photo?.let { p -> runCatching { v.delete("$PHOTOS/$p") } } }
        rewrite(v, NOTE, keep.map(::format))
    }
}

/** Where a return has got to. */
enum class ReturnStep(val label: String) { NONE("To do"), PACKED("Packed"), DROPPED("Dropped off"), REFUNDED("Refund in") }

/** One return: `- [ ] Boots · ASOS · 📅 2026-11-21 · packed · ![[receipt.jpg]]`. Ticked once the refund is in. */
data class Return(val item: String, val shop: String, val by: LocalDate, val step: ReturnStep = ReturnStep.NONE, val receipt: String? = null)

/** Return Radar (hangar-phase2): return-by dates, counted down in days, with buzzes 3 days before and on the day. */
object Returns {
    const val NOTE = "${FlightNotes.DIR}/Returns.md"
    const val SOON_DAYS = 3L
    val WINDOWS = listOf(14, 28, 30)

    val STARTER = """
        # Returns

        Parcels to send back: item · shop · 📅 return-by date, then where it's got to (packed, dropped). Ticked once the refund is in.
    """.trimIndent() + "\n"

    fun parse(text: String): List<Return> = text.lines().mapNotNull { line(it) }

    fun line(raw: String): Return? {
        val t = raw.trim()
        val done = t.startsWith("- [x] ")
        if (!done && !t.startsWith("- [ ] ")) return null
        val parts = t.drop(6).split(" · ").map { it.trim() }
        val by = parts.firstOrNull { it.startsWith("📅") }?.removePrefix("📅")?.trim()?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
        val item = parts.getOrNull(0)?.takeIf { it.isNotEmpty() && !it.startsWith("📅") } ?: return null
        val shop = parts.getOrNull(1)?.takeIf { !it.startsWith("📅") }.orEmpty()
        val step = when {
            done -> ReturnStep.REFUNDED
            parts.any { it == "dropped" } -> ReturnStep.DROPPED
            parts.any { it == "packed" } -> ReturnStep.PACKED
            else -> ReturnStep.NONE
        }
        val receipt = parts.firstOrNull { it.startsWith("![[") }?.removePrefix("![[")?.removeSuffix("]]")
        return Return(item, shop, by, step, receipt)
    }

    fun format(r: Return): String = buildString {
        append(if (r.step == ReturnStep.REFUNDED) "- [x] " else "- [ ] ")
        append(r.item.replace(" · ", ", "))
        if (r.shop.isNotBlank()) append(" · ").append(r.shop.replace(" · ", ", "))
        append(" · 📅 ").append(r.by)
        when (r.step) { ReturnStep.PACKED -> append(" · packed"); ReturnStep.DROPPED, ReturnStep.REFUNDED -> append(" · dropped"); else -> {} }
        r.receipt?.let { append(" · ![[").append(it).append("]]") }
    }

    fun byFor(arrived: LocalDate, windowDays: Int): LocalDate = arrived.plusDays(windowDays.toLong())

    fun daysLeft(r: Return, today: LocalDate) = ChronoUnit.DAYS.between(today, r.by)

    /** "1 day", "Today", "2 days late". */
    fun countdown(r: Return, today: LocalDate): String = daysLeft(r, today).let { d ->
        when { d == 0L -> "Today"; d == 1L -> "1 day"; d > 1 -> "$d days"; d == -1L -> "1 day late"; else -> "${-d} days late" }
    }

    /** Not sent back yet. */
    fun open(r: Return) = r.step < ReturnStep.DROPPED

    /** Open returns soonest first, then ones waiting on a refund, then refunded ones from the last 30 days. */
    fun ordered(rs: List<Return>, today: LocalDate): List<Return> =
        rs.filter(::open).sortedBy { it.by } + rs.filter { it.step == ReturnStep.DROPPED }.sortedBy { it.by } +
            rs.filter { it.step == ReturnStep.REFUNDED && ChronoUnit.DAYS.between(it.by, today) <= 30 }

    /** The buzz times for open returns: 09:00 three days before and 09:00 on the day, if still to come. */
    fun buzzes(rs: List<Return>, now: LocalDateTime): List<Pair<LocalDateTime, Return>> =
        rs.filter(::open).flatMap { r -> listOf(r.by.minusDays(SOON_DAYS).atTime(9, 0), r.by.atTime(9, 0)).map { it to r } }
            .filter { it.first.isAfter(now) }.sortedBy { it.first }

    fun load(v: Vault): List<Return> { ensure(v, NOTE, STARTER); return parse(VaultIo.read(v, NOTE)) }

    fun add(v: Vault, r: Return) = rewrite(v, NOTE, (load(v) + r).map(::format))

    fun update(v: Vault, old: Return, new: Return) = rewrite(v, NOTE, load(v).map { if (it == old) new else it }.map(::format))

    fun remove(v: Vault, r: Return) = rewrite(v, NOTE, load(v).filter { it != r }.map(::format))
}
