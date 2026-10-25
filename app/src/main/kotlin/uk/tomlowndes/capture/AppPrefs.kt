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
