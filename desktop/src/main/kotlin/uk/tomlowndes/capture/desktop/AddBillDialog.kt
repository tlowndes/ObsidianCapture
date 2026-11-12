package uk.tomlowndes.capture.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uk.tomlowndes.capture.BillRepeat
import uk.tomlowndes.capture.BillSuggestion
import uk.tomlowndes.capture.Bills
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Add bill (add-bill-v1), the Windows dialog: same fields as the phone sheet. [onAdd] gets the task text and due date. */
@Composable
fun AddBillDialog(suggestions: List<BillSuggestion>, onAdd: (String, LocalDate) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var due by remember { mutableStateOf(LocalDate.now()) }
    var repeat by remember { mutableStateOf(BillRepeat.MONTHLY) }
    var picking by remember { mutableStateOf(false) }
    val money = Bills.amount(amount)
    val ok = name.isNotBlank() && (amount.isBlank() || money != null)
    DialogCard("Add bill", onDismiss) {
        Column(Modifier.fillMaxWidth()) {
            SmallLabel("WHAT")
            Field(name, { name = it }, Modifier.fillMaxWidth().padding(top = 6.dp), "e.g. Car insurance")
            if (suggestions.isNotEmpty()) Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                suggestions.take(4).forEach { s -> Chip(s.name, small = true) { name = s.name; s.amount?.let { amount = it.toPlainString() } } }
            }
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    SmallLabel("AMOUNT (£)")
                    Field(amount, { amount = it }, Modifier.fillMaxWidth().padding(top = 6.dp), "0.00")
                    if (amount.isNotBlank() && money == null) Text("Numbers only, like 38.50", color = Warn, fontSize = 12.sp)
                }
                Column(Modifier.weight(1f)) {
                    SmallLabel("DUE")
                    Chip("📅 " + due.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)), modifier = Modifier.padding(top = 6.dp)) { picking = true }
                }
            }
            SmallLabel("REPEATS", Modifier.padding(top = 12.dp))
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BillRepeat.values().forEach { r -> Chip(r.label, repeat == r) { repeat = r } }
            }
            Text("Saves to Quick Task:\n" + Bills.preview(name.ifBlank { "…" }, money, repeat, due), fontSize = 11.sp,
                fontFamily = FontFamily.Monospace, color = Muted, modifier = Modifier.padding(top = 14.dp, bottom = 14.dp))
            DialogButtons(onDismiss) { PrimaryButton("Add bill", enabled = ok) { onAdd(Bills.text(name, money, repeat), due) } }
        }
    }
    if (picking) PickDateDialog(due, onPick = { d -> if (d != null) due = d; picking = false }, onDismiss = { picking = false })
}
