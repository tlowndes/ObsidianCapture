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

/**
 * What a swipe does (multiselect-swipes-v1). Each of swipe right, swipe left and long swipe left is set to one of
 * these in Settings › Swipes; [label] shows behind the row as it moves.
 */
enum class SwipeChoice(val title: String, val short: String, val label: String, val icon: String, val colour: Color) {
    DONE("Done", "Done", "✓ Done", "✓", Green),
    RESCHEDULE("Reschedule (pick a day)", "Reschedule", "Reschedule 📅", "📅", Primary),
    TOMORROW("Move to tomorrow", "Tomorrow", "Tomorrow →", "→", Amber),
    TODAY("Move to today", "Today", "Today ☀", "☀", Teal),
    PRIORITY("Set priority", "Priority", "Priority !", "!", Color(0xFFFFA36B)),
    FOCUS("Start focus", "Focus", "Focus ▶", "▶", Purple),
    REMOVE("Remove", "Remove", "Remove 🗑", "🗑", Warn),
    NOTHING("Nothing", "Nothing", "", "–", Muted);

    companion object {
        fun of(name: String?, fallback: SwipeChoice) = values().firstOrNull { it.name == name } ?: fallback
    }
}

/** Share of the row's width a swipe must pass: right past [RIGHT_AT], left past [LEFT_AT], long left past [LONG_AT]. */
private const val RIGHT_AT = 0.25f
private const val LEFT_AT = 0.25f
private const val LONG_AT = 0.55f

/** The action a swipe of [f] (share of the width, right positive) lands on, or null for none yet. */
internal fun swipeAt(f: Float, right: SwipeChoice, left: SwipeChoice, longLeft: SwipeChoice): SwipeChoice? = when {
    f > RIGHT_AT -> right
    f < -LONG_AT -> if (longLeft != SwipeChoice.NOTHING) longLeft else left
    f < -LEFT_AT -> left
    else -> null
}.takeIf { it != SwipeChoice.NOTHING }

/**
 * A task row that can be swiped (tasks-swipe-v1, multiselect-swipes-v1): right, left and further left each do the
 * action chosen in Settings. The action's colour shows behind the row as it moves (dim until the threshold, with a
 * buzz as each one is crossed). Letting go early snaps back. With [enabled] false the row is shown as it is.
 */
@Composable
internal fun SwipeRow(
    enabled: Boolean, right: SwipeChoice, left: SwipeChoice, longLeft: SwipeChoice, onAction: (SwipeChoice) -> Unit,
    bg: Color = Surface1, content: @Composable () -> Unit,
) {
    val canRight = right != SwipeChoice.NOTHING
    val canLeft = left != SwipeChoice.NOTHING || longLeft != SwipeChoice.NOTHING
    if (!enabled || (!canRight && !canLeft)) { content(); return }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val offset = remember { Animatable(0f) }
    var width by remember { mutableFloatStateOf(1f) }
    val act by rememberUpdatedState(onAction)
    val choices by rememberUpdatedState(Triple(right, left, longLeft))
    fun at(f: Float) = swipeAt(f, choices.first, choices.second, choices.third)
    val action = at(offset.value / width)
    var felt by remember { mutableStateOf<SwipeChoice?>(null) }
    LaunchedEffect(action) {
        if (action != null && action != felt) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        felt = action
    }

    Box(Modifier.fillMaxWidth().onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }) {
        if (offset.value != 0f) {
            val toRight = offset.value > 0
            val shown = action ?: if (toRight) right else left.takeIf { it != SwipeChoice.NOTHING } ?: longLeft
            Row(
                Modifier.matchParentSize().background(if (action == null) shown.colour.copy(alpha = 0.45f) else shown.colour)
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (toRight) Arrangement.Start else Arrangement.End
            ) {
                Text(shown.label, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = OnPrimary)
            }
        }
        Box(
            Modifier.fillMaxWidth().graphicsLayer { translationX = offset.value }.background(bg)
                .pointerInput(canLeft, canRight) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            val a = at(offset.value / width)
                            scope.launch {
                                when (a) {
                                    SwipeChoice.REMOVE -> {
                                        offset.animateTo(if (offset.value > 0) width else -width, tween(180))
                                        act(a)
                                        delay(400) // the row folds away meanwhile
                                        offset.snapTo(0f)
                                    }
                                    null -> offset.animateTo(0f, tween(180))
                                    else -> { offset.animateTo(0f, tween(180)); act(a) }
                                }
                            }
                        },
                        onDragCancel = { scope.launch { offset.animateTo(0f, tween(180)) } },
                    ) { change, amount ->
                        change.consume()
                        scope.launch { offset.snapTo((offset.value + amount).coerceIn(if (canLeft) -width else 0f, if (canRight) width else 0f)) }
                    }
                }
        ) { content() }
    }
}
