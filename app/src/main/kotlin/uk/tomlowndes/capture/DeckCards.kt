package uk.tomlowndes.capture

import android.Manifest
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

// The Deck's small cards and pieces, used by DeckScreen.kt.

/** A bottom sheet holding one of the Deck's cards. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DeckSheet(onDismiss: () -> Unit, full: Boolean = false, content: @Composable () -> Unit) {
    // [full] opens straight to full height, so a tall card (the journal) shows its Save button without a drag.
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = full)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = Bg) {
        Box(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) { content() }
    }
}

@Composable
internal fun DeckHeader(onOpenSearch: () -> Unit, onOpenSettings: () -> Unit, onPrint: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text("Flight Deck", fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp, maxLines = 2, modifier = Modifier.weight(1f).semantics { heading() })
        Row {
            // Daily Slip: today's printout for the pocket thermal printer.
            // Hangar moved to the dock above the bottom bar (hangar-dock-v1).
            // Spoken labels for the emoji buttons (care-welcome-v1).
            if (onPrint != null) IconButton(onClick = onPrint, modifier = Modifier.semantics { contentDescription = "Daily Slip" }) { Text("🧾", fontSize = 19.sp, modifier = Modifier.clearAndSetSemantics { }) }
            IconButton(onClick = onOpenSearch) { Icon(Icons.Outlined.Search, "Search", tint = Muted) }
            IconButton(onClick = onOpenSettings) { Icon(Icons.Outlined.Settings, "Settings", tint = Muted) }
        }
    }
}

@Composable
internal fun EnergyChip(selected: Energy, onSelect: (Energy) -> Unit) {
    Row(
        Modifier.clip(CircleShape).background(Surface1)
            .border(1.dp, Outline, CircleShape).padding(2.dp)
    ) {
        Energy.values().forEach { e ->
            val on = e == selected
            val (bg, fg) = when {
                !on -> Color.Transparent to Muted
                e == Energy.LOW -> AmberTint to Amber
                e == Energy.HIGH -> TaskTint to Green
                else -> PrimaryTint to Primary
            }
            Box(Modifier.clip(CircleShape).background(bg).clickable { onSelect(e) }.padding(horizontal = 12.dp, vertical = 4.dp)) {
                Text(
                    e.label, color = fg, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
internal fun WeatherCard(w: WeatherSummary, travel: TravelLine?, showStrip: Boolean, onToggle: () -> Unit) {
    DeckCard(border = WeatherBorder, bg = WeatherBg, modifier = Modifier.clickable(onClick = onToggle)) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text((if (travel != null) "WEATHER & TRAVEL" else "WEATHER") + if (w.tomorrow) " · TOMORROW" else "", fontSize = 12.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp, color = WeatherLabel)
                if (w.parts.isNotEmpty()) Text(if (showStrip) "▴" else "▾", fontSize = 12.sp, color = Muted)
            }
            Text("${w.high}° / ${w.low}° · ${w.headline}", fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp, bottom = 6.dp))
            if (showStrip && w.parts.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    w.parts.forEach { p ->
                        Column(
                            Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(WeatherSlot)
                                .border(1.dp, if (p.rain >= 50) WeatherSlotWet else WeatherSlotBorder, RoundedCornerShape(12.dp))
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                        ) {
                            Text(p.name.uppercase(), fontSize = 11.sp, fontFamily = FontFamily.Monospace, letterSpacing = 0.5.sp, color = Muted)
                            Text(p.icon, fontSize = 18.sp, modifier = Modifier.padding(top = 4.dp, bottom = 2.dp))
                            Text("${p.temp}°", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Text("${p.rain}%", fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, color = WeatherLabel, modifier = Modifier.padding(top = 2.dp))
                        }
                    }
                }
            }
            Text(w.detail, fontSize = 13.sp, color = Muted, lineHeight = 19.sp)
            w.tip?.let { Text(it, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 8.dp)) }
            w.tonight?.let {
                HorizontalDivider(color = WeatherBorder, modifier = Modifier.padding(top = 10.dp, bottom = 8.dp))
                Text(it, fontSize = 12.5.sp, color = Muted, lineHeight = 18.sp)
            }
            if (travel != null) {
                HorizontalDivider(color = WeatherBorder, modifier = Modifier.padding(top = 12.dp, bottom = 10.dp))
                val hm = DateTimeFormatter.ofPattern("HH:mm")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("🚗 ${travel.minutes} min", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Primary)
                    Column {
                        Text("${travel.event.title} · ${travel.event.start.format(hm)}", fontSize = 13.5.sp)
                        Text("Leave by ${travel.leaveBy.format(hm)} · ${travel.event.location}", fontSize = 12.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
internal fun StuckCard(items: List<StuckItem>, onOpen: () -> Unit, onStart: (StuckItem) -> Unit) {
    DeckCard {
        CardHead("Stuck? Pick one.", "All ›", onOpen)
        items.forEach { i ->
            HorizontalDivider(color = Outline)
            Row(Modifier.fillMaxWidth().clickable { onStart(i) }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text("${i.emoji} ${i.name}", fontSize = 13.5.sp, modifier = Modifier.weight(1f))
                Text("${i.minutes} min", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Muted)
            }
        }
    }
}

@Composable
internal fun DeckCard(border: Color = Outline, bg: Color = Surface1, modifier: Modifier = Modifier, content: @Composable () -> Unit) =
    ListCard(modifier, border, bg, content)

@Composable
internal fun CardHead(title: String, trailing: String? = null, onTrailing: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 11.dp, bottom = 7.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        if (trailing != null) Text(
            trailing, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Muted,
            modifier = if (onTrailing != null) Modifier.clickable(onClick = onTrailing) else Modifier
        )
    }
}

internal fun firstTag(t: TaskItem) = TaskViews.tags(t).firstOrNull()

@Composable
internal fun DeckButton(text: String, primary: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        text, textAlign = TextAlign.Center, fontSize = 13.sp,
        fontWeight = if (primary) FontWeight.Bold else FontWeight.Normal,
        color = if (primary) OnPrimary else Muted,
        modifier = modifier.clip(CircleShape)
            .then(if (primary) Modifier.background(Primary) else Modifier.border(1.dp, Outline, CircleShape))
            .clickable(onClick = onClick).padding(vertical = 9.dp)
    )
}

/** Habits on home: a bar and press button per counter, today's ticks with streaks. "All ›" opens the full Habits screen. */
@Composable
internal fun HabitsCard(n: HabitsNote, onChange: ((HabitDay) -> HabitDay) -> Unit, onAll: () -> Unit) {
    val today = LocalDate.now()
    val day = n.day(today)
    val (done, total) = n.progress(today)
    DeckCard {
        CardHead("Habits", "$done of $total · All ›", onAll)
        n.on(today).forEach { h ->
            HorizontalDivider(color = Outline)
            if (h.counter) {
                val v = day.value(h)
                val over = h.max && v > h.goal
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(h.emoji.ifEmpty { h.name.take(1) }, fontSize = 13.sp)
                    Box(Modifier.weight(1f).height(6.dp).clip(CircleShape).background(Surface2)) {
                        Box(Modifier.fillMaxWidth((v.toFloat() / h.goal.coerceAtLeast(1)).coerceIn(0f, 1f)).height(6.dp).background(if (over) Amber else Primary))
                    }
                    Text(Habits.short(h, v), fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = if (over) Amber else Muted)
                    Text(
                        Habits.press(h), color = Primary, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(CircleShape).background(PrimaryTint)
                            .clickable { onChange { it.add(h, h.step) } }.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            } else {
                val on = h.name in day.done
                val streak = n.streak(h, today)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TickBox(done = on, onClick = { onChange { it.toggle(h) } })
                    Text(h.label, fontSize = 13.5.sp, modifier = Modifier.weight(1f))
                    if (streak > 0) Text("🔥 $streak", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Muted)
                }
            }
        }
    }
}

@Composable
internal fun RowCard(title: String, trailing: String, accent: Boolean = false, onClick: () -> Unit) {
    DeckCard(modifier = Modifier.clickable(onClick = onClick)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, fontSize = 13.5.sp, color = if (accent) Primary else TextMain, fontWeight = if (accent) FontWeight.SemiBold else FontWeight.Normal)
            Text(trailing, fontSize = 13.sp, color = Muted, fontFamily = if (accent) FontFamily.Default else FontFamily.Monospace)
        }
    }
}
