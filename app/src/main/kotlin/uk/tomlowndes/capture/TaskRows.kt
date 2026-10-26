package uk.tomlowndes.capture

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
 * One task line as Flight Deck draws it: optional work/personal edge, tick box, text with its first tag, then
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
                TaskViews.tags(t).firstOrNull()?.let { tag ->
                    withStyle(SpanStyle(color = if (tag == "#bill") Amber else Purple, fontFamily = FontFamily.Monospace, fontSize = 12.sp)) {
                        append(" $tag")
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

@Composable
internal fun TickBox(done: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier.size(20.dp).clip(shape)
            .then(if (done) Modifier.background(Green) else Modifier.border(1.5.dp, Muted, shape))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { if (done) Text("✓", fontSize = 12.sp, color = OnPrimary) }
}

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
