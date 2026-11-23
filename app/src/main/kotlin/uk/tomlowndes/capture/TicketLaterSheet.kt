package uk.tomlowndes.capture

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DayMonth = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)

/**
 * New ticket for later (ticket-rail-future-v1): type a job, pick its day and tag, then Print now (saved to Quick Task
 * with its 📅, printed, given its 🆔) or Save only. [busy] greys the buttons while it prints.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun TicketLaterSheet(busy: Boolean, onDone: (text: String, tag: String?, day: LocalDate, print: Boolean) -> Unit, onDismiss: () -> Unit) {
    val today = remember { LocalDate.now() }
    val chips = remember { TicketRail.dayChips(today) }
    var text by remember { mutableStateOf("") }
    var day by remember { mutableStateOf(chips.first()) }
    var tag by remember { mutableStateOf<String?>(TicketRail.JOB_TAGS.first()) }
    var picking by remember { mutableStateOf(false) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val ready = text.isNotBlank() && !busy

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Surface2) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 18.dp, end = 18.dp, bottom = 24.dp)) {
            Text("New ticket for later", fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Text("Saved as a task in Quick Task, then printed now.", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp, bottom = 12.dp))
            OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), placeholder = { Text("What's the job?") },
                singleLine = true, colors = fieldColors(), shape = RoundedCornerShape(12.dp))

            LaterLabel("WHEN")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                chips.forEach { d ->
                    val label = TicketRail.chipLabel(d, today).let { if (d == day && d != chips.first()) "$it ${d.dayOfMonth}" else it }
                    PaperChip(label, d == day) { day = d }
                }
                val custom = day !in chips
                PaperChip(if (custom) "📅 ${day.format(DayMonth)}" else "📅 Pick", custom) { picking = true }
            }

            LaterLabel("TAG")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TicketRail.JOB_TAGS.forEach { t -> PaperChip(t, tag == t) { tag = t } }
                PaperChip("None", tag == null) { tag = null }
            }

            Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                PaperPreview(text, tag, day, today)
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Save only", color = if (ready) TextMain else Muted, fontSize = 14.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).clip(CircleShape).border(1.dp, Outline, CircleShape)
                        .clickable(enabled = ready) { onDone(text, tag, day, false) }.padding(vertical = 13.dp))
                Text(if (busy) "Printing…" else "Print now", color = PaperInk, fontSize = 14.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1.6f).clip(CircleShape).background(if (ready) Paper else Paper.copy(alpha = 0.5f))
                        .clickable(enabled = ready) { onDone(text, tag, day, true) }.padding(vertical = 13.dp))
            }
        }
    }
    if (picking) PickDateDialog(day, { d -> picking = false; if (d != null && d.isAfter(today)) day = d }, { picking = false })
}

@Composable
private fun LaterLabel(s: String) =
    Text(s, color = Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp, modifier = Modifier.padding(start = 2.dp, top = 14.dp, bottom = 6.dp))

@Composable
internal fun PaperChip(label: String, on: Boolean, onClick: () -> Unit) {
    Text(label, fontSize = 13.sp, color = if (on) Paper else Muted, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier.clip(CircleShape).background(if (on) PaperTint else Color.Transparent)
            .border(1.dp, if (on) Paper else Outline, CircleShape).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp))
}

/** A small copy of the ticket as it'll print. The number is chosen at print time, so it shows "···". */
@Composable
private fun PaperPreview(text: String, tag: String?, day: LocalDate, today: LocalDate) {
    val job = TicketRail.jobText(text.ifBlank { "Your job" }, tag)
    val t = TicketRail.ticket(TaskItem("", job, day), "tsk000", today, java.time.LocalTime.now())
    val prefix = TicketRail.prefix(job).uppercase(Locale.UK)
    val ink = Color(0xFF111111)
    Column(Modifier.width(200.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFFF4F1EA)).padding(12.dp)) {
        Text("$prefix ···", color = ink, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 22.sp)
        Text(listOf(t.slot, t.slotNote, t.tag).filter { it.isNotBlank() }.joinToString(" · "), color = ink, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        if (t.early.isNotBlank()) Text(t.early, color = Color(0xFFF4F1EA), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 10.sp,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).background(ink).padding(vertical = 3.dp))
        Text("TASK", color = Color(0xFF555555), fontFamily = FontFamily.Monospace, fontSize = 8.sp)
        Text(t.text, color = ink, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Text("Inbox / Quick Task", color = Color(0xFF555555), fontFamily = FontFamily.Monospace, fontSize = 8.5.sp)
        Text("PRT ${t.printedAt}", color = Color(0xFF555555), fontFamily = FontFamily.Monospace, fontSize = 8.sp, modifier = Modifier.padding(top = 8.dp))
    }
}
