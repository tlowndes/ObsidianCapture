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

    /** Tasks tab: the tag and side filters last used, so they stay on between visits. */
    var tasksTag: String?
        get() = p.getString("tasks_tag", null)
        set(v) { p.edit().putString("tasks_tag", v).apply() }
    var tasksFilter: String?
        get() = p.getString("tasks_filter", null)
        set(v) { p.edit().putString("tasks_filter", v).apply() }

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

    /** In-app updater: check GitHub for a new release when the app opens. */
    var updateChecks: Boolean
        get() = p.getBoolean("update_checks", true)
        set(v) { p.edit().putBoolean("update_checks", v).apply() }

    /** The release tag Later was pressed on; its banner stays hidden until a newer one comes out. */
    var updateSkip: String?
        get() = p.getString("update_skip", null)
        set(v) { p.edit().putString("update_skip", v).apply() }

    /** When GitHub was last asked, and the latest tag it gave. */
    var updateCheckedAt: Long
        get() = p.getLong("update_checked_at", 0)
        set(v) { p.edit().putLong("update_checked_at", v).apply() }
    var updateLatest: String?
        get() = p.getString("update_latest", null)
        set(v) { p.edit().putString("update_latest", v).apply() }

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

    /** What Jarvis last sorted, waiting for Check and save (null once saved or discarded). */
    var jarvisFound: SortResult?
        get() = Sorting.fromJson(p.getString("jarvis_found", null))
        set(v) { p.edit().apply { if (v == null) remove("jarvis_found") else putString("jarvis_found", Sorting.toJson(v)) }.remove("capture_queue").remove("sort_model").apply() }

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

    /** Capture's switch: 0 Task, 1 Read later, 2 Jarvis. Opens on the last one used. */
    var captureMode: Int
        get() = p.getInt("capture_mode", 0)
        set(v) { p.edit().putInt("capture_mode", v).apply() }

    var askedNotif: Boolean
        get() = p.getBoolean("asked_notif", false)
        set(v) { p.edit().putBoolean("asked_notif", v).apply() }

    /** Vault tab: show 99 folders, sort folders A–Z instead of newest first, and the last few searches. */
    var vaultShowHidden: Boolean
        get() = p.getBoolean("vault_show_hidden", false)
        set(v) { p.edit().putBoolean("vault_show_hidden", v).apply() }
    var vaultByName: Boolean
        get() = p.getBoolean("vault_by_name", false)
        set(v) { p.edit().putBoolean("vault_by_name", v).apply() }
    var vaultSearches: List<String>
        get() = p.getString("vault_searches", "").orEmpty().split("\u0001").filter { it.isNotBlank() }
        set(v) { p.edit().putString("vault_searches", v.joinToString("\u0001")).apply() }

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

    /** Jarvis voice tab. The token is in [SecretStore], never here. "demo" as the address needs no server. */
    var jarvisAddress: String
        get() = p.getString("jarvis_address", "").orEmpty()
        set(v) { p.edit().putString("jarvis_address", v.trim()).apply() }
    var jarvisWorker: String
        get() = p.getString("jarvis_worker", "jarvis") ?: "jarvis"
        set(v) { p.edit().putString("jarvis_worker", v).apply() }
    var jarvisSpeak: Boolean
        get() = p.getBoolean("jarvis_speak", true)
        set(v) { p.edit().putBoolean("jarvis_speak", v).apply() }
    /** Voice id sent with each recording ("" = the server's default) and its label for Settings. */
    var jarvisVoice: String
        get() = p.getString("jarvis_voice", "").orEmpty()
        set(v) { p.edit().putString("jarvis_voice", v).apply() }
    var jarvisVoiceLabel: String
        get() = p.getString("jarvis_voice_label", "").orEmpty()
        set(v) { p.edit().putString("jarvis_voice_label", v).apply() }
    /** Reply playback speed, done on the phone. */
    var jarvisSpeed: Float
        get() = p.getFloat("jarvis_speed", 1f)
        set(v) { p.edit().putFloat("jarvis_speed", v).apply() }
    /** The last exchange, shown until you talk again: heard, reply, epoch millis. */
    var jarvisLast: Triple<String, String, Long>?
        get() = p.getString("jarvis_last_reply", null)?.let {
            Triple(p.getString("jarvis_last_heard", "").orEmpty(), it, p.getLong("jarvis_last_at", 0))
        }
        set(v) {
            p.edit().apply {
                if (v == null) { remove("jarvis_last_heard"); remove("jarvis_last_reply"); remove("jarvis_last_at") }
                else { putString("jarvis_last_heard", v.first); putString("jarvis_last_reply", v.second); putLong("jarvis_last_at", v.third) }
            }.apply()
        }
    /** Finished-job ids already shown or dismissed, so the Done line doesn't come back. Keeps the last 50. */
    var jarvisSeenDone: List<String>
        get() = p.getString("jarvis_seen_done", "").orEmpty().split("\u0001").filter { it.isNotBlank() }
        set(v) { p.edit().putString("jarvis_seen_done", v.takeLast(50).joinToString("\u0001")).apply() }

    fun draft(key: String): String = p.getString("draft_$key", "") ?: ""
    fun setDraft(key: String, text: String) {
        p.edit().apply { if (text.isEmpty()) remove("draft_$key") else putString("draft_$key", text) }.apply()
    }
}
