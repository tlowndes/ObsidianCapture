package uk.tomlowndes.capture.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.tomlowndes.capture.Updater
import java.io.File
import kotlin.system.exitProcess

/**
 * The in-app updater on Windows: checks GitHub on launch, downloads the MSI, then closes the app,
 * runs the installer and opens the new version.
 */
object DesktopUpdate {
    enum class Phase { IDLE, CHECKING, AVAILABLE, DOWNLOADING, READY }

    var phase by mutableStateOf(Phase.IDLE)
    var release by mutableStateOf<Updater.Release?>(null)
    var done by mutableStateOf(0L)
    var total by mutableStateOf(0L)
    var error by mutableStateOf<String?>(null)
    /** The tag Later was pressed on (mirrors the saved setting so the banner hides at once). */
    var skip by mutableStateOf<String?>(null)
    private var file: File? = null
    private var checkedThisRun = false

    /** The version this build was made as (from gradle.properties via a generated resource). */
    val installed: String by lazy {
        DesktopUpdate::class.java.getResourceAsStream("/capture-version.txt")?.use { it.reader().readText().trim() } ?: "0"
    }

    /** Something to show: the banner and the Settings dot. */
    fun waiting(): Boolean = release != null && when (phase) {
        Phase.AVAILABLE -> skip != release?.tag
        Phase.DOWNLOADING, Phase.READY -> true
        else -> false
    }

    suspend fun check(prefs: DesktopPrefs, force: Boolean = false) {
        if (!force) {
            if (checkedThisRun || !prefs.updateChecks) return
            checkedThisRun = true
            skip = prefs.updateSkip
            val cached = prefs.updateLatest
            val recent = System.currentTimeMillis() - prefs.updateCheckedAt < Updater.EVERY_MS
            if (recent && (cached == null || !Updater.isNewer(cached, installed))) return
        }
        if (phase == Phase.DOWNLOADING || phase == Phase.CHECKING) return
        val token = prefs.githubToken ?: run { if (force) error = "Add a GitHub token first"; return }
        phase = Phase.CHECKING
        error = null
        val r = withContext(Dispatchers.IO) { runCatching { Updater.latest(token) } }
        prefs.updateCheckedAt = System.currentTimeMillis()
        r.onFailure { error = it.message; phase = Phase.IDLE }
        r.onSuccess { rel ->
            prefs.updateLatest = rel?.tag
            release = rel
            phase = if (rel != null && rel.asset(".msi") != null && Updater.isNewer(rel.tag, installed)) Phase.AVAILABLE else Phase.IDLE
        }
    }

    fun later(prefs: DesktopPrefs) {
        val tag = release?.tag ?: return
        prefs.updateSkip = tag
        skip = tag
    }

    suspend fun download(prefs: DesktopPrefs) {
        val rel = release ?: return
        val asset = rel.asset(".msi") ?: return
        val token = prefs.githubToken ?: return
        val dest = File(File(prefs.cacheDir, "updates"), asset.name)
        phase = Phase.DOWNLOADING
        error = null
        done = 0; total = asset.size
        val r = withContext(Dispatchers.IO) {
            runCatching {
                dest.parentFile?.listFiles()?.filter { it.name != asset.name }?.forEach { it.delete() }
                if (!(dest.exists() && dest.length() == asset.size)) Updater.download(asset, token, dest) { d, t -> done = d; total = t }
            }
        }
        r.onFailure { error = it.message; phase = Phase.AVAILABLE }
        r.onSuccess { file = dest; phase = Phase.READY }
    }

    /**
     * Writes a small script that waits for this app to close, runs the MSI with a progress bar,
     * then opens the new version; starts it and quits.
     */
    fun restartAndInstall() {
        val msi = file ?: return
        val exe = System.getProperty("jpackage.app-path")
        val script = File(msi.parentFile, "install-update.cmd")
        script.writeText(
            buildString {
                appendLine("@echo off")
                appendLine("echo Updating Vault Capture...")
                appendLine("ping -n 3 127.0.0.1 >nul")
                appendLine("msiexec /i \"${msi.absolutePath}\" /passive /norestart")
                if (exe != null) appendLine("start \"\" \"$exe\"")
            }.replace("\n", "\r\n")
        )
        runCatching {
            ProcessBuilder("cmd.exe", "/c", script.absolutePath)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        }.onFailure { error = "Couldn't start the installer: ${it.message}"; return }
        exitProcess(0)
    }
}

private val BannerBorder = Color(0xFF33406A)
private val UpGreen = Color(0xFF5FD39A)
private val UpAmber = Color(0xFFF5C06A)

/** The Deck banner: ready → downloading → Restart and install. */
@Composable
fun UpdateBanner(state: AppState) {
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { DesktopUpdate.check(state.prefs) }
    if (!DesktopUpdate.waiting()) return
    val rel = DesktopUpdate.release ?: return
    val phase = DesktopUpdate.phase
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().padding(bottom = 16.dp).clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0xFF1C2744), Color(0xFF172036))))
            .border(1.dp, BannerBorder, shape).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        val ready = phase == DesktopUpdate.Phase.READY
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(PrimaryTint), contentAlignment = Alignment.Center) {
            Text(if (ready) "✓" else "↓", color = if (ready) UpGreen else Primary, fontSize = 15.sp)
        }
        Column(Modifier.weight(1f)) {
            Text(
                when (phase) {
                    DesktopUpdate.Phase.DOWNLOADING -> "Downloading ${rel.version}…"
                    DesktopUpdate.Phase.READY -> "${rel.version} downloaded"
                    else -> "Version ${rel.version} is ready"
                },
                fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextMain
            )
            val sub = when (phase) {
                DesktopUpdate.Phase.DOWNLOADING -> "then the app closes and the installer runs"
                DesktopUpdate.Phase.READY -> "save anything open first"
                else -> buildList {
                    add("you have ${DesktopUpdate.installed}")
                    rel.asset(".msi")?.let { add("MSI ${Updater.size(it.size)}") }
                    if (rel.notes.isNotEmpty()) add(rel.notes.joinToString(", ").lowercase())
                }.joinToString(" · ")
            }
            Text(sub, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Muted, modifier = Modifier.padding(top = 2.dp), maxLines = 2)
            DesktopUpdate.error?.let { Text(it, fontSize = 12.sp, color = UpAmber, modifier = Modifier.padding(top = 4.dp)) }
        }
        when (phase) {
            DesktopUpdate.Phase.DOWNLOADING -> Column(Modifier.width(220.dp)) {
                val f = if (DesktopUpdate.total > 0) (DesktopUpdate.done.toFloat() / DesktopUpdate.total).coerceIn(0f, 1f) else 0f
                Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(Surface2)) {
                    Box(Modifier.fillMaxWidth(f).height(6.dp).clip(CircleShape).background(Primary))
                }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${Updater.size(DesktopUpdate.done)} of ${Updater.size(DesktopUpdate.total)}", fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, color = Muted)
                    Text("${(f * 100).toInt()}%", fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, color = Muted)
                }
            }
            DesktopUpdate.Phase.READY -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Chip("Later", small = true) { DesktopUpdate.later(state.prefs); DesktopUpdate.phase = DesktopUpdate.Phase.AVAILABLE }
                PrimaryButton("Restart and install") { DesktopUpdate.restartAndInstall() }
            }
            else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (rel.url.isNotBlank()) Text("What's new", fontSize = 13.sp, color = Muted,
                    modifier = Modifier.clip(CircleShape).hand().clickable { state.browse(rel.url) }.padding(horizontal = 8.dp, vertical = 6.dp))
                Chip("Later", small = true) { DesktopUpdate.later(state.prefs) }
                PrimaryButton("Update") { scope.launch { DesktopUpdate.download(state.prefs) } }
            }
        }
    }
}
