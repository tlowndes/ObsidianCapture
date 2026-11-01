package uk.tomlowndes.capture

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The in-app updater's state, shared by the Deck banner and Settings › Updates.
 * Checks GitHub on launch, downloads the APK into the cache and hands it to Android's installer.
 */
object AppUpdate {
    enum class Phase { IDLE, CHECKING, AVAILABLE, DOWNLOADING, READY }

    var phase by mutableStateOf(Phase.IDLE)
    var release by mutableStateOf<Updater.Release?>(null)
    var done by mutableStateOf(0L)
    var total by mutableStateOf(0L)
    var error by mutableStateOf<String?>(null)
    private var file: File? = null
    private var checkedThisRun = false

    fun installed(ctx: Context): String =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "0"

    /** Asks GitHub for the latest release. On launch it's skipped if checks are off or it asked in the last 6 hours. */
    suspend fun check(ctx: Context, force: Boolean = false) {
        val prefs = AppPrefs(ctx)
        if (!force) {
            if (checkedThisRun || !prefs.updateChecks) return
            checkedThisRun = true
            val cached = prefs.updateLatest
            val recent = System.currentTimeMillis() - prefs.updateCheckedAt < Updater.EVERY_MS
            if (recent && (cached == null || !Updater.isNewer(cached, installed(ctx)))) return
        }
        if (phase == Phase.DOWNLOADING || phase == Phase.CHECKING) return
        val token = SecretStore(ctx).githubToken ?: run { if (force) error = "Add a GitHub token first"; return }
        phase = Phase.CHECKING
        error = null
        val r = withContext(Dispatchers.IO) { runCatching { Updater.latest(token) } }
        prefs.updateCheckedAt = System.currentTimeMillis()
        r.onFailure { error = it.message; phase = Phase.IDLE }
        r.onSuccess { rel ->
            prefs.updateLatest = rel?.tag
            release = rel
            phase = if (rel != null && rel.asset(".apk") != null && Updater.isNewer(rel.tag, installed(ctx))) Phase.AVAILABLE else Phase.IDLE
        }
    }

    suspend fun download(ctx: Context) {
        val rel = release ?: return
        val asset = rel.asset(".apk") ?: return
        val token = SecretStore(ctx).githubToken ?: return
        val dest = File(File(ctx.cacheDir, "updates"), asset.name)
        phase = Phase.DOWNLOADING
        error = null
        done = 0; total = asset.size
        val r = withContext(Dispatchers.IO) {
            runCatching {
                dest.parentFile?.listFiles()?.filter { it.name != asset.name }?.forEach { it.delete() }
                if (!(dest.exists() && dest.length() == asset.size)) {
                    Updater.download(asset, token, dest) { d, t ->
                        // Progress comes from the IO thread; Compose state is fine to write from there.
                        done = d; total = t
                    }
                }
            }
        }
        r.onFailure { error = it.message; phase = Phase.AVAILABLE }
        r.onSuccess { file = dest; phase = Phase.READY; install(ctx) }
    }

    /** Opens Android's install sheet. The first time, Android wants "allow from this source" switched on first. */
    fun install(ctx: Context) {
        val f = file ?: return
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            error = "Allow installs from Flight Deck, then tap Install"
            runCatching {
                ctx.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            return
        }
        error = null
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        runCatching {
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { error = "Couldn't open the installer: ${it.message}" }
    }
}

private val BannerBorder = Color(0xFF33406A)

/** "Version 1.8 is ready" on Flight Deck: Update, Later, What's new; then a progress bar while it downloads. */
@Composable
fun UpdateBanner() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { AppPrefs(ctx) }
    var skip by remember { mutableStateOf(prefs.updateSkip) }
    LaunchedEffect(Unit) { AppUpdate.check(ctx) }

    val rel = AppUpdate.release ?: return
    val phase = AppUpdate.phase
    if (phase !in setOf(AppUpdate.Phase.AVAILABLE, AppUpdate.Phase.DOWNLOADING, AppUpdate.Phase.READY)) return
    if (phase == AppUpdate.Phase.AVAILABLE && skip == rel.tag) return
    fun later() { prefs.updateSkip = rel.tag; skip = rel.tag }

    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0xFF1C2744), Color(0xFF172036))))
            .border(1.dp, BannerBorder, shape).padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(30.dp).clip(RoundedCornerShape(10.dp)).background(PrimaryTint), contentAlignment = Alignment.Center) {
                Text(if (phase == AppUpdate.Phase.READY) "✓" else "↓", color = if (phase == AppUpdate.Phase.READY) Green else Primary, fontSize = 15.sp)
            }
            Column(Modifier.weight(1f)) {
                val title = when (phase) {
                    AppUpdate.Phase.DOWNLOADING -> "Downloading ${rel.version}…"
                    AppUpdate.Phase.READY -> "${rel.version} downloaded"
                    else -> "Version ${rel.version} is ready"
                }
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextMain)
                val sub = when (phase) {
                    AppUpdate.Phase.DOWNLOADING -> "keep the app open"
                    AppUpdate.Phase.READY -> "tap Install to finish"
                    else -> "you have ${AppUpdate.installed(ctx)}" + (rel.asset(".apk")?.let { " · ${Updater.size(it.size)}" } ?: "")
                }
                Text(sub, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, color = Muted, modifier = Modifier.padding(top = 2.dp))
            }
            if (phase == AppUpdate.Phase.AVAILABLE) Text("✕", color = Muted, fontSize = 14.sp, modifier = Modifier.clip(CircleShape).clickable { later() }.padding(4.dp))
        }
        Column(Modifier.padding(start = 40.dp)) {
            when (phase) {
                AppUpdate.Phase.AVAILABLE -> {
                    rel.notes.forEach { Text("•  $it", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 4.dp)) }
                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Pill("Update", filled = true) { scope.launch { AppUpdate.download(ctx) } }
                        Pill("Later") { later() }
                        if (rel.url.isNotBlank()) Text("What's new", fontSize = 12.5.sp, color = Muted,
                            modifier = Modifier.clip(CircleShape).clickable {
                                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(rel.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            }.padding(horizontal = 6.dp, vertical = 7.dp))
                    }
                }
                AppUpdate.Phase.DOWNLOADING -> {
                    val f = if (AppUpdate.total > 0) (AppUpdate.done.toFloat() / AppUpdate.total).coerceIn(0f, 1f) else 0f
                    Box(Modifier.fillMaxWidth().padding(top = 10.dp).height(6.dp).clip(CircleShape).background(Surface2)) {
                        Box(Modifier.fillMaxWidth(f).height(6.dp).clip(CircleShape).background(Primary))
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${Updater.size(AppUpdate.done)} of ${Updater.size(AppUpdate.total)}", fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, color = Muted)
                        Text("${(f * 100).toInt()}%", fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, color = Muted)
                    }
                }
                else -> Row(Modifier.padding(top = 10.dp)) { Pill("Install", filled = true) { AppUpdate.install(ctx) } }
            }
            AppUpdate.error?.let { Text(it, fontSize = 12.sp, color = Amber, modifier = Modifier.padding(top = 8.dp)) }
        }
    }
}

@Composable
private fun Pill(text: String, filled: Boolean = false, onClick: () -> Unit) {
    Text(
        text, fontSize = 12.5.sp, fontWeight = if (filled) FontWeight.Bold else FontWeight.Normal,
        color = if (filled) OnPrimary else Muted,
        modifier = Modifier.clip(CircleShape).background(if (filled) Primary else Color.Transparent)
            .then(if (filled) Modifier else Modifier.border(1.dp, Outline, CircleShape))
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 7.dp)
    )
}
