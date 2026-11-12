package uk.tomlowndes.capture

import java.math.BigDecimal

/** How often a bill comes round (add-bill-v1). [rule] is the Tasks plugin repeat, null for a one-off. */
enum class BillRepeat(val label: String, val rule: String?) {
    MONTHLY("Monthly", "every month"), YEARLY("Yearly", "every year"), WEEKLY("Weekly", "every week"), ONCE("Just once", null)
}

/** A past bill offered as a one-tap name, with its last amount. */
data class BillSuggestion(val name: String, val amount: BigDecimal?)

/**
 * Adding bills (add-bill-v1). A bill is an ordinary task in Quick Task:
 * `- [ ] #task Car insurance £38.50 #bill 🔁 every month 📅 2026-11-26`, so the Bills card, Don't forget,
 * the Week slip and Obsidian all read it, and ticking it as paid writes next month's line.
 */
object Bills {
    private val NUMBER = Regex("^£?\\s*(\\d+(?:[.,]\\d{1,2})?)$")

    /** "38.50", "£38.5" or "38,50" as an amount; null when blank or not a number. */
    fun amount(input: String): BigDecimal? =
        NUMBER.matchEntire(input.trim())?.groupValues?.get(1)?.replace(',', '.')?.let { runCatching { BigDecimal(it) }.getOrNull() }

    /** The task text for [VaultWriter.addTask] (which adds `#task` and the due date). */
    fun text(name: String, amount: BigDecimal?, repeat: BillRepeat): String =
        (listOfNotNull(name.trim().replace(Regex("\\s+"), " "), amount?.let { TaskViews.money(it) }, "#bill").joinToString(" ")) +
            (repeat.rule?.let { " 🔁 $it" } ?: "")

    /** The whole line as it will look in the note, for the sheet's preview. */
    fun preview(name: String, amount: BigDecimal?, repeat: BillRepeat, due: java.time.LocalDate?): String =
        TaskNote.newTask(text(name, amount, repeat), due?.toString())

    /** Names of past bills, open and paid, newest first, each with its latest amount. */
    fun suggestions(open: List<TaskItem>, done: List<TickedTask>, max: Int = 6): List<BillSuggestion> {
        val all = open.filter { Deck.isBill(it) }.map { it.raw to (it.due ?: java.time.LocalDate.MIN) } +
            done.filter { Deck.isBill(TaskItem(it.raw, it.text, null)) }.map { it.raw to (TaskRules.due(it.raw) ?: it.done ?: java.time.LocalDate.MIN) }
        return all.sortedByDescending { it.second }
            .map { (raw, _) -> TaskViews.bill(TaskItem(raw, TaskRules.cleanText(raw.substringAfter("] ")), null)) }
            .distinctBy { it.name.lowercase() }
            .take(max)
            .map { BillSuggestion(it.name, it.amount) }
    }
}
