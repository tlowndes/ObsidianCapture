package uk.tomlowndes.capture

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Where each quiet card still lives once it's off the Deck (quiet-day-v1). */
private fun HomeCard.stillAt(): String? = when (this) {
    HomeCard.PROJECTS -> "Still in Vault › Projects"
    HomeCard.JOBS -> "The Jobs screen stays"
    HomeCard.INBOX -> "Proton Mail stays your inbox"
    else -> null
}

/**
 * A quieter Deck? (quiet-day-v1): asked once after the update when quiet cards are still on. [cards] are the
 * ones on now. Every switch starts off; [onQuiet] gets the cards to keep. [onKeep] leaves the Deck as it is.
 * Either answer is final and shared with the PC through the vault. Swiping the sheet away asks again next time.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QuietDeckSheet(cards: List<HomeCard>, onQuiet: (Set<HomeCard>) -> Unit, onKeep: () -> Unit, onLater: () -> Unit) {
    var keep by remember { mutableStateOf(emptySet<HomeCard>()) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onLater, sheetState = sheet, containerColor = Surface1) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            Text("A quieter Deck?", fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Text("These cards are switched off for new installs now. Pick any you want to keep. Their notes stay in the vault.",
                fontSize = 13.sp, color = Muted, lineHeight = 19.sp, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))
            cards.forEach { c ->
                HorizontalDivider(color = Outline)
                Row(Modifier.fillMaxWidth().clickable { keep = if (c in keep) keep - c else keep + c }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(c.label, fontSize = 15.sp)
                        c.stillAt()?.let { Text(it, fontSize = 12.sp, color = Muted) }
                    }
                    Switch(checked = c in keep, onCheckedChange = { on -> keep = if (on) keep + c else keep - c },
                        colors = SwitchDefaults.colors(checkedTrackColor = Primary, checkedThumbColor = OnPrimary))
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Keep it as it is", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(99.dp)).border(1.dp, Outline, RoundedCornerShape(99.dp))
                        .clickable(onClick = onKeep).padding(vertical = 13.dp))
                Text("Make it quieter", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = OnPrimary, textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(99.dp)).background(Primary)
                        .clickable { onQuiet(keep) }.padding(vertical = 13.dp))
            }
        }
    }
}
