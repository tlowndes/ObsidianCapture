package uk.tomlowndes.capture

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Add bill (add-bill-v1): name (past bills one tap away), amount, due date and how often it repeats.
 * [onAdd] gets the task text for [VaultWriter.addTask] and the due date.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddBillSheet(suggestions: List<BillSuggestion>, onAdd: (String, LocalDate) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var due by remember { mutableStateOf(LocalDate.now()) }
    var repeat by remember { mutableStateOf(BillRepeat.MONTHLY) }
    var picking by remember { mutableStateOf(false) }
    val money: BigDecimal? = Bills.amount(amount)
    val ok = name.isNotBlank() && (amount.isBlank() || money != null)
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Surface1) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            Text("Add bill", fontSize = 19.sp, fontWeight = FontWeight.Bold)

            Label("What")
            OutlinedTextField(
                value = name, onValueChange = { name = it }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), colors = fieldColors(),
                placeholder = { Text("e.g. Car insurance") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
            if (suggestions.isNotEmpty()) Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                suggestions.forEach { s ->
                    Text(s.name, fontSize = 12.sp, color = Muted,
                        modifier = Modifier.clip(RoundedCornerShape(99.dp)).border(1.dp, Outline, RoundedCornerShape(99.dp))
                            .clickable { name = s.name; s.amount?.let { amount = it.toPlainString() } }
                            .padding(horizontal = 10.dp, vertical = 4.dp))
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    Label("Amount")
                    OutlinedTextField(
                        value = amount, onValueChange = { amount = it }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), colors = fieldColors(),
                        prefix = { Text("£ ") }, placeholder = { Text("0.00") },
                        isError = amount.isNotBlank() && money == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                }
                Column(Modifier.weight(1f)) {
                    Label("Due")
                    Text("📅 " + dueLabel(due), fontSize = 15.sp,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Surface1)
                            .border(1.dp, Outline, RoundedCornerShape(14.dp)).clickable { picking = true }.padding(horizontal = 14.dp, vertical = 16.dp))
                }
            }

            Label("Repeats")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BillRepeat.values().forEach { r -> AppChip(repeat == r, { repeat = r }, { Text(r.label, fontSize = 13.sp) }) }
            }

            Text("Saves to Quick Task:\n" + Bills.preview(name.ifBlank { "…" }, money, repeat, due),
                fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Muted, lineHeight = 16.sp,
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp).clip(RoundedCornerShape(10.dp)).background(Surface2).padding(10.dp))

            Text("Add bill", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = if (ok) OnPrimary else Muted,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(12.dp))
                    .background(if (ok) Amber else Surface2).clickable(enabled = ok) { onAdd(Bills.text(name, money, repeat), due) }
                    .padding(vertical = 14.dp))
        }
    }
    if (picking) PickDateDialog(due, onPick = { d -> if (d != null) due = d; picking = false }, onDismiss = { picking = false })
}

@Composable
private fun Label(text: String) {
    Text(text.uppercase(java.util.Locale.UK), fontSize = 11.sp, letterSpacing = 1.2.sp, color = Muted, fontFamily = FontFamily.Monospace,
        modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
}
