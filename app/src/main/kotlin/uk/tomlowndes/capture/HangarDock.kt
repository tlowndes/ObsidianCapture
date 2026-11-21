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
    else -> title
}

/**
 * Which docked tools have something waiting (hangar-dock-v1): a Did I? check not done today, a return due
 * within 2 days, a Cool-off wait that's up, or a med to reorder. Read off the main thread.
 */
internal fun dockNeeds(ctx: Context, uri: Uri, tools: List<HangarTool>): Set<HangarTool> {
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
    return out
}

/**
 * The Hangar dock (hangar-dock-v1): a slim strip above the bottom bar on Deck, Capture, Tasks and Vault.
 * The first five Hangar tools (in Hangar's order) open with one tap; a green dot marks one that needs you.
 * Dragging the strip up, or tapping ⌂ All, opens the full Hangar sheet.
 */
@Composable
fun HangarDock(tools: List<HangarTool>?, needs: Set<HangarTool>, onOpen: (HangarTool) -> Unit, onOpenAll: () -> Unit) {
    val shown = tools.orEmpty().take(DOCK_SLOTS)
    Column(
        Modifier.fillMaxWidth()
            .background(DockBg, RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
            // Its own drag target, so a swipe up here never fights with the screen scrolling behind it.
            .pointerInput(Unit) {
                var pulled = 0f
                detectVerticalDragGestures(
                    onDragStart = { pulled = 0f },
                    onDragEnd = { if (pulled < -24.dp.toPx()) onOpenAll() },
                ) { change, dy -> change.consume(); pulled += dy }
            }
            .padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 6.dp)
    ) {
        Box(Modifier.align(Alignment.CenterHorizontally).padding(bottom = 6.dp).size(40.dp, 4.dp)
            .background(Outline, RoundedCornerShape(4.dp)))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            shown.forEach { t ->
                DockItem(t.icon, t.dockLabel(), t.colour(), dot = t in needs,
                    description = t.title + if (t in needs) ", needs you" else "") { onOpen(t) }
            }
            // Spread a short dock the same as a full one, so ⌂ All stays at the right.
            repeat(DOCK_SLOTS - shown.size) { Box(Modifier.width(54.dp)) }
            DockItem("⌂", if (tools.isNullOrEmpty()) "Hangar" else "All ${tools.size}", Muted, dot = false, description = "Open Hangar", onClick = onOpenAll)
        }
    }
}

@Composable
private fun DockItem(icon: String, label: String, colour: Color, dot: Boolean, description: String, onClick: () -> Unit) {
    Column(
        Modifier.width(54.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box {
            Box(
                Modifier.size(40.dp, 34.dp).clip(RoundedCornerShape(11.dp)).background(Surface1)
                    .border(1.dp, Outline, RoundedCornerShape(11.dp)),
                contentAlignment = Alignment.Center
            ) { Text(icon, fontSize = 18.sp, color = colour, modifier = Modifier.clearAndSetSemantics { }) }
            if (dot) Box(Modifier.align(Alignment.TopEnd).offset(x = 3.dp, y = (-3).dp).size(11.dp).clip(CircleShape)
                .background(DockBg).padding(2.dp).clip(CircleShape).background(Green))
        }
        Text(label, fontSize = 10.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp).clearAndSetSemantics { })
    }
}
