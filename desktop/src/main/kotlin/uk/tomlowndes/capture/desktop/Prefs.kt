package uk.tomlowndes.capture.desktop

import uk.tomlowndes.capture.ReminderSettings
import uk.tomlowndes.capture.VaultPrefs
import com.sun.jna.platform.win32.Crypt32Util
import java.io.File
import java.util.Base64
import java.util.prefs.Preferences

/** Settings kept in the Windows user registry (java.util.prefs), the desktop twin of the Android app's AppPrefs. */
class DesktopPrefs(private val p: Preferences = Preferences.userRoot().node("uk/tomlowndes/capture")) : VaultPrefs {

    private fun str(key: String, def: String) = p.get(key, def)
    private fun put(key: String, v: String?) { if (v == null) p.remove(key) else p.put(key, v); p.flush() }

    var vault: File?
        get() = p.get("vault", null)?.let(::File)
        set(v) = put("vault", v?.absolutePath)

    /** Tasks tab: group by due date (true) or keep the note's own order (false). */
    var sortByDue: Boolean
        get() = p.getBoolean("sort_by_due", true)
        set(v) { p.putBoolean("sort_by_due", v); p.flush() }

    /** Tasks tab order (windows-catchup-v1): "due", "priority" or "note". */
    var taskOrder: String
        get() = p.get("task_order", null) ?: if (sortByDue) "due" else "note"
        set(v) = put("task_order", v)

    /** Tasks tab shows the Upcoming week instead of the list. */
    var tasksUpcoming: Boolean
        get() = p.getBoolean("tasks_upcoming", false)
        set(v) { p.putBoolean("tasks_upcoming", v); p.flush() }

    /** Projects tab: last updated first (true) or by title A–Z (false). */
    var ideasByUpdated: Boolean
        get() = p.getBoolean("ideas_by_updated", true)
        set(v) { p.putBoolean("ideas_by_updated", v); p.flush() }

    /** Ticking a 🔁 task also creates its next occurrence. */
    /** Quick Task guard (quick-task-guard-v1): keep copies of Quick Task on this PC and flag odd changes. */
    var quickGuard: Boolean
        get() = p.getBoolean("quick_guard", true)
        set(v) { p.putBoolean("quick_guard", v); p.flush() }
    var recurring: Boolean
        get() = p.getBoolean("recurring", true)
        set(v) { p.putBoolean("recurring", v); p.flush() }

    override var ideasDir: String
        get() = str("ideas_dir", "03 - Projects")
        set(v) = put("ideas_dir", v.trim().trim('/'))

    override var imagesDir: String
        get() = str("images_dir", "99 - Meta/Assets")
        set(v) = put("images_dir", v.trim().trim('/'))

    /** The quick capture shortcut, one of [Hotkey.CHOICES]. */
    var hotkey: String
        get() = str("hotkey", Hotkey.CHOICES.first().label)
        set(v) = put("hotkey", v)

    var readerSize: Float
        get() = p.getFloat("reader_size", 18f)
        set(v) { p.putFloat("reader_size", v); p.flush() }
    var readerSerif: Boolean
        get() = p.getBoolean("reader_serif", true)
        set(v) { p.putBoolean("reader_serif", v); p.flush() }

    /** How far through an article you got (0..1), keyed by file name; 0 clears it. */
    fun readPos(file: String): Float = p.getFloat("pos_${file.hashCode()}", 0f)
    fun setReadPos(file: String, f: Float) {
        if (f <= 0f) p.remove("pos_${file.hashCode()}") else p.putFloat("pos_${file.hashCode()}", f)
    }

    /** Next-step tasks put off with Later, by their line. Only today's count; tomorrow they come back. */
    var laterToday: Set<String>
        get() = if (str("later_day", "") == java.time.LocalDate.now().toString()) str("later", "").split("\n").filter { it.isNotEmpty() }.toSet() else emptySet()
        set(v) { put("later_day", java.time.LocalDate.now().toString()); put("later", v.joinToString("\n")) }

    /** Weather card: the Morning / Afternoon / Evening strip is open. */
    /** The day Plan today / Shut down was last done (yyyy-mm-dd), so the Now line stops asking. */
    var plannedOn: String
        get() = str("planned_on", "")
        set(v) = put("planned_on", v)
    var shutDownOn: String
        get() = str("shut_down_on", "")
        set(v) = put("shut_down_on", v)
    /** The day the Today card's "✓ N earlier" row was left open; it closes again tomorrow. */
    var earlierOpenOn: String
        get() = str("earlier_open_on", "")
        set(v) = put("earlier_open_on", v)

    /** Deck cards dragged into the left column (deck-drag-v1), by name. Null until the first drag: the default split. */
    var deckLeft: Set<String>?
        get() = p.get("deck_left", null)?.split(",")?.filter { it.isNotEmpty() }?.toSet()
        set(v) = put("deck_left", v?.joinToString(","))

    var weatherStrip: Boolean
        get() = p.getBoolean("weather_strip", false)
        set(v) { p.putBoolean("weather_strip", v); p.flush() }

    /** The town the weather is for, as typed in Settings, and where Open-Meteo found it. */
    var weatherPlace: String
        get() = str("weather_place", "")
        set(v) = put("weather_place", v.trim().ifEmpty { null })
    var weatherLatLon: Pair<Double, Double>?
        get() {
            val lat = p.get("weather_lat", null)?.toDoubleOrNull() ?: return null
            val lon = p.get("weather_lon", null)?.toDoubleOrNull() ?: return null
            return lat to lon
        }
        set(v) { put("weather_lat", v?.first?.toString()); put("weather_lon", v?.second?.toString()) }

    /** The last forecast and when it was fetched, so the card shows straight away. */
    var weatherJson: String?
        get() = p.get("weather_json", null)
        set(v) = put("weather_json", v)
    var weatherAt: Long
        get() = p.getLong("weather_at", 0)
        set(v) { p.putLong("weather_at", v); p.flush() }

    /** GitHub token for the repo line on a project, encrypted for this Windows user (DPAPI). */
    var githubToken: String?
        get() = p.get("github_token", null)?.let { enc ->
            runCatching { String(Crypt32Util.cryptUnprotectData(Base64.getDecoder().decode(enc)), Charsets.UTF_8) }.getOrNull()
        }
        set(v) {
            val t = v?.trim()?.ifEmpty { null }
            put("github_token", t?.let { Base64.getEncoder().encodeToString(Crypt32Util.cryptProtectData(it.toByteArray(Charsets.UTF_8))) })
        }

    /** In-app updater: check GitHub on launch, the tag Later hid, and the last check. */
    var updateChecks: Boolean
        get() = p.getBoolean("update_checks", true)
        set(v) { p.putBoolean("update_checks", v); p.flush() }
    var updateSkip: String?
        get() = p.get("update_skip", null)
        set(v) = put("update_skip", v)
    var updateCheckedAt: Long
        get() = p.getLong("update_checked_at", 0)
        set(v) { p.putLong("update_checked_at", v); p.flush() }
    var updateLatest: String?
        get() = p.get("update_latest", null)
        set(v) = put("update_latest", v)

    /** Settings › Reminders (windows-reminders-v1). */
    var reminders: ReminderSettings
        get() = ReminderSettings(
            beforeOn = p.getBoolean("rem_before_on", true), beforeMinutes = p.getInt("rem_before_min", 10),
            dueOn = p.getBoolean("rem_due_on", true), dueMinute = p.getInt("rem_due", 9 * 60),
            morningOn = p.getBoolean("rem_morning_on", false), morningMinute = p.getInt("rem_morning", 8 * 60),
            eveningOn = p.getBoolean("rem_evening_on", false), eveningMinute = p.getInt("rem_evening", 18 * 60 + 30),
            weeklyOn = p.getBoolean("rem_weekly_on", false), weeklyMinute = p.getInt("rem_weekly", 17 * 60),
        )
        set(v) {
            p.putBoolean("rem_before_on", v.beforeOn); p.putInt("rem_before_min", v.beforeMinutes)
            p.putBoolean("rem_due_on", v.dueOn); p.putInt("rem_due", v.dueMinute)
            p.putBoolean("rem_morning_on", v.morningOn); p.putInt("rem_morning", v.morningMinute)
            p.putBoolean("rem_evening_on", v.eveningOn); p.putInt("rem_evening", v.eveningMinute)
            p.putBoolean("rem_weekly_on", v.weeklyOn); p.putInt("rem_weekly", v.weeklyMinute)
            p.flush()
        }
    var reminderSound: Boolean
        get() = p.getBoolean("rem_sound", true)
        set(v) { p.putBoolean("rem_sound", v); p.flush() }
    /** Reminders already shown today (ReminderPlan keys). */
    var remindersShown: Set<String>
        get() = str("rem_shown", "").split("\n").filter { it.isNotEmpty() }.toSet()
        set(v) = put("rem_shown", v.joinToString("\n").ifEmpty { null })

    /** The assistant's name. Blank becomes Tower. The jarvis_* keys stay so the address and token survive. */
    var assistantName: String
        get() = uk.tomlowndes.capture.AssistantName.clean(str("assistant_name", ""))
        set(v) = put("assistant_name", uk.tomlowndes.capture.AssistantName.clean(v))

    /** Settings › Jarvis (windows-jarvis-v1). The token is encrypted for this Windows user, never in the vault. */
    var jarvisAddress: String
        get() = str("jarvis_address", "")
        set(v) = put("jarvis_address", v.trim().ifEmpty { null })
    var jarvisToken: String?
        get() = p.get("jarvis_token", null)?.let { enc ->
            runCatching { String(Crypt32Util.cryptUnprotectData(Base64.getDecoder().decode(enc)), Charsets.UTF_8) }.getOrNull()
        }
        set(v) {
            val t = v?.trim()?.ifEmpty { null }
            put("jarvis_token", t?.let { Base64.getEncoder().encodeToString(Crypt32Util.cryptProtectData(it.toByteArray(Charsets.UTF_8))) })
        }
    var jarvisWorker: String
        get() = str("jarvis_worker", "jarvis")
        set(v) = put("jarvis_worker", v)
    var jarvisSpeak: Boolean
        get() = p.getBoolean("jarvis_speak", false)
        set(v) { p.putBoolean("jarvis_speak", v); p.flush() }
    var jarvisSpeed: Float
        get() = p.getFloat("jarvis_speed", 1f)
        set(v) { p.putFloat("jarvis_speed", v); p.flush() }
    var jarvisHoldSpace: Boolean
        get() = p.getBoolean("jarvis_hold_space", true)
        set(v) { p.putBoolean("jarvis_hold_space", v); p.flush() }
    /** Finished background jobs already shown, by id. */
    var jarvisSeenDone: Set<String>
        get() = str("jarvis_seen_done", "").split("\n").filter { it.isNotEmpty() }.toSet()
        set(v) = put("jarvis_seen_done", v.toList().takeLast(200).joinToString("\n").ifEmpty { null })

    /** The receipt printer (Phomemo M02 Pro) picked once from a Bluetooth scan. */
    var printerAddress: String?
        get() = p.get("printer_address", null)
        set(v) = put("printer_address", v)
    var printerName: String
        get() = str("printer_name", "Printer")
        set(v) = put("printer_name", v)
    /** Daily Slip switches, as "weather,calendar,…". */
    var slipParts: String?
        get() = p.get("slip_parts", null)
        set(v) = put("slip_parts", v)
    /** Text size Large for every slip (printables-v1). */
    var slipLarge: Boolean
        get() = p.getBoolean("slip_large", false)
        set(v) { p.putBoolean("slip_large", v); p.flush() }
    /** Week slip switches that are off, as "bills,writeLine,…" (slip-week-v2). */
    var weekSlipOff: String?
        get() = p.get("week_slip_off", null)
        set(v) = put("week_slip_off", v)

    fun draft(key: String): String = str("draft_${key.hashCode()}", "")
    fun setDraft(key: String, text: String) = put("draft_${key.hashCode()}", text.ifEmpty { null })

    /** Folder for the vault-wide task scan cache. */
    val cacheDir: File get() = File(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"), "Vault Capture")
}
