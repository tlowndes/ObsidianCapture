package uk.tomlowndes.capture

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * The last Flight Deck the phone read, so Deck shows straight away when it opens and then refreshes from the
 * vault in the background. Kept in the app's private storage, not the vault.
 */
internal object DeckCache {
    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences("deck_cache", Context.MODE_PRIVATE)

    fun save(ctx: Context, d: DeckData) = runCatching {
        val o = JSONObject()
            .put("day", LocalDate.now().toString())
            .put("tasks", JSONArray(d.tasks.map { JSONObject().put("raw", it.raw).put("path", it.path) }))
            .put("unread", d.unread)
            .put("layout", DeckConfig.withLayout(null, d.layout))
            .put("stuck", JSONArray(d.stuck.map { FlightNotes.stuckLine(it) }))
            .put("focusMin", d.focusMin)
            .put("jobs", JSONArray(d.jobs.map {
                JSONObject().put("path", it.path).put("company", it.company).put("role", it.role).put("status", it.status)
                    .put("next", it.next?.toString() ?: "").put("applied", it.applied?.toString() ?: "")
            }))
        d.project?.let { s ->
            o.put("project", JSONObject().put("title", s.title).put("snippet", s.snippet).put("entries", s.entries).put("images", s.images)
                .put("status", s.status).put("modified", s.modified).put("type", s.type ?: "").put("area", s.area ?: "").put("next", s.next ?: ""))
        }
        d.habits?.let { h -> o.put("habits", habitsText(h)) }
        d.calendars?.let { o.put("calendars", CalendarConfig.withSources(null, it)) }
        p(ctx).edit().putString("deck", o.toString()).apply()
    }

    fun load(ctx: Context): DeckData? = runCatching {
        val o = JSONObject(p(ctx).getString("deck", null) ?: return null)
        val sameDay = o.optString("day") == LocalDate.now().toString()
        val tasks = o.getJSONArray("tasks").let { a ->
            (0 until a.length()).mapNotNull { i -> a.getJSONObject(i).let { t -> TaskNote.parse(t.getString("raw"))?.copy(path = t.getString("path")) } }
        }
        val stuck = FlightNotes.stuck("## Quick\n" + o.getJSONArray("stuck").let { a -> (0 until a.length()).joinToString("\n") { a.getString(it) } })
            .getValue(StuckLength.QUICK)
        val jobs = o.getJSONArray("jobs").let { a ->
            (0 until a.length()).map { i ->
                val j = a.getJSONObject(i)
                fun date(k: String) = j.optString(k).takeIf { it.isNotEmpty() }?.let { LocalDate.parse(it) }
                Job(j.getString("path"), j.getString("company"), j.getString("role"), j.getString("status"), date("next"), date("applied"))
            }
        }
        val project = o.optJSONObject("project")?.let { s ->
            IdeaSummary(
                s.getString("title"), s.getString("snippet"), s.getInt("entries"), s.getInt("images"), null, s.getString("status"),
                s.getLong("modified"), s.optString("type").ifEmpty { null }, s.optString("area").ifEmpty { null }, s.optString("next").ifEmpty { null }
            )
        }
        DeckData(
            tasks = tasks,
            project = project,
            unread = o.optInt("unread"),
            layout = DeckConfig.layout(o.optString("layout")),
            stuck = stuck,
            focusMin = if (sameDay) o.optInt("focusMin") else 0,
            habits = o.optString("habits").takeIf { it.isNotEmpty() }?.let { Habits.parse(it) },
            calendars = o.optString("calendars").takeIf { it.isNotEmpty() }?.let { CalendarConfig.sources(it) },
            jobs = jobs
        )
    }.getOrNull()

    /** A HabitsNote written back as note text, which [Habits.parse] reads. */
    private fun habitsText(h: HabitsNote) = buildString {
        append("## Habits\n")
        h.habits.forEach { append("- ").append(it).append('\n') }
        append("\n## Water\ngoal: ${h.waterGoalMl} ml\n\n## Log\n| Date | Water ml | Done |\n|---|---|---|\n")
        h.days.values.sortedBy { it.date }.takeLast(14).forEach { append(Habits.row(it, h.habits)).append('\n') }
    }
}
