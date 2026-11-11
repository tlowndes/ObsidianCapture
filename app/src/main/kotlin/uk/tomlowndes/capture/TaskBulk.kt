package uk.tomlowndes.capture

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A bulk action on the selected tasks (multiselect-swipes-v1). */
internal enum class BulkAction(val icon: String, val label: String, val colour: Color = TextMain) {
    TODAY("☀", "Today"), TOMORROW("→", "Tomorrow"), DATE("📅", "Date"), FLAG("!", "Flag"),
    TAG("#", "Tag"), DONE("✓", "Done"), REMOVE("🗑", "Remove", Warn)
}

/** Top of the Tasks tab in select mode: ✕, "N selected" and Select all. */
@Composable
internal fun SelectBar(count: Int, onClose: () -> Unit, onAll: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier.fillMaxWidth().padding(top = 12.dp, bottom = 10.dp).clip(shape).background(PrimaryTint).border(1.dp, NextBorder, shape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("✕", fontSize = 18.sp, color = Muted, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClose).padding(4.dp))
        Text("$count selected", fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text("Select all", fontSize = 13.sp, color = Primary, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onAll).padding(4.dp))
    }
}

/** The floating bar at the bottom in select mode: each action applies to every selected task. */
@Composable
internal fun BulkActionBar(modifier: Modifier = Modifier, onAction: (BulkAction) -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier.fillMaxWidth().padding(bottom = 12.dp).shadow(12.dp, shape).clip(shape).background(Surface2).border(1.dp, Outline, shape)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceAround
    ) {
        BulkAction.values().forEach { a ->
            Column(
                Modifier.width(48.dp).clip(RoundedCornerShape(10.dp)).clickable { onAction(a) }.padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(a.icon, fontSize = 18.sp, color = if (a == BulkAction.REMOVE) Warn else TextMain)
                Text(a.label, fontSize = 10.5.sp, color = Muted, maxLines = 1)
            }
        }
    }
}

/** Pick a priority for [count] tasks (bulk Flag, or the "Set priority" swipe). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun PrioritySheet(count: Int, current: Priority?, onPick: (Priority) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Bg) {
        Column(Modifier.padding(start = 18.dp, end = 18.dp, bottom = 28.dp)) {
            Text(if (count == 1) "Priority" else "Priority for $count tasks", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Priority.CHOICES.forEach { p ->
                    AppChip(current == p, { onPick(p) }, {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            PriorityFlag(p)
                            Text(if (p == Priority.NONE) "None" else p.title)
                        }
                    })
                }
            }
        }
    }
}

/** Pick a tag to add to [count] tasks: the same Tag row as Capture, where the first tap applies it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BulkTagSheet(count: Int, data: TagData, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Bg) {
        Column(Modifier.padding(start = 18.dp, end = 18.dp, bottom = 28.dp)) {
            Text("Tag $count task${if (count == 1) "" else "s"}", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            TagRow(data, emptyList()) { tag -> onPick(tag) }
        }
    }
}

/** Settings › Swipes: pick what one swipe does. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SwipeChoiceSheet(title: String, current: SwipeChoice, onPick: (SwipeChoice) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Bg) {
        Column(Modifier.padding(start = 18.dp, end = 18.dp, bottom = 28.dp)) {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 8.dp))
            SwipeChoice.values().forEach { c ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onPick(c) }.padding(vertical = 9.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        c.icon, fontSize = 14.sp, color = c.colour,
                        modifier = Modifier.clip(RoundedCornerShape(9.dp)).background(c.colour.copy(alpha = 0.16f)).padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                    Text(c.title, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    SelectMark(c == current, { onPick(c) }, 18.dp)
                }
            }
        }
    }
}
