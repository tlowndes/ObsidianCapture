package uk.tomlowndes.capture

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

// ---- design tokens (mirror design/ui.css) ----
internal val Bg = Color(0xFF13111A)
internal val Surface1 = Color(0xFF1D1A28)
internal val Surface2 = Color(0xFF262236)
internal val Outline = Color(0xFF37314D)
internal val Primary = Color(0xFF8B6CFF)
internal val OnPrimary = Color(0xFF17102E)
internal val TextMain = Color(0xFFECE9F6)
internal val Muted = Color(0xFF9A94B3)
internal val Warn = Color(0xFFFF8A80)
internal val TaskTint = Color(0xFF16352C)
internal val LinkTint = Color(0xFF142C40)

@Composable
fun CaptureTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Primary, onPrimary = OnPrimary,
            background = Bg, onBackground = TextMain,
            surface = Bg, onSurface = TextMain,
            surfaceVariant = Surface1, onSurfaceVariant = Muted,
            secondaryContainer = Surface2, onSecondaryContainer = Primary,
            outline = Outline, outlineVariant = Outline,
            inverseSurface = Color(0xFFE8E3FA), inverseOnSurface = Color(0xFF1B1630),
        ),
        content = content
    )
}

internal fun dueLabel(d: LocalDate): String {
    val today = LocalDate.now()
    return when (d) {
        today -> "today"
        today.plusDays(1) -> "tomorrow"
        else -> d.format(DateTimeFormatter.ofPattern("d MMM", Locale.UK))
    }
}

/** "Tue 7 Oct" style label used on due chips. */
internal fun dayChip(d: LocalDate): String {
    val today = LocalDate.now()
    return when (d) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        else -> d.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK))
    }
}

@Composable
internal fun Logo() {
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(13.dp))
            .background(Brush.linearGradient(listOf(Color(0xFFA58BFF), Color(0xFF5B3FD6)))),
        contentAlignment = Alignment.Center
    ) {
        Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.requiredSize(70.dp))
    }
}

/** Pill-shaped segmented switch used for Task|Read later and Morning|Evening|All. */
@Composable
internal fun PillSwitch(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(CircleShape).background(Surface1).padding(4.dp)) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier.weight(1f).clip(CircleShape)
                    .background(if (on) Primary else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(label, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = if (on) OnPrimary else Muted)
            }
        }
    }
}

@Composable
internal fun SmallLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Primary, modifier = modifier.padding(bottom = 6.dp))
}

@Composable
internal fun GroupLabel(text: String, color: Color = Muted) {
    Text(text.uppercase(Locale.UK), fontSize = 12.sp, letterSpacing = 1.2.sp, color = color, fontWeight = FontWeight.SemiBold)
}

@Composable
internal fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = Surface1, unfocusedContainerColor = Surface1,
    focusedBorderColor = Primary, unfocusedBorderColor = Outline,
    focusedLabelColor = Primary, unfocusedLabelColor = Muted, cursorColor = Primary
)

@Composable
internal fun ScreenTitle(title: String, sub: String? = null, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        trailing()
    }
    if (sub != null) Text(sub, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 3.dp, bottom = 12.dp))
}

/** Calendar dialog. [onPick] gets the chosen date, or null for "No date". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PickDateDialog(initial: LocalDate?, onPick: (LocalDate?) -> Unit, onDismiss: () -> Unit) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli()
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val picked = state.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                onPick(picked)
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = { onPick(null) }) { Text("No date") } }
    ) { DatePicker(state) }
}
