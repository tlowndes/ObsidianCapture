package uk.tomlowndes.capture

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.IsoFields
import java.util.Locale

/** One day of the week's plan: a recipe note, or plain text such as "Leftovers" or "Takeaway 🍕". */
data class MealDay(val date: LocalDate, val recipe: String? = null, val text: String? = null) {
    val empty get() = recipe == null && text == null
    val label get() = recipe ?: text ?: ""
}

/** One line of the week's shopping, with the recipes that need it. */
data class ShopItem(val text: String, val from: List<String>)

/**
 * Meal plan the week (meals-spend-v1). Builds on Recipe save, with no Claude: recipes go on days in
 * "Recipes/Week 47.md", and the unticked ingredients of the week's recipes merge into one shopping list.
 */
object MealPlan {
    private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)
    private val LINE = Regex("^- (\\w{3}) (\\d{1,2} \\w{3}): (.*)$")
    private val LINK = Regex("^\\[\\[([^\\]|]+)(?:\\|[^\\]]*)?\\]\\]")
    val QUICK = listOf("Leftovers", "Takeaway 🍕", "Out", "Something easy")

    fun monday(d: LocalDate): LocalDate = d.with(DayOfWeek.MONDAY)
    fun week(d: LocalDate) = d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
    fun path(v: Vault, d: LocalDate) = "${Recipes.folder(v)}/Week ${week(d)}.md"

    fun parse(text: String?, monday: LocalDate): List<MealDay> {
        val byDay = text.orEmpty().replace("\r", "").split("\n").mapNotNull { l ->
            val g = LINE.find(l.trim())?.groupValues ?: return@mapNotNull null
            val dow = runCatching { DayOfWeek.from(DateTimeFormatter.ofPattern("EEE", Locale.UK).parse(g[1])) }.getOrNull() ?: return@mapNotNull null
            val v = g[3].trim()
            val day = MealDay(monday.plusDays((dow.value - 1).toLong()))
            val link = LINK.find(v)?.groupValues?.get(1)?.trim()
            day.copy(recipe = link, text = if (link == null) v.ifEmpty { null } else null)
        }.associateBy { it.date }
        return (0..6L).map { monday.plusDays(it) }.map { byDay[it] ?: MealDay(it) }
    }

    fun load(v: Vault, d: LocalDate): List<MealDay> = monday(d).let { m -> parse(v.read(path(v, m)), m) }

    fun markdown(days: List<MealDay>): String = "---\ntype: meal-plan\nweek: ${days.first().date.get(IsoFields.WEEK_BASED_YEAR)}-W${week(days.first().date)}\n---\n\n" +
        days.joinToString("\n") { d -> "- ${d.date.format(DAY)}: " + (d.recipe?.let { "[[$it]]" } ?: d.text ?: "") } + "\n"

    fun save(v: Vault, days: List<MealDay>) {
        val p = path(v, days.first().date)
        if (v.entry(p) == null) { val dir = p.substringBeforeLast('/'); v.mkdirs(dir); VaultIo.write(v, v.create(dir, p.substringAfterLast('/').removeSuffix(".md"), "md"), markdown(days)) }
        else VaultIo.write(v, p, markdown(days))
    }

    fun set(days: List<MealDay>, date: LocalDate, recipe: String?, text: String? = null) =
        days.map { if (it.date == date) MealDay(date, recipe, if (recipe == null) text else null) else it }

    /** Swaps two days (drag, or ⇅ to move a meal down a day). */
    fun swap(days: List<MealDay>, a: LocalDate, b: LocalDate): List<MealDay> {
        val x = days.first { it.date == a }; val y = days.first { it.date == b }
        return days.map { when (it.date) { a -> y.copy(date = a); b -> x.copy(date = b); else -> it } }
    }

    fun planned(days: List<MealDay>) = days.count { !it.empty }

    fun tonight(days: List<MealDay>, today: LocalDate) = days.firstOrNull { it.date == today && !it.empty }

    private val QTY = Regex("^(\\d+(?:[.,]\\d+)?|½|¼|¾|a|an|one|two|three)\\s+", RegexOption.IGNORE_CASE)
    private val MEASURE = Regex("^\\d+(?:[.,]\\d+)?\\s*(g|kg|ml|l|tbsp|tsp|cups?|cans?|tins?)\\s+", RegexOption.IGNORE_CASE)
    private val WORDS = mapOf("a" to 1.0, "an" to 1.0, "one" to 1.0, "two" to 2.0, "three" to 3.0, "½" to 0.5, "¼" to 0.25, "¾" to 0.75)

    /** "2 onions" and "1 onion, finely chopped" both key as "onion". */
    fun key(item: String): String {
        val s = item.lowercase().substringBefore(",").substringBefore("(").replace(MEASURE, "").replace(QTY, "").trim()
        return s.replace(Regex("(es|s)$"), "").let { if (it.length < 3) s else it }
    }

    private fun qty(item: String): Double? = QTY.find(item.trim())?.groupValues?.get(1)?.lowercase()?.let { WORDS[it] ?: it.replace(',', '.').toDoubleOrNull() }

    /**
     * The week's shopping: unticked ingredients from the planned recipes, merged by name. Plain counts add up
     * ("1 onion" + "1 onion" = "2 onion"s); anything with units keeps both lines joined by "+".
     */
    fun shopping(days: List<MealDay>, recipes: List<RecipeNote>): List<ShopItem> {
        val wanted = days.mapNotNull { it.recipe }.mapNotNull { name -> recipes.firstOrNull { it.title.equals(name, true) || VaultTasks.noteName(it.path).equals(name, true) } }
        val groups = linkedMapOf<String, MutableList<Pair<String, String>>>()
        wanted.forEach { r -> r.ingredients.filter { !it.have }.forEach { i -> groups.getOrPut(key(i.text)) { mutableListOf() }.add(i.text.trim() to r.title) } }
        return groups.values.map { list ->
            val from = list.map { it.second }.distinct()
            if (list.size == 1) return@map ShopItem(list[0].first, from)
            val counts = list.map { qty(it.first) }
            val bare = list.map { it.first.replace(QTY, "").substringBefore(",").trim() }
            val sameUnit = bare.map { key(it) }.distinct().size == 1 && bare.all { b -> !Regex("^(g|kg|ml|l|tbsp|tsp|cups?|cans?|tins?)\\b", RegexOption.IGNORE_CASE).containsMatchIn(b) }
            if (counts.all { it != null } && sameUnit) {
                val n = counts.sumOf { it!! }
                val name = bare.maxByOrNull { it.length }!!
                ShopItem("${if (n % 1.0 == 0.0) n.toInt().toString() else n.toString()} $name", from)
            } else ShopItem(list.joinToString(" + ") { it.first.substringBefore(",").trim() }, from)
        }
    }

    /** "Merged: 2 onions (gnocchi + fajitas)": the first merged line, for the shopping card. */
    fun mergedNote(items: List<ShopItem>): String? = items.firstOrNull { it.from.size > 1 }?.let { i ->
        "Merged: ${i.text} (${i.from.joinToString(" + ") { it.lowercase() }})"
    }
}
