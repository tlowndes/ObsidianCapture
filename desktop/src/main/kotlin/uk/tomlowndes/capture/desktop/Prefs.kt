package uk.tomlowndes.capture.desktop

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

    /** Projects tab: last updated first (true) or by title A–Z (false). */
    var ideasByUpdated: Boolean
        get() = p.getBoolean("ideas_by_updated", true)
        set(v) { p.putBoolean("ideas_by_updated", v); p.flush() }

    /** Ticking a 🔁 task also creates its next occurrence. */
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

    fun draft(key: String): String = str("draft_${key.hashCode()}", "")
    fun setDraft(key: String, text: String) = put("draft_${key.hashCode()}", text.ifEmpty { null })

    /** Folder for the vault-wide task scan cache. */
    val cacheDir: File get() = File(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"), "Vault Capture")
}
