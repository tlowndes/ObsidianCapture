package uk.tomlowndes.capture

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** The cards Flight Deck can show on home, in their default order. */
enum class HomeCard(val label: String) {
    NEXT_STEP("Next step"), WEATHER("Weather & travel"), TODAY("Today"), BILLS("Bills"), PROJECTS("Projects"),
    TOP_TASKS("Top tasks"), HABITS("Habits"), STUCK("Stuck? Pick one."), READ_LATER("Read it later"), JOBS("Jobs"),
    // Added in v1.16 (build queue). Plan today and Mood show near the top at their time of day while switched on.
    PLAN("Plan today · Shut down"), MOOD("Mood at night"), ROUTINES("Routines"), HIGHLIGHTS("Daily highlights")
}

/** Cards that pop up at the top at their time of day rather than sitting in the order. */
val TIMED_CARDS = setOf(HomeCard.PLAN, HomeCard.MOOD)

/** Card order and which cards are switched off, from Settings › Cards on home. */
data class DeckLayout(val order: List<HomeCard> = HomeCard.values().toList(), val hidden: Set<HomeCard> = emptySet()) {
    /** Cards to show, in order. On a low-energy day Stuck? moves to the top. */
    fun visible(lowEnergy: Boolean): List<HomeCard> {
        val on = order.filter { it !in hidden }
        return if (lowEnergy && HomeCard.STUCK in on) listOf(HomeCard.STUCK) + (on - HomeCard.STUCK) else on
    }

    fun moved(from: Int, to: Int): DeckLayout {
        if (from == to || from !in order.indices || to !in order.indices) return this
        val list = order.toMutableList()
        list.add(to, list.removeAt(from))
        return copy(order = list)
    }

    /**
     * Drag on the Deck (deck-drag-v1): puts [card] just before [before] in the saved order, or straight after the
     * last of [after] when dropped at the end of a list. Hidden cards keep their places.
     */
    fun placed(card: HomeCard, before: HomeCard?, after: List<HomeCard> = emptyList()): DeckLayout {
        if (card == before) return this
        val list = order.toMutableList()
        if (!list.remove(card)) return this
        val at = when {
            before != null && before in list -> list.indexOf(before)
            else -> after.filter { it != card && it in list }.maxOfOrNull { list.indexOf(it) + 1 } ?: return this
        }
        list.add(at, card)
        return copy(order = list)
    }

    fun toggled(card: HomeCard): DeckLayout = copy(hidden = if (card in hidden) hidden - card else hidden + card)

    val onCount get() = order.count { it !in hidden }

    /**
     * Any card missing from a saved order (added in a later version) goes straight after the card it follows by
     * default, or on the end when that card isn't there either.
     */
    fun normalised(): DeckLayout {
        val list = order.distinct().toMutableList()
        val all = HomeCard.values()
        all.filter { it !in list }.forEach { card ->
            val before = all.take(card.ordinal).lastOrNull { it in list }
            if (before == null) list.add(card) else list.add(list.indexOf(before) + 1, card)
        }
        return copy(order = list)
    }
}

/**
 * The phone Deck's sections under the Next step hero (tidy-v1), which can be dragged about (deck-drag-v1).
 * Today sits where its first card is in the shared order, More where its last card is, so the default order keeps
 * Today, Top tasks, More.
 */
enum class DeckSection(val cards: List<HomeCard>) {
    TODAY(listOf(HomeCard.WEATHER, HomeCard.TODAY, HomeCard.BILLS)),
    TOP_TASKS(listOf(HomeCard.TOP_TASKS)),
    MORE(listOf(HomeCard.ROUTINES, HomeCard.PROJECTS, HomeCard.HIGHLIGHTS));

    fun at(order: List<HomeCard>): Int {
        val places = cards.map { order.indexOf(it) }.filter { it >= 0 }
        return (if (this == MORE) places.maxOrNull() else places.minOrNull()) ?: Int.MAX_VALUE
    }

    companion object {
        fun inOrder(layout: DeckLayout): List<DeckSection> = values().sortedBy { it.at(layout.order) }

        /** Moves all of [section]'s cards, together, to just before [before]'s cards, or after the others when null. */
        fun moved(layout: DeckLayout, section: DeckSection, before: DeckSection?): DeckLayout {
            if (section == before) return layout
            val list = layout.order.filter { it !in section.cards }.toMutableList()
            val block = section.cards.filter { it in layout.order }
            val at = if (before != null) before.cards.map { list.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: list.size
            else values().filter { it != section }.flatMap { it.cards }.maxOfOrNull { list.indexOf(it) + 1 } ?: list.size
            list.addAll(at, block)
            return layout.copy(order = list)
        }
    }
}

/**
 * Flight Deck settings kept in the vault (`99 - Meta/capture-config.json`) so Obsidian Sync carries them.
 * Other keys in the file are kept as they are.
 */
object DeckConfig {
    const val PATH = "99 - Meta/capture-config.json"

    fun layout(json: String?): DeckLayout {
        val o = parse(json)
        val names = o.optJSONArray("cards") ?: return DeckLayout()
        val hidden = o.optJSONArray("hiddenCards")
        fun cards(a: JSONArray?) = (0 until (a?.length() ?: 0)).mapNotNull { i ->
            HomeCard.values().firstOrNull { it.name == a!!.optString(i) }
        }
        return DeckLayout(cards(names), cards(hidden).toSet()).normalised()
    }

    fun withLayout(json: String?, layout: DeckLayout): String {
        val o = parse(json)
        o.put("cards", JSONArray(layout.order.map { it.name }))
        o.put("hiddenCards", JSONArray(layout.order.filter { it in layout.hidden }.map { it.name }))
        return o.toString(2)
    }

    private fun parse(json: String?): JSONObject =
        json?.takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()

    fun load(v: Vault): DeckLayout = layout(v.read(PATH))

    fun save(v: Vault, layout: DeckLayout) {
        if (v.entry(PATH) == null) {
            v.mkdirs(parentOf(PATH))
            v.create(parentOf(PATH), nameOf(PATH).removeSuffix(".json"), "json")
        }
        VaultIo.write(v, PATH, withLayout(v.read(PATH), layout))
    }

    // ---- Plan today / Shut down done (kept here so the phone and Windows agree) ----

    /** The day ("2026-11-12") Plan today and Shut down were last done on any device; "" if never. */
    fun dayDone(json: String?): DayDone {
        val o = parse(json)
        return DayDone(o.optString("plannedOn", ""), o.optString("shutDownOn", ""))
    }

    /** [json] with [key] set to [day], unless it already holds a later day. */
    fun withDone(json: String?, key: String, day: LocalDate): String {
        val o = parse(json)
        val had = o.optString(key, "")
        if (had < day.toString()) o.put(key, day.toString())
        return o.toString(2)
    }

    fun loadDayDone(v: Vault): DayDone = dayDone(v.read(PATH))

    fun markPlanned(v: Vault, day: LocalDate) = mark(v, "plannedOn", day)
    fun markShutDown(v: Vault, day: LocalDate) = mark(v, "shutDownOn", day)

    private fun mark(v: Vault, key: String, day: LocalDate) {
        if (v.entry(PATH) == null) {
            v.mkdirs(parentOf(PATH))
            v.create(parentOf(PATH), nameOf(PATH).removeSuffix(".json"), "json")
        }
        VaultIo.write(v, PATH, withDone(v.read(PATH), key, day))
    }
}

/** Daily highlights already kept or skipped on any device (short ids from [Highlights.key]), in `capture-config.json`. */
object HighlightsSeen {
    private const val KEY = "highlightsSeen"
    private const val MAX = 1000

    fun read(json: String?): Set<String> {
        val a = json?.takeIf { it.isNotBlank() }?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }?.optJSONArray(KEY)
            ?: return emptySet()
        return (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() }.toSet()
    }

    /** [json] with [add] appended to the seen list (oldest dropped past [MAX]); other keys kept. */
    fun with(json: String?, add: Collection<String>): String {
        val o = json?.takeIf { it.isNotBlank() }?.let { runCatching { org.json.JSONObject(it) }.getOrNull() } ?: org.json.JSONObject()
        val list = read(json).toMutableList().let { have -> (have + add.filter { it !in have }).distinct() }.takeLast(MAX)
        o.put(KEY, JSONArray(list))
        return o.toString(2)
    }

    fun load(v: Vault): Set<String> = read(v.read(DeckConfig.PATH))

    fun add(v: Vault, keys: Collection<String>): Set<String> {
        val path = DeckConfig.PATH
        if (v.entry(path) == null) {
            v.mkdirs(parentOf(path))
            v.create(parentOf(path), nameOf(path).removeSuffix(".json"), "json")
        }
        val now = v.read(path)
        if (keys.all { it in read(now) }) return read(now)
        val out = with(now, keys)
        VaultIo.write(v, path, out)
        return read(out)
    }
}

/** When Plan today / Shut down were last done, from `capture-config.json`. */
data class DayDone(val plannedOn: String = "", val shutDownOn: String = "") {
    fun planned(day: LocalDate) = plannedOn == day.toString()
    fun shutDown(day: LocalDate) = shutDownOn == day.toString()
}

/** Picking what Flight Deck shows from the vault's tasks. */
object Deck {
    private val BILL = Regex("(^|\\s)#bill(\\s|$)")

    /** Rough minutes from `⏱ 15m`, or null if the task doesn't say. */
    fun minutes(t: TaskItem): Int? = TaskRules.minutes(t.raw)

    fun isBill(t: TaskItem) = BILL.containsMatchIn(t.raw)

    /**
     * Open tasks in the Tasks tab's order: dated ones earliest due first (so overdue first), then undated ones as they
     * sit in the vault. Leaves out bills, ones put off today and, if set, long ones.
     */
    fun ranked(tasks: List<TaskItem>, skipped: Set<String> = emptySet(), maxMinutes: Int? = null): List<TaskItem> =
        tasks.asSequence()
            .filter { !isBill(it) && it.raw !in skipped }
            .filter { maxMinutes == null || (minutes(it) ?: Int.MAX_VALUE) <= maxMinutes }
            .sortedBy { it.due ?: LocalDate.MAX }
            .toList()

    fun nextStep(tasks: List<TaskItem>, skipped: Set<String> = emptySet(), maxMinutes: Int? = null): TaskItem? =
        ranked(tasks, skipped, maxMinutes).firstOrNull()

    /** The tasks after the next step: 2 normally, 3 on a high-energy day. */
    fun topTasks(tasks: List<TaskItem>, count: Int, skipped: Set<String> = emptySet(), maxMinutes: Int? = null): List<TaskItem> =
        ranked(tasks, skipped, maxMinutes).drop(1).take(count)

    /** The "Tasks due" pill: open tasks due today or earlier. */
    fun dueCount(tasks: List<TaskItem>, today: LocalDate): Int = tasks.count { it.due != null && !it.due.isAfter(today) }
}
