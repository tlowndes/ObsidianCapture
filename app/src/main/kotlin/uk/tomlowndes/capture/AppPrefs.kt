package uk.tomlowndes.capture

import android.content.Context
import android.net.Uri

/** Typed wrapper over the app's SharedPreferences. Cheap to construct anywhere. */
class AppPrefs(ctx: Context) {
    private val p = ctx.applicationContext.getSharedPreferences("capture", Context.MODE_PRIVATE)

    var vault: Uri?
        get() = p.getString("vault", null)?.let(Uri::parse)
        set(v) { p.edit().putString("vault", v?.toString()).apply() }

    /** Tasks tab: group by due date (true) or keep the note's own order (false). */
    var sortByDue: Boolean
        get() = p.getBoolean("sort_by_due", true)
        set(v) { p.edit().putBoolean("sort_by_due", v).apply() }

    /** Ticking a 🔁 task also creates its next occurrence. */
    var recurring: Boolean
        get() = p.getBoolean("recurring", true)
        set(v) { p.edit().putBoolean("recurring", v).apply() }

    var ideasDir: String
        get() = p.getString("ideas_dir", "03 - Projects")!!
        set(v) { p.edit().putString("ideas_dir", v.trim().trim('/')).apply() }

    var imagesDir: String
        get() = p.getString("images_dir", "99 - Meta/Assets")!!
        set(v) { p.edit().putString("images_dir", v.trim().trim('/')).apply() }

    /** Blank = use the folder/format from the vault's own .obsidian/daily-notes.json. */
    var dailyDir: String
        get() = p.getString("daily_dir", "")!!
        set(v) { p.edit().putString("daily_dir", v.trim().trim('/')).apply() }

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
    var skipIfFilled: Boolean
        get() = p.getBoolean("skip_filled", true)
        set(v) { p.edit().putBoolean("skip_filled", v).apply() }

    fun draft(key: String): String = p.getString("draft_$key", "") ?: ""
    fun setDraft(key: String, text: String) {
        p.edit().apply { if (text.isEmpty()) remove("draft_$key") else putString("draft_$key", text) }.apply()
    }
}
