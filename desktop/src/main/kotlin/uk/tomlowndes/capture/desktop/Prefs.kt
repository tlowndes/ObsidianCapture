package uk.tomlowndes.capture.desktop

import uk.tomlowndes.capture.VaultPrefs
import java.io.File
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

    /** Ideas tab: last updated first (true) or by title A–Z (false). */
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

    fun draft(key: String): String = str("draft_${key.hashCode()}", "")
    fun setDraft(key: String, text: String) = put("draft_${key.hashCode()}", text.ifEmpty { null })

    /** Folder for the vault-wide task scan cache. */
    val cacheDir: File get() = File(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"), "Vault Capture")
}
