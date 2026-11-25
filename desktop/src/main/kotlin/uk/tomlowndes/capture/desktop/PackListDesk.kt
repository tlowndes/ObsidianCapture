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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.tomlowndes.capture.PackItem
import uk.tomlowndes.capture.PackKind
import uk.tomlowndes.capture.PackList
import uk.tomlowndes.capture.PackLists
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What the list is showing: the list, an item form, or the Repack confirm. */
internal sealed interface PackView {
    data object List : PackView
    data class Edit(val at: Pair<Int, Int>?, val item: PackItem?, val section: String) : PackView
    data object Repack : PackView
}

/**
 * A packing or check list in the Windows Kits dialog (packing-list-v4, frame 5): sections in two columns' worth of
 * rows, click to tick, ⤼ to skip, ✎ to edit; nights, Print and Repack, or Mark checked.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PackListDesk(state: AppState, p: PackList, lists: List<PackList>, view: PackView, setView: (PackView) -> Unit, onOpen: (String) -> Unit) {
    val v = state.vault ?: return
    val today = LocalDate.now()
    fun save(changed: List<PackList>) = write(state, "Couldn't save the list") { changed.forEach { PackLists.save(v, it) } }

    when (view) {
        PackView.List -> Column {
            p.inside?.let { Sub("Inside the $it") }
            if (p.kind == PackKind.PACK) Nights("Staying", p.nights) { save(listOf(p.copy(nights = it))) }
            val c = PackLists.count(p, lists)
            val stamp = p.checked?.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK))
            Text(
                when {
                    c.full -> "All ${c.total} ${if (p.kind == PackKind.PACK) "packed" else "checked"} ✓"
                    p.kind == PackKind.PACK -> "${c.done} of ${c.total} packed" + (stamp?.let { " · last repack $it" } ?: "")
                    else -> "${c.done} of ${c.total} checked · " + (stamp?.let { "last checked $it" } ?: "not checked yet")
                },
                color = if (c.full) TaskGreen else if (PackLists.due(p, today)) Warn else Amber, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 8.dp)
            )
            Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).height(5.dp).clip(CircleShape).background(Surface2)) {
                Box(Modifier.fillMaxWidth(if (c.total == 0) 0f else c.done / c.total.toFloat()).height(5.dp).clip(CircleShape).background(TaskGreen))
            }
            p.sections.forEachIndexed { si, s ->
                Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp)) {
                    GroupLabel(s.title)
                    Box(Modifier.weight(1f))
                    Text(PackLists.count(s, lists).toString(), color = TaskGreen, fontSize = 12.sp)
                }
                s.items.forEachIndexed { ii, item ->
                    val child = PackLists.linked(item, lists)
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).hand()
                        .clickable { if (child != null) onOpen(child.name) else save(listOf(PackLists.toggle(p, si, ii))) }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val done = PackLists.isDone(item, lists) && !item.skipped
                        val box = RoundedCornerShape(6.dp)
                        Box(Modifier.size(18.dp).clip(box).let { if (done) it.background(TaskGreen) else it.border(1.5.dp, Outline, box) }, contentAlignment = Alignment.Center) {
                            if (done) Text("✓", color = Color(0xFF06210F), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        Text((if (item.emoji == "•") "" else item.emoji + "  ") + item.label(p.nights), fontSize = 14.sp, modifier = Modifier.weight(1f),
                            color = if (done || item.skipped) Muted else TextMain,
                            fontStyle = if (item.skipped) FontStyle.Italic else FontStyle.Normal,
                            textDecoration = if (done) TextDecoration.LineThrough else TextDecoration.None)
                        if (item.skipped) Text("skipped", color = Muted, fontSize = 11.sp)
                        else if (item.perDay) Text("1/day", color = Amber, fontSize = 11.sp)
                        child?.let { Text("${PackLists.count(it, lists)} ›", color = Muted, fontSize = 12.sp) }
                        TextAction(if (item.skipped) "↺" else "⤼", Muted) { save(listOf(PackLists.skip(p, si, ii))) }
                        TextAction("✎", Muted) { setView(PackView.Edit(si to ii, item, s.title)) }
                    }
                }
                TextAction("＋ Add item") { setView(PackView.Edit(null, null, s.title)) }
            }
            if (p.sections.isEmpty()) TextAction("＋ Add the first item") { setView(PackView.Edit(null, null, "Things")) }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextAction("🖨 Print") { printList(state, p, lists, today) }
                Box(Modifier.weight(1f))
                if (p.kind == PackKind.PACK) PrimaryButton("↺ Repack") { setView(PackView.Repack) }
                else PrimaryButton("✓ Mark checked") { save(listOf(PackLists.markChecked(p, today))) }
            }
        }
        is PackView.Edit -> Column {
            var emoji by remember(view) { mutableStateOf(view.item?.emoji?.takeIf { it != "•" } ?: "") }
            var name by remember(view) { mutableStateOf(view.item?.name ?: "") }
            var section by remember(view) { mutableStateOf(view.section) }
            var newSection by remember(view) { mutableStateOf("") }
            var perDay by remember(view) { mutableStateOf(view.item?.perDay ?: false) }
            var spare by remember(view) { mutableStateOf(view.item?.spare ?: 0) }
            var skipped by remember(view) { mutableStateOf(view.item?.skipped ?: false) }
            val target = newSection.trim().ifEmpty { section }
            SmallLabel(if (view.item == null) "Add item" else "Edit item")
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(emoji, { emoji = it.trim().take(8) }, Modifier.width(70.dp), placeholder = "🙂")
                Field(name, { name = it }, Modifier.weight(1f), placeholder = "e.g. T-shirts")
            }
            SmallLabel("Type", Modifier.padding(top = 12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                p.sections.forEach { s -> Chip(s.title, selected = s.title == section && newSection.isBlank(), small = true) { section = s.title; newSection = "" } }
            }
            Field(newSection, { newSection = it }, Modifier.fillMaxWidth().padding(top = 6.dp), placeholder = "Or a new type")
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Toggle(perDay) { perDay = it }; Text("Per day (×nights)", fontSize = 14.sp)
                if (perDay) { Box(Modifier.width(12.dp)); TextAction("−", Muted) { spare = (spare - 1).coerceAtLeast(0) }; Text("+$spare spare", color = Amber, fontSize = 13.sp); TextAction("+", Muted) { spare = (spare + 1).coerceAtMost(9) } }
            }
            if (view.item != null) Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Toggle(skipped) { skipped = it }; Text("Skip this pack", fontSize = 14.sp)
            }
            Box(Modifier.padding(top = 16.dp)) {
                DialogButtons({ setView(PackView.List) }, onDelete = view.at?.let { (s, i) -> { save(listOf(PackLists.removeItem(p, s, i))); setView(PackView.List) } }) {
                    PrimaryButton("Save", enabled = name.isNotBlank() && target.isNotBlank()) {
                        val base = view.item ?: PackItem("•", "")
                        val item = base.copy(emoji = emoji.ifBlank { "•" }, name = name.trim(), perDay = perDay, spare = if (perDay) spare else 0,
                            skipped = skipped, done = if (skipped) false else base.done)
                        save(listOf(PackLists.putItem(p, item, target.trim(), view.at)))
                        setView(PackView.List)
                    }
                }
            }
        }
        PackView.Repack -> Column {
            var nights by remember { mutableStateOf(p.nights) }
            Sub("Clears every tick so you can pack again. Your items stay.")
            Nights("Nights at next place", nights) { nights = it }
            fun go(print: Boolean) {
                val kids = PackLists.children(p, lists).map { PackLists.repack(it, it.nights, today) }
                val fresh = PackLists.repack(p, nights, today)
                save(listOf(fresh) + kids)
                if (print) printList(state, fresh, lists.map { l -> kids.firstOrNull { it.name == l.name } ?: l }, today)
                setView(PackView.List)
            }
            Box(Modifier.padding(top = 16.dp)) {
                DialogButtons({ setView(PackView.List) }) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextAction("Repack + print") { go(true) }
                        PrimaryButton("Repack") { go(false) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Nights(label: String, nights: Int, onChange: (Int) -> Unit) {
    Row(Modifier.padding(top = 8.dp).clip(RoundedCornerShape(12.dp)).background(Surface1).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("🌙  $label", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        TextAction("−", Muted) { onChange((nights - 1).coerceAtLeast(1)) }
        Text("$nights", color = Amber, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        TextAction("+", Muted) { onChange((nights + 1).coerceAtMost(30)) }
        Text("nights", color = Muted, fontSize = 13.sp)
    }
}

/** Draws the list slip and sends it to the saved printer. */
private fun printList(state: AppState, p: PackList, lists: List<PackList>, today: LocalDate) {
    state.scope.launch {
        val img = withContext(Dispatchers.Default) { runCatching { DesktopSlip.drawList(PackLists.printDoc(p, lists, today)) }.getOrNull() }
            ?: return@launch state.say("Couldn't draw the slip")
        if (!printImage(state, img) {}) state.say("Pick a printer in Print first")
    }
}
