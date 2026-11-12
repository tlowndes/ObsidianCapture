package uk.tomlowndes.capture

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// Tidy-up (mockups/tidy-v1): one hero per screen, the rest as quiet rows.

/** One thing waiting at the top of the Deck: plan, shut down, mood, migrate or a missing vault. */
internal sealed class NowItem {
    /** [onDismiss]: swiping the line sideways sets it aside (the evening check-in, for tonight). */
    data class Line(val text: String, val action: String, val warn: Boolean = false, val onDismiss: (() -> Unit)? = null, val onClick: () -> Unit) : NowItem()
    data class Mood(val onPick: (Int) -> Unit) : NowItem()
}

/** The slim "Now" line: the first waiting item, with "+N" to step through the rest. */
@Composable
internal fun NowLine(items: List<NowItem>, index: Int, onNext: () -> Unit) {
    if (items.isEmpty()) return
    val item = items[index % items.size]
    val warn = item is NowItem.Line && item.warn
    val dismiss = (item as? NowItem.Line)?.onDismiss
    var dragX by remember(item) { mutableFloatStateOf(0f) }
    val swipe = if (dismiss == null) Modifier else Modifier
        .offset { IntOffset(dragX.roundToInt(), 0) }
        .graphicsLayer { alpha = (1f - kotlin.math.abs(dragX) / 600f).coerceIn(0.2f, 1f) }
        .pointerInput(item) {
            detectHorizontalDragGestures(
                onDragEnd = { if (kotlin.math.abs(dragX) > 120.dp.toPx()) dismiss() else dragX = 0f },
                onDragCancel = { dragX = 0f },
            ) { _, delta -> dragX += delta }
        }
    Row(
        Modifier.fillMaxWidth().padding(bottom = 12.dp).then(swipe).clip(RoundedCornerShape(14.dp))
            .background(if (warn) AmberTint else PrimaryTint)
            .then(if (item is NowItem.Line) Modifier.clickable(onClick = item.onClick) else Modifier)
            .padding(start = 14.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        when (item) {
            is NowItem.Line -> {
                Text(item.text, fontSize = 13.5.sp, color = if (warn) Amber else TextMain, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(item.action, fontSize = 13.sp, color = if (warn) Amber else Primary, fontWeight = FontWeight.SemiBold)
            }
            is NowItem.Mood -> {
                Text("How was today?", fontSize = 13.5.sp, modifier = Modifier.weight(1f))
                Mood.FACES.forEachIndexed { i, f ->
                    Text(f, fontSize = 19.sp, modifier = Modifier.clip(CircleShape).clickable { item.onPick(i + 1) }.padding(2.dp))
                }
            }
        }
        if (items.size > 1) Text(
            "+${items.size - 1}", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Muted,
            modifier = Modifier.clip(CircleShape).background(Surface2).clickable(onClick = onNext).padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

/** Date and greeting on the left, the energy switch on the right. */
@Composable
internal fun SubLine(text: String, right: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        right()
    }
}

/** Next step as the Deck's hero: Start, Later, Split (Break it down) and a quiet Stuck? link. */
@Composable
internal fun NextStepHero(
    t: TaskItem?, meta: String, onStart: (TaskItem, Int) -> Unit, onLater: (TaskItem) -> Unit,
    onSplit: (TaskItem) -> Unit, onStuck: () -> Unit
) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF1C2744), Surface1)))
            .border(1.dp, NextBorder, RoundedCornerShape(22.dp)).padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 14.dp)
    ) {
        Text("NEXT STEP", fontSize = 11.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.2.sp, color = Primary)
        if (t == null) {
            Text("✓ All clear", fontSize = 19.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp, bottom = 12.dp))
            DeckButton("Stuck? Pick one.", primary = true, modifier = Modifier.fillMaxWidth(), onClick = onStuck)
            return@Column
        }
        Text(t.text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, lineHeight = 25.sp, modifier = Modifier.padding(top = 6.dp, bottom = 4.dp))
        if (meta.isNotEmpty()) Text(meta, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, color = Muted)
        val mins = Deck.minutes(t) ?: TaskViews.SHORT_MIN
        Row(Modifier.fillMaxWidth().padding(top = 13.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            HeroButton("▶ Start ${mins}m", primary = true) { onStart(t, mins) }
            HeroButton("Later") { onLater(t) }
            if (t.raw.isNotEmpty()) HeroButton("🌶 Split") { onSplit(t) }
            Box(Modifier.weight(1f))
            Text("Stuck?", fontSize = 12.5.sp, color = Muted, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onStuck).padding(4.dp))
        }
    }
}

@Composable
private fun HeroButton(text: String, primary: Boolean = false, onClick: () -> Unit) {
    Text(
        text, fontSize = 13.sp, maxLines = 1,
        fontWeight = if (primary) FontWeight.Bold else FontWeight.Normal, color = if (primary) OnPrimary else TextMain,
        modifier = Modifier.clip(CircleShape)
            .then(if (primary) Modifier.background(Primary) else Modifier.border(1.dp, Outline, CircleShape))
            .clickable(onClick = onClick).padding(horizontal = if (primary) 14.dp else 11.dp, vertical = 7.dp)
    )
}

/** Due today, focus and habits as three small stat boxes. */
@Composable
internal fun StatPills(due: Int, focusMin: Int, habits: Pair<Int, Int>?, onTasks: () -> Unit, onFocus: () -> Unit, onHabits: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Stat("$due", "due today", onTasks)
        Stat("${focusMin}m", "focus", onFocus)
        Stat(habits?.let { (d, t) -> "$d/$t" } ?: "–", "habits", onHabits)
    }
}

@Composable
private fun RowScope.Stat(value: String, label: String, onClick: () -> Unit) {
    Column(
        Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(Surface1).border(1.dp, Outline, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(value, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text(label, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, color = Muted)
    }
}

/** A small mono section label, with an optional link on the right. */
@Composable
internal fun SectionLabel(text: String, link: String? = null, onLink: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.2.sp, color = Muted, modifier = Modifier.weight(1f))
        if (link != null) Text(link, fontSize = 12.sp, color = Primary, modifier = if (onLink != null) Modifier.clickable(onClick = onLink) else Modifier)
    }
}

/** One row inside a grouped card: icon, text, a quiet right-hand value. */
@Composable
internal fun QuietRow(
    icon: String?, text: String, right: String? = null, rightColour: Color = Muted, textColour: Color = TextMain,
    highlight: Boolean = false, onClick: (() -> Unit)? = null, lead: (@Composable () -> Unit)? = null,
    strike: Boolean = false, trailing: (@Composable () -> Unit)? = null
) {
    Row(
        Modifier.fillMaxWidth().background(if (highlight) PrimaryTint else Color.Transparent)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)
    ) {
        if (lead != null) lead() else if (icon != null) Text(icon, fontSize = 15.sp, modifier = Modifier.width(22.dp))
        Text(
            text, fontSize = 14.sp, color = textColour, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            textDecoration = if (strike) TextDecoration.LineThrough else null
        )
        if (right != null) Text(right, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = rightColour, maxLines = 1)
        trailing?.invoke()
    }
}

/** A card of [rows], with dividers between them. */
@Composable
internal fun RowsCard(rows: List<@Composable () -> Unit>) {
    if (rows.isEmpty()) return
    DeckCard {
        rows.forEachIndexed { i, r ->
            if (i > 0) HorizontalDivider(color = Outline)
            r()
        }
    }
}

/**
 * Today's rows from the calendar: current event highlighted, finished ones faded and, from two up, folded into
 * "✓ N earlier" (past-events-highlights-v1); Connect or Reconnect when needed.
 */
internal fun calendarRows(
    sources: List<CalendarSource>?, cache: List<CalendarCache>, onConnect: () -> Unit,
    earlierOpen: Boolean = false, onToggleEarlier: () -> Unit = {}
): List<@Composable () -> Unit> {
    if (sources == null) return emptyList()
    if (sources.isEmpty()) return listOf<@Composable () -> Unit>({ QuietRow("📅", "Calendar", "Connect ›", Primary, onClick = onConnect) })
    val on = sources.filter { it.enabled }.map { it.id }.toSet()
    val mine = cache.filter { it.id in on }
    val today = LocalDate.now()
    val now = LocalDateTime.now()
    val hm = DateTimeFormatter.ofPattern("HH:mm")
    val events = mine.flatMap { it.events }.filter { it.on(today) }
    val out = mutableListOf<@Composable () -> Unit>()
    if (mine.any { !it.ok }) out.add { QuietRow("📅", "Calendar out of date", "Reconnect ›", Amber, onClick = onConnect) }
    TodayEvents.rows(events, now, earlierOpen).forEach { r ->
        when (r) {
            is TodayRow.Earlier -> out.add {
                QuietRow(
                    null, TodayEvents.earlierLabel(r), if (r.open) "▴" else "show ▾", textColour = Muted, onClick = onToggleEarlier
                )
            }
            is TodayRow.Event -> {
                val e = r.event
                val live = e.isNow(now)
                out.add {
                    QuietRow(
                        "📅", e.title, if (e.allDay) "all day" else e.start.toLocalTime().format(hm), highlight = live,
                        rightColour = if (r.past) PastFaint else if (live) Primary else Muted,
                        textColour = if (r.past) PastFaint else TextMain,
                        trailing = if (live) ({ NowBadge() }) else null
                    )
                }
            }
        }
    }
    return out
}

private val PastFaint = Color(0xFF5C6478)

@Composable
private fun NowBadge() {
    Text(
        "now", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Primary,
        modifier = Modifier.border(1.dp, Color(0xFF2F3D63), RoundedCornerShape(99.dp)).padding(horizontal = 7.dp, vertical = 1.dp)
    )
}

/** A small tile in the More row. */
@Composable
internal fun RowScope.Tile(icon: String, title: String, sub: String?, dot: Boolean = false, onClick: () -> Unit) {
    Column(
        Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(Surface1).border(1.dp, Outline, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick).padding(horizontal = 11.dp, vertical = 10.dp)
    ) {
        Text(icon, fontSize = 17.sp)
        Text(title, fontSize = 12.5.sp, modifier = Modifier.padding(top = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (sub != null) Row(verticalAlignment = Alignment.CenterVertically) {
            Text(sub, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (dot) Box(Modifier.padding(start = 4.dp).size(6.dp).clip(CircleShape).background(Amber))
        }
    }
}

/** Up to three tiles side by side; empty slots keep the others the same width. */
@Composable
internal fun TileRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
}
