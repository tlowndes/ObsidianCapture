package uk.tomlowndes.capture

import java.math.BigDecimal
import java.time.LocalDate

/** A `#bill` task: `- [ ] Council tax £128 #bill 📅 2026-10-26`. */
data class Bill(val task: TaskItem, val name: String, val amount: BigDecimal?)

/** A month of bills: what's left to pay and what's been paid, with totals. */
data class MonthBills(val unpaid: List<Bill>, val paid: List<Bill>, val left: BigDecimal, val paidTotal: BigDecimal, val dueThisWeek: BigDecimal) {
    val empty get() = unpaid.isEmpty() && paid.isEmpty()
    val all get() = (paid.map { it to true } + unpaid.map { it to false }).sortedBy { it.first.task.due }
}

/** The groups the Flight Deck Tasks tab shows, in order. Later also holds undated tasks. */
enum class TaskGroup(val title: String) { OVERDUE("Overdue"), TODAY("Today"), WEEK("This week"), LATER("Later") }

/** Filters on the Tasks tab, beyond tags. */
enum class TaskFilter { WORK, PERSONAL, SHORT }

object TaskViews {
    private val TAG = Regex("(?:^|\\s)(#(?!task\\b)[\\w/-]+)")
    private val AMOUNT = Regex("£\\s*(\\d+(?:[.,]\\d{1,2})?)")
    /** Tags that put a task on the work side (amber edge); everything else is personal. */
    val WORK_TAGS = setOf("#work", "#jobs")
    const val SHORT_MIN = 15

    fun tags(t: TaskItem): List<String> = TAG.findAll(t.raw).map { it.groupValues[1].lowercase() }.distinct().toList()

    fun isWork(t: TaskItem) = tags(t).any { it in WORK_TAGS }

    /** Task text without its tags, for showing next to a tag chip. */
    fun plainText(t: TaskItem) = t.text.replace(TAG, "").replace(Regex("\\s+"), " ").trim().ifBlank { t.text }

    /** Tags used across [tasks], most used first, for the filter chips. */
    fun topTags(tasks: List<TaskItem>, max: Int = 6): List<String> =
        tasks.flatMap { tags(it) }.groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(max).map { it.key }

    fun matches(t: TaskItem, tag: String?, filter: TaskFilter?, query: String = ""): Boolean {
        if (tag != null && tag !in tags(t)) return false
        when (filter) {
            TaskFilter.WORK -> if (!isWork(t)) return false
            TaskFilter.PERSONAL -> if (isWork(t)) return false
            TaskFilter.SHORT -> if ((Deck.minutes(t) ?: Int.MAX_VALUE) > SHORT_MIN) return false
            null -> {}
        }
        return query.isBlank() || t.text.contains(query.trim(), ignoreCase = true)
    }

    fun groupOf(d: LocalDate?, today: LocalDate): TaskGroup = when (TaskGroups.bucketOf(d, today)) {
        Bucket.OVERDUE -> TaskGroup.OVERDUE
        Bucket.TODAY -> TaskGroup.TODAY
        Bucket.TOMORROW, Bucket.WEEK -> TaskGroup.WEEK
        Bucket.LATER, Bucket.NONE -> TaskGroup.LATER
    }

    /** Non-empty groups in order, each by due date with undated tasks last. */
    fun groups(tasks: List<TaskItem>, today: LocalDate): List<Pair<TaskGroup, List<TaskItem>>> =
        TaskGroup.values().mapNotNull { g ->
            tasks.filter { groupOf(it.due, today) == g }.sortedBy { it.due ?: LocalDate.MAX }
                .takeIf { it.isNotEmpty() }?.let { g to it }
        }

    /**
     * The [max] most recently ticked of [done]. [stamps] holds the moment the app ticked a line (done line ->
     * epoch millis); other lines count from the start of their ✅ day. Ties keep [done]'s order.
     */
    fun recentDone(done: List<TickedTask>, stamps: Map<String, Long>, max: Int = 5): List<TickedTask> =
        done.sortedByDescending { d ->
            stamps[d.raw] ?: d.done?.atStartOfDay(java.time.ZoneId.systemDefault())?.toInstant()?.toEpochMilli() ?: Long.MIN_VALUE
        }.take(max)

    fun bill(t: TaskItem): Bill {
        val amount = AMOUNT.find(t.raw)?.groupValues?.get(1)?.replace(',', '.')?.let { runCatching { BigDecimal(it) }.getOrNull() }
        val name = plainText(t).replace(AMOUNT, "").replace(Regex("\\s+"), " ").trim()
        return Bill(t, name.ifBlank { t.text }, amount)
    }

    /** Bills due in the next 7 days (today and the 6 after), overdue ones first. */
    fun billsDue(tasks: List<TaskItem>, today: LocalDate): List<Bill> =
        tasks.filter { Deck.isBill(it) && it.due != null && it.due.isBefore(today.plusDays(7)) }
            .sortedBy { it.due }
            .map { bill(it) }

    /**
     * Bills this month (build-queue O5) for the card at the top of Upcoming: open #bill tasks due this month (or late),
     * and #bill tasks ticked this month as paid. Empty when the month has none.
     */
    fun billsMonth(open: List<TaskItem>, done: List<TickedTask>, today: LocalDate): MonthBills {
        val first = today.withDayOfMonth(1)
        val last = today.withDayOfMonth(today.lengthOfMonth())
        val unpaid = open.filter { Deck.isBill(it) && it.due != null && !it.due.isAfter(last) }.sortedBy { it.due }.map { bill(it) }
        val paid = done.filter { BILL_DONE.containsMatchIn(it.raw) && it.done != null && !it.done.isBefore(first) && !it.done.isAfter(last) }
            .map { d -> bill(TaskItem(d.raw, d.text, TaskRules.due(d.raw) ?: d.done, null, d.path)) }.sortedBy { it.task.due }
        val week = unpaid.filter { it.task.due!!.isBefore(today.plusDays(7)) }
        return MonthBills(unpaid, paid, total(unpaid), total(paid), total(week))
    }

    private val BILL_DONE = Regex("(^|\\s)#bill(\\s|$)")

    fun total(bills: List<Bill>): BigDecimal = bills.mapNotNull { it.amount }.fold(BigDecimal.ZERO, BigDecimal::add)

    /** "£163" or "£12.50". */
    fun money(v: BigDecimal): String = "£" + v.stripTrailingZeros().let { if (it.scale() <= 0) it.toPlainString() else v.setScale(2).toPlainString() }
}
