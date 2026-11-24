package uk.tomlowndes.capture.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import uk.tomlowndes.capture.Chores
import uk.tomlowndes.capture.CoolOff
import uk.tomlowndes.capture.DeckConfig
import uk.tomlowndes.capture.DidI
import uk.tomlowndes.capture.DidStatus
import uk.tomlowndes.capture.Hangar
import uk.tomlowndes.capture.HangarTool
import uk.tomlowndes.capture.Refill
import uk.tomlowndes.capture.Returns
import uk.tomlowndes.capture.TicketRail
import uk.tomlowndes.capture.Vault
import java.time.LocalDate
import java.time.LocalDateTime

/** How many tools the sidebar shows under ⌂ Hangar (hangar-windows-v1), like the phone's dock. */
private const val SIDE_SLOTS = 5

/** Each tool keeps the phone's colour. */
internal fun HangarTool.deskColour(): Color = when (this) {
    HangarTool.DID_I -> TaskGreen
    HangarTool.LAUNCH_PAD -> Amber
    HangarTool.HEADS_UP, HangarTool.INBOX -> Primary
    HangarTool.NIGHT_PAD -> Color(0xFFB9A2FF)
    HangarTool.POCKET_PASS -> Color(0xFF5FC8D3)
    HangarTool.LAST_SEEN, HangarTool.HOME -> Color(0xFFFF9E7A)
    HangarTool.RETURN_RADAR -> Color(0xFFFF8AC0)
    HangarTool.COOL_OFF -> Color(0xFF7AD3FF)
    HangarTool.REFILL, HangarTool.PROJECTS -> Color(0xFFC6E06A)
    HangarTool.WAITING, HangarTool.MEALS -> Color(0xFFE0C49A)
    HangarTool.ERRAND_RUN, HangarTool.ERRANDS -> Color(0xFFFFB36B)
    HangarTool.TICKET_RAIL -> Color(0xFFE9D9B4)
    HangarTool.SENSORY -> Color(0xFFE8963A)
    HangarTool.BRIEF -> Amber
}

/** What the Hangar page and sidebar show on Windows: the shared order, what needs you, and each tile's line. */
/** [tools] is what Windows shows; [all] is the shared order, phone-only tools included, so a reorder here keeps them. */
class DeskHangar(
    val tools: List<HangarTool>, val needs: Set<HangarTool>, val lines: Map<HangarTool, String>, val did: List<DidStatus>,
    val rail: Int? = null, val all: List<HangarTool> = tools,
)

/** Tools that can't be used on a PC: Heads Up buzzes you on the phone. Hidden on Windows, kept in the shared order. */
internal val PHONE_ONLY = setOf(HangarTool.HEADS_UP)

/** Puts Windows' new order of [shown] back into [all], leaving each phone-only tool in its place. */
internal fun withPhoneOnly(all: List<HangarTool>, shown: List<HangarTool>): List<HangarTool> {
    val q = ArrayDeque(shown)
    return all.map { t -> if (t in PHONE_ONLY) t else q.removeFirstOrNull() ?: t }.distinct() + q
}

/**
 * Until the phone has shared its Hangar list, Windows shows every ready tool, so Hangar is never missing from the
 * sidebar. The phone's list replaces this as soon as it lands in the vault.
 */
internal val DESK_FALLBACK: List<HangarTool> = HangarTool.values().filter { it.ready && it != HangarTool.ERRAND_RUN && it !in PHONE_ONLY }

/** Reads the Hangar list the phone shares through the vault, and each tool's status. Off the UI thread. */
internal fun loadDeskHangar(state: AppState, v: Vault): DeskHangar? {
    val all = DeckConfig.loadHangar(v)?.takeIf { it.isNotEmpty() } ?: DESK_FALLBACK
    val tools = all - PHONE_ONLY
    val today = LocalDate.now()
    val now = LocalDateTime.now()
    val needs = mutableSetOf<HangarTool>()
    val lines = mutableMapOf<HangarTool, String>()
    var did = emptyList<DidStatus>()
    var rail: Int? = null
    fun look(t: HangarTool, work: () -> Unit) { if (t in tools) runCatching(work) }
    look(HangarTool.DID_I) {
        did = DidI.today(v)
        lines[HangarTool.DID_I] = DidI.progress(did)
        if (DidI.nextOpen(did) != null) needs += HangarTool.DID_I
    }
    look(HangarTool.BRIEF) {
        val d = uk.tomlowndes.capture.DailyBrief.load(v, today)
        lines[HangarTool.BRIEF] = when { d == null -> "Not written yet"; d.heard -> "Heard ✓"; d.opened -> "Read"; else -> "Ready" }
        if (d != null && !d.heard && !d.opened) needs += HangarTool.BRIEF
    }
    look(HangarTool.INBOX) {
        val n = state.inboxOpen
        lines[HangarTool.INBOX] = if (n > 0) "$n need you" else "All caught up"
        if (n > 0) needs += HangarTool.INBOX
    }
    look(HangarTool.HOME) {
        val n = Chores.due(Chores.load(v, today), today).size
        lines[HangarTool.HOME] = if (n > 0) "$n need doing" else "All fresh"
        if (n > 0) needs += HangarTool.HOME
    }
    look(HangarTool.PROJECTS) {
        lines[HangarTool.PROJECTS] = state.ideaStore()?.list()?.firstOrNull { it.status.lowercase() == "active" }?.title ?: "No active project"
    }
    look(HangarTool.RETURN_RADAR) {
        val open = Returns.load(v).filter(Returns::open)
        lines[HangarTool.RETURN_RADAR] = if (open.isEmpty()) "Nothing to send" else "${open.size} open"
        if (open.any { Returns.daysLeft(it, today) <= 2 }) needs += HangarTool.RETURN_RADAR
    }
    look(HangarTool.COOL_OFF) {
        val cs = CoolOff.load(v)
        val n = CoolOff.cooling(cs).size
        lines[HangarTool.COOL_OFF] = if (n > 0) "$n waiting" else "Nothing cooling"
        if (CoolOff.due(cs, now).isNotEmpty()) needs += HangarTool.COOL_OFF
    }
    look(HangarTool.REFILL) {
        val meds = Refill.load(v)
        val stamps = Refill.stamps(v, meds, today)
        lines[HangarTool.REFILL] = if (meds.isEmpty()) "Add a med" else "${meds.size} med${if (meds.size == 1) "" else "s"}"
        if (meds.any { Refill.needsOrder(it, Refill.left(it, stamps), today) }) needs += HangarTool.REFILL
    }
    look(HangarTool.TICKET_RAIL) {
        rail = TicketRail.onRail(state.writer()!!.listTaskLists().open).size
        lines[HangarTool.TICKET_RAIL] = "$rail on the rail"
    }
    return DeskHangar(tools, needs, lines, did, rail, all)
}

/** Opens a tool: the ones with a Windows page go there; the rest are on the phone for now. */
internal fun openDeskTool(state: AppState, t: HangarTool) {
    when (t) {
        HangarTool.INBOX -> state.tab = Tab.INBOX
        HangarTool.HOME, HangarTool.ERRANDS, HangarTool.MEALS -> state.tab = Tab.HOME
        HangarTool.PROJECTS -> state.openProjects(null)
        in DESK_TOOLS -> { state.hangarTool = t; state.tab = Tab.HANGAR }
        else -> state.say("${t.title} works on your phone only.")
    }
}

/** The sidebar's ⌂ Hangar group (hangar-windows-v1): your first five tools, with a dot when one needs you. */
@Composable
internal fun SidebarHangar(state: AppState) {
    val h = state.hangar ?: return
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 6.dp, top = 14.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("⌂ HANGAR", fontSize = 10.5.sp, letterSpacing = 1.1.sp, color = if (state.tab == Tab.HANGAR) Primary else Muted,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text("All ${h.tools.size}", fontSize = 12.sp, color = Primary, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).hand().clickable { state.hangarTool = null; state.tab = Tab.HANGAR }.padding(horizontal = 6.dp, vertical = 2.dp))
    }
    h.tools.take(SIDE_SLOTS).forEach { t ->
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).hand().clickable { openDeskTool(state, t) }
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(t.icon, fontSize = 14.sp, color = t.deskColour(), modifier = Modifier.width(20.dp), textAlign = TextAlign.Center)
            Text(t.title, fontSize = 13.5.sp, color = TextMain, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (t in h.needs) Box(Modifier.size(7.dp).clip(CircleShape).background(TaskGreen))
        }
    }
}

/** The Hangar page on Windows (hangar-windows-v1): Right now, then every tool in your order, five across. */
@Composable
fun HangarPane(state: AppState) {
    state.hangarTool?.let { t -> HangarToolPane(state, t); return }
    val h = state.hangar
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 22.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                H1("⌂ Hangar", 26)
                Sub(when {
                    h == null -> "Open Hangar on your phone once to bring your tools across."
                    h.needs.isEmpty() -> "All clear"
                    h.needs.size == 1 -> "1 thing needs you"
                    else -> "${h.needs.size} things need you"
                })
            }
        }
        if (h == null) return@Column
        DidI.nextOpen(h.did)?.let { s -> RightNowDesk(state, s) }
        Box(Modifier.padding(top = 14.dp).widthIn(max = 980.dp)) { HangarGridDesk(state, h) }
        Sub("Drag a tile by its ⠿ to rearrange. The order is shared with your phone. Add or hide tools from Hangar on the phone.",
            Modifier.padding(top = 14.dp))
    }
}

@Composable
private fun RightNowDesk(state: AppState, s: DidStatus) {
    Row(
        Modifier.padding(top = 14.dp).widthIn(max = 980.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(TaskTint)
            .border(1.dp, Color(0xFF23573B), RoundedCornerShape(14.dp)).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("RIGHT NOW", fontSize = 10.5.sp, letterSpacing = 1.1.sp, color = Muted, fontWeight = FontWeight.SemiBold)
        Text("${s.check.emoji} ${s.check.question}", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TaskGreen, modifier = Modifier.weight(1f))
        Text("Yes, done", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = OnPrimary,
            modifier = Modifier.clip(CircleShape).background(TaskGreen).hand().clickable {
                val v = state.vault ?: return@clickable
                state.scope.launch {
                    val stamp = state.io("Couldn't save") { DidI.stamp(v, s.check.name) } ?: return@launch
                    state.changed()
                    state.offerUndo("${s.check.name} logged") {
                        state.io("Couldn't undo") { DidI.undo(v, LocalDate.now(), stamp) }
                        state.changed()
                    }
                }
            }.padding(horizontal = 16.dp, vertical = 8.dp))
    }
}

/** The tiles, five across. Drag one by its ⠿ grip onto another to put it there; Esc-free, let go off a tile to cancel. */
@Composable
private fun HangarGridDesk(state: AppState, h: DeskHangar) {
    val list = h.tools
    val bounds = remember { mutableStateMapOf<Int, Rect>() }
    var dragging by remember { mutableStateOf<Int?>(null) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var target by remember { mutableStateOf<Int?>(null) }
    fun reset() { dragging = null; target = null; offset = Offset.Zero }

    fun move(from: Int, to: Int) {
        val v = state.vault ?: return
        val before = h.all
        val after = Hangar.moved(list, from, to)
        val saved = withPhoneOnly(h.all, after)
        state.hangar = DeskHangar(after, h.needs, h.lines, h.did, h.rail, saved)
        state.scope.launch {
            state.io("Couldn't save the Hangar order") { DeckConfig.saveHangar(v, saved) }
            state.offerUndo("Order saved · phone too") {
                state.io("Couldn't undo") { DeckConfig.saveHangar(v, before) }
                state.changed()
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        list.indices.chunked(5).forEach { row ->
            Row(Modifier.zIndex(if (dragging?.let { it in row } == true) 1f else 0f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { i ->
                    val t = list[i]
                    val lifted = dragging == i
                    val hover = remember { MutableInteractionSource() }
                    val hovered by hover.collectIsHoveredAsState()
                    val edge = when { lifted -> Primary; dragging != null && target == i -> Primary; hovered && dragging == null -> Primary; else -> Outline }
                    Box(
                        Modifier.weight(1f)
                            .onGloballyPositioned { bounds[i] = it.boundsInRoot() }
                            .zIndex(if (lifted) 1f else 0f)
                            .graphicsLayer {
                                if (lifted) { translationX = offset.x; translationY = offset.y; scaleX = 1.05f; scaleY = 1.05f; rotationZ = -2f }
                                else if (dragging != null && target != i) alpha = 0.45f
                            }
                            .hoverable(hover)
                    ) {
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (lifted) PrimaryTint else Surface1)
                                .border(if (edge == Outline) 1.dp else 2.dp, edge, RoundedCornerShape(14.dp))
                                .hand().clickable { openDeskTool(state, t) }.padding(horizontal = 8.dp, vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(t.icon, fontSize = 20.sp, color = t.deskColour())
                            Text(t.title, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = t.deskColour(), maxLines = 1,
                                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                            Text(h.lines[t] ?: "On your phone", fontSize = 11.5.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (t in h.needs) Box(Modifier.align(Alignment.TopEnd).padding(9.dp).size(8.dp).clip(CircleShape).background(TaskGreen))
                        // The grip only shows on hover; only it drags, so a click still opens the tool.
                        if (hovered || lifted) Text("⠿", fontSize = 14.sp, color = Muted,
                            modifier = Modifier.align(Alignment.TopStart).padding(4.dp).clip(RoundedCornerShape(6.dp))
                                .pointerInput(t, list.size) {
                                    detectDragGestures(
                                        onDragStart = { dragging = i; target = i; offset = Offset.Zero },
                                        onDrag = drag@{ change, d ->
                                            change.consume()
                                            offset += d
                                            val at = (bounds[i] ?: return@drag).center + offset
                                            target = bounds.entries.firstOrNull { (k, r) -> k < list.size && r.contains(at) }?.key
                                        },
                                        onDragEnd = { val from = dragging; val to = target; reset(); if (from != null && to != null && from != to) move(from, to) },
                                        onDragCancel = { reset() },
                                    )
                                }.padding(horizontal = 4.dp, vertical = 2.dp))
                    }
                }
                repeat(5 - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}
