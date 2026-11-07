package uk.tomlowndes.capture

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One task line as Flight Deck draws it: optional work/personal edge, tick box, text with up to two tags, then
 * [trailing] (a due pill or ▶ 15m).
 */
@Composable
internal fun TaskLine(
    t: TaskItem, edge: Boolean, onTick: () -> Unit, modifier: Modifier = Modifier,
    faded: Boolean = false, onOpen: (() -> Unit)? = null, trailing: @Composable RowScope.() -> Unit
) {
    Row(
        modifier.fillMaxWidth().height(IntrinsicSize.Min)
            .padding(start = if (edge) 0.dp else 16.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (edge) Box(
            Modifier.width(4.dp).fillMaxHeight().clip(RoundedCornerShape(topEnd = 3.dp, bottomEnd = 3.dp))
                .background(if (TaskViews.isWork(t)) WorkEdge else PersonalEdge)
        )
        TickBox(done = faded, onClick = onTick, modifier = Modifier.padding(start = if (edge) 6.dp else 0.dp))
        Text(
            buildAnnotatedString {
                append(TaskViews.plainText(t))
                if (t.recurrence != null) append(" ↻")
                // Up to two tags, coloured like the Tag row: Work amber, Personal green, the rest purple.
                TaskViews.tags(t).take(2).forEach { tag ->
                    withStyle(SpanStyle(color = tagColour(tag).first, fontFamily = FontFamily.Monospace, fontSize = 12.sp)) {
                        append(" ${if (Tags.kind(tag) == TagKind.OTHER) tag else tag.removePrefix("#")}")
                    }
                }
                Deck.minutes(t)?.let { m ->
                    withStyle(SpanStyle(color = Muted, fontFamily = FontFamily.Monospace, fontSize = 12.sp)) { append(" ${TaskRules.minutesLabel(m)}") }
                }
            },
            fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            color = if (faded) Muted else TextMain,
            modifier = Modifier.weight(1f).padding(vertical = 9.dp)
                .then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier)
        )
        trailing()
    }
}

/**
 * A tick box. Ticking fills it green with a quick pop and draws the tick in; [outline] is the empty box's edge.
 */
@Composable
internal fun TickBox(
    done: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier,
    size: Dp = 20.dp, radius: Dp = 6.dp, outline: Color = Muted, fill: Color = Green, mark: Color = OnPrimary,
) {
    val shape = RoundedCornerShape(radius)
    val bg by animateColorAsState(if (done) fill else Color.Transparent, tween(180), label = "tickBg")
    val edge by animateColorAsState(if (done) fill else outline, tween(180), label = "tickEdge")
    val drawn by animateFloatAsState(if (done) 1f else 0f, tween(if (done) 260 else 120, delayMillis = if (done) 60 else 0), label = "tickMark")
    val scale = remember { Animatable(1f) }
    var seen by remember { mutableStateOf(done) }
    LaunchedEffect(done) {
        if (done && !seen) { scale.animateTo(1.25f, tween(110)); scale.animateTo(1f, spring(dampingRatio = 0.45f)) }
        seen = done
    }
    Box(
        modifier.size(size).graphicsLayer { scaleX = scale.value; scaleY = scale.value }.clip(shape)
            .background(bg).border(if (size >= 24.dp) 2.dp else 1.5.dp, edge, shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (drawn > 0f) Canvas(Modifier.size(size * 0.6f)) {
            val p = Path().apply {
                moveTo(this@Canvas.size.width * 0.16f, this@Canvas.size.height * 0.53f)
                lineTo(this@Canvas.size.width * 0.40f, this@Canvas.size.height * 0.76f)
                lineTo(this@Canvas.size.width * 0.84f, this@Canvas.size.height * 0.26f)
            }
            val m = PathMeasure().apply { setPath(p, false) }
            val part = Path()
            m.getSegment(0f, m.length * drawn, part, true)
            drawPath(part, mark, style = Stroke(this@Canvas.size.width * 0.17f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/**
 * A task on the Tasks tab: side edge, tick box in the side's colour, the task in 15sp with its tags, estimate and
 * repeat on a small line under it, then [trailing]. While [ticked] the row turns green, the text is struck through
 * and [trailing] gives way to "✓ Done".
 */
@Composable
internal fun TaskRow(
    t: TaskItem, ticked: Boolean, onTick: () -> Unit, onOpen: () -> Unit, trailing: @Composable RowScope.() -> Unit
) {
    val side = if (TaskViews.isWork(t)) WorkEdge else PersonalEdge
    val bg by animateColorAsState(if (ticked) TaskTint else Color.Transparent, tween(250), label = "rowBg")
    val edge by animateColorAsState(if (ticked) Green else side, tween(250), label = "rowEdge")
    val textColour by animateColorAsState(if (ticked) Muted else TextMain, tween(200), label = "rowText")
    Row(
        Modifier.fillMaxWidth().background(bg)
            .drawBehind {
                val inset = 12.dp.toPx()
                drawRoundRect(edge, Offset(0f, inset), Size(3.dp.toPx(), size.height - inset * 2), CornerRadius(3.dp.toPx()))
            }
            .padding(start = 16.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TickBox(ticked, onTick, size = 24.dp, radius = 8.dp, outline = side)
        Column(Modifier.weight(1f).clickable(onClick = onOpen)) {
            Text(
                TaskViews.plainText(t), fontSize = 15.sp, lineHeight = 19.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = textColour, textDecoration = if (ticked) TextDecoration.LineThrough else null
            )
            val tags = TaskViews.tags(t).take(2)
            val mins = Deck.minutes(t)
            if (tags.isNotEmpty() || mins != null || t.recurrence != null) Row(
                Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                tags.forEach { tag ->
                    val (fg, tint) = tagColour(tag)
                    Text(
                        if (Tags.kind(tag) == TagKind.OTHER) tag else tag.removePrefix("#"),
                        fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = fg, maxLines = 1,
                        modifier = Modifier.clip(CircleShape).background(tint).padding(horizontal = 7.dp)
                    )
                }
                mins?.let { Text(TaskRules.minutesLabel(it), fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Muted) }
                t.recurrence?.let {
                    Text("↻ $it", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (ticked) Text("✓ Done", fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Green)
        else trailing()
    }
}

/** A recently ticked task in Completed: small, muted and struck through. Tapping the box un-ticks it. */
@Composable
internal fun TickedRow(d: TickedTask, onUntick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 14.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TickBox(true, onUntick, size = 20.dp, radius = 7.dp, fill = DoneFill, mark = DoneMark)
        Text(
            d.text, fontSize = 13.5.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            textDecoration = TextDecoration.LineThrough, modifier = Modifier.weight(1f)
        )
        d.done?.let { day ->
            val today = LocalDate.now()
            Text(
                when (day) { today -> "Today"; today.minusDays(1) -> "Yesterday"; else -> day.format(DateTimeFormatter.ofPattern("EEE d", Locale.UK)) },
                fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Faint
            )
        }
    }
}

private val DoneFill = Color(0xFF2C5A47)
private val DoneMark = Color(0xFFBFEEDD)
private val Faint = Color(0xFF5C6478)

/** Mono pill with a short date ("Thu 22"); red when overdue. */
@Composable
internal fun DuePill(d: LocalDate, onClick: (() -> Unit)? = null) {
    val overdue = d.isBefore(LocalDate.now())
    Text(
        d.format(DateTimeFormatter.ofPattern("EEE d", Locale.UK)), fontSize = 12.sp, fontFamily = FontFamily.Monospace,
        color = if (overdue) Warn else Muted,
        modifier = Modifier.clip(CircleShape).border(1.dp, if (overdue) OverdueLine else Outline, CircleShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 7.dp, vertical = 2.dp)
    )
}

/** Time chips offered for a task's estimate. */
internal val ESTIMATES = TaskRules.ESTIMATE_CHOICES

/** "▶ 15m" on today's tasks: starts a Focus session. */
@Composable
internal fun StartPill(minutes: Int, onClick: () -> Unit) {
    Text(
        "▶ ${TaskRules.minutesLabel(minutes)}", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Primary,
        modifier = Modifier.clip(CircleShape).background(PrimaryTint).clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 3.dp)
    )
}

/** Shared card frame for Flight Deck lists. */
@Composable
internal fun ListCard(modifier: Modifier = Modifier, border: Color = Outline, bg: Color = Surface1, content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Column(
        modifier.fillMaxWidth().padding(bottom = 12.dp).clip(RoundedCornerShape(18.dp)).background(bg)
            .border(1.dp, border, RoundedCornerShape(18.dp))
    ) { content() }
}
