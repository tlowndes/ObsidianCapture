package uk.tomlowndes.capture.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

// ---- design tokens (mirror design/ui.css and the Android app's Ui.kt) ----
val Bg = Color(0xFF13111A)
val SideBg = Color(0xFF171420)
val Divider = Color(0xFF221E31)
val Surface1 = Color(0xFF1D1A28)
val Surface2 = Color(0xFF262236)
val Outline = Color(0xFF37314D)
val Primary = Color(0xFF8B6CFF)
val OnPrimary = Color(0xFF17102E)
val TextMain = Color(0xFFECE9F6)
val Muted = Color(0xFF9A94B3)
val Faint = Color(0xFF5E5878)
val Warn = Color(0xFFFF8A80)
val WarnTint = Color(0xFF3A1D22)
val TaskGreen = Color(0xFF6EE7B7)
val TaskTint = Color(0xFF16352C)
val LinkBlue = Color(0xFF7CC4FF)
val PurpleTint = Color(0xFF2A2250)
val Popup = Color(0xFF1B1827)
val Menu = Color(0xFF201C2E)

@Composable
fun CaptureTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Primary, onPrimary = OnPrimary,
            background = Bg, onBackground = TextMain,
            surface = Bg, onSurface = TextMain,
            surfaceVariant = Surface1, onSurfaceVariant = Muted,
            secondaryContainer = Surface2, onSecondaryContainer = Primary,
            surfaceContainerHigh = Surface1, surfaceContainer = Surface1,
            outline = Outline, outlineVariant = Outline,
            inverseSurface = Color(0xFFE8E3FA), inverseOnSurface = Color(0xFF1B1630), inversePrimary = Color(0xFF5A3FD0),
        ),
        content = content
    )
}

fun dueLabel(d: LocalDate): String {
    val today = LocalDate.now()
    return when (d) {
        today -> "today"
        today.plusDays(1) -> "tomorrow"
        else -> d.format(DateTimeFormatter.ofPattern("d MMM", Locale.UK))
    }
}

/** "Tue 7 Oct" style label used on due chips. */
fun dayChip(d: LocalDate): String {
    val today = LocalDate.now()
    return when (d) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        else -> if (d.year == today.year) d.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK))
        else d.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK))
    }
}

fun Modifier.hand() = pointerHoverIcon(PointerIcon.Hand)

@Composable
fun H1(text: String, size: Int = 30) = Text(text, fontSize = size.sp, fontWeight = FontWeight.Bold, color = TextMain)

@Composable
fun Sub(text: String, modifier: Modifier = Modifier) = Text(text, color = Muted, fontSize = 13.sp, modifier = modifier)

@Composable
fun SmallLabel(text: String, modifier: Modifier = Modifier) =
    Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Primary, modifier = modifier.padding(bottom = 6.dp))

@Composable
fun GroupLabel(text: String, color: Color = Muted) =
    Text(text.uppercase(Locale.UK), fontSize = 12.sp, letterSpacing = 1.2.sp, color = color, fontWeight = FontWeight.Bold)

/** Little count badge next to a group heading or sidebar item. */
@Composable
fun Count(n: Int, warn: Boolean = false, text: String = "$n") = Text(
    text, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (warn) Warn else Primary,
    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (warn) WarnTint else PurpleTint)
        .padding(horizontal = 7.dp, vertical = 1.dp)
)

/** Outlined chip; purple when [selected]. */
@Composable
fun Chip(
    text: String, selected: Boolean = false, modifier: Modifier = Modifier, small: Boolean = false,
    color: Color? = null, onClick: () -> Unit
) {
    val shape = RoundedCornerShape(12.dp)
    Text(
        text, fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        color = color ?: if (selected) Primary else TextMain,
        modifier = modifier.clip(shape).background(if (selected) Surface2 else Color.Transparent)
            .border(1.5.dp, if (selected) Primary else Outline, shape).hand().clickable(onClick = onClick)
            .padding(horizontal = if (small) 11.dp else 14.dp, vertical = if (small) 5.dp else 7.dp)
    )
}

@Composable
fun PrimaryButton(
    text: String, modifier: Modifier = Modifier, enabled: Boolean = true, hint: String? = null, onClick: () -> Unit
) {
    Row(
        modifier.clip(CircleShape).background(if (enabled) Primary else Outline)
            .then(if (enabled) Modifier.hand().clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
    ) {
        Text(text, color = if (enabled) OnPrimary else Muted, fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
        if (hint != null) Text(hint, color = OnPrimary.copy(alpha = .6f), fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
    }
}

@Composable
fun TextAction(text: String, color: Color = Primary, modifier: Modifier = Modifier, onClick: () -> Unit) =
    Text(text, color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        modifier = modifier.clip(RoundedCornerShape(8.dp)).hand().clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 4.dp))

/** A keyboard key, like the ones in the mockups. */
@Composable
fun Kbd(text: String) = Text(
    text, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = TextMain,
    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Surface1).border(1.dp, Outline, RoundedCornerShape(6.dp))
        .padding(horizontal = 5.dp, vertical = 1.dp)
)

/** Segmented switch: Unread | Read | Archived, Task | Link. */
@Composable
fun Pill(options: List<String>, selected: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    Row(modifier.clip(CircleShape).background(Surface1).padding(4.dp)) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier.weight(1f).clip(CircleShape).background(if (on) Primary else Color.Transparent)
                    .hand().clickable { onSelect(i) }.padding(vertical = 8.dp, horizontal = 12.dp),
                contentAlignment = Alignment.Center
            ) { Text(label, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = if (on) OnPrimary else Muted) }
        }
    }
}

/** Single- or multi-line text box in the app's style, with a placeholder. */
@Composable
fun Field(
    value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, placeholder: String = "",
    singleLine: Boolean = true, fontSize: Int = 14, shape: Shape = RoundedCornerShape(12.dp), focusBorder: Boolean = true,
    minLines: Int = 1,
) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    BasicTextField(
        value, onChange, singleLine = singleLine, minLines = minLines, interactionSource = source,
        textStyle = TextStyle(color = TextMain, fontSize = fontSize.sp, lineHeight = (fontSize * 1.45).sp),
        cursorBrush = SolidColor(Primary),
        modifier = modifier,
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth().clip(shape).background(Surface1)
                    .border(1.5.dp, if (focused && focusBorder) Primary else Outline, shape)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                if (value.isEmpty()) Text(placeholder, color = Muted, fontSize = fontSize.sp)
                inner()
            }
        }
    )
}

@Composable
fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = Surface1, unfocusedContainerColor = Surface1,
    focusedBorderColor = Primary, unfocusedBorderColor = Outline,
    focusedLabelColor = Primary, unfocusedLabelColor = Muted, cursorColor = Primary
)

/** Row that shows [hover] content only while the mouse is over it. */
@Composable
fun HoverRow(modifier: Modifier = Modifier, content: @Composable (hovered: Boolean) -> Unit) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Box(modifier.hoverable(source)) { content(hovered) }
}

/** Calendar dialog. [onPick] gets the chosen date, or null for "No date". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PickDateDialog(initial: LocalDate?, onPick: (LocalDate?) -> Unit, onDismiss: () -> Unit) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = (initial ?: LocalDate.now()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                onPick(state.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() })
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = { onPick(null) }) { Text("No date") } }
    ) { DatePicker(state) }
}

@Composable
fun Card(modifier: Modifier = Modifier, selected: Boolean = false, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier.fillMaxWidth().clip(shape).background(if (selected) Surface2 else Surface1)
            .then(if (selected) Modifier.border(1.5.dp, Primary, shape) else Modifier)
    ) { content() }
}
