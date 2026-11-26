package uk.tomlowndes.capture

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Settings sub-pages and their pieces, used by SettingsScreen.kt.

/** "‹ Jarvis": a settings sub-page title that goes back to the main page. */
@Composable
internal fun SubPageTitle(title: String, onBack: () -> Unit) {
    Text(
        "‹ $title", fontSize = 22.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 12.dp, bottom = 12.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onBack).padding(vertical = 4.dp)
    )
}

/** A small two-or-more option switch, like "Under task | Sheet". */
@Composable
internal fun Segmented(options: List<String>, selected: Int, onPick: (Int) -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(10.dp)).background(Surface2).padding(2.dp)) {
        options.forEachIndexed { i, o ->
            val on = i == selected
            Text(
                o, fontSize = 12.sp, color = if (on) Primary else Muted, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (on) PrimaryTint else Color.Transparent)
                    .clickable { onPick(i) }.padding(horizontal = 9.dp, vertical = 4.dp)
            )
        }
    }
}

internal val STALE_STEPS = listOf(2, 5, 15, 30)

/** "2 min ago" for Settings › Updates. */
internal fun checkedAgo(at: Long): String {
    if (at <= 0) return "Never"
    val min = (System.currentTimeMillis() - at) / 60_000
    return when {
        min < 1 -> "Just now"
        min < 60 -> "$min min ago"
        min < 24 * 60 -> "${min / 60} h ago"
        else -> "${min / (24 * 60)} days ago"
    }
}

/** Settings › Swipes (multiselect-swipes-v1): swipes on or off, and what swipe right, left and long left each do. */
@Composable
internal fun SwipesSection(prefs: AppPrefs) {
    var on by remember { mutableStateOf(prefs.taskSwipe) }
    var right by remember { mutableStateOf(prefs.swipeRight) }
    var left by remember { mutableStateOf(prefs.swipeLeft) }
    var longLeft by remember { mutableStateOf(prefs.swipeLongLeft) }
    var picking by remember { mutableStateOf<String?>(null) }
    DeckCard {
        Box(Modifier.padding(horizontal = 14.dp)) {
            SettingRow("Swipe on tasks", "Turn all swipes on or off") { AppSwitch(on) { on = it; prefs.taskSwipe = it } }
        }
    }
    if (on) {
        Box(Modifier.height(14.dp))
        GroupLabel("What each swipe does")
        Box(Modifier.height(8.dp))
        DeckCard {
            listOf(
                Triple("Swipe right", "Short swipe from the left edge", right),
                Triple("Swipe left", "Short swipe from the right edge", left),
                Triple("Long swipe left", "Swipe past half way", longLeft),
            ).forEachIndexed { i, (title, sub, c) ->
                if (i > 0) HorizontalDivider(color = Outline)
                Box(Modifier.clickable { picking = title }.padding(horizontal = 14.dp)) {
                    SettingRow(title, sub) { Text(c.short + " ›", fontSize = 13.sp, color = Primary, fontWeight = FontWeight.SemiBold) }
                }
            }
        }
        Box(Modifier.height(14.dp))
        GroupLabel("Preview")
        Box(Modifier.height(8.dp))
        SwipePreview(right, toRight = true)
        Box(Modifier.height(8.dp))
        SwipePreview(left.takeIf { it != SwipeChoice.NOTHING } ?: longLeft, toRight = false)
        Text("Swipes only work in the list, and are off while you're selecting tasks.", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 10.dp, bottom = 20.dp))
    }
    picking?.let { title ->
        val current = when (title) { "Swipe right" -> right; "Swipe left" -> left; else -> longLeft }
        SwipeChoiceSheet(title, current, onPick = { c ->
            when (title) {
                "Swipe right" -> { right = c; prefs.swipeRight = c }
                "Swipe left" -> { left = c; prefs.swipeLeft = c }
                else -> { longLeft = c; prefs.swipeLongLeft = c }
            }
            picking = null
        }, onDismiss = { picking = null })
    }
}

/** A row pulled part way, showing the colour and label of [c] behind it. */
@Composable
internal fun SwipePreview(c: SwipeChoice, toRight: Boolean) {
    val shape = RoundedCornerShape(14.dp)
    Box(Modifier.fillMaxWidth().height(46.dp).clip(shape).border(1.dp, Outline, shape).background(if (c == SwipeChoice.NOTHING) Surface2 else c.colour)) {
        if (c != SwipeChoice.NOTHING) Text(
            c.label, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = OnPrimary,
            modifier = Modifier.align(if (toRight) Alignment.CenterStart else Alignment.CenterEnd).padding(horizontal = 16.dp)
        )
        Box(
            Modifier.fillMaxHeight().fillMaxWidth(0.68f).align(if (toRight) Alignment.CenterEnd else Alignment.CenterStart).background(Surface1)
                .padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart
        ) { Text(if (c == SwipeChoice.NOTHING) "Nothing happens" else "Book MOT", fontSize = 13.sp, color = if (c == SwipeChoice.NOTHING) Muted else TextMain) }
    }
}

/** Door Ink (hangar-phase4): the e-ink screen by the front door, where it is, and a test that sends it the screen. */
@Composable
internal fun DoorInkSection(prefs: AppPrefs) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var addr by remember { mutableStateOf(prefs.doorInkAddress) }
    Text(
        "Shows the leave-by time, what to grab and the weather before you head out, and today's Did I? checks the " +
            "rest of the day. Sent over your home Wi-Fi when you tick or stamp something, and every 15 minutes. " +
            "Leave blank to turn it off.",
        fontSize = 12.sp, color = Muted, lineHeight = 17.sp, modifier = Modifier.padding(bottom = 8.dp)
    )
    PathField("Address", addr) {
        prefs.doorInkAddress = it; addr = it.trim(); result = null
        DoorInkSend.keep(ctx, addr.isNotBlank())
    }
    Text(
        "doorink.local, or the IP address the door screen shows if that doesn't connect.",
        fontSize = 12.sp, color = Muted, modifier = Modifier.padding(bottom = 4.dp)
    )
    SettingRow("Test connection", result ?: if (testing) "Checking…" else null) {
        TextButton(onClick = {
            testing = true; result = null
            scope.launch { result = withContext(Dispatchers.IO) { DoorInkSend.test(ctx) }; testing = false }
        }, enabled = !testing && addr.isNotBlank()) { Text("Test", color = Primary) }
    }
}

/** The e-ink screen at home: where it is, and a test. */
@Composable
internal fun PiholegotchiSection(prefs: AppPrefs) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var addr by remember { mutableStateOf(prefs.piholegotchiAddress) }
    Text(
        "Shows your next step, tasks due, focus, habits and job follow-ups on the Piholegotchi screen, and a Focus " +
            "session while it runs. Sent over your home Wi-Fi when Deck refreshes. Leave blank to turn it off.",
        fontSize = 12.sp, color = Muted, lineHeight = 17.sp, modifier = Modifier.padding(bottom = 8.dp)
    )
    PathField("Address", addr) { prefs.piholegotchiAddress = it; addr = it.trim(); result = null }
    Text(
        "piholegotchi.local, or the IP address the Pi shows on its screen if that doesn't connect.",
        fontSize = 12.sp, color = Muted, modifier = Modifier.padding(bottom = 4.dp)
    )
    SettingRow("Test connection", result ?: if (testing) "Checking…" else null) {
        TextButton(onClick = {
            testing = true; result = null
            scope.launch {
                result = withContext(Dispatchers.IO) { Piholegotchi.test(addr) }
                testing = false
                if (result == "Connected") DeckCache.load(ctx)?.let { withContext(Dispatchers.IO) { Piholegotchi.sendDeck(ctx, it) } }
            }
        }, enabled = !testing && addr.isNotBlank()) { Text("Test", color = Primary) }
    }
}
