package uk.tomlowndes.capture

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Server fallback (mockup server-fallback-v1): when this phone's Obsidian Sync is behind the Jarvis server's copy,
 * every screen reads and saves through the server until the phone catches up. [appVault] is what screens open.
 */
object VaultRoute {
    data class State(
        val useServer: Boolean = false, val behindMin: Long = 0,
        val serverAt: Long = 0, val phoneAt: Long? = null, val checkedAt: Long = 0, val error: String? = null,
    )

    var state by mutableStateOf(State())
        private set

    private const val EVERY_MS = 60_000L

    /** Checks the server at most once a minute (or now, with [force]). Blocking: call off the main thread. */
    fun check(ctx: Context, tree: Uri, force: Boolean = false): State {
        val prefs = AppPrefs(ctx)
        val now = System.currentTimeMillis()
        if (!force && now - state.checkedAt < EVERY_MS) return state
        val address = prefs.jarvisAddress
        val token = SecretStore(ctx).jarvisToken.orEmpty()
        if (!prefs.serverFallback || address.isBlank() || Jarvis.isDemo(address) || token.isBlank()) {
            return State(checkedAt = now).also { state = it }
        }
        val next = runCatching {
            val status = RemoteVault(address, token).status()
            val local = SafVault(ctx, tree)
            val text = runCatching { local.read(status.path) }.getOrNull()
            val modified = runCatching { local.entry(status.path)?.modified }.getOrNull()
            val v = ServerFallback.judge(status, text, modified, now, prefs.fallbackStaleMin)
            State(v.useServer, v.behindMin, v.serverAt, v.phoneAt, now)
        }.getOrElse { State(checkedAt = now, error = it.message ?: it.javaClass.simpleName) }
        state = next
        return next
    }

    fun vault(ctx: Context, tree: Uri): Vault {
        val local = SafVault(ctx, tree)
        if (!state.useServer) return local
        val token = SecretStore(ctx).jarvisToken ?: return local
        return RemoteVault(AppPrefs(ctx).jarvisAddress, token, local)
    }
}

/** The vault every screen uses: this phone's folder, or the server's copy while the phone is behind. */
fun appVault(ctx: Context, tree: Uri): Vault = VaultRoute.vault(ctx, tree)

/** Tasks: the amber "Using the server copy" banner. Tap for Settings. */
@Composable
fun ServerCopyBanner(onClick: () -> Unit) {
    val s = VaultRoute.state
    if (!s.useServer) return
    Row(
        Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(14.dp)).background(AmberTint)
            .border(1.dp, Amber.copy(alpha = 0.35f), RoundedCornerShape(14.dp)).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(Modifier.padding(top = 6.dp).size(8.dp).clip(CircleShape).background(Amber))
        Text(buildAnnotatedString {
            withStyle(SpanStyle(color = Amber, fontWeight = FontWeight.SemiBold)) { append("Using the server copy. ") }
            append("This phone last synced ${s.behindMin} min ago. Ticks and new tasks save to the server and reach Obsidian from there.")
        }, fontSize = 13.sp, lineHeight = 19.sp)
    }
}

/** Deck: the small "server copy · phone N min behind" chip. */
@Composable
fun ServerCopyChip(onClick: () -> Unit) {
    val s = VaultRoute.state
    if (!s.useServer) return
    Row(
        Modifier.padding(bottom = 10.dp).clip(RoundedCornerShape(50)).border(1.dp, Outline, RoundedCornerShape(50))
            .clickable(onClick = onClick).padding(horizontal = 11.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(Amber))
        Text("server copy · phone ${s.behindMin} min behind", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Muted)
    }
}

