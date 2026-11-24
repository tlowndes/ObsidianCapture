package uk.tomlowndes.capture.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uk.tomlowndes.capture.Kit
import uk.tomlowndes.capture.KitGroup
import uk.tomlowndes.capture.KitItem
import uk.tomlowndes.capture.KitRule
import uk.tomlowndes.capture.KitSlot
import uk.tomlowndes.capture.KitTrigger
import uk.tomlowndes.capture.Kits

private sealed interface KitsView {
    data object List : KitsView
    data class Edit(val slot: KitSlot, val item: KitItem?) : KitsView
    data object Trigger : KitsView
    data object New : KitsView
    data object Delete : KitsView
}

/**
 * Kits on Windows (kits-backpack-v1): the same in-app editing as the phone, in a dialog. A tab per kit; Use when,
 * Always, When and the night-before lists; add, edit, move and delete. Saved straight to the kit's note.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun KitsDialog(state: AppState, start: String? = null, onClose: () -> Unit) {
    val v = state.vault ?: return
    val all = loaded(state, "Couldn't read your kits") { Kits.loadAll(v) }
    var pick by remember { mutableStateOf(start) }
    var view by remember { mutableStateOf<KitsView>(KitsView.List) }
    fun save(vararg ks: Kit) = write(state, "Couldn't save the kit") { ks.forEach { Kits.save(v, it) } }

    val kits = all ?: run { DialogCard("Kits", onClose) { Sub("Loading your kits…") }; return }
    val k = kits.firstOrNull { it.name == pick } ?: kits.firstOrNull() ?: return

    when (val vw = view) {
        KitsView.List -> DialogCard("Kits", onClose) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                kits.forEach { kit -> Chip("${kit.emoji} ${kit.name}", selected = kit.name == k.name) { pick = kit.name } }
                Chip("＋ New kit") { view = KitsView.New }
            }
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()).padding(top = 12.dp)) {
                Section("Use this kit when")
                val u = k.useWhen
                if (u == null) Sub("Every trip. Add a trigger to bring it only on some days.")
                else if (u.isEmpty()) Sub("Only when you tick it on.")
                u?.forEach { t -> EntryRow(Kits.words(t.kind, t.arg), onDelete = { save(Kits.removeTrigger(k, t)) }) }
                TextAction("＋ Add a trigger") { view = KitsView.Trigger }

                Section("Always")
                k.always.forEachIndexed { i, item ->
                    EntryRow("${item.emoji}  ${item.name}", onEdit = { view = KitsView.Edit(KitSlot(), item) },
                        onUp = if (i > 0) ({ save(Kits.moveAlways(k, i, i - 1)) }) else null,
                        onDelete = { save(Kits.remove(k, KitSlot(), item.name)) })
                }
                TextAction("＋ Add item") { view = KitsView.Edit(KitSlot(), null) }

                Section("When…")
                Kits.entries(k).filter { it.first.group == KitGroup.BAG && it.first.kind != null }.forEach { (slot, item) ->
                    EntryRow("${item.emoji}  ${item.name}", sub = Kits.words(slot.kind!!, slot.arg),
                        onEdit = { view = KitsView.Edit(slot, item) }, onDelete = { save(Kits.remove(k, slot, item.name)) })
                }
                TextAction("＋ Add a rule") { view = KitsView.Edit(KitSlot(kind = KitRule.Kind.RAIN), null) }

                Section("Night before · charge and wear")
                Kits.entries(k).filter { it.first.group != KitGroup.BAG }.forEach { (slot, item) ->
                    val where = (if (slot.group == KitGroup.CHARGE) "Charge" else "Wear") + (slot.kind?.let { " · " + Kits.words(it, slot.arg).lowercase() } ?: "")
                    EntryRow("${item.emoji}  ${item.name}", sub = where,
                        onEdit = { view = KitsView.Edit(slot, item) }, onDelete = { save(Kits.remove(k, slot, item.name)) })
                }
                TextAction("＋ Add to charge or wear") { view = KitsView.Edit(KitSlot(KitGroup.CHARGE), null) }
            }
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                TextAction("Open in Obsidian ↗", Muted) { state.openInObsidian(k.path ?: Kits.path(k.name)) }
                if (!k.name.equals(Kits.POCKET, true)) TextAction("Delete kit", Warn) { view = KitsView.Delete }
                Box(Modifier.weight(1f))
                PrimaryButton("Done", onClick = onClose)
            }
        }
        is KitsView.Edit -> ItemForm(vw, k, kits, onBack = { view = KitsView.List }) { item, slot, toKit ->
            val old = vw.item
            when {
                toKit != k.name -> save(if (old != null) Kits.remove(k, vw.slot, old.name) else k, Kits.put(kits.first { it.name == toKit }, item, slot))
                old != null -> save(Kits.replace(k, vw.slot, old.name, item, slot))
                else -> save(Kits.put(k, item, slot))
            }
            view = KitsView.List
        }
        KitsView.Trigger -> TriggerForm(onBack = { view = KitsView.List }) { save(Kits.addTrigger(k, it)); view = KitsView.List }
        KitsView.New -> DialogCard("New kit", { view = KitsView.List }) {
            var emoji by remember { mutableStateOf("🏋️") }
            var name by remember { mutableStateOf("") }
            val ok = name.isNotBlank() && kits.none { it.name.equals(name.trim(), true) }
            Sub("It comes when you tick it on, or once you add a trigger.")
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(emoji, { emoji = it.trim().take(8) }, Modifier.width(70.dp))
                Field(name, { name = it }, Modifier.weight(1f), placeholder = "e.g. Gym bag")
            }
            DialogButtons({ view = KitsView.List }) {
                PrimaryButton("Add kit", enabled = ok) {
                    val n = name.trim(); val e = emoji
                    write(state, "Couldn't add the kit") { Kits.create(v, n, e) }
                    pick = n; view = KitsView.List
                }
            }
        }
        KitsView.Delete -> DialogCard("Delete ${k.name}?", { view = KitsView.List }) {
            Sub("Its note goes from the vault. Obsidian's file recovery can bring it back.")
            DialogButtons({ view = KitsView.List }) {
                PrimaryButton("Delete") { write(state, "Couldn't delete the kit") { Kits.delete(v, k) }; pick = null; view = KitsView.List }
            }
        }
    }
}

@Composable
private fun Section(text: String) = Box(Modifier.padding(top = 12.dp, bottom = 4.dp)) { GroupLabel(text) }

@Composable
private fun EntryRow(text: String, sub: String? = null, onEdit: (() -> Unit)? = null, onUp: (() -> Unit)? = null, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(text, fontSize = 14.sp, color = TextMain)
            if (sub != null) Text(sub, fontSize = 12.sp, color = Muted)
        }
        if (onUp != null) TextAction("↑", Muted, onClick = onUp)
        if (onEdit != null) TextAction("✎", Muted, onClick = onEdit)
        TextAction("×", Muted, onClick = onDelete)
    }
}

private val WHEN = listOf<Pair<KitRule.Kind?, String>>(null to "Always", KitRule.Kind.LONG to "Out long", KitRule.Kind.RAIN to "Rain",
    KitRule.Kind.COLD to "Cold", KitRule.Kind.EVENT to "Event says…")

private fun cleanArg(kind: KitRule.Kind?, arg: String) = when (kind) {
    KitRule.Kind.EVENT -> arg.trim().lowercase()
    KitRule.Kind.LONG -> arg.trim().toIntOrNull()?.coerceIn(1, 24)?.toString() ?: ""
    KitRule.Kind.COLD -> arg.trim().toIntOrNull()?.toString() ?: ""
    else -> ""
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ItemForm(e: KitsView.Edit, kit: Kit, kits: List<Kit>, onBack: () -> Unit, onSave: (KitItem, KitSlot, String) -> Unit) {
    var emoji by remember { mutableStateOf(e.item?.emoji?.takeIf { it != "•" } ?: "") }
    var name by remember { mutableStateOf(e.item?.name ?: "") }
    var kind by remember { mutableStateOf(e.slot.kind) }
    var arg by remember { mutableStateOf(e.slot.arg.ifEmpty { if (e.slot.kind == KitRule.Kind.LONG) "4" else "" }) }
    var group by remember { mutableStateOf(e.slot.group) }
    var toKit by remember { mutableStateOf(kit.name) }
    val clean = cleanArg(kind, arg)
    val ok = name.isNotBlank() && (kind != KitRule.Kind.EVENT && kind != KitRule.Kind.LONG || clean.isNotEmpty())
    DialogCard(if (e.item == null) "Add to ${kit.name}" else "Edit ${e.item.name}", onBack) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(emoji, { emoji = it.trim().take(8) }, Modifier.width(70.dp), placeholder = "🙂")
            Field(name, { name = it }, Modifier.weight(1f), placeholder = "e.g. Power bank")
        }
        SmallLabel("When to take it", Modifier.padding(top = 12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            WHEN.forEach { (kd, label) -> Chip(label, selected = kd == kind, small = true) { kind = kd; arg = if (kd == KitRule.Kind.LONG) "4" else "" } }
        }
        if (kind == KitRule.Kind.EVENT || kind == KitRule.Kind.LONG || kind == KitRule.Kind.COLD)
            Field(arg, { arg = it }, Modifier.fillMaxWidth().padding(top = 8.dp),
                placeholder = when (kind) { KitRule.Kind.EVENT -> "Word in the event title"; KitRule.Kind.LONG -> "Hours out or more"; else -> "Low of (°C) or less, blank for 5" })
        SmallLabel("What it's for", Modifier.padding(top = 12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(KitGroup.BAG to "Take it", KitGroup.CHARGE to "🔌 Charge the night before", KitGroup.WEAR to "Wear").forEach { (g, l) ->
                Chip(l, selected = g == group, small = true) { group = g }
            }
        }
        if (kits.size > 1) {
            SmallLabel("Kit", Modifier.padding(top = 12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                kits.forEach { kt -> Chip("${kt.emoji} ${kt.name}", selected = kt.name == toKit, small = true) { toKit = kt.name } }
            }
        }
        Box(Modifier.padding(top = 16.dp)) {
            DialogButtons(onBack) {
                PrimaryButton("Save", enabled = ok) { onSave(KitItem(emoji.ifBlank { "•" }, name.trim()), KitSlot(group, kind, clean), toKit) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TriggerForm(onBack: () -> Unit, onAdd: (KitTrigger) -> Unit) {
    var kind by remember { mutableStateOf(KitRule.Kind.EVENT) }
    var arg by remember { mutableStateOf("") }
    val clean = cleanArg(kind, arg)
    val ok = (kind != KitRule.Kind.EVENT && kind != KitRule.Kind.LONG) || clean.isNotEmpty()
    DialogCard("Bring this kit when…", onBack) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            WHEN.drop(1).forEach { (kd, label) -> Chip(label, selected = kd == kind, small = true) { kind = kd!!; arg = if (kd == KitRule.Kind.LONG) "4" else "" } }
        }
        if (kind != KitRule.Kind.RAIN) Field(arg, { arg = it }, Modifier.fillMaxWidth().padding(top = 8.dp),
            placeholder = when (kind) { KitRule.Kind.EVENT -> "Word in the event title, e.g. work"; KitRule.Kind.LONG -> "Hours out or more"; else -> "Low of (°C) or less, blank for 5" })
        Box(Modifier.padding(top = 16.dp)) {
            DialogButtons(onBack) { PrimaryButton("Add", enabled = ok) { onAdd(KitTrigger(kind, clean)) } }
        }
    }
}
