package uk.tomlowndes.capture

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.LocalDateTime

private val DockBg = Color(0xFF151B29)

/** How many tools fit in the dock beside the ⌂ All button. */
internal const val DOCK_SLOTS = 5

/** Short dock labels, so five fit across a phone. */
internal fun HangarTool.dockLabel(): String = when (this) {
    HangarTool.LAUNCH_PAD -> "Launch"
    HangarTool.NIGHT_PAD -> "Night"
    HangarTool.POCKET_PASS -> "Pass"
    HangarTool.LAST_SEEN -> "Seen"
    HangarTool.RETURN_RADAR -> "Returns"
    HangarTool.WAITING -> "Waiting"
    HangarTool.ERRAND_RUN -> "Errands"
    HangarTool.TICKET_RAIL -> "Rail"
    HangarTool.SENSORY -> "Sensory"
    HangarTool.BRIEF -> "Brief"
    HangarTool.PLAN -> "Plan"
    else -> title
}

/**
 * Which Hangar tools have something waiting (hangar-dock-v1, v2): a Did I? check not done today, a return due
 * within 2 days, a Cool-off wait that's up, a med to reorder, email that needs you, or chores due. Off the main thread.
 */
internal fun dockNeeds(ctx: Context, uri: Uri, prefs: AppPrefs, tools: List<HangarTool>): Set<HangarTool> {
    val v = appVault(ctx, uri)
    val today = LocalDate.now()
    val out = mutableSetOf<HangarTool>()
    fun check(t: HangarTool, test: () -> Boolean) { if (t in tools && runCatching(test).getOrDefault(false)) out += t }
    check(HangarTool.DID_I) { DidI.nextOpen(DidI.today(v)) != null }
    check(HangarTool.RETURN_RADAR) { Returns.load(v).any { Returns.open(it) && Returns.daysLeft(it, today) <= 2 } }
    check(HangarTool.COOL_OFF) { CoolOff.due(CoolOff.load(v), LocalDateTime.now()).isNotEmpty() }
    check(HangarTool.REFILL) {
        val meds = Refill.load(v)
        val stamps = Refill.stamps(v, meds, today)
        meds.any { Refill.needsOrder(it, Refill.left(it, stamps), today) }
    }
    check(HangarTool.BILLS) { BillPage.needsYou(VaultWriter(ctx, uri).listTasks(), today) }
    if (HangarTool.INBOX in tools || HangarTool.HOME in tools) loadAround(ctx, uri, prefs, tools.filter { it == HangarTool.INBOX || it == HangarTool.HOME }).let { a ->
        if (a.mail > 0) out += HangarTool.INBOX
        if (a.chores > 0) out += HangarTool.HOME
    }
    return out
}
