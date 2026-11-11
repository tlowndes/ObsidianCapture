package uk.tomlowndes.capture.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uk.tomlowndes.capture.Priority
import uk.tomlowndes.capture.Tags

/** A bulk action on the selected tasks (multiselect-swipes-v1, Windows). */
enum class BulkAction(val icon: String, val label: String) {
    TODAY("☀", "Today"), TOMORROW("→", "Tomorrow"), DATE("📅", "Date"), FLAG("!", "Flag"),
    TAG("#", "Tag"), DONE("✓", "Done"), REMOVE("🗑", "Remove")
}

/** The floating bar while tasks are selected: count, the actions, Select all and ✕. */
@Composable
fun BulkBar(count: Int, onAction: (BulkAction) -> Unit, onAll: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier.shadow(14.dp, shape).clip(shape).background(Surface2).border(1.dp, Outline, shape)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("✕", fontSize = 15.sp, color = Muted, modifier = Modifier.clip(RoundedCornerShape(8.dp)).hand().clickable(onClick = onClose).padding(6.dp))
        Text("$count selected", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextMain, modifier = Modifier.padding(end = 8.dp))
        BulkAction.values().forEach { a ->
            Column(
                Modifier.width(62.dp).clip(RoundedCornerShape(10.dp)).hand().clickable { onAction(a) }.padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(a.icon, fontSize = 16.sp, color = if (a == BulkAction.REMOVE) Warn else TextMain)
                Text(a.label, fontSize = 11.sp, color = Muted, maxLines = 1)
            }
        }
        Text("Select all", fontSize = 13.sp, color = Primary, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(8.dp)).hand().clickable(onClick = onAll).padding(6.dp))
    }
}

/** The round select mark that stands in for the tick circle while selecting. */
@Composable
fun SelectMark(on: Boolean, size: Int = 20, onClick: () -> Unit) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(if (on) Primary else Color.Transparent)
            .border(2.dp, if (on) Primary else Muted, CircleShape).hand().clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { if (on) Text("✓", fontSize = 11.sp, color = OnPrimary, fontWeight = FontWeight.Bold) }
}

/** Flag [count] tasks at once. */
@Composable
fun BulkPriorityDialog(count: Int, onPick: (Priority) -> Unit, onDismiss: () -> Unit) {
    DialogCard("Priority for $count task${if (count == 1) "" else "s"}", onDismiss) {
        PriorityRow(Priority.NONE) { onPick(it) }
        Box(Modifier.padding(top = 14.dp)) { DialogButtons(onDismiss) {} }
    }
}

/** Add one tag to [count] tasks: pinned ones as chips, or type a new one. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BulkTagDialog(count: Int, pinned: List<String>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    DialogCard("Tag $count task${if (count == 1) "" else "s"}", onDismiss) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            (listOf(Tags.WORK, Tags.PERSONAL) + pinned.filter { it !in Tags.BUILT_IN }).forEach { tag ->
                TagChip(tag, false) { onPick(tag) }
            }
        }
        Field(typed, { typed = it }, Modifier.padding(top = 12.dp).width(476.dp), "#new-tag")
        Box(Modifier.padding(top = 14.dp)) {
            val norm = Tags.norm(typed)
            DialogButtons(onDismiss) { PrimaryButton("Add tag", enabled = norm != null) { norm?.let(onPick) } }
        }
    }
}
