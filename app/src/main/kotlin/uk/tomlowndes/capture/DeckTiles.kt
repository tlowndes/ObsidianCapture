package uk.tomlowndes.capture

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The Hangar tools shown as tiles in the Deck's More row. Ticket Rail by default (add-to-more-v1). */
internal fun deckTiles(prefs: AppPrefs): List<HangarTool> =
    prefs.deckTiles?.let { Hangar.parse(it) } ?: listOf(HangarTool.TICKET_RAIL)

/** Add to More (add-to-more-v1): tap a Hangar tool to put it on the Deck as a tile, tap again to take it off. */
@Composable
fun AddTilesSheet(prefs: AppPrefs, onDone: () -> Unit) {
    var chosen by remember { mutableStateOf(deckTiles(prefs)) }
    val all = Hangar.parse(prefs.hangarTools).orEmpty().filter { it.ready }
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Add to More", fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = onDone) { Text("Done", color = Primary) }
        }
        Text("Tap a tool to put it on the Deck. Tap again to take it off.", fontSize = 13.sp, color = Muted, modifier = Modifier.padding(bottom = 12.dp))
        all.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { t ->
                    val on = t in chosen
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(if (on) PrimaryTint else Surface1)
                            .border(1.dp, if (on) Primary else Outline, RoundedCornerShape(14.dp))
                            .clickable {
                                chosen = if (on) chosen - t else chosen + t
                                prefs.deckTiles = Hangar.format(chosen)
                            }
                            .semantics(mergeDescendants = true) { contentDescription = t.title + if (on) ", on the Deck" else ", not on the Deck" }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(t.icon, fontSize = 19.sp, color = t.colour(), modifier = Modifier.clearAndSetSemantics { })
                        Text(t.dockLabel(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 3.dp).clearAndSetSemantics { })
                    }
                }
                repeat(4 - row.size) { Box(Modifier.weight(1f)) }
            }
        }
        Text("${chosen.size} of ${all.size} on the Deck", fontSize = 12.sp, color = Muted, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 24.dp))
    }
}
