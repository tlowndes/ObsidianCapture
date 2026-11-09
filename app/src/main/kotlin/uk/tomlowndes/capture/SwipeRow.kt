package uk.tomlowndes.capture

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What letting go of a swiped row does. */
private enum class SwipeAction(val label: String, val colour: Color) {
    DONE("✓ Done", Green), MOVE("Reschedule ⟶", Primary), REMOVE("Remove 🗑", Warn)
}

/** Share of the row's width a swipe must pass: right past [DONE_AT] ticks, left past [MOVE_AT] reschedules, past [REMOVE_AT] removes. */
private const val DONE_AT = 0.25f
private const val MOVE_AT = 0.25f
private const val REMOVE_AT = 0.55f

private fun actionAt(f: Float): SwipeAction? = when {
    f > DONE_AT -> SwipeAction.DONE
    f < -REMOVE_AT -> SwipeAction.REMOVE
    f < -MOVE_AT -> SwipeAction.MOVE
    else -> null
}

/**
 * A task row that can be swiped (tasks-swipe-v1): right to tick, left to open the reschedule sheet, further left to remove.
 * The colour shows behind the row as it moves (dim until the threshold, with a buzz as each one is crossed).
 * Letting go early snaps back. With [enabled] false the row is shown as it is.
 */
@Composable
internal fun SwipeRow(
    enabled: Boolean, onDone: () -> Unit, onMove: () -> Unit, onRemove: () -> Unit, bg: Color = Surface1,
    content: @Composable () -> Unit,
) {
    if (!enabled) { content(); return }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val offset = remember { Animatable(0f) }
    var width by remember { mutableFloatStateOf(1f) }
    val done by rememberUpdatedState(onDone)
    val move by rememberUpdatedState(onMove)
    val remove by rememberUpdatedState(onRemove)
    val action = actionAt(offset.value / width)
    var felt by remember { mutableStateOf<SwipeAction?>(null) }
    LaunchedEffect(action) {
        if (action != null && action != felt) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        felt = action
    }

    Box(Modifier.fillMaxWidth().onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }) {
        if (offset.value != 0f) {
            val right = offset.value > 0
            val shown = action ?: if (right) SwipeAction.DONE else SwipeAction.MOVE
            Row(
                Modifier.matchParentSize().background(if (action == null) shown.colour.copy(alpha = 0.45f) else shown.colour)
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (right) Arrangement.Start else Arrangement.End
            ) {
                Text(shown.label, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = OnPrimary)
            }
        }
        Box(
            Modifier.fillMaxWidth().graphicsLayer { translationX = offset.value }.background(bg)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            val a = actionAt(offset.value / width)
                            scope.launch {
                                when (a) {
                                    SwipeAction.REMOVE -> {
                                        offset.animateTo(-width, tween(180))
                                        remove()
                                        delay(400) // the row folds away meanwhile
                                        offset.snapTo(0f)
                                    }
                                    SwipeAction.DONE -> { offset.animateTo(0f, tween(180)); done() }
                                    SwipeAction.MOVE -> { offset.animateTo(0f, tween(180)); move() }
                                    null -> offset.animateTo(0f, tween(180))
                                }
                            }
                        },
                        onDragCancel = { scope.launch { offset.animateTo(0f, tween(180)) } },
                    ) { change, amount ->
                        change.consume()
                        scope.launch { offset.snapTo((offset.value + amount).coerceIn(-width, width)) }
                    }
                }
        ) { content() }
    }
}
