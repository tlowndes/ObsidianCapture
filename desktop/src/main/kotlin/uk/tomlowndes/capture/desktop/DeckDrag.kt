package uk.tomlowndes.capture.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uk.tomlowndes.capture.HomeCard
import java.awt.Cursor
import kotlin.math.roundToInt

/** Where a dragged card would land: which column, and its place among that column's other cards. */
data class DeckSpot(val left: Boolean, val index: Int)

/**
 * Drag to rearrange the Deck (deck-drag-v1). Positions are in window (root) coordinates. Where a card lands is
 * worked out from where the cards were when the drag began, so the slot opening up doesn't make it jump about.
 */
class DeckDrag {
    var card by mutableStateOf<HomeCard?>(null)
        private set
    var pointer by mutableStateOf(Offset.Zero)
        private set
    var target by mutableStateOf<DeckSpot?>(null)
        private set
    /** The dragged card's size when it was picked up. */
    var size: Rect = Rect.Zero
        private set
    /** The pointer's place on the card when it was picked up. */
    var grab = Offset.Zero
        private set

    val bounds = mutableMapOf<HomeCard, Rect>()
    private var snap: Map<HomeCard, Rect> = emptyMap()

    /** The two columns as shown now; set on each draw. */
    var columns: Pair<List<HomeCard>, List<HomeCard>> = emptyList<HomeCard>() to emptyList()
    /** Root x where the right column starts. */
    var split = 0f

    fun start(c: HomeCard, at: Offset) {
        val b = bounds[c] ?: return
        snap = bounds.toMap()
        size = b
        grab = at - b.topLeft
        pointer = at
        card = c
        target = spotFor(at)
    }

    fun move(by: Offset) {
        if (card == null) return
        pointer += by
        target = spotFor(pointer)
    }

    fun stop() { card = null; target = null }

    private fun spotFor(p: Offset): DeckSpot {
        val left = p.x < split
        val list = (if (left) columns.first else columns.second).filter { it != card }
        val index = list.count { c -> snap[c]?.let { it.center.y < p.y } ?: false }
        return DeckSpot(left, index)
    }
}

/** One column of the Deck, with a dashed slot where a dragged card would land. */
@Composable
fun DeckDragColumn(
    modifier: Modifier, cards: List<HomeCard>, left: Boolean, drag: DeckDrag,
    onPickUp: () -> Unit, onDrop: () -> Unit, show: @Composable (HomeCard) -> Unit,
) {
    Column(modifier, verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp)) {
        val target = drag.target?.takeIf { it.left == left }
        var i = 0
        cards.forEach { c ->
            if (c != drag.card) {
                if (target?.index == i) DropSlot(drag)
                i++
            }
            DraggableCard(c, drag, onPickUp, onDrop) { show(c) }
        }
        if (target?.index == i) DropSlot(drag)
    }
}

@Composable
private fun DropSlot(drag: DeckDrag) {
    val h = with(LocalDensity.current) { drag.size.height.toDp() }
    val shape = RoundedCornerShape(16.dp)
    Box(Modifier.fillMaxWidth().height(h).clip(shape).background(Primary.copy(alpha = 0.07f)).border(2.dp, Primary, shape))
}

@Composable
private fun DraggableCard(card: HomeCard, drag: DeckDrag, onPickUp: () -> Unit, onDrop: () -> Unit, content: @Composable () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val grip = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val dragging = drag.card == card
    Box(
        Modifier.fillMaxWidth().onGloballyPositioned { drag.bounds[card] = it.boundsInRoot() }
            .hoverable(source).alpha(if (dragging) 0.35f else 1f)
    ) {
        content()
        // Keep the grip while this card is dragged, or the gesture would end with it.
        if ((hovered && drag.card == null) || dragging) Grip(
            Modifier.align(Alignment.TopEnd).padding(top = 9.dp, end = 10.dp)
                .onGloballyPositioned { grip[0] = it }
                .pointerInput(card) {
                    detectDragGestures(
                        onDragStart = { o -> grip[0]?.let { drag.start(card, it.localToRoot(o)); onPickUp() } },
                        onDrag = { change, by -> change.consume(); drag.move(by) },
                        onDragEnd = onDrop,
                        onDragCancel = { drag.stop() },
                    )
                }
        )
    }
}

@Composable
private fun Grip(modifier: Modifier) {
    val shape = RoundedCornerShape(8.dp)
    Text(
        "⠿", fontSize = 15.sp, color = Muted,
        modifier = modifier.clip(shape).background(Surface2).border(1.dp, Outline, shape)
            .pointerHoverIcon(PointerIcon(Cursor(Cursor.MOVE_CURSOR))).padding(horizontal = 7.dp, vertical = 1.dp)
    )
}

/** The card under the pointer while it's dragged, drawn over the Deck. [origin] is the Deck's root position. */
@Composable
fun LiftedCard(drag: DeckDrag, origin: Offset, content: @Composable ColumnScope.() -> Unit) {
    if (drag.card == null) return
    val w = with(LocalDensity.current) { drag.size.width.toDp() }
    val at = drag.pointer - drag.grab - origin
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.offset { IntOffset(at.x.roundToInt(), at.y.roundToInt()) }.width(w).rotate(-1.2f)
            .shadow(24.dp, shape).border(2.dp, Primary, shape).clip(shape).background(Color.Transparent),
        content = content
    )
}
