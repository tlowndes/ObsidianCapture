package uk.tomlowndes.capture

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What Snap anything can make from a photo. */
enum class SnapKind(val label: String) { TASK("✓ Task"), BILL("£ Bill"), NOTE("✎ Note") }

/** What the user asked for on the camera: Auto works it out. */
enum class SnapMode(val label: String) { AUTO("Auto"), TASKS("Tasks"), BILL("Bill"), NOTE("Note") }

/** One suggestion from a photo. [keep] is its tick on the result sheet. */
data class SnapItem(
    val kind: SnapKind,
    val text: String,
    val due: LocalDate? = null,
    val time: LocalTime? = null,
    val amount: BigDecimal? = null,
    val repeat: BillRepeat = BillRepeat.ONCE,
    val keep: Boolean = true,
)

/** What Claude made of a photo: a heading ("Looks like a bill"), what it saw, and the suggestions. */
data class SnapResult(val title: String, val what: String, val items: List<SnapItem>, val steps: Boolean = false) {
    val kept get() = items.filter { it.keep }
}

/**
 * Snap anything (snap-anything-v1): a photo of a letter, receipt, whiteboard, list or screenshot goes to Claude Fast
 * (vision), which suggests tasks, a bill or a note. Nothing is saved until you tick and press Save.
 */
object Snap {
    private val DAY = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.UK)

    /** Photos are shrunk to this many pixels on the long side before sending (plenty for reading text). */
    const val MAX_SIDE = 1568

    fun system(today: LocalDate, mode: SnapMode, tags: List<String>): String = """
        You read photos and screenshots for Tom's to-do app and suggest what to save. Today is ${today.format(DAY)}.
        Reply with JSON only, no prose and no code fences:
        {"title": string, "what": string, "items": [{"kind": "task"|"bill"|"note", "text": string, "due": "YYYY-MM-DD" or null, "time": "HH:MM" or null, "amount": "12.34" or null, "repeat": "monthly"|"yearly"|"weekly"|"once", "keep": boolean}], "steps": boolean}
        - title: a few words like "Looks like a bill", "5 tasks found", "Looks like a booking".
        - what: what the photo is, e.g. "Council Tax letter", "Handwritten list".
        - A letter or invoice with money to pay: one "bill" (text is the payee, e.g. "Council Tax"; due the pay-by date), plus a "task" to pay it due the day before (keep true), and any other useful follow-up as a task with keep false.
        - A receipt: one "note" summarising shop, total and date (keep true).
        - A list, whiteboard or notes: one "task" per line. Crossed-out lines get keep false. Set "steps" true when they clearly belong to one job.
        - A booking, appointment or chat with a date: a "task" with due and time.
        - Anything else worth keeping: one "note".
        Keep task text short and in UK English. Use dates in the future. ${tags.takeIf { it.isNotEmpty() }?.let { "Tags Tom uses: ${it.joinToString(" ")}. Add one to a task's text only when it clearly fits." } ?: ""}
        ${when (mode) {
            SnapMode.AUTO -> ""
            SnapMode.TASKS -> "Tom picked Tasks: suggest tasks only."
            SnapMode.BILL -> "Tom picked Bill: suggest the bill (and the pay task)."
            SnapMode.NOTE -> "Tom picked Note: suggest one note only."
        }}
    """.trimIndent()

    const val PROMPT = "What should I save from this?"

    fun parse(reply: String): SnapResult? {
        val raw = reply.trim()
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val o = runCatching { JSONObject(raw.substring(start, end + 1)) }.getOrNull() ?: return null
        val arr = o.optJSONArray("items") ?: JSONArray()
        val items = (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::item) }
        return SnapResult(
            o.optString("title").trim().ifEmpty { if (items.isEmpty()) "Nothing to save" else "Found ${items.size}" },
            o.optString("what").trim(), items, o.optBoolean("steps", false),
        )
    }

    private fun str(o: JSONObject, k: String): String? = o.opt(k)?.takeIf { it != JSONObject.NULL }?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

    private fun item(o: JSONObject): SnapItem? {
        val text = str(o, "text") ?: return null
        val kind = when (str(o, "kind")?.lowercase()) { "bill" -> SnapKind.BILL; "note" -> SnapKind.NOTE; else -> SnapKind.TASK }
        return SnapItem(
            kind, text,
            due = str(o, "due")?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() },
            time = str(o, "time")?.let { runCatching { LocalTime.parse(it.padStart(5, '0').take(5)) }.getOrNull() },
            amount = str(o, "amount")?.let { Bills.amount(it) },
            repeat = when (str(o, "repeat")?.lowercase()) { "monthly" -> BillRepeat.MONTHLY; "yearly" -> BillRepeat.YEARLY; "weekly" -> BillRepeat.WEEKLY; else -> BillRepeat.ONCE },
            keep = o.optBoolean("keep", true),
        )
    }

    /** How an item reads on the sheet: "Council Tax · £142.00 · due Tue 1 Dec · monthly". */
    fun detail(i: SnapItem): String = listOfNotNull(
        i.amount?.let { TaskViews.money(it) },
        i.due?.let { "due " + it.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)) },
        i.time?.let { it.format(DateTimeFormatter.ofPattern("HH:mm")) },
        i.repeat.takeIf { i.kind == SnapKind.BILL && it != BillRepeat.ONCE }?.label?.lowercase(),
    ).joinToString(" · ")

    /**
     * Saves the kept items: bills and tasks to Quick Task (a timed one gets its time), notes to the inbox note under a
     * "📷 Snap" line. With [steps], the tasks become steps under one task named [parent]. Returns how many were saved.
     */
    fun save(w: VaultWriter, r: SnapResult, steps: Boolean, parent: String, today: LocalDate = LocalDate.now()): Int {
        val kept = r.kept
        var n = 0
        kept.filter { it.kind == SnapKind.BILL }.forEach { b ->
            w.addTask(Bills.text(b.text, b.amount, b.repeat), b.due?.toString()); n++
        }
        val tasks = kept.filter { it.kind == SnapKind.TASK }
        if (steps && tasks.size > 1) {
            val due = tasks.mapNotNull { it.due }.minOrNull()
            w.addTask(parent.trim().ifEmpty { r.what.ifEmpty { "Snapped list" } }, due?.toString())
            val line = TaskNote.newTask(parent.trim().ifEmpty { r.what.ifEmpty { "Snapped list" } }, due?.toString())
            w.listQuickTasks().lastOrNull { it.raw == line }?.let { t -> w.addSubtasks(t, tasks.map { Step(it.text, null) }) }
            n += tasks.size
        } else tasks.forEach { t ->
            val due = t.due ?: if (t.time != null) today else null
            w.addTask(t.text, due?.toString())
            if (t.time != null && due != null) {
                val line = TaskNote.newTask(t.text, due.toString())
                w.listQuickTasks().lastOrNull { it.raw == line }?.let { item -> w.planAt(item, due, t.time) }
            }
            n++
        }
        val notes = kept.filter { it.kind == SnapKind.NOTE }
        if (notes.isNotEmpty()) {
            w.appendInbox(listOf("📷 Snap · $today" + (r.what.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: "")) + notes.map { "- ${it.text}" })
            n += notes.size
        }
        return n
    }
}
