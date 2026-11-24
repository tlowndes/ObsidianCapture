package uk.tomlowndes.capture

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** What the add/edit sheet is open for: a new item in [slot], or the existing [item] at [slot]. */
private data class ItemEdit(val slot: KitSlot, val item: KitItem? = null)

/**
 * Kits page (kits-backpack-v1): a tab per kit, and every kit edited the same way in the app: Use when, Always,
 * When rules, and what Night Pad charges and lays out. Changes are saved straight to the kit's note.
 */
@Composable
internal fun KitsView(vault: Uri?, snack: SnackbarHostState, start: String? = null, onChanged: () -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var kits by remember { mutableStateOf<List<Kit>?>(null) }
    var pick by remember { mutableStateOf(start) }
    var reload by remember { mutableIntStateOf(0) }
    var edit by remember { mutableStateOf<ItemEdit?>(null) }
    var addTrigger by remember { mutableStateOf(false) }
    var newKit by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Kit?>(null) }

    LaunchedEffect(vault, reload) {
        val uri = vault ?: return@LaunchedEffect
        withContext(Dispatchers.IO) { runCatching { Kits.loadAll(appVault(ctx, uri)) } }
            .onSuccess { kits = it }.onFailure { snack.showSnackbar("Couldn't read your kits: ${it.message}") }
    }

    /** Saves changed kits, then reloads. */
    fun save(vararg changed: Kit) {
        val uri = vault ?: return
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { val v = appVault(ctx, uri); changed.forEach { Kits.save(v, it) } } }
                .onSuccess { reload++; onChanged() }.onFailure { snack.showSnackbar("Couldn't save: ${it.message}") }
        }
    }

    val all = kits ?: run { Text("Loading your kits…", color = Muted, modifier = Modifier.padding(top = 12.dp)); return }
    val k = all.firstOrNull { it.name == pick } ?: all.firstOrNull() ?: return

    // Tabs: one per kit, then New.
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp).clip(RoundedCornerShape(12.dp)).background(Surface1).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        all.forEach { kit ->
            val on = kit.name == k.name
            Text("${kit.emoji} ${kit.name}", maxLines = 1, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (on) TextMain else Muted,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(9.dp)).background(if (on) Surface2 else Surface1)
                    .clickable { pick = kit.name }.padding(vertical = 8.dp))
        }
        Text("＋ New", maxLines = 1, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Muted,
            modifier = Modifier.clip(RoundedCornerShape(9.dp)).clickable { newKit = true }.padding(horizontal = 10.dp, vertical = 8.dp))
    }

    // Use when
    Section("Use this kit when")
    ListCard(Modifier.padding(top = 6.dp)) {
        val u = k.useWhen
        if (u == null) KitRow("Every trip", sub = "Add a trigger to bring it only on some days")
        else if (u.isEmpty()) KitRow("Only when you tap it on", sub = "Or add a trigger below")
        u?.forEach { t ->
            HorizontalDivider(color = Outline)
            KitRow(Kits.words(t.kind, t.arg), end = "×") { save(Kits.removeTrigger(k, t)) }
        }
        HorizontalDivider(color = Outline)
        KitRow("＋ Add a trigger", add = true) { addTrigger = true }
    }

    // Always, with drag to reorder.
    Section("Always")
    ListCard(Modifier.padding(top = 6.dp)) {
        val density = LocalDensity.current
        val rowPx = with(density) { 46.dp.toPx() }
        k.always.forEachIndexed { i, item ->
            var drag by remember(k.name, item.name) { mutableFloatStateOf(0f) }
            if (i > 0) HorizontalDivider(color = Outline)
            Row(
                Modifier.fillMaxWidth().offset { IntOffset(0, drag.roundToInt()) }.clickable { edit = ItemEdit(KitSlot(), item) }
                    .padding(start = 4.dp, end = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("≡", color = Muted, fontSize = 18.sp, modifier = Modifier.pointerInput(k.name, item.name, k.always.size) {
                    detectDragGesturesAfterLongPress(
                        onDrag = { change, amount -> change.consume(); drag += amount.y },
                        onDragCancel = { drag = 0f },
                        onDragEnd = {
                            val to = (i + (drag / rowPx).roundToInt()).coerceIn(0, k.always.lastIndex)
                            drag = 0f
                            if (to != i) save(Kits.moveAlways(k, i, to))
                        }
                    )
                }.padding(horizontal = 10.dp, vertical = 12.dp))
                Text("${item.emoji}  ${item.name}", fontSize = 15.sp, modifier = Modifier.weight(1f))
                Text("›", color = Muted, fontSize = 16.sp)
            }
        }
        if (k.always.isNotEmpty()) HorizontalDivider(color = Outline)
        KitRow("＋ Add item", add = true) { edit = ItemEdit(KitSlot()) }
    }

    // When rules
    Section("When…")
    ListCard(Modifier.padding(top = 6.dp)) {
        k.rules.filter { it.group == KitGroup.BAG }.forEach { r ->
            Text(Kits.words(r.kind, r.arg), color = Muted, fontSize = 12.sp, modifier = Modifier.padding(start = 16.dp, top = 10.dp))
            r.items.forEach { item -> KitRow("${item.emoji}  ${item.name}", end = "›") { edit = ItemEdit(KitSlot(r.group, r.kind, r.arg), item) } }
            HorizontalDivider(color = Outline)
        }
        KitRow("＋ Add a rule", add = true) { edit = ItemEdit(KitSlot(kind = KitRule.Kind.RAIN)) }
    }

    // Night Pad: charge and wear
    Section("Night before · charge and wear")
    ListCard(Modifier.padding(top = 6.dp)) {
        val night = Kits.entries(k).filter { it.first.group != KitGroup.BAG }
        night.forEach { (slot, item) ->
            val where = (if (slot.group == KitGroup.CHARGE) "Charge" else "Wear") + (slot.kind?.let { " · " + Kits.words(it, slot.arg).lowercase() } ?: "")
            KitRow("${item.emoji}  ${item.name}", sub = where, end = "›") { edit = ItemEdit(slot, item) }
            HorizontalDivider(color = Outline)
        }
        KitRow("＋ Add to charge or wear", add = true) { edit = ItemEdit(KitSlot(KitGroup.CHARGE)) }
    }

    val path = k.path ?: Kits.path(k.name)
    Text("Saved to $path · Open in Obsidian ↗", color = Muted, fontSize = 12.sp,
        modifier = Modifier.padding(start = 4.dp, top = 2.dp).clickable { openKitInObsidian(ctx, vault, path) }.padding(vertical = 6.dp))
    if (!k.name.equals(Kits.POCKET, true)) Text("Delete this kit", color = Warn, fontSize = 13.sp,
        modifier = Modifier.padding(start = 4.dp, bottom = 24.dp).clickable { deleting = k }.padding(vertical = 8.dp))

    edit?.let { e ->
        ItemSheet(e, k, all, onDismiss = { edit = null },
            onDelete = { edit = null; save(Kits.remove(k, e.slot, e.item!!.name)) },
            onSave = { item, slot, toKit ->
                edit = null
                val old = e.item
                when {
                    toKit != k.name -> {
                        val other = all.first { it.name == toKit }
                        val here = if (old != null) Kits.remove(k, e.slot, old.name) else k
                        save(here, Kits.put(other, item, slot))
                    }
                    old != null -> save(Kits.replace(k, e.slot, old.name, item, slot))
                    else -> save(Kits.put(k, item, slot))
                }
            })
    }
    if (addTrigger) TriggerSheet(onDismiss = { addTrigger = false }) { t -> addTrigger = false; save(Kits.addTrigger(k, t)) }
    if (newKit) NewKitSheet(all.map { it.name.lowercase() }.toSet(), onDismiss = { newKit = false }) { name, emoji ->
        newKit = false
        val uri = vault ?: return@NewKitSheet
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { Kits.create(appVault(ctx, uri), name, emoji) } }
                .onSuccess { pick = it.name; reload++; onChanged() }.onFailure { snack.showSnackbar("Couldn't add the kit: ${it.message}") }
        }
    }
    deleting?.let { d ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${d.name}?") },
            text = { Text("Its note goes from the vault. Obsidian's file recovery can bring it back.") },
            confirmButton = { TextButton(onClick = {
                deleting = null
                val uri = vault ?: return@TextButton
                scope.launch {
                    withContext(Dispatchers.IO) { runCatching { Kits.delete(appVault(ctx, uri), d) } }
                        .onSuccess { pick = null; reload++; onChanged() }.onFailure { snack.showSnackbar("Couldn't delete: ${it.message}") }
                }
            }) { Text("Delete", color = Warn) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Keep") } },
            containerColor = Surface1,
        )
    }
}

@Composable
private fun Section(text: String) = Row(Modifier.padding(start = 4.dp, top = 10.dp)) { GroupLabel(text) }

@Composable
private fun KitRow(text: String, sub: String? = null, end: String? = null, add: Boolean = false, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(text, fontSize = 15.sp, color = if (add) Primary else TextMain, fontWeight = if (add) FontWeight.SemiBold else FontWeight.Normal)
            if (sub != null) Text(sub, color = Muted, fontSize = 12.sp)
        }
        if (end != null) Text(end, color = Muted, fontSize = 16.sp)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Choices(options: List<Pair<T, String>>, chosen: T, onPick: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
        options.forEach { (value, label) ->
            val on = value == chosen
            Text(label, fontSize = 13.sp, color = if (on) Primary else Muted, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(CircleShape).background(if (on) PrimaryTint else Surface1)
                    .border(1.dp, if (on) Primary else Outline, CircleShape).clickable { onPick(value) }
                    .padding(horizontal = 12.dp, vertical = 7.dp))
        }
    }
}

@Composable
private fun Label(text: String) = Text(text.uppercase(), color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp,
    modifier = Modifier.padding(top = 14.dp))

/** When an item comes: Always or a rule kind. The word or hours go in the box under it. */
private enum class When(val label: String, val kind: KitRule.Kind?) {
    ALWAYS("Always", null), LONG("Out long", KitRule.Kind.LONG), RAIN("Rain", KitRule.Kind.RAIN),
    COLD("Cold", KitRule.Kind.COLD), EVENT("Event says…", KitRule.Kind.EVENT)
}

private fun whenOf(kind: KitRule.Kind?) = When.entries.first { it.kind == kind }

/** Add or edit one item (kits-backpack-v1 sheet): emoji, name, when, what kind, and which kit. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ItemSheet(e: ItemEdit, kit: Kit, kits: List<Kit>, onDismiss: () -> Unit, onDelete: () -> Unit, onSave: (KitItem, KitSlot, String) -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var emoji by remember { mutableStateOf(e.item?.emoji?.takeIf { it != "•" } ?: "") }
    var name by remember { mutableStateOf(e.item?.name ?: "") }
    var whenAt by remember { mutableStateOf(whenOf(e.slot.kind)) }
    var arg by remember { mutableStateOf(e.slot.arg.ifEmpty { if (e.slot.kind == KitRule.Kind.LONG) "4" else "" }) }
    var group by remember { mutableStateOf(e.slot.group) }
    var toKit by remember { mutableStateOf(kit.name) }
    val needsArg = whenAt == When.EVENT || whenAt == When.LONG
    val cleanArg = when (whenAt) {
        When.EVENT -> arg.trim().lowercase()
        When.LONG -> arg.trim().toIntOrNull()?.coerceIn(1, 24)?.toString() ?: ""
        When.COLD -> arg.trim().toIntOrNull()?.toString() ?: ""
        else -> ""
    }
    val ok = name.isNotBlank() && (!needsArg || cleanArg.isNotEmpty())

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Surface1) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            Text(if (e.item == null) "Add to ${kit.name}" else "Edit ${e.item.name}", fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(emoji, { emoji = it.trim().take(8) }, singleLine = true, modifier = Modifier.width(72.dp),
                    shape = RoundedCornerShape(14.dp), colors = fieldColors(), placeholder = { Text("🙂") })
                OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp), colors = fieldColors(), placeholder = { Text("e.g. Power bank") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
            }
            Label("When to take it")
            Choices(When.entries.map { it to it.label }, whenAt) { whenAt = it; if (it == When.LONG && arg.toIntOrNull() == null) arg = "4" else if (it == When.EVENT && arg.toIntOrNull() != null) arg = "" }
            if (whenAt == When.EVENT || whenAt == When.LONG || whenAt == When.COLD) OutlinedTextField(
                arg, { arg = it }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                shape = RoundedCornerShape(14.dp), colors = fieldColors(),
                label = { Text(when (whenAt) { When.EVENT -> "Word in the event title"; When.LONG -> "Hours out or more"; else -> "Low of (°C) or less, blank for 5" }) },
                keyboardOptions = if (whenAt == When.EVENT) KeyboardOptions.Default else KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            Label("What it's for")
            Choices(listOf(KitGroup.BAG to "Take it", KitGroup.CHARGE to "🔌 Charge the night before", KitGroup.WEAR to "Wear"), group) { group = it }
            if (kits.size > 1) {
                Label("Kit")
                Choices(kits.map { it.name to "${it.emoji} ${it.name}" }, toKit) { toKit = it }
            }
            Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (e.item != null) Text("Delete", color = Warn, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.weight(1f).clip(CircleShape).border(1.dp, Outline, CircleShape).clickable(onClick = onDelete).padding(vertical = 13.dp))
                Text("Save", color = if (ok) OnPrimary else Muted, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.weight(1f).clip(CircleShape).background(if (ok) Primary else Surface2).clickable(enabled = ok) {
                        val item = KitItem(emoji.ifBlank { "•" }, name.trim())
                        onSave(item, KitSlot(group, whenAt.kind, cleanArg), toKit)
                    }.padding(vertical = 13.dp))
            }
        }
    }
}

/** Adds a Use when line. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TriggerSheet(onDismiss: () -> Unit, onAdd: (KitTrigger) -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var kind by remember { mutableStateOf(KitRule.Kind.EVENT) }
    var arg by remember { mutableStateOf("") }
    val clean = when (kind) {
        KitRule.Kind.EVENT -> arg.trim().lowercase()
        KitRule.Kind.LONG -> arg.trim().toIntOrNull()?.coerceIn(1, 24)?.toString() ?: ""
        KitRule.Kind.COLD -> arg.trim().toIntOrNull()?.toString() ?: ""
        KitRule.Kind.RAIN -> ""
    }
    val ok = (kind != KitRule.Kind.EVENT && kind != KitRule.Kind.LONG) || clean.isNotEmpty()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Surface1) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            Text("Bring this kit when…", fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Choices(listOf(KitRule.Kind.EVENT to "Event says…", KitRule.Kind.LONG to "Out long", KitRule.Kind.RAIN to "Rain", KitRule.Kind.COLD to "Cold"), kind) {
                kind = it; arg = if (it == KitRule.Kind.LONG) "4" else ""
            }
            if (kind != KitRule.Kind.RAIN) OutlinedTextField(
                arg, { arg = it }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                shape = RoundedCornerShape(14.dp), colors = fieldColors(),
                label = { Text(when (kind) { KitRule.Kind.EVENT -> "Word in the event title, e.g. work"; KitRule.Kind.LONG -> "Hours out or more"; else -> "Low of (°C) or less, blank for 5" }) },
                keyboardOptions = if (kind == KitRule.Kind.EVENT) KeyboardOptions.Default else KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            Text("Add", color = if (ok) OnPrimary else Muted, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp).clip(CircleShape).background(if (ok) Primary else Surface2)
                    .clickable(enabled = ok) { onAdd(KitTrigger(kind, clean)) }.padding(vertical = 13.dp))
        }
    }
}

/** Starts a new kit: a name and an emoji. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewKitSheet(taken: Set<String>, onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var emoji by remember { mutableStateOf("🏋️") }
    var name by remember { mutableStateOf("") }
    val ok = name.isNotBlank() && name.trim().lowercase() !in taken
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Surface1) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            Text("New kit", fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Text("It comes when you tap it on Launch Pad, or once you add a trigger.", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(emoji, { emoji = it.trim().take(8) }, singleLine = true, modifier = Modifier.width(72.dp),
                    shape = RoundedCornerShape(14.dp), colors = fieldColors())
                OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp), colors = fieldColors(), placeholder = { Text("e.g. Gym bag") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
            }
            Text("Add kit", color = if (ok) OnPrimary else Muted, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp).clip(CircleShape).background(if (ok) Primary else Surface2)
                    .clickable(enabled = ok) { onAdd(name.trim(), emoji) }.padding(vertical = 13.dp))
        }
    }
}

private fun openKitInObsidian(ctx: android.content.Context, vault: Uri?, path: String) {
    val name = vault?.lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/') ?: return
    val url = "obsidian://open?vault=${Uri.encode(name)}&file=${Uri.encode(path.removeSuffix(".md"))}"
    runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
