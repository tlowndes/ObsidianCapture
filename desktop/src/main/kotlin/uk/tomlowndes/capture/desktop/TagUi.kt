package uk.tomlowndes.capture.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uk.tomlowndes.capture.TagKind
import uk.tomlowndes.capture.Tags

/** Work amber, Personal green, the rest purple; with the tinted fill a selected chip uses. Matches the phone. */
fun tagColour(tag: String): Pair<Color, Color> = when (Tags.kind(tag)) {
    TagKind.WORK -> Amber to AmberTint
    TagKind.PERSONAL -> TaskGreen to TaskTint
    TagKind.OTHER -> if (tag == "#bill") Amber to AmberTint else Purple to TagTint
}

/** A tag chip with its coloured dot; tinted when [selected]. */
@Composable
fun TagChip(tag: String, selected: Boolean, count: Int? = null, onClick: () -> Unit) {
    val (fg, bg) = tagColour(tag)
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.clip(shape).background(if (selected) bg else Color.Transparent)
            .border(1.5.dp, if (selected) fg else Outline, shape).hand().clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(fg))
        Text(Tags.label(tag) + (count?.let { " $it" } ?: ""), fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, color = if (selected) fg else TextMain)
    }
}

/** Small mono tag label on a task row. */
@Composable
fun TagMini(tag: String) {
    val (fg, bg) = tagColour(tag)
    Text(
        if (Tags.kind(tag) == TagKind.OTHER) tag else tag.removePrefix("#"),
        fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = fg, maxLines = 1,
        modifier = Modifier.padding(start = 6.dp).clip(CircleShape).background(bg).padding(horizontal = 7.dp, vertical = 1.dp)
    )
}

/** Work, Personal, the phone's pinned tags and any tag already [on]; then ＋ Tag, which [onAdd] handles. */
@Composable
fun TagRow(pinned: List<String>, on: List<String>, onToggle: (String) -> Unit, onAdd: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Tags.row(pinned, on).take(8).forEach { tag -> TagChip(tag, tag in on) { onToggle(tag) } }
        Text(
            "＋ Tag", fontSize = 13.sp, color = Muted,
            modifier = Modifier.clip(RoundedCornerShape(12.dp)).border(1.5.dp, Outline, RoundedCornerShape(12.dp))
                .hand().clickable(onClick = onAdd).padding(horizontal = 11.dp, vertical = 5.dp)
        )
    }
}
