package uk.tomlowndes.capture.desktop

import uk.tomlowndes.capture.ReminderSettings
import uk.tomlowndes.capture.KeyStore
import uk.tomlowndes.capture.SharedSettings
import com.sun.jna.platform.win32.Crypt32Util
import java.io.File
import java.util.Base64
import java.util.prefs.Preferences

/** The Windows registry node as core's [KeyStore]; every write is flushed. */
private class RegistryStore(private val p: Preferences) : KeyStore {
    override fun string(key: String): String? = p.get(key, null)
    override fun putString(key: String, value: String?) { if (value == null) p.remove(key) else p.put(key, value); p.flush() }
    override fun bool(key: String, def: Boolean) = p.getBoolean(key, def)
    override fun putBool(key: String, value: Boolean) { p.putBoolean(key, value); p.flush() }
    override fun int(key: String, def: Int) = p.getInt(key, def)
    override fun putInt(key: String, value: Int) { p.putInt(key, value); p.flush() }
    override fun long(key: String, def: Long) = p.getLong(key, def)
    override fun putLong(key: String, value: Long) { p.putLong(key, value); p.flush() }
    override fun float(key: String, def: Float) = p.getFloat(key, def)
    override fun putFloat(key: String, value: Float) { p.putFloat(key, value); p.flush() }
}

/**
 * Settings kept in the Windows user registry (java.util.prefs), the desktop twin of the Android app's AppPrefs.
 * The settings the phone has too live in core ([SharedSettings]); this adds the PC's own.
 */
class DesktopPrefs(private val p: Preferences = Preferences.userRoot().node("uk/tomlowndes/capture")) :
    SharedSettings(RegistryStore(p), readerSizeDefault = 18f, readerSerifDefault = true, jarvisSpeakDefault = false) {

    private fun str(key: String, def: String) = p.get(key, def)
    private fun put(key: String, v: String?) { if (v == null) p.remove(key) else p.put(key, v); p.flush() }

    var vault: File?
        get() = p.get("vault", null)?.let(::File)
        set(v) = put("vault", v?.absolutePath)

    /** Tasks tab order (windows-catchup-v1): "due", "priority" or "note". */
    var taskOrder: String
        get() = p.get("task_order", null) ?: if (sortByDue) "due" else "note"
        set(v) = put("task_order", v)

    /** Claude from this PC (windows-settings-gaps-v1). The key is encrypted for this Windows user, never in the vault. */
    var pcBrain: Boolean
        get() = p.getBoolean("pc_brain", false)
        set(v) { p.putBoolean("pc_brain", v); p.flush() }
    var claudeKey: String?
        get() = p.get("claude_key", null)?.let { enc ->
            runCatching { String(Crypt32Util.cryptUnprotectData(Base64.getDecoder().decode(enc)), Charsets.UTF_8) }.getOrNull()
        }
        set(v) {
            val t = v?.trim()?.ifEmpty { null }
            put("claude_key", t?.let { Base64.getEncoder().encodeToString(Crypt32Util.cryptProtectData(it.toByteArray(Charsets.UTF_8))) })
        }

    /** Background sync with Obsidian Headless (windows-nav-sync-v1). */
    var bgSync: Boolean
        get() = p.getBoolean("bg_sync", false)
        set(v) { p.putBoolean("bg_sync", v); p.flush() }


    /** The quick capture shortcut, one of [Hotkey.CHOICES]. */
    var hotkey: String
        get() = str("hotkey", Hotkey.CHOICES.first().label)
        set(v) = put("hotkey", v)

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

    var jarvisToken: String?
        get() = p.get("jarvis_token", null)?.let { enc ->
            runCatching { String(Crypt32Util.cryptUnprotectData(Base64.getDecoder().decode(enc)), Charsets.UTF_8) }.getOrNull()
        }
        set(v) {
            val t = v?.trim()?.ifEmpty { null }
            put("jarvis_token", t?.let { Base64.getEncoder().encodeToString(Crypt32Util.cryptProtectData(it.toByteArray(Charsets.UTF_8))) })
        }
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
    // Stays "Vault Capture" after the rename to Flight Deck, so the cache, Quick Task backups
    // and Jarvis chats saved by older versions are still found.
    val cacheDir: File get() = File(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"), "Vault Capture")
}
