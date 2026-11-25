package uk.tomlowndes.capture

import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** Which item the edit sheet is for: [at] is (section, index) for an existing one, null for a new one. */
private data class PackEdit(val at: Pair<Int, Int>?, val item: PackItem?, val section: String)

/**
 * A packing or check list on the Kits page (packing-list-v4). Tap to tick, swipe left to skip for this pack,
 * long-press to edit. Packing has the nights stepper, Print and Repack; Car and Go bag have Mark checked.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PackListView(p: PackList, lists: List<PackList>, snack: SnackbarHostState, onOpen: (String) -> Unit, save: (List<PackList>) -> Unit) {
    val today = LocalDate.now()
    var edit by remember { mutableStateOf<PackEdit?>(null) }
    var repacking by remember { mutableStateOf(false) }
    var printing by remember { mutableStateOf(false) }
    val count = PackLists.count(p, lists)

    // Header: where it lives, nights, and progress.
    p.inside?.let { Text("Inside the $it", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)) }
    if (p.kind == PackKind.PACK) NightsRow("Staying", p.nights) { save(listOf(p.copy(nights = it))) }
    val stamp = p.checked?.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK))
    val head = when {
        count.full -> "All ${count.total} ${if (p.kind == PackKind.PACK) "packed" else "checked"} ✓"
        p.kind == PackKind.PACK -> "${count.done} of ${count.total} packed" + (stamp?.let { " · last repack $it" } ?: "")
        else -> "${count.done} of ${count.total} checked · " + (stamp?.let { "last checked $it" } ?: "not checked yet")
    }
    Text(head, color = if (count.full) Green else if (PackLists.due(p, today)) Warn else Amber, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp))
    Box(Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 8.dp).height(6.dp).clip(CircleShape).background(Surface2)) {
        Box(Modifier.fillMaxWidth(if (count.total == 0) 0f else count.done / count.total.toFloat()).height(6.dp).clip(CircleShape).background(Green))
    }

    p.sections.forEachIndexed { si, s ->
        val c = PackLists.count(s, lists)
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 10.dp, bottom = 6.dp)) {
            GroupLabel(s.title)
            Box(Modifier.weight(1f))
            Text(c.toString(), color = Green, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        ListCard {
            s.items.forEachIndexed { ii, item ->
                if (ii > 0) HorizontalDivider(color = Outline)
                val child = PackLists.linked(item, lists)
                PackRow(item, p.nights, done = PackLists.isDone(item, lists), end = child?.let { "${PackLists.count(it, lists)} ›" },
                    onTap = { if (child != null) onOpen(child.name) else save(listOf(PackLists.toggle(p, si, ii))) },
                    onSkip = { save(listOf(PackLists.skip(p, si, ii))) },
                    onEdit = { edit = PackEdit(si to ii, item, s.title) })
            }
            if (s.items.isNotEmpty()) HorizontalDivider(color = Outline)
            Text("＋ Add item", color = Primary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth().clickable { edit = PackEdit(null, null, s.title) }.padding(horizontal = 16.dp, vertical = 11.dp))
        }
    }
    if (p.sections.isEmpty()) Text("＋ Add the first item", color = Primary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(4.dp).clickable { edit = PackEdit(null, null, "Things") }.padding(vertical = 10.dp))

    Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Pill("🖨 Print", false, Modifier.weight(1f)) { printing = true }
        if (p.kind == PackKind.PACK) Pill("↺ Repack", true, Modifier.weight(1f)) { repacking = true }
        else Pill("✓ Mark checked", true, Modifier.weight(1f)) { save(listOf(PackLists.markChecked(p, today))) }
    }
    Text(if (p.kind == PackKind.PACK) "Repack clears every tick for next time. Items stay." else "Mark checked clears the ticks and stamps today. Due again in ${PackLists.CHECK_EVERY_DAYS} days.",
        color = Muted, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 6.dp))

    edit?.let { e ->
        PackItemSheet(e, p.sections.map { it.title }, onDismiss = { edit = null },
            onDelete = { edit = null; e.at?.let { (s, i) -> save(listOf(PackLists.removeItem(p, s, i))) } },
            onSave = { item, section -> edit = null; save(listOf(PackLists.putItem(p, item, section, e.at))) })
    }
    if (repacking) RepackSheet(p, onDismiss = { repacking = false }) { nights, print ->
        repacking = false
        val kids = PackLists.children(p, lists).map { PackLists.repack(it, it.nights, today) }
        save(listOf(PackLists.repack(p, nights, today)) + kids)
        if (print) printing = true
    }
    if (printing) PrintListSheet(PackLists.printDoc(p, lists, today), snack) { printing = false }
}

@Composable
private fun Pill(text: String, main: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Text(text, color = if (main) Color(0xFF1B1404) else TextMain, fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
        modifier = modifier.clip(CircleShape).let { if (main) it.background(Amber) else it.border(1.dp, Outline, CircleShape) }
            .clickable(onClick = onClick).padding(vertical = 13.dp))
}

@Composable
private fun NightsRow(label: String, nights: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 4.dp).clip(RoundedCornerShape(12.dp)).background(Surface1).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("🌙  $label", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Step("−") { onChange((nights - 1).coerceAtLeast(1)) }
        Text("$nights", color = Amber, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Step("+") { onChange((nights + 1).coerceAtMost(30)) }
        Text("nights", color = Muted, fontSize = 14.sp)
    }
}

@Composable
private fun Step(s: String, onClick: () -> Unit) {
    Box(Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).border(1.dp, Outline, RoundedCornerShape(9.dp)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(s, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
}

/** One line: a tick box, the item, ×n for per-day items, and swipe left past a third of a thumb to skip. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PackRow(item: PackItem, nights: Int, done: Boolean, end: String?, onTap: () -> Unit, onSkip: () -> Unit, onEdit: () -> Unit) {
    val density = LocalDensity.current
    val limit = with(density) { 84.dp.toPx() }
    var drag by remember(item) { mutableFloatStateOf(0f) }
    Box(Modifier.fillMaxWidth()) {
        if (drag < 0f) Text(if (item.skipped) "Unskip" else "⤼ Skip", color = TextMain, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp))
        Row(
            Modifier.fillMaxWidth().offset { IntOffset(drag.roundToInt(), 0) }.background(Surface1)
                .pointerInput(item) {
                    detectHorizontalDragGestures(
                        onDragEnd = { if (drag < -limit) onSkip(); drag = 0f },
                        onDragCancel = { drag = 0f },
                    ) { change, dx -> change.consume(); drag = (drag + dx).coerceIn(-limit * 1.4f, 0f) }
                }
                .combinedClickable(onClick = onTap, onLongClick = onEdit)
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val box = RoundedCornerShape(7.dp)
            Box(Modifier.size(22.dp).clip(box).let { if (done && !item.skipped) it.background(Green) else it.border(2.dp, Outline, box) }, contentAlignment = Alignment.Center) {
                if (done && !item.skipped) Text("✓", color = Color(0xFF06210F), fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            if (item.emoji != "•") Text(item.emoji, fontSize = 15.sp)
            Text(item.label(nights), fontSize = 15.sp, modifier = Modifier.weight(1f),
                color = if (done || item.skipped) Muted else TextMain,
                fontStyle = if (item.skipped) FontStyle.Italic else FontStyle.Normal,
                textDecoration = if (done && !item.skipped) TextDecoration.LineThrough else TextDecoration.None)
            when {
                item.skipped -> Tag("skipped", Muted)
                item.perDay -> Tag("1/day", Amber)
            }
            end?.let { Text(it, color = Muted, fontSize = 12.sp) }
        }
    }
}

@Composable
private fun Tag(s: String, c: Color) = Text(s, color = c, fontSize = 10.sp,
    modifier = Modifier.border(1.dp, Outline, RoundedCornerShape(5.dp)).padding(horizontal = 5.dp, vertical = 1.dp))

/** Add or edit an item (packing-list-v3, 3b): emoji, name, type, per day + spare, skip, delete. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun PackItemSheet(e: PackEdit, sections: List<String>, onDismiss: () -> Unit, onDelete: () -> Unit, onSave: (PackItem, String) -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var emoji by remember { mutableStateOf(e.item?.emoji?.takeIf { it != "•" } ?: "") }
    var name by remember { mutableStateOf(e.item?.name ?: "") }
    var section by remember { mutableStateOf(e.section) }
    var newSection by remember { mutableStateOf("") }
    var perDay by remember { mutableStateOf(e.item?.perDay ?: false) }
    var spare by remember { mutableStateOf(e.item?.spare ?: 0) }
    var skipped by remember { mutableStateOf(e.item?.skipped ?: false) }
    val target = newSection.trim().ifEmpty { section }
    val ok = name.isNotBlank() && target.isNotBlank()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Surface1) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            Text(if (e.item == null) "Add item" else "Edit item", fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(emoji, { emoji = it.trim().take(8) }, singleLine = true, modifier = Modifier.width(72.dp),
                    shape = RoundedCornerShape(14.dp), colors = fieldColors(), placeholder = { Text("🙂") })
                OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp), colors = fieldColors(), placeholder = { Text("e.g. T-shirts") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
            }
            Text("TYPE", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 14.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                sections.forEach { s ->
                    val on = s == section && newSection.isBlank()
                    Text(s, fontSize = 13.sp, color = if (on) Primary else Muted, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(CircleShape).background(if (on) PrimaryTint else Surface1).border(1.dp, if (on) Primary else Outline, CircleShape)
                            .clickable { section = s; newSection = "" }.padding(horizontal = 12.dp, vertical = 7.dp))
                }
            }
            OutlinedTextField(newSection, { newSection = it }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                shape = RoundedCornerShape(14.dp), colors = fieldColors(), label = { Text("Or a new type") })
            ToggleRow("Per day", "×nights", perDay) { perDay = it }
            if (perDay) Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Extra spare", fontSize = 15.sp, modifier = Modifier.weight(1f))
                Step("−") { spare = (spare - 1).coerceAtLeast(0) }
                Text("+$spare", color = Amber, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Step("+") { spare = (spare + 1).coerceAtMost(9) }
            }
            if (e.item != null) ToggleRow("Skip this pack", "back on Repack", skipped) { skipped = it }
            Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (e.item != null) Text("Delete", color = Warn, fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).clip(CircleShape).border(1.dp, Outline, CircleShape).clickable(onClick = onDelete).padding(vertical = 13.dp))
                Text("Save", color = if (ok) OnPrimary else Muted, fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).clip(CircleShape).background(if (ok) Primary else Surface2).clickable(enabled = ok) {
                        val base = e.item ?: PackItem("•", "")
                        onSave(base.copy(emoji = emoji.ifBlank { "•" }, name = name.trim(), perDay = perDay, spare = if (perDay) spare else 0,
                            skipped = skipped, done = if (skipped) false else base.done), target.trim())
                    }.padding(vertical = 13.dp))
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, sub: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, Outline, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 15.sp)
        Text("  · $sub", color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Switch(on, onChange, colors = SwitchDefaults.colors(checkedTrackColor = Amber))
    }
}

/** Repack (packing-list-v2, frame 3): nights for the next stay, then clear, or clear and print. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RepackSheet(p: PackList, onDismiss: () -> Unit, onRepack: (nights: Int, print: Boolean) -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var nights by remember { mutableStateOf(p.nights) }
    val n = p.items.count { it.done || it.skipped }
    val perDay = p.items.filter { it.perDay }.joinToString(", ") { it.name }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Surface1) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            Text("Repack?", fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Text("Clears $n tick${if (n == 1) "" else "s"} so you can pack again. Your items stay.", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))
            GroupLabel("Next stay")
            NightsRow("Nights at next place", nights) { nights = it }
            if (perDay.isNotEmpty()) Text("$perDay become ×$nights.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Repack + print", false, Modifier.weight(1f)) { onRepack(nights, true) }
                Pill("Repack", true, Modifier.weight(1f)) { onRepack(nights, false) }
            }
        }
    }
}

/** The slip preview and the Print button, on the printer saved in Daily Slip. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PrintListSheet(doc: ListDoc, snack: SnackbarHostState, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var paper by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(doc) { paper = withContext(Dispatchers.Default) { runCatching { SlipRender.drawList(doc) }.getOrNull() } }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Surface1) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            Text("Print ${doc.title.lowercase(Locale.UK).replaceFirstChar { it.titlecase(Locale.UK) }}", fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Text(doc.sub, color = Muted, fontSize = 13.sp)
            Box(Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = 120.dp).clip(RoundedCornerShape(6.dp)).background(Color.White).padding(vertical = 10.dp),
                contentAlignment = Alignment.TopCenter) {
                paper?.let { Image(it.asImageBitmap(), "Preview of the slip", Modifier.fillMaxWidth(0.86f), contentScale = ContentScale.FillWidth, filterQuality = FilterQuality.Medium) }
            }
            SlipPrintButton(paper, snack, Modifier.padding(top = 10.dp))
        }
    }
}
