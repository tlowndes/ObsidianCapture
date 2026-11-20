package uk.tomlowndes.capture

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor

private val STAMP_AT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

private fun stampOf(s: String): LocalDateTime? = runCatching { LocalDateTime.parse(s.trim(), STAMP_AT) }.getOrNull()

/** Makes a Day to day note from [starter] the first time it's needed. */
private fun ensureNote(v: Vault, path: String, starter: String) {
    if (v.entry(path) != null) return
    v.mkdirs(FlightNotes.DIR)
    VaultIo.write(v, v.create(FlightNotes.DIR, path.substringAfterLast('/').removeSuffix(".md"), "md"), starter)
}

/** Keeps the note's header (everything before the first list line) and replaces the list with [body]. */
private fun rewriteNote(v: Vault, path: String, body: List<String>) = VaultIo.edit(v, path) { lines ->
    val head = lines.takeWhile { !it.trimStart().startsWith("- ") }.toMutableList()
    while (head.isNotEmpty() && head.last().isBlank()) head.removeAt(head.lastIndex)
    lines.clear(); lines.addAll(head); lines.add(""); lines.addAll(body)
}

/** "£249", or "£249.50" when there are pence. */
fun money(p: Double): String = if (p % 1.0 == 0.0) "£${p.toLong()}" else "£" + "%.2f".format(Locale.UK, p)

// ---- Cool-off ----

enum class CoolStep { COOLING, BOUGHT, LET_GO }

/** One price seen on the shop's page. */
data class PricePoint(val on: LocalDate, val price: Double)

/**
 * One Cool-off item: `- 2026-11-20 17:40 · Desk lamp · £45 · https://ikea.com/… · ⏳ 48h`, then once decided
 * `· ✗ let go 2026-11-22` or `· ✓ bought 2026-11-22`, and any tracked prices `· 📉 2026-11-21 £39, 2026-11-22 £42`.
 */
data class CoolItem(
    val what: String, val added: LocalDateTime, val price: Double? = null, val link: String? = null, val hours: Int = CoolOff.HOURS,
    val step: CoolStep = CoolStep.COOLING, val decided: LocalDate? = null, val prices: List<PricePoint> = emptyList(),
) {
    val dueAt: LocalDateTime get() = added.plusHours(hours.toLong())
    /** The latest tracked price, else the one you typed. */
    val now: Double? get() = prices.lastOrNull()?.price ?: price
    /** What it cost when you added it: the price you typed, else the first one tracked. */
    val was: Double? get() = price ?: prices.firstOrNull()?.price
}

/**
 * Cool-off (hangar-phase3): a wait before impulse buys. When the wait's up it asks once whether you still want it;
 * letting it go adds to a "not spent" total. Items with a link have their price tracked while they cool.
 */
object CoolOff {
    const val NOTE = "${FlightNotes.DIR}/Cool-off.md"
    const val HOURS = 48
    val WAITS = listOf(24, 48, 168)
    /** Prices kept per item: the first plus the latest few. */
    private const val KEEP_PRICES = 8

    val STARTER = """
        # Cool-off

        Things you wanted to buy, waiting before you do: added time · item · price · link · ⏳ wait.
        Flight Deck asks once when the wait's up, and tracks the price of anything with a link.
    """.trimIndent() + "\n"

    private val PRICE = Regex("""^£\s?(\d[\d,]*(?:\.\d{1,2})?)$""")
    private val WAIT = Regex("""^⏳\s*(\d+)\s*h$""")
    private val POINT = Regex("""(\d{4}-\d{2}-\d{2})\s+£\s?(\d[\d,]*(?:\.\d{1,2})?)""")
    private val URL = Regex("""https?://\S+""")

    fun parse(text: String): List<CoolItem> = text.lines().mapNotNull { line(it) }

    fun line(raw: String): CoolItem? {
        val body = raw.trim().takeIf { it.startsWith("- ") && !it.startsWith("- [") }?.removePrefix("- ") ?: return null
        val parts = body.split(" · ").map { it.trim() }
        val added = parts.getOrNull(0)?.let(::stampOf) ?: return null
        val what = parts.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: return null
        var item = CoolItem(what, added)
        parts.drop(2).forEach { p ->
            when {
                PRICE.matches(p) -> item = item.copy(price = PRICE.find(p)!!.groupValues[1].replace(",", "").toDouble())
                p.startsWith("http") -> item = item.copy(link = p)
                WAIT.matches(p) -> item = item.copy(hours = WAIT.find(p)!!.groupValues[1].toInt())
                p.startsWith("✗") -> item = item.copy(step = CoolStep.LET_GO, decided = dateIn(p))
                p.startsWith("✓") -> item = item.copy(step = CoolStep.BOUGHT, decided = dateIn(p))
                p.startsWith("📉") -> item = item.copy(prices = POINT.findAll(p).mapNotNull { m ->
                    runCatching { PricePoint(LocalDate.parse(m.groupValues[1]), m.groupValues[2].replace(",", "").toDouble()) }.getOrNull()
                }.toList())
            }
        }
        return item
    }

    private fun dateIn(s: String) = Regex("""\d{4}-\d{2}-\d{2}""").find(s)?.value?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    fun format(c: CoolItem): String = buildString {
        append("- ").append(c.added.format(STAMP_AT)).append(" · ").append(c.what.replace(" · ", ", "))
        c.price?.let { append(" · ").append(money(it)) }
        c.link?.let { append(" · ").append(it) }
        append(" · ⏳ ").append(c.hours).append("h")
        when (c.step) {
            CoolStep.LET_GO -> append(" · ✗ let go").append(c.decided?.let { " $it" } ?: "")
            CoolStep.BOUGHT -> append(" · ✓ bought").append(c.decided?.let { " $it" } ?: "")
            else -> {}
        }
        if (c.prices.isNotEmpty()) append(" · 📉 ").append(c.prices.joinToString(", ") { "${it.on} ${money(it.price)}" })
    }

    fun left(c: CoolItem, now: LocalDateTime): Duration = Duration.between(now, c.dueAt).let { if (it.isNegative) Duration.ZERO else it }

    fun isDue(c: CoolItem, now: LocalDateTime) = c.step == CoolStep.COOLING && !now.isBefore(c.dueAt)

    /** "31h", "45m", or "Time's up". */
    fun leftWords(c: CoolItem, now: LocalDateTime): String = left(c, now).let { d ->
        when { d.isZero -> "Time's up"; d.toHours() >= 1 -> "${d.toHours()}h"; else -> "${maxOf(1, d.toMinutes())}m" }
    }

    /** Still cooling, the one up soonest first. */
    fun cooling(cs: List<CoolItem>) = cs.filter { it.step == CoolStep.COOLING }.sortedBy { it.dueAt }

    fun due(cs: List<CoolItem>, now: LocalDateTime) = cooling(cs).filter { isDue(it, now) }

    /** What you let go of in [month], at its latest price. */
    fun notSpent(cs: List<CoolItem>, month: YearMonth): Double =
        cs.filter { it.step == CoolStep.LET_GO && it.decided?.let(YearMonth::from) == month }.sumOf { it.now ?: 0.0 }

    fun letGo(c: CoolItem, today: LocalDate) = c.copy(step = CoolStep.LET_GO, decided = today)

    fun bought(c: CoolItem, today: LocalDate) = c.copy(step = CoolStep.BOUGHT, decided = today)

    /** Another wait from [now] ("Not sure, 48h more"). */
    fun wait(c: CoolItem, now: LocalDateTime, hours: Int = HOURS): CoolItem =
        c.copy(hours = (ChronoUnit.MINUTES.between(c.added, now) / 60).toInt() + hours)

    // ---- price tracking ----

    /** [c] with [price] seen on [today]: a new point when it changed, the same day's point replaced. */
    fun track(c: CoolItem, price: Double, today: LocalDate): CoolItem {
        val ps = c.prices.toMutableList()
        if (ps.lastOrNull()?.on == today) ps.removeAt(ps.lastIndex)
        val last = ps.lastOrNull()?.price ?: c.price
        if (last == null || kotlin.math.abs(last - price) >= 0.01) ps.add(PricePoint(today, price))
        val kept = if (ps.size > KEEP_PRICES) listOf(ps.first()) + ps.takeLast(KEEP_PRICES - 1) else ps
        return c.copy(prices = kept)
    }

    /** How much it's come down since you added it, or null if it hasn't. */
    fun drop(c: CoolItem): Double? {
        val was = c.was ?: return null
        val now = c.now ?: return null
        return (was - now).takeIf { it >= 0.01 }
    }

    /** True when [after]'s price is lower than [before]'s latest: the moment to buzz. */
    fun droppedSince(before: CoolItem, after: CoolItem): Boolean {
        val b = before.now ?: return false
        val a = after.now ?: return false
        return b - a >= 0.01
    }

    /** "now £39, was £45", "up to £52 from £45", or just the price. */
    fun priceWords(c: CoolItem): String? {
        val now = c.now ?: return null
        val was = c.was ?: return money(now)
        return when {
            was - now >= 0.01 -> "now ${money(now)}, was ${money(was)}"
            now - was >= 0.01 -> "up to ${money(now)} from ${money(was)}"
            else -> money(now)
        }
    }

    /** Cooling items with a link, to check for price changes. */
    fun tracked(cs: List<CoolItem>) = cooling(cs).filter { it.link != null }

    /** One buzz when each wait is up. A wait that ends overnight (21:00 to 08:00) buzzes at 09:00 instead. */
    fun buzzes(cs: List<CoolItem>, now: LocalDateTime): List<Pair<LocalDateTime, CoolItem>> = cooling(cs).map { c ->
        val at = c.dueAt
        val quiet = at.hour >= 21 || at.hour < 8
        (if (!quiet) at else (if (at.hour >= 21) at.toLocalDate().plusDays(1) else at.toLocalDate()).atTime(9, 0)) to c
    }.filter { it.first.isAfter(now) }.sortedBy { it.first }

    /** An item from a shared shop page: the link, a name from the share's subject or the page title, and any £ price. */
    fun fromShare(text: String, subject: String?, now: LocalDateTime): CoolItem? {
        val link = URL.find(text)?.value?.trimEnd(')', '.', ',') ?: return null
        val name = (subject?.takeIf { it.isNotBlank() } ?: text.replace(link, "").trim().lines().firstOrNull { it.isNotBlank() })
            ?.replace(Regex("""\s*[|:–-]\s*(Amazon|IKEA|eBay|Argos|John Lewis).*$""", RegexOption.IGNORE_CASE), "")
            ?.replace("·", "")?.trim()?.take(60)?.ifBlank { null }
            ?: java.net.URI(link).host?.removePrefix("www.") ?: "Something"
        val price = Regex("""£\s?(\d[\d,]*(?:\.\d{1,2})?)""").find(text)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()
        return CoolItem(name, now.withSecond(0).withNano(0), price, link)
    }

    fun load(v: Vault): List<CoolItem> { ensureNote(v, NOTE, STARTER); return parse(VaultIo.read(v, NOTE)) }

    fun add(v: Vault, c: CoolItem) = rewriteNote(v, NOTE, (load(v) + c).map(::format))

    fun update(v: Vault, old: CoolItem, new: CoolItem) = rewriteNote(v, NOTE, load(v).map { if (it == old) new else it }.map(::format))

    fun remove(v: Vault, c: CoolItem) = rewriteNote(v, NOTE, load(v).filter { it != c }.map(::format))

    /** Saves every changed item at once (after a price check), matched by when it was added. */
    fun saveAll(v: Vault, changed: List<CoolItem>) {
        val byAdded = changed.associateBy { it.added to it.what }
        rewriteNote(v, NOTE, load(v).map { byAdded[it.added to it.what] ?: it }.map(::format))
    }
}

/** Reads a price off a shop's product page: structured data first, then the usual price tags. */
object PriceScrape {
    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    private val LD_PRICE = Regex(""""(?:price|lowPrice)"\s*:\s*"?(\d[\d,]*(?:\.\d{1,2})?)"?""")
    private val NUMBER = Regex("""(\d[\d,]*(?:\.\d{1,2})?)""")

    fun parse(html: String): Double? = parse(Jsoup.parse(html))

    fun parse(doc: Document): Double? {
        doc.select("script[type=application/ld+json]").forEach { s ->
            val json = s.data()
            if ("offers" in json || "\"price\"" in json) LD_PRICE.find(json)?.groupValues?.get(1)?.let(::number)?.let { return it }
        }
        listOf("meta[property=product:price:amount]", "meta[property=og:price:amount]", "meta[itemprop=price]", "[itemprop=price][content]")
            .forEach { q -> doc.selectFirst(q)?.attr("content")?.let(::number)?.let { return it } }
        listOf("#corePrice_feature_div .a-offscreen", ".a-price .a-offscreen", "[itemprop=price]", "[data-testid=price]", ".price")
            .forEach { q -> doc.selectFirst(q)?.text()?.takeIf { "£" in it || q.startsWith("[itemprop") }?.let(::number)?.let { return it } }
        return null
    }

    private fun number(s: String): Double? =
        NUMBER.find(s.replace("£", ""))?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()?.takeIf { it in 0.01..100_000.0 }

    /** Fetches [url] and reads its price, or null. Blocking: call off the main thread. */
    fun check(url: String): Double? = runCatching {
        parse(Jsoup.connect(url).userAgent(UA).timeout(15_000).followRedirects(true).maxBodySize(4_000_000).get())
    }.getOrNull()
}

// ---- Refill ----

/**
 * One med in Refill: `- Sertraline 50mg · 8 left · 1 a day · follows Meds · counted 2026-11-20 17:42 · box 28`, and
 * `· ordered` once it's on its way. The count is as of [counted]; Did I? ticks of [check] after that take one off each.
 */
data class Med(
    val name: String, val count: Int, val counted: LocalDateTime, val perDay: Double = 1.0, val check: String = Refill.CHECK,
    val box: Int = Refill.BOX, val ordered: Boolean = false,
)

/** Refill (hangar-phase3): counts meds down from Did I? ticks and buzzes to reorder a week before they run out. */
object Refill {
    const val NOTE = "${FlightNotes.DIR}/Refill.md"
    const val CHECK = "Meds"
    const val BOX = 28
    const val REORDER_DAYS = 7L
    /** How far back Did I? logs are read when a count is old. */
    private const val MAX_DAYS = 120L

    val STARTER = """
        # Refill

        One med per line: name · N left · how many a day · follows <Did I? check> · counted <when> · box <size>.
        Each Did I? tick of that check takes one off. Flight Deck buzzes a week before you run out.
    """.trimIndent() + "\n"

    private val LEFT = Regex("""^(\d+) left$""")
    private val PER_DAY = Regex("""^(\d+(?:\.\d+)?|½) a day$""")
    private val BOX_SIZE = Regex("""^box (\d+)$""")

    fun parse(text: String): List<Med> = text.lines().mapNotNull { line(it) }

    fun line(raw: String): Med? {
        val body = raw.trim().takeIf { it.startsWith("- ") && !it.startsWith("- [") }?.removePrefix("- ") ?: return null
        val parts = body.split(" · ").map { it.trim() }
        val name = parts.getOrNull(0)?.takeIf { it.isNotEmpty() } ?: return null
        val count = parts.firstNotNullOfOrNull { LEFT.find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: return null
        val counted = parts.firstOrNull { it.startsWith("counted ") }?.removePrefix("counted ")?.let(::stampOf) ?: return null
        val perDay = parts.firstNotNullOfOrNull { PER_DAY.find(it)?.groupValues?.get(1) }?.let { if (it == "½") 0.5 else it.toDouble() } ?: 1.0
        val check = parts.firstOrNull { it.startsWith("follows ") }?.removePrefix("follows ")?.trim()?.ifEmpty { null } ?: CHECK
        val box = parts.firstNotNullOfOrNull { BOX_SIZE.find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: BOX
        return Med(name, count, counted, perDay, check, box, parts.any { it == "ordered" })
    }

    fun perDayWords(d: Double) = when { d == 0.5 -> "½ a day"; d % 1.0 == 0.0 -> "${d.toInt()} a day"; else -> "$d a day" }

    fun format(m: Med): String =
        "- ${m.name.replace(" · ", ", ")} · ${m.count} left · ${perDayWords(m.perDay)} · follows ${m.check} · counted ${m.counted.format(STAMP_AT)} · box ${m.box}" +
            if (m.ordered) " · ordered" else ""

    /** Doses taken since the count: ticks of the med's check after [Med.counted], at most a day's worth per day. */
    fun used(m: Med, stamps: List<Pair<LocalDate, DidStamp>>): Int {
        val cap = maxOf(1, ceil(m.perDay).toInt())
        return stamps.filter { (d, s) -> s.name.equals(m.check, ignoreCase = true) && d.atTime(s.time).isAfter(m.counted) }
            .groupBy { it.first }.values.sumOf { minOf(cap, it.size) }
    }

    fun left(m: Med, stamps: List<Pair<LocalDate, DidStamp>>) = maxOf(0, m.count - used(m, stamps))

    /** The day you'd have none left. */
    fun runsOut(m: Med, left: Int, today: LocalDate): LocalDate = today.plusDays(floor(left / m.perDay).toLong())

    fun reorderOn(m: Med, left: Int, today: LocalDate): LocalDate = runsOut(m, left, today).minusDays(REORDER_DAYS)

    fun needsOrder(m: Med, left: Int, today: LocalDate) = !m.ordered && !today.isBefore(reorderOn(m, left, today))

    /** "8 left · runs out Fri 27 Nov". */
    fun runsOutWords(m: Med, left: Int, today: LocalDate): String =
        if (left == 0) "None left" else "runs out " + runsOut(m, left, today).format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK))

    /** The new box is in: today's count plus a box, counted from [now]. */
    fun pickedUp(m: Med, left: Int, now: LocalDateTime) = m.copy(count = left + m.box, counted = now.withSecond(0).withNano(0), ordered = false)

    /** A new count you typed in, from [now]. */
    fun recount(m: Med, count: Int, now: LocalDateTime) = m.copy(count = count, counted = now.withSecond(0).withNano(0))

    /** The reorder buzz: 09:00 on the reorder day, or the next 09:00 if that's been and it's still not ordered. */
    fun buzz(m: Med, left: Int, now: LocalDateTime): LocalDateTime? {
        if (m.ordered) return null
        val on = reorderOn(m, left, now.toLocalDate()).atTime(9, 0)
        if (on.isAfter(now)) return on
        val today = now.toLocalDate().atTime(9, 0)
        return if (today.isAfter(now)) today else today.plusDays(1)
    }

    /** Did I? stamps from the oldest count up to [today]. */
    fun stamps(v: Vault, meds: List<Med>, today: LocalDate): List<Pair<LocalDate, DidStamp>> {
        val from = meds.minOfOrNull { it.counted.toLocalDate() }?.let { maxOf(it, today.minusDays(MAX_DAYS)) } ?: return emptyList()
        return generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(today) }
            .flatMap { d -> DidI.loadStamps(v, d).map { d to it } }.toList()
    }

    fun load(v: Vault): List<Med> { ensureNote(v, NOTE, STARTER); return parse(VaultIo.read(v, NOTE)) }

    fun add(v: Vault, m: Med) = rewriteNote(v, NOTE, (load(v).filter { !it.name.equals(m.name, ignoreCase = true) } + m).map(::format))

    fun update(v: Vault, old: Med, new: Med) = rewriteNote(v, NOTE, load(v).map { if (it == old) new else it }.map(::format))

    fun remove(v: Vault, m: Med) = rewriteNote(v, NOTE, load(v).filter { it != m }.map(::format))
}

// ---- Waiting Mode ----

/**
 * Waiting Mode (hangar-phase3): stuck in a queue, three things instead of scrolling: a task of 5 minutes or less
 * (`⏱ 5m` or `#quick`), an unread article under 5 minutes, or a minute of slow breathing.
 */
object WaitingMode {
    const val QUICK_MINUTES = 5
    const val READ_MINUTES = 5
    const val BREATHE_IN = 4
    const val BREATHE_OUT = 6
    const val BREATHE_SECONDS = 60
    private const val WORDS_A_MINUTE = 220
    private val QUICK = Regex("""(^|\s)#quick(\s|$)""")

    fun quickTasks(tasks: List<TaskItem>): List<TaskItem> =
        tasks.filter { t -> QUICK.containsMatchIn(t.raw) || (TaskRules.minutes(t.raw)?.let { it in 1..QUICK_MINUTES } == true) }

    fun readMinutes(words: Int) = maxOf(1, (words + WORDS_A_MINUTE / 2) / WORDS_A_MINUTE)

    fun shortReads(articles: List<SavedArticle>): List<SavedArticle> =
        articles.filter { !it.read && !it.ref.archived && it.words > 0 && readMinutes(it.words) <= READ_MINUTES }

    /** The [turn]th pick from [xs], wrapping round, so Shuffle walks through them all. */
    fun <T> pick(xs: List<T>, turn: Int): T? = if (xs.isEmpty()) null else xs[Math.floorMod(turn, xs.size)]

    /** "6 min" for how long you've been waiting. */
    fun waitedWords(from: LocalDateTime, now: LocalDateTime) = "${maxOf(0, ChronoUnit.MINUTES.between(from, now))} min"
}

// ---- Errand Run ----

/** One thing to do at a stop: a task to tick, a shopping item, or a parcel to send back. */
data class RunItem(val text: String, val task: TaskItem? = null, val ret: Return? = null)

/** One stop on an Errand Run: a place from the Places note (or none yet), with what to do there. */
data class RunStop(val tag: String, val title: String, val place: Place?, val items: List<RunItem>, val metres: Double? = null)

/**
 * Errand Run (hangar-phase3): every place with an errand waiting, as one run, nearest first. Packed Return Radar parcels
 * add a Post Office stop. The route itself is handed to the maps app.
 */
object ErrandRun {
    val POST_TAGS = listOf("@postoffice", "@post", "@po", "@postbox")
    const val POST_TAG = "@postoffice"

    private fun postPlace(places: List<Place>) =
        places.firstOrNull { it.tag in POST_TAGS } ?: places.firstOrNull { it.label.contains("post office", ignoreCase = true) }

    /** Returns waiting at the post office: packed (or still to do) but not dropped off yet. */
    fun parcels(returns: List<Return>) = returns.filter(Returns::open).sortedBy { it.by }

    fun returnWords(r: Return, today: LocalDate): String {
        val d = Returns.daysLeft(r, today)
        val due = when { d < 0 -> "overdue"; d == 0L -> "due today"; d == 1L -> "due tomorrow"; else -> "due in $d days" }
        return "↩ Return ${r.item.replaceFirstChar { it.lowercase(Locale.UK) }}" + (if (r.shop.isNotBlank()) " (${r.shop})" else "") + " · $due"
    }

    /**
     * The run: one stop per errand place plus the post office, in nearest-next order from [here] (or the Errands order
     * when there's no fix). Stops without a place on the map go last.
     */
    fun stops(errands: List<Errand>, returns: List<Return>, places: List<Place>, here: Pair<Double, Double>?, today: LocalDate): List<RunStop> {
        val post = postPlace(places)
        val postTag = post?.tag ?: POST_TAG
        val out = errands.map { e ->
            val items = e.items.mapIndexed { i, text -> RunItem(text, e.tasks.getOrNull(i)) }
            RunStop(e.tag, e.place?.label ?: e.tag.removePrefix("@").replaceFirstChar { it.uppercase(Locale.UK) }, e.place, items)
        }.toMutableList()
        val parcels = parcels(returns).map { RunItem(returnWords(it, today), ret = it) }
        if (parcels.isNotEmpty()) {
            val i = out.indexOfFirst { it.tag == postTag || it.tag in POST_TAGS }
            if (i >= 0) out[i] = out[i].copy(items = parcels + out[i].items)
            else out += RunStop(postTag, post?.label ?: "Post Office", post, parcels)
        }
        val placed = out.filter { it.place != null }.toMutableList()
        val loose = out.filter { it.place == null }
        if (here == null) return placed + loose
        val route = mutableListOf<RunStop>()
        var at: Pair<Double, Double> = here
        while (placed.isNotEmpty()) {
            val next = placed.minByOrNull { s -> Errands.metres(at.first, at.second, s.place!!.lat, s.place.lon) }!!
            placed.remove(next)
            route += next.copy(metres = Errands.metres(here.first, here.second, next.place!!.lat, next.place.lon))
            at = next.place.lat to next.place.lon
        }
        return route + loose
    }

    /** "0.4 mi", or "Here" when it's under 100 m. */
    fun distance(m: Double): String = if (m < 100) "Here" else "%.1f mi".format(Locale.UK, m / 1609.344)

    /** A Google Maps walking route through the placed stops in order, from wherever you are. */
    fun mapsUrl(stops: List<RunStop>): String? {
        val pts = stops.mapNotNull { it.place }.take(10).map { "%.5f,%.5f".format(Locale.ROOT, it.lat, it.lon) }
        if (pts.isEmpty()) return null
        val way = pts.dropLast(1)
        return "https://www.google.com/maps/dir/?api=1&destination=${pts.last()}&travelmode=walking" +
            if (way.isNotEmpty()) "&waypoints=" + way.joinToString("%7C") else ""
    }

    /** Stops on the run and the names of the first few: "3 stops", "Tesco, B&Q, Post Office". */
    fun summary(stops: List<RunStop>): Pair<String, String> =
        "${stops.size} stop${if (stops.size == 1) "" else "s"}" to stops.take(3).joinToString(", ") { it.title }
}
