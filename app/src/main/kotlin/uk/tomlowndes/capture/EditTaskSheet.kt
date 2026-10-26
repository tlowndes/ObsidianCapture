package uk.tomlowndes.capture

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate

/** Edit the wording, due date, estimate and recurrence of a task already in the vault. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditTaskSheet(
    t: TaskItem,
    onSave: (text: String, due: LocalDate?, recurrence: String?, minutes: Int?) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember(t.raw) { mutableStateOf(t.text) }
    var due by remember(t.raw) { mutableStateOf(t.due) }
    var minutes by remember(t.raw) { mutableStateOf(Deck.minutes(t)) }
    var repeat by remember(t.raw) { mutableStateOf(t.recurrence) }
    var picking by remember { mutableStateOf(false) }
    val today = LocalDate.now()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Surface1) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 28.dp)
        ) {
            Text("Edit task", fontSize = 19.sp, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp), shape = RoundedCornerShape(20.dp),
                placeholder = { Text("What needs doing?") }, minLines = 2,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                colors = fieldColors()
            )
            val custom = due != null && due != today && due != today.plusDays(1)
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppChip(due == today, { due = if (due == today) null else today }, { Text("Today") })
                AppChip(due == today.plusDays(1), { due = if (due == today.plusDays(1)) null else today.plusDays(1) }, { Text("Tomorrow") })
                AppChip(custom, { picking = true }, { Text(if (custom) dueLabel(due!!) else "Pick date") })
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ESTIMATES.forEach { m ->
                    AppChip(minutes == m, { minutes = if (minutes == m) null else m }, { Text(TaskRules.minutesLabel(m)) })
                }
            }
            RepeatPicker(repeat, due, inlineCustom = true) { repeat = it }
            Text(
                "Remove task", color = Warn, fontSize = 14.sp, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp).fillMaxWidth().clip(CircleShape).clickable(onClick = onRemove).padding(vertical = 8.dp)
            )
            Text(
                "Save", color = OnPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp).fillMaxWidth().clip(CircleShape)
                    .background(if (text.isBlank()) Primary.copy(alpha = 0.4f) else Primary)
                    .clickable(enabled = text.isNotBlank()) { onSave(text.trim(), due, repeat, minutes) }
                    .padding(vertical = 14.dp)
            )
        }
    }
    if (picking) PickDateDialog(due, onPick = { due = it; picking = false }, onDismiss = { picking = false })
}
