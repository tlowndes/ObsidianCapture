package uk.tomlowndes.capture

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val OWNER = "Tom"

/** Energy chip on Flight Deck. Low brings Stuck? to the top and short tasks; High shows 3 top tasks. */
enum class Energy(val label: String) { LOW("Low"), MED("Med"), HIGH("High") }

internal fun greeting(now: LocalTime = LocalTime.now()) = when {
    now.hour < 12 -> "good morning"
    now.hour < 18 -> "good afternoon"
    else -> "good evening"
}

/** Flight Deck home. Cards arrive in the home-cards step; for now the header. */
@Composable
fun DeckScreen(vault: Uri?, prefs: AppPrefs, snack: SnackbarHostState, onOpenSearch: () -> Unit, onOpenSettings: () -> Unit) {
    var refresh by remember { mutableIntStateOf(0) }
    var energy by remember { mutableStateOf(prefs.energy) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        DeckHeader(energy, onOpenSearch = onOpenSearch, onRefresh = { refresh++ }, onOpenSettings = onOpenSettings)
        EnergyChip(energy) { energy = it; prefs.energy = it }
    }
}

@Composable
private fun DeckHeader(energy: Energy, onOpenSearch: () -> Unit, onRefresh: () -> Unit, onOpenSettings: () -> Unit) {
    val today = LocalDate.now()
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text("Flight Deck", fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp)
        Row {
            IconButton(onClick = onOpenSearch) { Icon(Icons.Outlined.Search, "Search", tint = Muted) }
            IconButton(onClick = onRefresh) { Icon(Icons.Outlined.Refresh, "Refresh", tint = Muted) }
            IconButton(onClick = onOpenSettings) { Icon(Icons.Outlined.Settings, "Settings", tint = Muted) }
        }
    }
    Text(
        buildAnnotatedString {
            append("${today.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK))} · ${greeting()}, $OWNER")
            if (energy == Energy.LOW) {
                append(" · ")
                withStyle(SpanStyle(color = Amber)) { append("energy low") }
            }
        },
        color = Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
        modifier = Modifier.padding(top = 2.dp, bottom = 10.dp)
    )
}

@Composable
private fun EnergyChip(selected: Energy, onSelect: (Energy) -> Unit) {
    Row(
        Modifier.padding(bottom = 12.dp).clip(CircleShape).background(Surface1)
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
