package uk.tomlowndes.capture.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.tomlowndes.capture.CapSummary
import uk.tomlowndes.capture.ClaudeCap
import uk.tomlowndes.capture.MailDigest
import uk.tomlowndes.capture.SharedList

/* Stage 6 on Windows (extras-v1): the Claude spend cap in Settings, its Deck banner, and the "👥 Shared" pill. */

const val CAP_DEVICE_PC = "pc"

/** Writes this PC's spend note, reads the cap and the rest, and refreshes the gate. */
internal suspend fun capSyncDesk(state: AppState): CapSummary? {
    val v = state.vault ?: return null
    return withContext(Dispatchers.IO) {
        runCatching {
            ClaudeCap.sync(v, CAP_DEVICE_PC, ClaudeCap.ledger(state.prefs.spendByFeature), runCatching { MailDigest.load(v)?.costMonth }.getOrNull())
        }.getOrNull()
    }
}

/** One line on the Deck at 80% of the cap or more. */
@Composable
internal fun CapBannerDesk(state: AppState) {
    var s by remember { mutableStateOf<CapSummary?>(null) }
    LaunchedEffect(state.vault, state.changes) { s = capSyncDesk(state) }
    val cur = s ?: return
    val line = ClaudeCap.banner(cur) ?: return
    Text(line, fontSize = 13.sp, color = if (cur.paused) Warn else Primary,
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(12.dp)).background(PrimaryTint)
            .hand().clickable { state.tab = Tab.SETTINGS }.padding(horizontal = 14.dp, vertical = 10.dp))
}

/** "👥 Shared" when the box's shared page is on. */
@Composable
internal fun SharedPillDesk(state: AppState) {
    var on by remember { mutableStateOf(false) }
    LaunchedEffect(state.vault) {
        val v = state.vault ?: return@LaunchedEffect
        on = withContext(Dispatchers.IO) { runCatching { SharedList.on(v) }.getOrDefault(false) }
    }
    if (on) Text("👥 Shared", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Primary,
        modifier = Modifier.clip(RoundedCornerShape(99.dp)).background(PrimaryTint).padding(horizontal = 10.dp, vertical = 4.dp))
}

/** Settings › Claude: this month against the cap, by feature, the cap and sharing it with the phone. */
@Composable
internal fun CapSettingsDesk(state: AppState) {
    val scope = rememberCoroutineScope()
    var s by remember { mutableStateOf<CapSummary?>(null) }
    var tick by remember { mutableStateOf(0) }
    var dismissed by remember { mutableStateOf(false) }
    LaunchedEffect(state.vault, tick) { s = capSyncDesk(state) }
    val cur = s ?: return
    fun save(cap: Double?, shared: Boolean) {
        val v = state.vault ?: return
        scope.launch { state.io("Couldn't save the cap") { ClaudeCap.saveCap(v, cap, shared) }; tick++ }
    }
    Setting("All Claude this month", ClaudeCap.breakdown(cur)) {
        Text(ClaudeCap.label(cur), fontSize = 13.sp, color = if (cur.paused) Warn else Muted, fontFamily = FontFamily.Monospace)
    }
    val opts = listOf<Double?>(null) + ClaudeCap.CAPS
    Setting("Monthly cap", "Warn at 80% on the Deck, stop at 100%. Everything else still works.") {
        Pill(opts.map { it?.let { c -> "£%.0f".format(c) } ?: "Off" }, opts.indexOf(cur.capGbp).coerceAtLeast(0), Modifier.width(380.dp)) { save(opts[it], cur.shared) }
    }
    Setting("Phone and PC share the cap", "Spend is kept in 99 - Meta, so both count. The box's inbox cost counts too.") {
        Toggle(cur.shared) { save(cur.capGbp, it) }
    }
    if (cur.paused && !dismissed) {
        val cap = cur.capGbp ?: 0.0
        Column(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(12.dp)).background(PrimaryTint).padding(14.dp)) {
            Text(ClaudeCap.pausedText(), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Warn)
            Text("You've used ${ClaudeCap.money(cur.spentGbp)} this month.", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 2.dp))
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                val next = ClaudeCap.CAPS.firstOrNull { it > maxOf(cap, cur.spentGbp) } ?: (Math.ceil(cur.spentGbp / 10) * 10 + 10)
                Chip("Raise to £%.0f".format(next), selected = true) { save(next, cur.shared) }
                Chip("Okay") { dismissed = true }
            }
        }
    }
}
