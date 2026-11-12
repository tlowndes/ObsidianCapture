package uk.tomlowndes.capture

import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One group of a list slip: its heading (null before any heading) and its items. */
data class ListGroup(val heading: String?, val items: List<String>)

/**
 * A list or a block of text for the printer (printables-v1): the shopping list slip and Print from share.
 * [groups] print as tick boxes; [body] prints as plain text. One of them is empty.
 */
data class ListDoc(val title: String, val sub: String, val groups: List<ListGroup> = emptyList(), val body: String? = null) {
    val count get() = groups.sumOf { it.items.size }
}

/** How a shared piece of text prints (printables-v1, frame 2). */
enum class ShareAs(val label: String) { TEXT("Text"), TICKS("Tick boxes"), SUMMARY("Summary") }

/** The Bills slip (printables-v1, frame 3): one month's bills, still to pay and paid. */
data class BillsDoc(val month: LocalDate, val toPay: List<Bill>, val paid: List<Bill>, val left: BigDecimal, val paidTotal: BigDecimal) {
    val title: String get() = month.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.UK)).uppercase(Locale.UK)
    /** "Thu 12" for a bill's row. */
    fun day(b: Bill): String = b.task.due?.format(DateTimeFormatter.ofPattern("EEE d", Locale.UK)) ?: ""
}

object PrintDocs {
    private val HEADING = Regex("^#{1,6}\\s+(.*)$")
    private val ITEM = Regex("^\\s*[-*+]\\s+(?:\\[( |x|X)]\\s+)?(.*)$")
    private val NUMBERED = Regex("^\\s*\\d+[.)]\\s+(.*)$")

    /** Note names offered as list chips: anything that sounds like a list. */
    private val LISTY = Regex("(?i)shopping|groceries|grocery|packing|list")

    fun looksLikeList(path: String): Boolean = LISTY.containsMatchIn(path.substringAfterLast('/'))

    /**
     * A note's list as groups: `##` headings become groups (when [byHeading]), `- [ ]`, `- ` and `1.` lines become
     * items, and ticked `- [x]` lines are left out when [skipTicked]. Frontmatter, tags and Tasks-plugin dates are
     * stripped from each item.
     */
    fun listFrom(title: String, text: String, byHeading: Boolean = true, skipTicked: Boolean = true, date: LocalDate = LocalDate.now()): ListDoc {
        val lines = Md.fmBody(text.replace("\r\n", "\n").split("\n"))
        val groups = mutableListOf<ListGroup>()
        var heading: String? = null
        var items = mutableListOf<String>()
        fun flush() { if (items.isNotEmpty()) groups += ListGroup(heading, items); items = mutableListOf() }
        for (l in lines) {
            val h = HEADING.matchEntire(l.trim())
            if (h != null) {
                if (byHeading) { flush(); heading = clean(h.groupValues[1]).uppercase(Locale.UK) }
                continue
            }
            val m = ITEM.matchEntire(l) ?: NUMBERED.matchEntire(l)?.let { n -> ITEM.matchEntire("- " + n.groupValues[1]) } ?: continue
            val ticked = m.groupValues[1].equals("x", ignoreCase = true)
            val item = clean(m.groupValues[2])
            if (item.isNotEmpty() && !(ticked && skipTicked)) items += item
        }
        flush()
        val doc = ListDoc(title.uppercase(Locale.UK), "", if (byHeading) groups else listOf(ListGroup(null, groups.flatMap { it.items })).filter { it.items.isNotEmpty() })
        return doc.copy(sub = stamp(date) + " · " + plural(doc.count, "item"))
    }

    /** A typed list: one item per non-blank line. */
    fun typed(text: String, date: LocalDate = LocalDate.now()): ListDoc {
        val items = text.split("\n").map { clean(it.trim().removePrefix("- [ ]").removePrefix("-").removePrefix("*")) }.filter { it.isNotEmpty() }
        return ListDoc("LIST", stamp(date) + " · " + plural(items.size, "item"), listOf(ListGroup(null, items)).filter { it.items.isNotEmpty() })
    }

    /** Shared text: plain [ShareAs.TEXT], or one box per line for [ShareAs.TICKS]. [from] is the app it came from. */
    fun shared(title: String, text: String, how: ShareAs, from: String?, time: String): ListDoc {
        val sub = listOfNotNull(from?.let { "Shared from $it" }, time).joinToString(" · ")
        val t = title.trim().ifEmpty { "Shared" }.uppercase(Locale.UK)
        return if (how == ShareAs.TICKS) ListDoc(t, sub, listOf(ListGroup(null, text.split("\n").map { clean(it.trim().removePrefix("-").removePrefix("*")) }.filter { it.isNotEmpty() })))
        else ListDoc(t, sub, body = text.trim())
    }

    /** A title for shared text: the subject if there is one, else the first few words. */
    fun shareTitle(subject: String?, text: String): String {
        subject?.trim()?.takeIf { it.isNotEmpty() }?.let { return it.take(40) }
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return words.take(4).joinToString(" ").trimEnd('.', ',', ':', ';').take(40)
    }

    /** [month]'s bills, from the same numbers as the Bills card. [showPaid] off leaves the paid ones off. */
    fun bills(open: List<TaskItem>, done: List<TickedTask>, month: LocalDate, showPaid: Boolean = true, today: LocalDate = LocalDate.now()): BillsDoc {
        val ref = if (month.year == today.year && month.month == today.month) today else month.withDayOfMonth(1)
        val m = TaskViews.billsMonth(open, done, ref)
        return BillsDoc(month.withDayOfMonth(1), m.unpaid, if (showPaid) m.paid else emptyList(), m.left, m.paidTotal)
    }

    private fun clean(s: String) = TaskRules.cleanText(Tags.strip(s)).replace(Regex("\\[\\[([^]|]*\\|)?([^]]*)]]"), "$2").trim()
    private fun stamp(d: LocalDate) = d.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK))
    private fun plural(n: Int, w: String) = "$n $w" + if (n == 1) "" else "s"
}
