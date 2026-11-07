package uk.tomlowndes.capture

import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Work amber, Personal green, the rest purple; with the tinted fill a selected chip uses. */
internal fun tagColour(tag: String): Pair<Color, Color> = when (Tags.kind(tag)) {
    TagKind.WORK -> Amber to AmberTint
    TagKind.PERSONAL -> Green to TaskTint
    TagKind.OTHER -> if (tag == "#bill") Amber to AmberTint else Purple to TagTint
}

/** Pinned tags (from capture-config.json) and the tags the vault's tasks already use, for the Tag row. */
@Stable
internal class TagData(private val ctx: android.content.Context, private val vault: Uri?) {
    var pinned by mutableStateOf<List<String>>(emptyList())
    var known by mutableStateOf<List<Pair<String, Int>>>(emptyList())

    suspend fun load() {
        val uri = vault ?: return
        withContext(Dispatchers.IO) {
            runCatching { Tags.loadPinned(appVault(ctx, uri)) }.getOrNull()?.let { pinned = it }
            runCatching { Tags.counts(VaultWriter(ctx, uri).listTasks().map { it.raw }) }.getOrNull()?.let { known = it }
        }
    }

    suspend fun setPinned(tags: List<String>) {
        val uri = vault ?: return
        pinned = tags
        withContext(Dispatchers.IO) { runCatching { Tags.savePinned(appVault(ctx, uri), tags) } }
    }
}

@Composable
internal fun rememberTagData(vault: Uri?): TagData {
    val ctx = LocalContext.current
    val data = remember(vault) { TagData(ctx, vault) }
    LaunchedEffect(data) { data.load() }
    return data
}

/** One tag chip: a coloured dot, tinted when on, with an optional [count]. Long-press does [onLongClick] (unpin). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TagChip(tag: String, selected: Boolean, onClick: () -> Unit, onLongClick: (() -> Unit)? = null, count: Int? = null) {
    val (fg, bg) = tagColour(tag)
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.clip(shape).background(if (selected) bg else Color.Transparent)
            .border(1.dp, if (selected) fg else Outline, shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(fg))
        Text(Tags.label(tag) + (count?.let { " $it" } ?: ""), fontSize = 14.sp, color = if (selected) fg else TextMain)
    }
}

/**
 * The Tag row on Capture and Edit task: Work, Personal, pinned tags, any tag already [on], and ＋ Tag.
 * Tapping toggles; long-pressing a pinned tag unpins it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TagRow(data: TagData, on: List<String>, onToggle: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }
    Column(Modifier.padding(top = 14.dp)) {
        GroupLabel("Tag")
        FlowRow(
            Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Tags.row(data.pinned, on).forEach { tag ->
                val pinned = tag in data.pinned
                TagChip(tag, tag in on, onClick = { onToggle(tag) },
                    onLongClick = if (pinned) ({ scope.launch { data.setPinned(data.pinned - tag) }; Unit }) else null)
            }
            Text(
                "＋ Tag", fontSize = 14.sp, color = Muted,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).border(1.dp, Outline, RoundedCornerShape(12.dp))
                    .clickable { adding = true }.padding(horizontal = 12.dp, vertical = 7.dp)
            )
        }
        Text("Tap to add, tap again to remove · more than one is fine", fontSize = 12.5.sp, color = Muted, modifier = Modifier.padding(top = 8.dp))
    }
    if (adding) AddTagSheet(
        data.known.filter { it.first !in Tags.BUILT_IN },
        onAdd = { tag, pin ->
            adding = false
            if (tag !in on) onToggle(tag)
            if (pin && tag !in data.pinned && tag !in Tags.BUILT_IN) scope.launch { data.setPinned(data.pinned + tag) }
        },
        onDismiss = { adding = false }
    )
}

/** ＋ Tag: type a tag, pick one the vault already uses, and choose whether it stays on the Tag row. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddTagSheet(known: List<Pair<String, Int>>, onAdd: (tag: String, pin: Boolean) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<String?>(null) }
    var pin by remember { mutableStateOf(true) }
    val typed = Tags.norm(query)
    val matches = Tags.suggest(query, known)
    val chosen = picked ?: matches.firstOrNull { it.first == typed }?.first ?: typed
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Surface1) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            Text("Add a tag", fontSize = 19.sp, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = query, onValueChange = { query = it; picked = null },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp), shape = RoundedCornerShape(14.dp), singleLine = true,
                prefix = { Text("# ", color = Muted, fontFamily = FontFamily.Monospace) },
                placeholder = { Text("garden") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                colors = fieldColors()
            )
            if (matches.isNotEmpty()) {
                Box(Modifier.padding(top = 14.dp)) { GroupLabel("From your vault") }
                matches.forEach { (tag, n) ->
                    val on = chosen == tag
                    Row(
                        Modifier.fillMaxWidth().clickable { picked = if (picked == tag) null else tag }.padding(vertical = 11.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        val shape = RoundedCornerShape(6.dp)
                        Box(
                            Modifier.size(20.dp).clip(shape).then(if (on) Modifier.background(Purple) else Modifier.border(1.5.dp, Muted, shape)),
                            contentAlignment = Alignment.Center
                        ) { if (on) Text("✓", fontSize = 12.sp, color = OnPrimary) }
                        Text(tag, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        Text(if (n == 1) "1 task" else "$n tasks", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Muted)
                    }
                    HorizontalDivider(color = Outline)
                }
            }
            if (typed != null && matches.none { it.first == typed }) {
                Text("＋ Create “$typed”", color = Primary, fontSize = 15.sp,
                    modifier = Modifier.fillMaxWidth().clickable { picked = typed }.padding(vertical = 11.dp, horizontal = 4.dp))
            }
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Pin to Capture", fontSize = 14.sp)
                    Text("Shows as a chip every time. Long-press a chip to unpin.", fontSize = 12.sp, color = Muted)
                }
                Switch(pin, { pin = it }, colors = SwitchDefaults.colors(checkedTrackColor = Primary, checkedThumbColor = OnPrimary))
            }
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SheetButton("Cancel", filled = false, onClick = onDismiss)
                SheetButton(chosen?.let { "Add $it" } ?: "Add", filled = chosen != null) { chosen?.let { onAdd(it, pin) } }
            }
        }
    }
}
