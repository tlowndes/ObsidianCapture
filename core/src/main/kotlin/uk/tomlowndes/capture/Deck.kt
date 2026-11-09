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
