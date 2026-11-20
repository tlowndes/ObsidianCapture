package uk.tomlowndes.capture

import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

enum class SpendCategory(val label: String) { BILLS("Bills"), FOOD("Food"), HOME("Home"), FUN("Fun"), OTHER("Other") }

/** Where a receipt came from: a photo (Snap), an email (the Inbox digest) or a #bill task ticked as paid. */
enum class SpendSource(val emoji: String, val label: String) { SNAP("📷", "snapped"), EMAIL("✉", "email receipt"), BILL("£", "bill") }

data class Receipt(val date: LocalDate, val shop: String, val amount: BigDecimal, val category: SpendCategory, val source: SpendSource, val key: String)

data class MonthSpend(val month: YearMonth, val receipts: List<Receipt>) {
    val total: BigDecimal get() = receipts.fold(BigDecimal.ZERO) { a, r -> a + r.amount }
    fun by(c: SpendCategory): BigDecimal = receipts.filter { it.category == c }.fold(BigDecimal.ZERO) { a, r -> a + r.amount }
    /** Categories with money in them, biggest first. */
    val categories: List<Pair<SpendCategory, BigDecimal>> get() = SpendCategory.values().map { it to by(it) }.filter { it.second.signum() > 0 }.sortedByDescending { it.second }
    val latest: List<Receipt> get() = receipts.sortedByDescending { it.date }
}

/**
 * Spend from receipts (meals-spend-v1): no bank login. It counts what it sees: snapped receipts, email receipts from the
 * Inbox digest, and #bill tasks ticked this month. Receipts are one line each in "99 - Meta/Spend/yyyy-mm.md"; bills are
 * read from the ticked tasks, so they're never written twice. A rough guide, and it says how many receipts it's from.
 */
object Spend {
    const val DIR = "99 - Meta/Spend"
    fun path(m: YearMonth) = "$DIR/$m.md"
    private val LINE = Regex("^- (\\d{4}-\\d{2}-\\d{2}) · (.+?) · £([0-9.,]+) · (\\w+) · (\\w+)(?: <!--k:([^>]+)-->)?\\s*$")

    private val WORDS = mapOf(
        SpendCategory.FOOD to listOf("tesco", "sainsbury", "asda", "aldi", "lidl", "co-op", "coop", "m&s", "marks", "waitrose", "morrisons", "iceland",
            "ocado", "greggs", "pret", "deliveroo", "just eat", "uber eats", "costa", "starbucks", "cafe", "café", "bakery", "butcher"),
        SpendCategory.HOME to listOf("b&q", "ikea", "argos", "screwfix", "wickes", "homebase", "dunelm", "the range", "wilko", "toolstation", "boots", "superdrug"),
        SpendCategory.BILLS to listOf("octopus", "council", "water", "energy", "gas", "electric", "broadband", "virgin", "bt ", "sky", "ee ", "vodafone",
            "three", "o2", "insurance", "tv licence", "rent", "mortgage"),
        SpendCategory.FUN to listOf("cinema", "odeon", "vue", "netflix", "spotify", "steam", "playstation", "xbox", "nintendo", "ticketmaster", "pub", "bar ",
            "disney", "apple.com", "google play", "waterstones"),
    )

    /** A first guess from the shop's name; Tom can change it with a tap. */
    fun guess(shop: String): SpendCategory {
        val s = " ${shop.lowercase()} "
        return WORDS.entries.firstOrNull { (_, w) -> w.any { it in s } }?.key ?: SpendCategory.OTHER
    }

    fun key(date: LocalDate, shop: String, amount: BigDecimal) = "%08x".format("$date|${shop.lowercase().trim()}|${amount.setScale(2)}".hashCode())

    fun receipt(date: LocalDate, shop: String, amount: BigDecimal, source: SpendSource, category: SpendCategory? = null) =
        Receipt(date, shop.trim().replace("·", "-"), amount.setScale(2, java.math.RoundingMode.HALF_UP), category ?: guess(shop), source, key(date, shop, amount))

    fun line(r: Receipt) = "- ${r.date} · ${r.shop} · £${r.amount.toPlainString()} · ${r.category.label} · ${r.source.name.lowercase()} <!--k:${r.key}-->"

    fun parse(text: String): List<Receipt> = text.replace("\r", "").split("\n").mapNotNull { l ->
        val g = LINE.find(l.trim())?.groupValues ?: return@mapNotNull null
        val date = runCatching { LocalDate.parse(g[1]) }.getOrNull() ?: return@mapNotNull null
        val amount = runCatching { BigDecimal(g[3].replace(",", "")) }.getOrNull() ?: return@mapNotNull null
        val cat = SpendCategory.values().firstOrNull { it.label.equals(g[4], true) } ?: SpendCategory.OTHER
        val src = SpendSource.values().firstOrNull { it.name.equals(g[5], true) } ?: SpendSource.SNAP
        Receipt(date, g[2], amount, cat, src, g[6].ifEmpty { key(date, g[2], amount) })
    }

    /** Adds [r] to its month's note unless it's already there. True when added. */
    fun add(v: Vault, r: Receipt): Boolean {
        val p = path(YearMonth.from(r.date))
        val text = v.read(p)
        if (text != null && parse(text).any { it.key == r.key }) return false
        if (text == null) {
            v.mkdirs(DIR)
            VaultIo.write(v, v.create(DIR, YearMonth.from(r.date).toString(), "md"), "---\ntype: spend\nmonth: ${YearMonth.from(r.date)}\n---\n\n${line(r)}\n")
        } else VaultIo.edit(v, p) { lines ->
            while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.lastIndex)
            lines.add(line(r)); lines.add("")
        }
        return true
    }

    /** Changes a receipt's category. A bill (not in the note) gets written with the new category, which then wins. */
    fun recategorise(v: Vault, r: Receipt, c: SpendCategory) {
        val p = path(YearMonth.from(r.date))
        val there = v.read(p)?.let { parse(it).any { x -> x.key == r.key } } == true
        if (!there) { add(v, r.copy(category = c)); return }
        VaultIo.edit(v, p) { lines ->
            val i = lines.indexOfFirst { l -> parse(l).firstOrNull()?.key == r.key }
            if (i >= 0) lines[i] = line(r.copy(category = c))
        }
    }

    /** Bills ticked as paid in [m], as receipts (category Bills). */
    fun bills(done: List<TickedTask>, m: YearMonth): List<Receipt> {
        val bills = TaskViews.billsMonth(emptyList(), done, m.atDay(1)).paid
        return bills.mapNotNull { b ->
            val amount = b.amount ?: return@mapNotNull null
            val day = done.firstOrNull { it.raw == b.task.raw }?.done ?: b.task.due ?: m.atDay(1)
            receipt(day, b.name, amount, SpendSource.BILL, SpendCategory.BILLS)
        }
    }

    /** The month: receipts from the note, plus paid bills not already written there. */
    fun month(v: Vault, done: List<TickedTask>, m: YearMonth): MonthSpend {
        val saved = v.read(path(m))?.let { parse(it) }.orEmpty()
        val keys = saved.map { it.key }.toSet()
        return MonthSpend(m, saved + bills(done, m).filter { it.key !in keys })
    }

    /** "£48 more on food than October by now": compares the same days of last month, for the category that moved most. */
    fun compare(now: MonthSpend, last: MonthSpend, today: LocalDate): String? {
        val day = today.dayOfMonth
        val before = MonthSpend(last.month, last.receipts.filter { it.date.dayOfMonth <= day })
        val (c, diff) = SpendCategory.values().filter { it != SpendCategory.BILLS }
            .map { it to now.by(it) - before.by(it) }.maxByOrNull { it.second.abs() } ?: return null
        if (diff.abs() < BigDecimal(10) || before.receipts.isEmpty()) return null
        val month = last.month.month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.UK)
        return "${TaskViews.money(diff.abs().setScale(0, java.math.RoundingMode.HALF_UP))} ${if (diff.signum() > 0) "more" else "less"} on ${c.label.lowercase()} than $month by now"
    }

    /** From the digest: email receipts (kind receipt with a shop and amount). */
    fun fromMail(m: Mail): Receipt? {
        if (m.kind != MailKind.RECEIPT) return null
        val amount = m.amount ?: return null
        return receipt(m.date?.toLocalDate() ?: m.due ?: return null, m.shop ?: m.from, amount, SpendSource.EMAIL)
    }

    /** Adds any new email receipts from the digest. Returns how many were added. */
    fun syncMail(v: Vault, d: Digest): Int = d.items.mapNotNull { fromMail(it) }.count { add(v, it) }
}
