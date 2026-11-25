package uk.tomlowndes.capture

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate

/**
 * printed-marks-v1: what the phone shows about printed tickets. A task is printed when its line has a Ticket Rail
 * `🆔`; when the vault wouldn't take one, [warning] says so until the app manages it.
 */
object TicketMarks {
    /** The last print's "couldn't be marked" note, or null when every ticket got its 🆔. */
    var warning by mutableStateOf<String?>(null)

    /** After a print: [unsaved] are the tasks whose 🆔 didn't save. */
    fun printed(unsaved: List<TaskItem>) {
        warning = if (unsaved.isEmpty()) null else {
            val name = Tags.strip(unsaved.first().text)
            val more = if (unsaved.size > 1) " and ${unsaved.size - 1} more" else ""
            "Printed, but $name$more couldn't be marked in the vault yet. The app remembers it and will mark it next time it opens, so it won't print again."
        }
    }

    /** This phone's printed tickets, for "printed Mon 21:59". */
    var log by mutableStateOf<List<Printed>>(emptyList())

    /** After a fresh look at the vault: clears the warning once nothing printed is left without its 🆔. */
    fun looked(lists: TaskLists, log: List<Printed>) {
        if (lists.unmarked.isEmpty()) warning = null
        this.log = log
    }

    /** "HOM 041 · printed today 07:02" for a printed task, or just the code when this phone didn't print it. */
    fun line(t: TaskItem, today: LocalDate = LocalDate.now()): String? {
        val id = TicketRail.railId(t.raw) ?: return null
        val at = PrintLog.at(log, id)
        val early = t.due != null && t.due.isAfter(today) && at != null && at.toLocalDate().isBefore(t.due)
        return TicketRail.code(id) + (at?.let { " · " + PrintLog.words(it, today) + if (early) ", early" else "" } ?: "")
    }
}

/** The tag on a printed task: "🧾 PRINTED", or "🧾 FRI" for one printed ahead of its day. Null when [t] isn't printed. */
internal fun printedTag(t: TaskItem, today: LocalDate = LocalDate.now()): String? =
    if (TicketRail.railId(t.raw) == null) null else PrintLog.tag(t, today)

/** The paper-coloured printed tag (printed-marks-v1). */
@Composable
internal fun PrintedChip(label: String, modifier: Modifier = Modifier) {
    Text(
        label, color = PaperInk, fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, maxLines = 1,
        modifier = modifier.clip(RoundedCornerShape(5.dp)).background(Paper).padding(horizontal = 6.dp, vertical = 1.dp)
    )
}
