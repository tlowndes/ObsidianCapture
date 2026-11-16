package uk.tomlowndes.capture

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Stage 6 on the phone (extras-v1): the "👥 Shared" pill, the Claude spend cap in Settings and its Deck banner.
 * Widgets are in Widgets.kt.
 */

const val CAP_DEVICE = "phone"

/** Writes this phone's spend note, reads the cap and the rest, and refreshes the gate. Call off the main thread. */
internal fun capSync(ctx: Context, prefs: AppPrefs, uri: Uri): CapSummary? = runCatching {
    val v = appVault(ctx, uri)
    ClaudeCap.sync(v, CAP_DEVICE, ClaudeCap.ledger(prefs.spendByFeature), runCatching { MailDigest.load(v)?.costMonth }.getOrNull())
}.getOrNull()

/** "👥 Shared" when the box's shared page is on. */
@Composable
internal fun SharedPill(vault: Uri?) {
    val ctx = LocalContext.current
    var on by remember { mutableStateOf(false) }
    LaunchedEffect(vault) {
        val uri = vault ?: return@LaunchedEffect
        on = withContext(Dispatchers.IO) { runCatching { SharedList.on(appVault(ctx, uri)) }.getOrDefault(false) }
    }
    if (on) Text("👥 Shared", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Primary,
        modifier = Modifier.padding(end = 6.dp).clip(RoundedCornerShape(99.dp)).background(PrimaryTint).padding(horizontal = 10.dp, vertical = 4.dp))
}

/** One line on the Deck at 80% of the cap or more. */
@Composable
internal fun CapBanner(prefs: AppPrefs, vault: Uri?, refresh: Int = 0) {
    val ctx = LocalContext.current
    var s by remember { mutableStateOf<CapSummary?>(null) }
    LaunchedEffect(vault, refresh) {
        val uri = vault ?: return@LaunchedEffect
        s = withContext(Dispatchers.IO) { capSync(ctx, prefs, uri) }
    }
    val line = s?.let { ClaudeCap.banner(it) } ?: return
    val paused = s?.paused == true
    Text(line, fontSize = 13.sp, color = if (paused) Warn else Primary,
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(12.dp)).background(PrimaryTint).padding(horizontal = 14.dp, vertical = 10.dp))
}

/** Settings › Claude: this month against the cap, by feature, the cap itself and "phone and PC share it". */
@Composable
internal fun CapSettings(prefs: AppPrefs, vault: Uri?) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var s by remember { mutableStateOf<CapSummary?>(null) }
    var tick by remember { mutableStateOf(0) }
    var dismissed by remember { mutableStateOf(false) }
    LaunchedEffect(vault, tick) {
        val uri = vault ?: return@LaunchedEffect
        s = withContext(Dispatchers.IO) { capSync(ctx, prefs, uri) }
    }
    val cur = s ?: return
    fun save(cap: Double?, shared: Boolean) {
        val uri = vault ?: return
        scope.launch { withContext(Dispatchers.IO) { runCatching { ClaudeCap.saveCap(appVault(ctx, uri), cap, shared) } }; tick++ }
    }
    SettingRow("This month", ClaudeCap.breakdown(cur)) {
        Text(ClaudeCap.label(cur), fontSize = 13.sp, color = if (cur.paused) Warn else Muted, fontFamily = FontFamily.Monospace)
    }
    Text("Monthly cap", fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp, bottom = 8.dp))
    val opts = listOf<Double?>(null) + ClaudeCap.CAPS
    PillSwitch(opts.map { it?.let { c -> "£%.0f".format(c) } ?: "Off" }, opts.indexOf(cur.capGbp).coerceAtLeast(0)) { save(opts[it], cur.shared) }
    Text("Warn at 80% on the Deck, stop at 100%. Everything else still works.", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 6.dp))
    SettingRow("Phone and PC share the cap", "Spend is kept in 99 - Meta, so both count. The box's inbox cost counts too.") {
        AppSwitch(cur.shared) { save(cur.capGbp, it) }
    }
    if (cur.paused && !dismissed) {
        val cap = cur.capGbp ?: 0.0
        Column(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(12.dp)).background(PrimaryTint).padding(14.dp)) {
            Text(ClaudeCap.pausedText(), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Warn)
            Text("You've used ${ClaudeCap.money(cur.spentGbp)} this month.", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 2.dp))
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                val next = ClaudeCap.CAPS.firstOrNull { it > maxOf(cap, cur.spentGbp) } ?: (Math.ceil(cur.spentGbp / 10) * 10 + 10)
                Box(Modifier.clickable { save(next, cur.shared) }) { Text("Raise to £%.0f".format(next), color = Primary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
                Box(Modifier.clickable { dismissed = true }) { Text("Okay", color = Muted, fontSize = 14.sp) }
            }
        }
    }
}
