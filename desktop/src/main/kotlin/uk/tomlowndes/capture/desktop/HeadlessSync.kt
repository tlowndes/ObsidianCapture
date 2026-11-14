package uk.tomlowndes.capture.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Background sync (windows-nav-sync-v1): runs the official Obsidian Headless client (`ob sync --continuous`)
 * hidden while Flight Deck is running, so the vault stays synced without opening Obsidian.
 *
 * Two Sync clients must never sync the same vault at once, so it pauses while Obsidian.exe is running
 * and starts again when Obsidian closes.
 */
object HeadlessSync {
    enum class Status { OFF, CHECKING, NO_NODE, NOT_SET_UP, RUNNING, PAUSED, STOPPED }

    var status by mutableStateOf(Status.OFF)
    /** Last time `ob` printed something that wasn't an error. */
    var lastSync by mutableStateOf<LocalTime?>(null)
    var lastLine by mutableStateOf<String?>(null)
    var nodeVersion by mutableStateOf<String?>(null)

    private var proc: Process? = null
    private var loopStarted = false
    private var restartFails = 0
    private val HM = DateTimeFormatter.ofPattern("HH:mm")

    val logFile: File get() = File(DesktopPrefs().cacheDir, "background-sync.log")

    /** The sidebar's line under the vault path, or null when background sync is off. */
    fun sidebarLine(): Pair<String, Boolean>? = when (status) {
        Status.OFF, Status.CHECKING -> null
        Status.RUNNING -> (lastSync?.let { "● Synced ${it.format(HM)}" } ?: "● Syncing…") to true
        Status.PAUSED -> "● Paused · Obsidian is open" to false
        Status.NOT_SET_UP -> "● Sync not set up" to false
        Status.NO_NODE -> "● Sync needs Node 22" to false
        Status.STOPPED -> "● Sync stopped" to false
    }

    /** Starts the watch loop once per run; it follows the setting and Obsidian being open. */
    fun start(state: AppState) {
        if (loopStarted) return
        loopStarted = true
        Runtime.getRuntime().addShutdownHook(Thread { stopProcess() })
        state.scope.launch {
            while (true) {
                runCatching { tick(state) }
                delay(15_000)
            }
        }
    }

    /** Called by the Settings toggle: applies the change now instead of waiting for the next tick. */
    fun applyNow(state: AppState) = state.scope.launch { runCatching { tick(state) } }

    fun syncNow(state: AppState) = state.scope.launch {
        withContext(Dispatchers.IO) { stopProcess() }
        restartFails = 0
        tick(state)
    }

    private suspend fun tick(state: AppState) {
        val vault = state.prefs.vault
        if (!state.prefs.bgSync || vault == null) {
            withContext(Dispatchers.IO) { stopProcess() }
            status = Status.OFF
            return
        }
        if (obsidianRunning()) {
            withContext(Dispatchers.IO) { stopProcess() }
            status = Status.PAUSED
            return
        }
        if (proc?.isAlive == true) { status = Status.RUNNING; return }
        if (status == Status.OFF) status = Status.CHECKING
        val node = withContext(Dispatchers.IO) { nodeMajor() }
        if (node == null || node < 22) { status = Status.NO_NODE; return }
        val ob = withContext(Dispatchers.IO) { findOb() }
        if (ob == null || !withContext(Dispatchers.IO) { isSetUp(ob, vault) }) { status = Status.NOT_SET_UP; return }
        // A run that keeps dying (bad login, network) shouldn't be restarted forever; Sync now resets this.
        if (restartFails >= 3) { status = Status.STOPPED; return }
        withContext(Dispatchers.IO) { startProcess(ob, vault) }
        status = if (proc?.isAlive == true) Status.RUNNING else Status.STOPPED
    }

    private fun startProcess(ob: File, vault: File) {
        val log = logFile.apply { parentFile?.mkdirs() }
        // Java starts console programs without a window (CREATE_NO_WINDOW), so nothing pops up.
        val p = ProcessBuilder("cmd.exe", "/c", ob.absolutePath, "sync", "--continuous", "--path", vault.absolutePath)
            .directory(vault).redirectErrorStream(true).start()
        proc = p
        lastLine = null
        thread(isDaemon = true, name = "headless-sync-log") {
            runCatching {
                log.appendText("\n--- ${java.time.LocalDateTime.now()} start ---\n")
                p.inputStream.bufferedReader().forEachLine { line ->
                    val l = line.trim()
                    if (l.isEmpty()) return@forEachLine
                    runCatching { log.appendText(l + "\n") }
                    lastLine = l
                    if (!l.contains("error", ignoreCase = true) && !l.contains("fail", ignoreCase = true)) lastSync = LocalTime.now()
                }
            }
            val code = runCatching { p.waitFor() }.getOrNull()
            runCatching { log.appendText("--- exited ${code ?: "?"} ---\n") }
            if (proc === p && code != null) restartFails++
        }
    }

    private fun stopProcess() {
        val p = proc ?: return
        proc = null
        // cmd.exe runs node as a child; end the children too, or node keeps syncing.
        runCatching { p.descendants().forEach { it.destroy() } }
        runCatching { p.destroy(); if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly() }
    }

    /** True while the Obsidian desktop app is open. */
    fun obsidianRunning(): Boolean = runCatching {
        ProcessHandle.allProcesses().anyMatch { h ->
            h.info().command().map { it.substringAfterLast('\\').equals("Obsidian.exe", ignoreCase = true) }.orElse(false)
        }
    }.getOrDefault(false)

    /** Node's major version, or null when Node isn't installed. */
    fun nodeMajor(): Int? = runCatching {
        val out = exec(listOf("cmd.exe", "/c", "node", "--version"), 10) ?: return null
        nodeVersion = out.trim()
        out.trim().removePrefix("v").substringBefore('.').toInt()
    }.getOrNull()

    /** The `ob` command that `npm install -g obsidian-headless` puts in %APPDATA%\npm, or wherever PATH has it. */
    fun findOb(): File? {
        System.getenv("APPDATA")?.let { File(it, "npm\\ob.cmd") }?.takeIf { it.isFile }?.let { return it }
        val where = exec(listOf("cmd.exe", "/c", "where", "ob.cmd"), 10) ?: return null
        return where.lines().map { it.trim() }.firstOrNull { it.endsWith(".cmd", true) }?.let(::File)?.takeIf { it.isFile }
    }

    /**
     * Whether this vault folder has been linked to a remote vault with `ob sync-setup`: it's in
     * `ob sync-list-local`, or `ob sync-status` for the folder succeeds.
     */
    private fun isSetUp(ob: File, vault: File): Boolean {
        val local = exec(listOf("cmd.exe", "/c", ob.absolutePath, "sync-list-local", "--json"), 20)
        if (local != null && local.contains(vault.name, ignoreCase = true)) return true
        val status = exec(listOf("cmd.exe", "/c", ob.absolutePath, "sync-status", "--path", vault.absolutePath), 20) ?: return false
        return !status.contains("not configured", true) && !status.contains("not set up", true)
    }

    /**
     * Opens a console window that installs Obsidian Headless if it's missing, signs in, lists the remote vaults
     * and links this vault folder to the one you type. One time only.
     */
    fun openSetup(vault: File) {
        val script = File(System.getenv("TEMP") ?: System.getProperty("java.io.tmpdir"), "flight-deck-sync-setup.cmd")
        script.writeText(
            buildString {
                appendLine("@echo off")
                appendLine("title Flight Deck - background sync setup")
                appendLine("cd /d \"${vault.absolutePath}\"")
                appendLine("where ob >nul 2>nul || (")
                appendLine("  echo Installing Obsidian Headless...")
                appendLine("  call npm install -g obsidian-headless || goto failed")
                appendLine(")")
                appendLine("echo.")
                appendLine("echo Step 1 of 2: sign in to your Obsidian account.")
                appendLine("call ob login || goto failed")
                appendLine("echo.")
                appendLine("echo Your remote vaults:")
                appendLine("call ob sync-list-remote")
                appendLine("echo.")
                appendLine("set /p VAULTNAME=Step 2 of 2: type the remote vault name for this folder and press Enter: ")
                appendLine("call ob sync-setup --vault \"%VAULTNAME%\" --path \"${vault.absolutePath}\" || goto failed")
                appendLine("echo.")
                appendLine("echo All set. You can close this window; Flight Deck starts syncing within 15 seconds.")
                appendLine("pause")
                appendLine("exit /b 0")
                appendLine(":failed")
                appendLine("echo.")
                appendLine("echo Something went wrong. Nothing in your vault was changed.")
                appendLine("pause")
            }.replace("\n", "\r\n")
        )
        ProcessBuilder("cmd.exe", "/c", "start", "Flight Deck sync setup", "cmd.exe", "/c", script.absolutePath).start()
    }

    /** Runs [cmd] and returns its output when it exits 0 within [seconds]; a stuck command is killed. */
    private fun exec(cmd: List<String>, seconds: Long): String? {
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val out = StringBuilder()
        val reader = thread(isDaemon = true) { runCatching { out.append(p.inputStream.bufferedReader().readText()) } }
        if (!p.waitFor(seconds, TimeUnit.SECONDS)) { p.descendants().forEach { it.destroyForcibly() }; p.destroyForcibly(); return null }
        reader.join(2_000)
        return if (p.exitValue() == 0) out.toString() else null
    }
}
