package uk.tomlowndes.capture

import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Claude stopped because this month's cap is used up. */
class CapReached(message: String) : IllegalStateException(message)

/** This month's spend: who spent what (pounds) against the cap. */
data class CapSummary(val month: String, val capGbp: Double?, val shared: Boolean, val byFeature: Map<String, Double>) {
    val spentGbp get() = byFeature.values.sum()
    val share get() = capGbp?.takeIf { it > 0 }?.let { spentGbp / it } ?: 0.0
    val warn get() = capGbp != null && share >= ClaudeCap.WARN && share < 1.0
    val paused get() = capGbp != null && share >= 1.0
}

/**
 * Claude spend cap (extras-v1, idea 18). Each device keeps its own spend by feature and writes it to
 * `99 - Meta/Claude spend/<device>.md`; the cap lives in `99 - Meta/Claude spend/Cap.md` so phone and PC agree.
 * With "shared" on, the other device's spend and the box's inbox cost count too. Warn at 80%, stop at 100%:
 * every call through [PhoneBrain] checks [check] first.
 */
object ClaudeCap {
    const val DIR = "${FlightNotes.DIR}/Claude spend"
    const val CAP = "$DIR/Cap.md"
    const val WARN = 0.8
    const val BOX = "Inbox (box)"
    val CAPS = listOf(5.0, 10.0, 15.0, 20.0, 30.0, 50.0)
    private val ROW = Regex("^- (.+?) · \\$(\\d+(?:\\.\\d+)?)\\s*$")
    private val RESUME = DateTimeFormatter.ofPattern("d MMM", Locale.UK)

    // In memory, for the gate: own spend is kept fresh by the app, the rest by [sync].
    @Volatile var capGbp: Double? = null
    @Volatile var shared: Boolean = true
    @Volatile var ownUsd: Double = 0.0
    @Volatile var othersGbp: Double = 0.0

    fun spentGbp() = ownUsd * PhoneBrain.GBP_PER_USD + (if (shared) othersGbp else 0.0)
    fun paused(): Boolean = capGbp?.let { it > 0 && spentGbp() >= it } ?: false
    fun resumes(today: LocalDate = LocalDate.now()): LocalDate = YearMonth.from(today).plusMonths(1).atDay(1)
    fun pausedText(today: LocalDate = LocalDate.now()) = "Claude paused until ${resumes(today).format(RESUME)}. This month's cap is used up."

    /** Throws [CapReached] when the cap is used up. */
    fun check() { if (paused()) throw CapReached(pausedText()) }

    // ---- per-feature ledger (micro-dollars), stored by each app as "2026-11|Jarvis=1200;Snap=300" ----

    fun ledger(raw: String?, now: YearMonth = YearMonth.now()): Map<String, Long> {
        val s = raw.orEmpty()
        if (s.substringBefore("|") != now.toString()) return emptyMap()
        return s.substringAfter("|", "").split(";").mapNotNull { p ->
            val k = p.substringBefore("=").trim(); val n = p.substringAfter("=", "").toLongOrNull()
            if (k.isEmpty() || n == null) null else k to n
        }.toMap()
    }

    fun ledgerRaw(m: Map<String, Long>, now: YearMonth = YearMonth.now()) =
        now.toString() + "|" + m.entries.joinToString(";") { "${it.key.replace(Regex("[;=|]"), " ")}=${it.value}" }

    fun added(raw: String?, feature: String, usd: Double, now: YearMonth = YearMonth.now()): String {
        val m = ledger(raw, now).toMutableMap()
        m[feature] = (m[feature] ?: 0) + Math.round(usd * 1e6)
        return ledgerRaw(m, now)
    }

    // ---- vault ----

    fun deviceNote(device: String, m: Map<String, Long>, now: YearMonth = YearMonth.now()) =
        "---\ntype: claude-spend\nmonth: $now\ndevice: $device\n---\n\nWritten by Flight Deck on the $device. Dollars this month, by feature.\n\n" +
            m.entries.sortedByDescending { it.value }.joinToString("") { "- ${it.key} · \$${"%.4f".format(Locale.ROOT, it.value / 1e6)}\n" }

    /** (month, feature to dollars) from a device note. */
    fun parseDevice(text: String): Pair<String, Map<String, Double>> {
        val lines = text.replace("\r", "").split("\n")
        val month = Md.fmGet(lines, "month")?.trim().orEmpty()
        return month to lines.mapNotNull { ROW.find(it.trim())?.groupValues?.let { g -> g[1].trim() to g[2].toDouble() } }.toMap()
    }

    fun parseCap(text: String?): Pair<Double?, Boolean> {
        val lines = text.orEmpty().replace("\r", "").split("\n")
        val cap = Md.fmGet(lines, "cap_gbp")?.trim()?.toDoubleOrNull()?.takeIf { it > 0 }
        val sh = Md.fmGet(lines, "shared")?.trim()?.lowercase() != "false"
        return cap to sh
    }

    fun saveCap(v: Vault, cap: Double?, shared: Boolean) {
        val body = "---\ntype: claude-cap\ncap_gbp: ${cap?.let { "%.0f".format(Locale.ROOT, it) } ?: ""}\nshared: $shared\n---\n\n" +
            "Monthly Claude cap in pounds, set in Flight Deck › Settings › Claude. Empty means no cap.\n"
        if (v.entry(CAP) == null) { v.mkdirs(DIR); VaultIo.write(v, v.create(DIR, "Cap", "md"), body) } else VaultIo.write(v, CAP, body)
        capGbp = cap; this.shared = shared
    }

    /**
     * Writes this device's note, reads the cap, the other devices and the box, and updates the gate.
     * [own] is this device's ledger; [boxGbp] the inbox digest's cost this month.
     */
    fun sync(v: Vault, device: String, own: Map<String, Long>, boxGbp: Double?, now: YearMonth = YearMonth.now()): CapSummary {
        val mine = "$DIR/$device.md"
        runCatching {
            val text = deviceNote(device, own, now)
            if (v.entry(mine) == null) { v.mkdirs(DIR); VaultIo.write(v, v.create(DIR, device, "md"), text) } else VaultIo.write(v, mine, text)
        }
        val (cap, sh) = parseCap(runCatching { v.read(CAP) }.getOrNull())
        val others = runCatching { v.list(DIR) }.getOrDefault(emptyList())
            .filter { !it.isDir && it.name.endsWith(".md") && it.name != "Cap.md" && it.name != "$device.md" }
            .mapNotNull { e -> runCatching { parseDevice(VaultIo.read(v, "$DIR/${e.name}")) }.getOrNull() }
            .filter { it.first == now.toString() }.map { it.second }
        capGbp = cap; shared = sh
        ownUsd = own.values.sum() / 1e6
        othersGbp = summary(now, cap, true, emptyMap(), others, boxGbp).spentGbp
        return summary(now, cap, sh, own, others, boxGbp)
    }

    fun summary(now: YearMonth, cap: Double?, shared: Boolean, own: Map<String, Long>, others: List<Map<String, Double>>, boxGbp: Double?): CapSummary {
        val by = linkedMapOf<String, Double>()
        own.forEach { (k, n) -> by[k] = (by[k] ?: 0.0) + n / 1e6 * PhoneBrain.GBP_PER_USD }
        if (shared) {
            others.forEach { m -> m.forEach { (k, usd) -> by[k] = (by[k] ?: 0.0) + usd * PhoneBrain.GBP_PER_USD } }
            boxGbp?.takeIf { it > 0 }?.let { by[BOX] = (by[BOX] ?: 0.0) + it }
        }
        return CapSummary(now.toString(), cap, shared, by.entries.sortedByDescending { it.value }.associate { it.key to it.value })
    }

    fun money(gbp: Double): String = if (gbp < 0.01) "0p" else if (gbp < 1) "${Math.round(gbp * 100)}p" else "£%.2f".format(Locale.UK, gbp)

    /** "£3.12 of £10" or "£3.12 · no cap". */
    fun label(s: CapSummary) = money(s.spentGbp) + (s.capGbp?.let { " of £%.0f".format(Locale.UK, it) } ?: " · no cap")

    /** "Jarvis £1.40 · Snap 62p · …" (the biggest first). */
    fun breakdown(s: CapSummary, max: Int = 6): String =
        s.byFeature.entries.filter { it.value >= 0.005 }.take(max).joinToString(" · ") { "${it.key} ${money(it.value)}" }.ifEmpty { "Nothing yet this month" }

    /** One line for the Deck at 80% or more, or null. */
    fun banner(s: CapSummary, today: LocalDate = LocalDate.now()): String? = when {
        s.paused -> pausedText(today)
        s.warn -> "Claude: ${label(s)} this month (${Math.round(s.share * 100)}%)"
        else -> null
    }
}
