package uk.tomlowndes.capture

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

/**
 * Where an element lives (where-things-live-v1): switched off, a Hangar tile only, or a Hangar tile plus its usual
 * spot in the main app. [MAIN] is for elements that have no Hangar page yet: on, in the main app only.
 */
enum class Lives(val label: String) { OFF("Off"), HANGAR("Hangar"), BOTH("Both"), MAIN("Main") }

/**
 * Everything that can be switched on or off, and moved into Hangar (where-things-live-v1). An element's spot in the
 * main app is its Deck [card], a Tasks [view], or (for a plain Hangar tool) a tile in the Deck's More row.
 * [tool] is its Hangar tile; elements without one stay in the main app and only switch on or off.
 */
enum class Element(
    val id: String, val label: String, val icon: String, val group: String,
    val tool: HangarTool? = null, val card: HomeCard? = null, val view: String? = null,
) {
    BILLS("bills", "Bills", "£", "Money", HangarTool.BILLS, HomeCard.BILLS),
    SPEND("spend", "Spent this month", "🧾", "Money", card = HomeCard.SPEND),

    CHORES("chores", "Home chores", "🏠", "Home", HangarTool.HOME, HomeCard.CHORES),
    MEALS("meals", "Meals", "🍽", "Home", HangarTool.MEALS),
    ERRANDS("errands", "Errands", "📍", "Home", HangarTool.ERRANDS),
    INBOX("inbox", "Inbox", "✉", "Home", HangarTool.INBOX, HomeCard.INBOX),
    PEOPLE("people", "People", "👥", "Home", HangarTool.PEOPLE, HomeCard.PEOPLE),

    NEXT_STEP("next", "Next step", "◎", "Day", card = HomeCard.NEXT_STEP),
    BRIEF("brief", "Daily Brief", "☀", "Day", HangarTool.BRIEF, HomeCard.BRIEF),
    WEATHER("weather", "Weather & travel", "☁", "Day", card = HomeCard.WEATHER),
    TODAY("today", "Today's calendar", "📅", "Day", card = HomeCard.TODAY),
    TOP_TASKS("top", "Top tasks", "✓", "Day", card = HomeCard.TOP_TASKS),
    UPCOMING("upcoming", "Upcoming", "📆", "Day", view = "UPCOMING"),
    PLAN("plan", "Plan today · Shut down", "☑", "Day", HangarTool.PLAN, HomeCard.PLAN),
    HABITS("habits", "Habits", "✿", "Day", HangarTool.HABITS, HomeCard.HABITS),
    STUCK("stuck", "Stuck? Pick one.", "⚑", "Day", card = HomeCard.STUCK),
    ROUTINES("routines", "Routines", "🔁", "Day", card = HomeCard.ROUTINES),
    MOOD("mood", "Mood at night", "☾", "Day", card = HomeCard.MOOD),
    JOURNAL("journal", "Journal check-in", "📓", "Day", card = HomeCard.HIGHLIGHTS),

    PROJECTS("projects", "Projects", "💡", "Work and learning", HangarTool.PROJECTS, HomeCard.PROJECTS),
    JOBS("jobs", "Jobs", "💼", "Work and learning", HangarTool.JOBS, HomeCard.JOBS),
    LEARN("learn", "Learn", "🧠", "Work and learning", HangarTool.LEARN, HomeCard.LEARN),
    READ_LATER("read", "Read it later", "📖", "Work and learning", card = HomeCard.READ_LATER),
    READING("reading", "From your reading", "❝", "Work and learning", card = HomeCard.READING),

    DID_I("didi", "Did I?", "✓", "Tools", HangarTool.DID_I),
    LAUNCH_PAD("launch", "Launch Pad", "↗", "Tools", HangarTool.LAUNCH_PAD),
    NIGHT_PAD("night", "Night Pad", "☾", "Tools", HangarTool.NIGHT_PAD),
    HEADS_UP("heads", "Heads Up", "◔", "Tools", HangarTool.HEADS_UP),
    POCKET_PASS("pass", "Pocket Pass", "⌗", "Tools", HangarTool.POCKET_PASS),
    LAST_SEEN("seen", "Last Seen", "⌖", "Tools", HangarTool.LAST_SEEN),
    RETURN_RADAR("returns", "Return Radar", "↩", "Tools", HangarTool.RETURN_RADAR),
    COOL_OFF("cool", "Cool-off", "❄", "Tools", HangarTool.COOL_OFF),
    REFILL("refill", "Refill", "✚", "Tools", HangarTool.REFILL),
    WAITING("waiting", "Waiting Mode", "⧗", "Tools", HangarTool.WAITING),
    TICKET_RAIL("rail", "Ticket Rail", "▤", "Tools", HangarTool.TICKET_RAIL),
    SENSORY("sensory", "Sensory Dial", "◐", "Tools", HangarTool.SENSORY);

    /** True when it can be a Hangar tile. Next step and the rest without a page of their own can't (yet). */
    val hangarOk get() = tool != null

    /** Where it shows in the main app when it's there, for the row's second line. */
    val mainSpot: String get() = when {
        this == BILLS -> "Deck bills + Tasks › Bills"
        this == CHORES -> "Deck chore row + Tasks › Chores"
        this == NEXT_STEP -> "Always on the Deck"
        view != null -> "Tasks view"
        card != null && card in TILE_CARDS -> "More tile"
        card != null && card in PHONE_AS_TILE -> "Windows Deck card"
        card != null -> "Deck card"
        else -> "More tile"
    }

    companion object {
        val GROUPS = listOf("Money", "Home", "Day", "Work and learning", "Tools")
        fun byId(id: String) = values().firstOrNull { it.id == id.trim() }
        fun forTool(t: HangarTool) = values().firstOrNull { it.tool == t }
        /** HomeCards that show as tiles in the Deck's More row rather than cards. */
        val TILE_CARDS = setOf(HomeCard.PEOPLE, HomeCard.LEARN, HomeCard.ROUTINES, HomeCard.HIGHLIGHTS)
        /**
         * Cards the phone Deck no longer draws (they moved to Hangar in hangar-dock-v2) but Windows still does, so
         * Both adds their Windows Deck card.
         */
        val PHONE_AS_TILE = setOf(HomeCard.HABITS, HomeCard.JOBS, HomeCard.PROJECTS, HomeCard.INBOX, HomeCard.READ_LATER)
    }
}

/**
 * What decides where every element lives: the Deck layout (cards on or off), the Hangar list, the Hangar tools shown
 * as More tiles, and the Tasks views switched off. All of it sits in `capture-config.json`, so the phone and Windows
 * read the same thing.
 */
data class PlaceState(
    val layout: DeckLayout = DeckLayout(),
    val hangar: List<HangarTool> = emptyList(),
    val tiles: List<HangarTool> = listOf(HangarTool.TICKET_RAIL),
    val viewsOff: Set<String> = emptySet(),
) {
    private fun inMain(e: Element): Boolean = when {
        e.card != null -> e.card !in layout.hidden
        e.view != null -> e.view !in viewsOff
        e.tool != null -> e.tool in tiles
        else -> false
    }

    fun place(e: Element): Lives {
        val main = inMain(e)
        if (!e.hangarOk) return if (main) Lives.MAIN else Lives.OFF
        val inHangar = e.tool in hangar
        return when {
            inHangar && main -> Lives.BOTH
            inHangar -> Lives.HANGAR
            // On in the main app but not in Hangar: only before the first move (see [Places.migrated]).
            main -> Lives.BOTH
            else -> Lives.OFF
        }
    }

    fun on(e: Element) = place(e) != Lives.OFF

    /** True when the element shows in the main app (its card, Tasks view or More tile). */
    fun main(e: Element) = place(e).let { it == Lives.BOTH || it == Lives.MAIN }

    /** Moves [e] to [p]. A Hangar tile that comes back goes on the end of Hangar; one that goes keeps the others' order. */
    fun with(e: Element, p: Lives): PlaceState {
        val toMain = p == Lives.BOTH || p == Lives.MAIN
        val toHangar = (p == Lives.HANGAR || p == Lives.BOTH) && e.hangarOk
        var s = this
        e.card?.let { c ->
            val hidden = if (toMain) s.layout.hidden - c else s.layout.hidden + c
            s = s.copy(layout = s.layout.copy(hidden = hidden))
        }
        e.view?.let { v -> s = s.copy(viewsOff = if (toMain) s.viewsOff - v else s.viewsOff + v) }
        e.tool?.let { t ->
            s = s.copy(hangar = if (toHangar) Hangar.add(s.hangar, t) else s.hangar - t)
            // A plain tool's main-app spot is a More tile; a tool with a card keeps its tile list as it was.
            if (e.card == null && e.view == null) s = s.copy(tiles = if (toMain) Hangar.add(s.tiles, t) else s.tiles - t)
            else if (!toHangar && !toMain) s = s.copy(tiles = s.tiles - t)
        }
        return s
    }

    /** Counts for the filter chips: everything, on, in Hangar, off. */
    fun counts(): IntArray {
        val all = Element.values()
        return intArrayOf(all.size, all.count { on(it) }, all.count { place(it).let { p -> p == Lives.HANGAR || p == Lives.BOTH } }, all.count { !on(it) })
    }
}

object Places {
    /**
     * The one-off move when this arrives (where-things-live-v1): every element that is on in the main app and can be
     * a Hangar tile gets its tile too (so nothing that was on is lost), and Bills moves to Hangar only, out of Tasks
     * and off the Deck. Nothing is switched off.
     */
    fun migrated(s: PlaceState): PlaceState {
        var out = s
        Element.values().filter { it.hangarOk && it.tool !in s.hangar && out.main(it) }.forEach { e -> out = out.copy(hangar = out.hangar + e.tool!!) }
        out = out.with(Element.BILLS, Lives.HANGAR)
        // Bills first: it's what moved.
        return out.copy(hangar = listOf(HangarTool.BILLS) + (out.hangar - HangarTool.BILLS))
    }

    // ---- capture-config.json ----

    private const val TILES = "deckTiles"
    private const val VIEWS_OFF = "viewsOff"
    private const val MOVED = "placesMoved"
    private const val HANGAR_AT = "hangarAt"

    private fun parse(json: String?): JSONObject =
        json?.takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()

    /** More tiles as saved in the vault ("rail,didi"), or null before any device saved them. */
    fun tiles(json: String?): List<HangarTool>? = parse(json).optString(TILES, "").takeIf { parse(json).has(TILES) }?.let { s ->
        s.split(",").mapNotNull { HangarTool.byId(it) }
    }

    fun viewsOff(json: String?): Set<String> = parse(json).optJSONArray(VIEWS_OFF)?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() }.toSet() } ?: emptySet()

    fun moved(json: String?): Boolean = parse(json).optBoolean(MOVED, false)

    /** When the Hangar list was last changed (epoch millis) by whichever device saved it; 0 when never stamped. */
    fun hangarAt(json: String?): Long = parse(json).optLong(HANGAR_AT, 0L)

    /** Reads the whole state from [json]. [phoneHangar]/[phoneTiles] fill in what the vault doesn't have yet. */
    fun state(json: String?, phoneHangar: List<HangarTool>? = null, phoneTiles: List<HangarTool>? = null): PlaceState = PlaceState(
        layout = DeckConfig.layout(json),
        hangar = Hangar.parse(DeckConfig.hangar(json)) ?: phoneHangar ?: emptyList(),
        tiles = tiles(json) ?: phoneTiles ?: listOf(HangarTool.TICKET_RAIL),
        viewsOff = viewsOff(json),
    )

    /** [json] with [s] written in, other keys kept. [at] stamps the Hangar list so the other device takes it. */
    fun withState(json: String?, s: PlaceState, at: Long, moved: Boolean = true): String {
        val o = JSONObject(DeckConfig.withLayout(json, s.layout))
        o.put("hangar", Hangar.format(s.hangar))
        o.put(HANGAR_AT, at)
        o.put(TILES, Hangar.format(s.tiles))
        o.put(VIEWS_OFF, JSONArray(s.viewsOff.sorted()))
        if (moved) o.put(MOVED, true)
        return o.toString(2)
    }

    fun load(v: Vault, phoneHangar: List<HangarTool>? = null, phoneTiles: List<HangarTool>? = null): PlaceState =
        state(v.read(DeckConfig.PATH), phoneHangar, phoneTiles)

    fun save(v: Vault, s: PlaceState, at: Long = System.currentTimeMillis()) {
        val path = DeckConfig.PATH
        if (v.entry(path) == null) {
            v.mkdirs(parentOf(path))
            v.create(parentOf(path), nameOf(path).removeSuffix(".json"), "json")
        }
        VaultIo.write(v, path, withState(v.read(path), s, at))
    }

    /**
     * Which Hangar list wins when the phone's meets the vault's. A list stamped later than the phone's own last change
     * was set on the other device, so it's taken as it is (tools added or removed there too). Otherwise the older rule
     * stands: the vault's order when the tools match, the phone's list when they don't.
     */
    fun synced(phone: List<HangarTool>, phoneAt: Long, vault: List<HangarTool>?, vaultAt: Long): List<HangarTool> =
        if (vault != null && vaultAt > phoneAt) vault else Hangar.merged(phone, vault)
}

/** One bill on the Bills page: an open line, one ticked as paid, or a repeat that hasn't been written yet. */
data class BillRow(val bill: Bill, val due: LocalDate, val paid: Boolean = false, val projected: Boolean = false)

/** A month on the Bills page (where-things-live-v1): what's to pay, what's paid, and the totals. */
data class BillMonth(val month: YearMonth, val rows: List<BillRow>) {
    val toPay: BigDecimal get() = TaskViews.total(rows.filter { !it.paid }.map { it.bill })
    val paid: BigDecimal get() = TaskViews.total(rows.filter { it.paid }.map { it.bill })
    /** Rows grouped by due day, in order. */
    val byDay: List<Pair<LocalDate, List<BillRow>>> get() = rows.groupBy { it.due }.toSortedMap().map { it.key to it.value }
}

object BillPage {
    private val MONTHLY = Regex("🔁\\s*every month")
    private val YEARLY = Regex("🔁\\s*every year")

    /**
     * Bills for [month]: open #bill lines due in it (and late ones, in the current month), bills ticked as paid in it,
     * and monthly or yearly repeats whose next line comes before it, shown on the day they'll fall due.
     */
    fun month(open: List<TaskItem>, done: List<TickedTask>, month: YearMonth, today: LocalDate): BillMonth {
        val now = YearMonth.from(today)
        val rows = mutableListOf<BillRow>()
        open.filter { Deck.isBill(it) && it.due != null }.forEach { t ->
            val due = t.due!!
            val b = TaskViews.bill(t)
            when {
                YearMonth.from(due) == month -> rows += BillRow(b, due)
                due.isBefore(month.atDay(1)) && month == now -> rows += BillRow(b, due)
                due.isBefore(month.atDay(1)) && MONTHLY.containsMatchIn(t.raw) ->
                    rows += BillRow(b, month.atDay(minOf(due.dayOfMonth, month.lengthOfMonth())), projected = true)
                due.isBefore(month.atDay(1)) && YEARLY.containsMatchIn(t.raw) && due.month == month.month ->
                    rows += BillRow(b, month.atDay(minOf(due.dayOfMonth, month.lengthOfMonth())), projected = true)
            }
        }
        done.filter { Deck.isBill(TaskItem(it.raw, it.text, null)) && it.done != null && YearMonth.from(it.done) == month }.forEach { d ->
            val due = TaskRules.due(d.raw) ?: d.done!!
            rows += BillRow(TaskViews.bill(TaskItem(d.raw, d.text, due, null, d.path)), due, paid = true)
        }
        return BillMonth(month, rows.sortedWith(compareBy({ it.due }, { it.bill.name.lowercase() })))
    }

    /** Every open bill, soonest first, for the All chip. */
    fun all(open: List<TaskItem>): List<BillRow> =
        open.filter { Deck.isBill(it) && it.due != null }.sortedBy { it.due }.map { BillRow(TaskViews.bill(it), it.due!!) }

    /** The open bills due on the soonest day still to come (or late), for Right now and the tile. */
    fun next(open: List<TaskItem>, today: LocalDate): List<Bill> {
        val bills = open.filter { Deck.isBill(it) && it.due != null }
        val day = bills.minOfOrNull { it.due!! } ?: return emptyList()
        return bills.filter { it.due == day }.map { TaskViews.bill(it) }
    }

    /** "£37.62 + 2 more due Mon 1 Dec" style line, or null with nothing open. */
    fun nextLine(open: List<TaskItem>, today: LocalDate): String? {
        val n = next(open, today).takeIf { it.isNotEmpty() } ?: return null
        val day = n.first().task.due!!
        val first = n.first().amount?.let { TaskViews.money(it) } ?: n.first().name
        val more = if (n.size > 1) " + ${n.size - 1} more" else ""
        val whenWords = when {
            day.isBefore(today) -> "late"
            day == today -> "due today"
            day == today.plusDays(1) -> "due tomorrow"
            else -> "due " + day.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", java.util.Locale.UK))
        }
        return "$first$more $whenWords"
    }

    private val RULE = Regex("🔁\\s*([^📅⏳🛫✅#]+)")

    /** "monthly", "yearly", "weekly", or the repeat rule as written; null for a one-off. */
    fun repeatWords(raw: String): String? = RULE.find(raw)?.groupValues?.get(1)?.trim()?.let { r ->
        when (r.lowercase()) { "every month" -> "monthly"; "every year" -> "yearly"; "every week" -> "weekly"; else -> r }
    }

    /** True when a bill is late or due in the next two days. */
    fun needsYou(open: List<TaskItem>, today: LocalDate): Boolean =
        open.any { Deck.isBill(it) && it.due != null && it.due.isBefore(today.plusDays(3)) }
}
