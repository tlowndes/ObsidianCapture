package uk.tomlowndes.capture.desktop

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.tomlowndes.capture.FileVault
import uk.tomlowndes.capture.IdeaStore
import uk.tomlowndes.capture.ReadStore
import uk.tomlowndes.capture.VaultWriter
import java.awt.Desktop
import java.io.File
import java.net.URI
import java.net.URLEncoder

/** obsidian:// link for [path] inside the vault named [vaultName]. Spaces are %20; the .md is dropped. */
fun obsidianOpenUrl(vaultName: String, path: String): String {
    fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    return "obsidian://open?vault=${enc(vaultName)}&file=${enc(path.removeSuffix(".md"))}"
}

enum class Tab { TASKS, IDEAS, READ, FIND, SETTINGS }

/** Everything the windows share: the vault, settings, and a nudge to reload when the vault changed elsewhere. */
class AppState(val prefs: DesktopPrefs) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val snack = SnackbarHostState()

    var vault by mutableStateOf(prefs.vault?.takeIf { it.isDirectory }?.let(::FileVault))
        private set
    var tab by mutableStateOf(Tab.TASKS)
    var windowVisible by mutableStateOf(true)
    var quickVisible by mutableStateOf(false)
    /** Bumped when notes may have changed outside the open tab (window refocused, quick capture saved). */
    var changes by mutableIntStateOf(0)
        private set
    /** The quick capture shortcut, and whether Windows refused it (another app has it). */
    var hotkeyLabel by mutableStateOf(prefs.hotkey)
    var hotkeyFailed by mutableStateOf(false)
    /** Shows a Windows notification from the tray icon. */
    var notify: (String, String) -> Unit = { _, _ -> }
    /** Counts for the sidebar. */
    var overdue by mutableIntStateOf(0)
    var ideas by mutableIntStateOf(0)
    var unread by mutableIntStateOf(0)

    fun setVault(dir: File) {
        prefs.vault = dir
        vault = FileVault(dir)
        changed()
    }

    fun changed() { changes++ }

    fun writer() = vault?.let { VaultWriter(it, File(prefs.cacheDir, "vault-tasks.json")) }
    fun readStore() = vault?.let { ReadStore(it) }
    fun ideaStore() = vault?.let { IdeaStore(it, prefs) }

    /** Runs [work] off the UI thread; shows its failure as a message. Returns null on failure. */
    suspend fun <T> io(failure: String = "Failed", work: () -> T): T? =
        withContext(Dispatchers.IO) { runCatching(work) }.onFailure { say("$failure: ${it.message}") }.getOrNull()

    fun say(msg: String) { scope.launch { snack.showSnackbar(msg, withDismissAction = false) } }

    /** Shows [msg] with Undo; runs [undo] if it's pressed. */
    fun offerUndo(msg: String, undo: suspend () -> Unit) {
        scope.launch {
            if (snack.showSnackbar(msg, "Undo", duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) undo()
        }
    }

    /** Opens a vault note in Obsidian (obsidian://open), or the URL in the browser. */
    fun openInObsidian(path: String) {
        val v = prefs.vault ?: return
        browse(obsidianOpenUrl(v.name, path))
    }

    fun browse(url: String) {
        runCatching { Desktop.getDesktop().browse(URI(url)) }.onFailure { say("Couldn't open it: ${it.message}") }
    }
}
