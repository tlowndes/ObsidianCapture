package uk.tomlowndes.capture

import android.content.Context
import android.net.Uri

/** Typed wrapper over the app's SharedPreferences. Cheap to construct anywhere. */
class AppPrefs(ctx: Context) : VaultPrefs {
    private val p = ctx.applicationContext.getSharedPreferences("capture", Context.MODE_PRIVATE)

    var vault: Uri?
        get() = p.getString("vault", null)?.let(Uri::parse)
        set(v) { p.edit().putString("vault", v?.toString()).apply() }

    /** Tasks tab: group by due date (true) or keep the note's own order (false). */
    var sortByDue: Boolean
        get() = p.getBoolean("sort_by_due", true)
        set(v) { p.edit().putBoolean("sort_by_due", v).apply() }

    /** Ideas tab: last updated first (true) or by title A–Z (false). */
    var ideasByUpdated: Boolean
        get() = p.getBoolean("ideas_by_updated", true)
        set(v) { p.edit().putBoolean("ideas_by_updated", v).apply() }

    /** Ticking a 🔁 task also creates its next occurrence. */
    var recurring: Boolean
        get() = p.getBoolean("recurring", true)
        set(v) { p.edit().putBoolean("recurring", v).apply() }

    override var ideasDir: String
        get() = p.getString("ideas_dir", "03 - Projects")!!
        set(v) { p.edit().putString("ideas_dir", v.trim().trim('/')).apply() }

    override var imagesDir: String
        get() = p.getString("images_dir", "99 - Meta/Assets")!!
        set(v) { p.edit().putString("images_dir", v.trim().trim('/')).apply() }

    /** The morning and evening reminders on the Tasks tab. */
    var morningOn: Boolean
        get() = p.getBoolean("morning_on", true)
        set(v) { p.edit().putBoolean("morning_on", v).apply() }
    var morningMinute: Int
        get() = p.getInt("morning_min", 8 * 60)
        set(v) { p.edit().putInt("morning_min", v).apply() }
    var eveningOn: Boolean
        get() = p.getBoolean("evening_on", true)
        set(v) { p.edit().putBoolean("evening_on", v).apply() }
    var eveningMinute: Int
        get() = p.getInt("evening_min", 21 * 60)
        set(v) { p.edit().putInt("evening_min", v).apply() }

    /** Flight Deck energy chip. Medium unless set today, so it resets each morning. */
    var energy: Energy
        get() = if (p.getString("energy_day", null) == java.time.LocalDate.now().toString())
            Energy.values().firstOrNull { it.name == p.getString("energy", null) } ?: Energy.MED
        else Energy.MED
        set(v) { p.edit().putString("energy", v.name).putString("energy_day", java.time.LocalDate.now().toString()).apply() }

    /** Weather card: Morning / Afternoon / Evening strip, shown after a tap on the card. */
    var weatherStrip: Boolean
        get() = p.getBoolean("weather_strip", false)
        set(v) { p.edit().putBoolean("weather_strip", v).apply() }

    /** Tasks put off with "Later" on Next step (raw lines). Cleared the next day; due dates stay as they are. */
    var laterToday: Set<String>
        get() = if (p.getString("later_day", null) == java.time.LocalDate.now().toString())
            p.getStringSet("later", null).orEmpty() else emptySet()
        set(v) { p.edit().putStringSet("later", v).putString("later_day", java.time.LocalDate.now().toString()).apply() }

    /**
     * Claude model id for Sort it. Blank or missing uses [Sorting.MODEL], so a retired id can be
     * replaced in Settings without a new build.
     */
    var sortModel: String
        get() = p.getString("sort_model", null)?.trim()?.ifEmpty { null } ?: Sorting.MODEL
        set(v) {
            val id = v.trim()
            p.edit().apply { if (id.isEmpty() || id == Sorting.MODEL) remove("sort_model") else putString("sort_model", id) }.apply()
        }

    /** Brain dumps waiting to be sorted or saved; raw text is kept until Save. */
    var captureQueue: List<QueuedDump>
        get() = Sorting.queueFromJson(p.getString("capture_queue", null))
        set(v) { p.edit().putString("capture_queue", Sorting.queueToJson(v)).apply() }

    /** Task due alerts (off until switched on) and the Sunday weekly review. */
    var dueOn: Boolean
        get() = p.getBoolean("due_on", false)
        set(v) { p.edit().putBoolean("due_on", v).apply() }
    var dueMinute: Int
        get() = p.getInt("due_min", 9 * 60)
        set(v) { p.edit().putInt("due_min", v).apply() }
    var weeklyOn: Boolean
        get() = p.getBoolean("weekly_on", true)
        set(v) { p.edit().putBoolean("weekly_on", v).apply() }
    var weeklyMinute: Int
        get() = p.getInt("weekly_min", 19 * 60)
        set(v) { p.edit().putInt("weekly_min", v).apply() }

    /** Capture's switch: 0 Task, 1 Read later, 2 Brain dump. Opens on the last one used. */
    var captureMode: Int
        get() = p.getInt("capture_mode", 0)
        set(v) { p.edit().putInt("capture_mode", v).apply() }

    var askedNotif: Boolean
        get() = p.getBoolean("asked_notif", false)
        set(v) { p.edit().putBoolean("asked_notif", v).apply() }

    /** Reader text size in sp, and serif (true) or sans body text. */
    var readerSize: Float
        get() = p.getFloat("reader_size", 16.5f)
        set(v) { p.edit().putFloat("reader_size", v).apply() }
    var readerSerif: Boolean
        get() = p.getBoolean("reader_serif", false)
        set(v) { p.edit().putBoolean("reader_serif", v).apply() }

    /** Where you got to in an article (pixels), keyed by file name; 0 clears it. */
    fun scrollPos(file: String): Int = p.getInt("scroll_$file", 0)
    fun setScrollPos(file: String, y: Int) {
        p.edit().apply { if (y <= 0) remove("scroll_$file") else putInt("scroll_$file", y) }.apply()
    }

    fun draft(key: String): String = p.getString("draft_$key", "") ?: ""
    fun setDraft(key: String, text: String) {
        p.edit().apply { if (text.isEmpty()) remove("draft_$key") else putString("draft_$key", text) }.apply()
    }
}
