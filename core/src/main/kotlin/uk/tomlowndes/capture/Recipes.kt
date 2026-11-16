package uk.tomlowndes.capture

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.time.LocalDate

/** A recipe as saved: just the cooking bits, none of the blog. */
data class Recipe(
    val title: String,
    val url: String? = null,
    val minutes: Int? = null,
    val serves: String? = null,
    val ingredients: List<String> = emptyList(),
    val steps: List<String> = emptyList(),
) {
    val host get() = url?.let { ReadStore.host(it) }
}

/** One ingredient line in a saved recipe note; [have] is its tick. */
data class Ingredient(val text: String, val have: Boolean)

/** A saved recipe note, read back for the recipe screen and Cook mode. */
data class RecipeNote(
    val path: String, val title: String, val url: String?, val minutes: Int?, val serves: String?,
    val ingredients: List<Ingredient>, val steps: List<String>,
)

/**
 * Recipe save (home-life-v1). Most recipe sites publish a schema.org Recipe in JSON-LD, so the page is read for that
 * first (no Claude needed). If there isn't one, Claude Fast can pull the recipe out of the page text. The note goes in
 * a Recipes folder: frontmatter, `## Ingredients` as ticks (ticked = you have it), `## Method` as numbered steps.
 * "Add to shopping" puts the unticked ones on the Shopping list note.
 */
object Recipes {
    const val FOLDER = "Recipes"
    const val META = "99 - Meta"
    const val SHOPPING = "$META/Shopping list.md"
    /** Where the note lived before 1.38. [shopping] moves it to [SHOPPING] the first time it's read. */
    const val OLD_SHOPPING = "${VaultWriter.INBOX}/Shopping list.md"
    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    // ---- reading a page ----

    /** The page's schema.org Recipe, or null when it doesn't have one. */
    fun fromHtml(html: String, url: String?): Recipe? {
        val doc = Jsoup.parse(html, url ?: "")
        doc.select("script[type=application/ld+json]").forEach { s ->
            val json = s.data().trim().ifEmpty { s.html().trim() }
            val found = runCatching { findRecipe(if (json.startsWith("[")) JSONArray(json) else JSONObject(json)) }.getOrNull()
            if (found != null) return fromJsonLd(found, url)
        }
        return null
    }

    /** Fetches [url] and reads its recipe; null when it has none (then [pageText] can go to Claude). Blocking. */
    fun fetch(url: String): Pair<Recipe?, String> {
        val doc = Jsoup.connect(url).userAgent(UA).timeout(15_000).followRedirects(true).maxBodySize(6_000_000).get()
        val html = doc.outerHtml()
        return fromHtml(html, url) to pageText(html)
    }

    /** The page's readable text, trimmed, for Claude when there's no JSON-LD. */
    fun pageText(html: String, max: Int = 12_000): String {
        val doc = Jsoup.parse(html)
        val title = doc.title()
        doc.select("script,style,noscript,nav,footer,header,aside,form,iframe,svg,button").remove()
        return (title + "\n\n" + doc.body()?.wholeText().orEmpty())
            .replace(Regex("[ \\t]+"), " ").replace(Regex("\\n\\s*\\n+"), "\n").trim().take(max)
    }

    private fun isRecipe(o: JSONObject): Boolean = when (val t = o.opt("@type")) {
        is String -> t.equals("Recipe", true)
        is JSONArray -> (0 until t.length()).any { t.optString(it).equals("Recipe", true) }
        else -> false
    }

    private fun findRecipe(node: Any?): JSONObject? = when (node) {
        is JSONObject -> if (isRecipe(node)) node else findRecipe(node.opt("@graph")) ?: findRecipe(node.opt("mainEntity"))
        is JSONArray -> (0 until node.length()).firstNotNullOfOrNull { findRecipe(node.opt(it)) }
        else -> null
    }

    private fun clean(s: String) = Jsoup.parse(s).text().replace(Regex("\\s+"), " ").trim()

    private fun strings(v: Any?): List<String> = when (v) {
        is String -> listOf(v)
        is JSONArray -> (0 until v.length()).flatMap { strings(v.opt(it)) }
        is JSONObject -> listOf(v.optString("text", v.optString("name", "")))
        is Number -> listOf(v.toString())
        else -> emptyList()
    }

    private fun steps(v: Any?): List<String> = when (v) {
        is String -> v.split(Regex("\\n+|(?<=\\.)\\s+(?=\\d+\\.\\s)")).map { clean(it) }.filter { it.isNotEmpty() }
        is JSONArray -> (0 until v.length()).flatMap { steps(v.opt(it)) }
        is JSONObject -> if (v.optString("@type").equals("HowToSection", true)) steps(v.opt("itemListElement"))
            else listOf(clean(v.optString("text", v.optString("name", "")))).filter { it.isNotEmpty() }
        else -> emptyList()
    }

    fun fromJsonLd(o: JSONObject, url: String?): Recipe {
        val time = listOf("totalTime", "cookTime", "prepTime").firstNotNullOfOrNull { k -> o.optString(k).takeIf { it.isNotBlank() }?.let(::isoMinutes) }
            ?: listOf("prepTime", "cookTime").mapNotNull { o.optString(it).takeIf { s -> s.isNotBlank() }?.let(::isoMinutes) }.takeIf { it.isNotEmpty() }?.sum()
        val serves = strings(o.opt("recipeYield")).map { clean(it) }.firstOrNull { it.isNotEmpty() }
            ?.let { y -> Regex("\\d+").find(y)?.value ?: y }
        return Recipe(
            title = clean(o.optString("name")).ifEmpty { "Recipe" },
            url = url,
            minutes = time?.takeIf { it > 0 },
            serves = serves,
            ingredients = strings(o.opt("recipeIngredient") ?: o.opt("ingredients")).map { clean(it) }.filter { it.isNotEmpty() },
            steps = steps(o.opt("recipeInstructions")),
        )
    }

    /** "PT1H30M" → 90. */
    fun isoMinutes(s: String): Int? {
        val m = Regex("P(?:(\\d+)D)?T?(?:(\\d+)H)?(?:(\\d+)M)?", RegexOption.IGNORE_CASE).matchEntire(s.trim()) ?: return null
        val (d, h, min) = m.destructured
        val total = (d.toIntOrNull() ?: 0) * 1440 + (h.toIntOrNull() ?: 0) * 60 + (min.toIntOrNull() ?: 0)
        return total.takeIf { it > 0 }
    }

    // ---- Claude fallback ----

    val CLAUDE_SYSTEM = """
        You pull recipes out of web pages. Reply with JSON only, no prose and no code fences:
        {"title": string, "minutes": number or null, "serves": string or null, "ingredients": [string], "steps": [string]}
        Use UK spelling and metric units as written. Leave out the life story, adverts and comments. If the page has no recipe, reply {"title": ""}.
    """.trimIndent()

    /** Claude's JSON reply as a recipe; null when it found none. */
    fun fromClaude(reply: String, url: String?): Recipe? {
        val json = reply.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            .let { s -> s.substring(s.indexOf('{').coerceAtLeast(0), (s.lastIndexOf('}') + 1).coerceAtLeast(0)) }
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val title = o.optString("title").trim()
        val ingredients = strings(o.opt("ingredients")).map { it.trim() }.filter { it.isNotEmpty() }
        if (title.isEmpty() || ingredients.isEmpty()) return null
        return Recipe(title, url, o.optInt("minutes", 0).takeIf { it > 0 }, o.optString("serves").takeIf { it.isNotBlank() && it != "null" },
            ingredients, strings(o.opt("steps")).map { it.trim() }.filter { it.isNotEmpty() })
    }

    // ---- the note ----

    fun markdown(r: Recipe, today: LocalDate): String = buildString {
        append("---\ntype: recipe\n")
        r.url?.let { append("source: $it\n") }
        r.minutes?.let { append("time: $it\n") }
        r.serves?.let { append("serves: $it\n") }
        append("saved: $today\ntags:\n  - recipe\n---\n\n")
        append("# ${r.title}\n\n")
        val meta = listOfNotNull(r.minutes?.let { TaskRules.minutesLabel(it) }, r.serves?.let { "serves $it" }, r.host?.let { "from [$it](${r.url})" })
        if (meta.isNotEmpty()) append(meta.joinToString(" · ") + "\n\n")
        append("## Ingredients\n\n")
        r.ingredients.forEach { append("- [ ] $it\n") }
        append("\n## Method\n\n")
        r.steps.forEachIndexed { i, s -> append("${i + 1}. $s\n") }
    }

    private val TICK = Regex("^\\s*[-*]\\s+\\[([ xX])]\\s+(.+)$")
    private val STEP = Regex("^\\s*(?:\\d+[.)]|[-*])\\s+(.+)$")

    fun parse(path: String, text: String): RecipeNote {
        val lines = text.split("\n")
        val body = Md.fmBody(lines)
        val title = body.firstOrNull { it.startsWith("# ") }?.removePrefix("# ")?.trim() ?: nameOf(path).removeSuffix(".md")
        fun section(name: String): List<String> = Md.sectionRange(lines, name)?.let { r -> lines.subList(r.first, r.last + 1) }.orEmpty()
        val ingredients = section("Ingredients").mapNotNull { l -> TICK.matchEntire(l)?.let { Ingredient(it.groupValues[2].trim(), it.groupValues[1] != " ") } }
        val steps = section("Method").mapNotNull { l -> STEP.matchEntire(l)?.groupValues?.get(1)?.trim() }.filter { !it.startsWith("[") }
        return RecipeNote(
            path, title, Md.fmGet(lines, "source"), Md.fmGet(lines, "time")?.toIntOrNull(), Md.fmGet(lines, "serves"),
            ingredients, steps,
        )
    }

    fun isRecipe(text: String): Boolean = Md.fmGet(text.split("\n"), "type")?.trim()?.equals("recipe", true) == true

    /** The note's text with ingredient [item]'s tick set to [have]. */
    fun withHave(text: String, item: String, have: Boolean): String = text.split("\n").joinToString("\n") { l ->
        val m = TICK.matchEntire(l)
        if (m != null && m.groupValues[2].trim() == item) l.replaceFirst(Regex("\\[[ xX]]"), if (have) "[x]" else "[ ]") else l
    }

    /** Where recipes go: a Recipes folder inside the vault's Resources folder (`04 - Resources`…), else at the top. */
    fun folder(v: Vault): String {
        runCatching { v.list("").firstOrNull { it.isDir && Regex("^\\d+\\s*-\\s*Resources$", RegexOption.IGNORE_CASE).matches(it.name) } }
            .getOrNull()?.let { return "${it.name}/$FOLDER" }
        return FOLDER
    }

    /** Saves [r] as a note and returns its path. */
    fun save(v: Vault, r: Recipe, today: LocalDate = LocalDate.now()): String {
        val dir = folder(v)
        v.mkdirs(dir)
        val name = r.title.replace(Regex("[\\\\/:*?\"<>|#^\\[\\]]"), "-").replace(Regex("\\s+"), " ").trim().take(90).ifEmpty { "Recipe" }
        val path = v.create(dir, name, "md")
        VaultIo.write(v, path, markdown(r, today))
        return path
    }

    /** Every recipe note, newest first. */
    fun list(v: Vault): List<RecipeNote> {
        val dir = folder(v)
        return runCatching { v.list(dir) }.getOrDefault(emptyList()).filter { !it.isDir && it.name.endsWith(".md") }
            .sortedByDescending { it.modified }
            .mapNotNull { e -> runCatching { parse("$dir/${e.name}", VaultIo.read(v, "$dir/${e.name}")) }.getOrNull() }
    }

    fun setHave(v: Vault, path: String, item: String, have: Boolean) =
        VaultIo.edit(v, path) { lines -> val out = withHave(lines.joinToString("\n"), item, have).split("\n"); lines.clear(); lines.addAll(out) }

    /** Open items already on the shopping list. */
    fun shoppingOpen(text: String): List<String> = text.split("\n").mapNotNull { l -> TICK.matchEntire(l)?.takeIf { it.groupValues[1] == " " }?.groupValues?.get(2)?.trim() }

    /** The shopping note with [items] added (skipping any already open there), plus how many were added. */
    fun withShopping(text: String, items: List<String>): Pair<String, Int> {
        val have = shoppingOpen(text).map { SharedList.clean(it).lowercase() }.toSet()
        val add = items.map { it.trim() }.filter { it.isNotEmpty() && it.lowercase() !in have }.distinctBy { it.lowercase() }
        if (add.isEmpty()) return text to 0
        val lines = text.split("\n").toMutableList()
        while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.lastIndex)
        if (lines.isEmpty()) lines.addAll(listOf("# Shopping list", ""))
        lines.addAll(add.map { "- [ ] $it" })
        return lines.joinToString("\n") + "\n" to add.size
    }

    /** Moves the old Inbox copy of the Shopping list into 99 - Meta, once, if there isn't one there yet. */
    fun moveShopping(v: Vault) {
        if (v.entry(SHOPPING) == null && v.entry(OLD_SHOPPING) != null) runCatching { v.mkdirs(META); v.move(OLD_SHOPPING, META) }
    }

    /** The Shopping list note's text (moving it from the Inbox first if needed), or null if there isn't one. */
    fun shopping(v: Vault): String? { moveShopping(v); return v.read(SHOPPING) }

    /** Adds [items] to the Shopping list note (made if missing). Returns how many were new. */
    fun addToShopping(v: Vault, items: List<String>): Int {
        moveShopping(v)
        if (v.entry(SHOPPING) == null) VaultIo.write(v, v.create(META, "Shopping list", "md"), "# Shopping list\n\n")
        var added = 0
        VaultIo.edit(v, SHOPPING) { lines ->
            val (out, n) = withShopping(lines.joinToString("\n"), items)
            added = n
            lines.clear(); lines.addAll(out.removeSuffix("\n").split("\n"))
        }
        return added
    }
}
