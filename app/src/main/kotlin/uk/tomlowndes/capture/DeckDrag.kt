package uk.tomlowndes.capture

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Long-press drag for the phone Deck's sections (deck-drag-v1). Positions are in window (root) coordinates.
 * Where a section lands comes from where the sections were when the drag began, plus how far the Deck has
 * scrolled since, so the opening slot doesn't make it jump about.
 */
class SectionDrag {
    var section by mutableStateOf<DeckSection?>(null)
        private set
    var pointer by mutableStateOf(Offset.Zero)
        private set
    /** Index among the other shown sections where the dragged one would land. */
    var target by mutableStateOf<Int?>(null)
        private set
    var size: Rect = Rect.Zero
        private set
    var grab = Offset.Zero
        private set

    val bounds = mutableMapOf<DeckSection, Rect>()
    private var snap: Map<DeckSection, Rect> = emptyMap()
    private var scrolled = 0f
    /** Sections in the order shown; set on each draw. */
    var shown: List<DeckSection> = emptyList()

    /** Shown sections with something in them, apart from the one being dragged. */
    fun others(): List<DeckSection> = shown.filter { it != section && (bounds[it]?.height ?: 0f) > 1f }

    fun start(s: DeckSection, at: Offset) {
        val b = bounds[s] ?: return
        snap = bounds.toMap()
        scrolled = 0f
        size = b
        grab = at - b.topLeft
        pointer = at
        section = s
        retarget()
    }

    fun move(by: Offset) {
        if (section == null) return
        pointer += by
        retarget()
    }

    fun scrolledBy(px: Float) {
        scrolled += px
        retarget()
    }

    fun stop() { section = null; target = null }

    private fun retarget() {
        val y = pointer.y + scrolled
        target = others().count { s -> snap[s]?.let { it.center.y < y } ?: false }
    }
}

/**
 * One Deck section: long-press lifts it. While another is dragged it dims; this one shows as a ghost.
 * A column, so the heading, agenda and forecast each take their own height. A box would paint them
 * all in the same place (the agenda covering the weather tiles underneath).
 */
@Composable
fun DraggableSection(section: DeckSection, drag: SectionDrag, onDrop: () -> Unit, content: @Composable () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val coords = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val active = drag.section
    Column(
        Modifier.fillMaxWidth()
            .onGloballyPositioned { coords[0] = it; drag.bounds[section] = it.boundsInRoot() }
            .alpha(if (active == null) 1f else if (active == section) 0.35f else 0.55f)
            .pointerInput(section) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { o ->
                        coords[0]?.let {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            drag.start(section, it.localToRoot(o))
                        }
                    },
                    onDrag = { change, by -> change.consume(); drag.move(by) },
                    onDragEnd = onDrop,
                    onDragCancel = { drag.stop() },
                )
            }
    ) { content() }
}

/** The dashed space where the dragged section will land. */
@Composable
fun SectionSlot(drag: SectionDrag) {
    val h = with(LocalDensity.current) { drag.size.height.toDp() }
    Box(
        Modifier.padding(top = 12.dp).fillMaxWidth().height(h).clip(RoundedCornerShape(16.dp)).background(Primary.copy(alpha = 0.07f))
            .drawBehind {
                drawRoundRect(
                    Primary, cornerRadius = CornerRadius(16.dp.toPx()),
                    style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
                )
            }
    )
}

/** The section under the finger, drawn over the Deck. [origin] is the Deck's root position. */
@Composable
fun LiftedSection(drag: SectionDrag, origin: Offset, content: @Composable () -> Unit) {
    if (drag.section == null) return
    val w = with(LocalDensity.current) { drag.size.width.toDp() }
    val at = drag.pointer - drag.grab - origin
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.offset { IntOffset(at.x.roundToInt(), at.y.roundToInt()) }.width(w).rotate(-1f)
            .shadow(20.dp, shape).clip(shape).background(Bg).border(2.dp, Primary, shape)
    ) { content() }
}

/** Scrolls the Deck while a dragged section is held near the top or bottom edge. */
@Composable
fun DragAutoScroll(drag: SectionDrag, scroll: ScrollState, viewport: () -> Rect) {
    val edge = with(LocalDensity.current) { 72.dp.toPx() }
    val active = drag.section
    LaunchedEffect(active) {
        if (active == null) return@LaunchedEffect
        while (drag.section != null) {
            val v = viewport()
            val y = drag.pointer.y
            val step = when {
                y < v.top + edge -> -(v.top + edge - y) / 6f
                y > v.bottom - edge -> (y - (v.bottom - edge)) / 6f
                else -> 0f
            }
            if (step != 0f) {
                val used = scroll.scrollBy(step)
                if (used != 0f) drag.scrolledBy(used)
            }
            delay(16)
        }
    }
}
