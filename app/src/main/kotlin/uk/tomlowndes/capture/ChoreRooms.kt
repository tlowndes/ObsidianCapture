package uk.tomlowndes.capture

import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** List · Upcoming · Chores across the top of Tasks (chores-rooms-v1). */
internal enum class TasksView(val label: String) { LIST("List"), UPCOMING("Upcoming"), CHORES("Chores") }

private val RESET_DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)
private val ROOM_ICONS = listOf("🛁", "🍳", "🛏️", "🛋️", "🧺", "🚗", "🌱", "🏠", "🧸", "🖥️")
private val HOW_OFTEN = listOf(3 to "Every 3 days", 7 to "Weekly", 14 to "Fortnightly", 30 to "Monthly")

/**
 * Chores by room (chores-rooms-v1): each room's jobs to tick, a progress bar and ↺ Reset (unticks the room, with Undo).
 * Kept in Chores.md as `## 🛁 Bathroom · reset 2026-11-15` headings, so the Deck's chores card still works.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun ChoreRoomsView(vault: Uri?, snack: SnackbarHostState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf<String?>(null) }
    var dueOnly by remember { mutableStateOf(false) }
    var folded by remember { mutableStateOf(setOf<String>()) }
    var editingRoom by remember { mutableStateOf<ChoreRoom?>(null) }
    var addingRoom by remember { mutableStateOf(false) }
    var addingJob by remember { mutableStateOf<ChoreRoom?>(null) }
    val today = LocalDate.now()

    LaunchedEffect(vault) {
        val uri = vault ?: return@LaunchedEffect
        text = withContext(Dispatchers.IO) { runCatching { Chores.text(appVault(ctx, uri)) }.getOrNull() }
    }

    /** Writes the note as [change] makes it; [undo] puts the old text back from a snackbar. */
    fun write(msg: String? = null, undo: Boolean = false, change: (String) -> String) {
        val uri = vault ?: return
        val old = text ?: return
        val new = change(old)
        text = new
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Chores.write(appVault(ctx, uri), new) } }
            if (r.isFailure) { text = old; snack.showSnackbar("Couldn't save: ${r.exceptionOrNull()?.message}"); return@launch }
            if (msg != null) {
                val res = snack.showSnackbar(msg, actionLabel = if (undo) "Undo" else null, duration = SnackbarDuration.Short)
                if (res == SnackbarResult.ActionPerformed) {
                    text = old
                    withContext(Dispatchers.IO) { runCatching { Chores.write(appVault(ctx, uri), old) } }
                }
            }
        }
    }

    val rooms = text?.let { Chores.rooms(it) } ?: emptyList()
    val dueCount = rooms.sumOf { r -> r.chores.count { !it.ticked && it.fill(today) >= Chores.DUE } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AppChip(!dueOnly, { dueOnly = false }, { Text("All rooms") })
            AppChip(dueOnly, { dueOnly = true }, { Text("Needs doing · $dueCount") })
        }
        if (text == null) Text("Reading Chores.md…", color = Muted, fontSize = 13.sp)
        rooms.forEach { room ->
            val jobs = if (dueOnly) room.chores.filter { !it.ticked && it.fill(today) >= Chores.DUE } else room.chores
            if (dueOnly && jobs.isEmpty()) return@forEach
            val open = room.name !in folded
            ListCard {
                Row(
                    Modifier.fillMaxWidth().combinedClickable(
                        onClick = { folded = if (open) folded + room.name else folded - room.name },
                        onLongClick = { editingRoom = room },
                    ).padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(room.label, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${room.ticked} of ${room.chores.size}" + if (open) "" else " ›", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Muted)
                    if (open && room.chores.isNotEmpty()) Text(
                        "↺ Reset", fontSize = 12.5.sp, color = Primary,
                        modifier = Modifier.padding(start = 8.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, Outline, RoundedCornerShape(10.dp))
                            .clickable { write("${room.name} reset · ${room.chores.size} jobs to do", undo = true) { Chores.resetIn(it, room.name, today) } }
                            .padding(horizontal = 9.dp, vertical = 4.dp)
                    )
                }
                if (!open) return@ListCard
                val share = if (room.chores.isEmpty()) 0f else room.ticked.toFloat() / room.chores.size
                Box(Modifier.padding(horizontal = 14.dp).padding(bottom = 10.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(4.dp)).background(Surface2)) {
                    Box(Modifier.fillMaxWidth(share.coerceIn(0f, 1f)).height(4.dp).background(Green))
                }
                jobs.forEach { c ->
                    HorizontalDivider(color = Outline)
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            val next = if (c.ticked) c.copy(ticked = false) else c.copy(ticked = true, done = today, by = null)
                            write(if (c.ticked) null else "${c.name} done", undo = !c.ticked) { Chores.editedIn(it, room.name, c.name, next) }
                        }.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier.size(20.dp).clip(RoundedCornerShape(6.dp)).background(if (c.ticked) Green else Color.Transparent)
                                .border(2.dp, if (c.ticked) Green else Muted, RoundedCornerShape(6.dp))
                        )
                        Text(
                            c.label, fontSize = 14.5.sp, color = if (c.ticked) Muted else TextMain, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            textDecoration = if (c.ticked) TextDecoration.LineThrough else null, modifier = Modifier.padding(start = 10.dp).weight(1f)
                        )
                        Text(
                            if (c.ticked && c.done == today) "done today" else "every ${c.every}",
                            fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, color = Muted, modifier = Modifier.padding(horizontal = 8.dp)
                        )
                        val fill = if (c.ticked) 0.0 else c.fill(today)
                        Box(Modifier.width(46.dp).height(5.dp).clip(RoundedCornerShape(5.dp)).background(Surface2)) {
                            Box(Modifier.fillMaxWidth(fill.coerceIn(0.0, 1.0).toFloat()).height(5.dp).background(choreColour(fill)))
                        }
                    }
                }
                HorizontalDivider(color = Outline)
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(room.reset?.let { "Last reset " + if (it == today) "today" else it.format(RESET_DAY) } ?: "Not reset yet", fontSize = 11.5.sp, color = Muted, modifier = Modifier.weight(1f))
                    Text("＋ Add job", fontSize = 12.5.sp, color = Primary, modifier = Modifier.clickable { addingJob = room })
                }
            }
        }
        Text(
            "＋ Add room", fontSize = 14.sp, color = Primary, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp).clip(RoundedCornerShape(14.dp)).border(1.dp, Outline, RoundedCornerShape(14.dp))
                .clickable { addingRoom = true }.padding(12.dp)
        )
        if (rooms.isNotEmpty()) Text("Hold a room to rename it or change its jobs.", fontSize = 11.5.sp, color = Muted, modifier = Modifier.padding(bottom = 20.dp))
    }

    if (addingRoom || editingRoom != null) RoomSheet(
        editingRoom,
        onDismiss = { addingRoom = false; editingRoom = null },
        onDelete = editingRoom?.let { r -> { write("${r.name} removed", undo = true) { Chores.withoutRoom(it, r.name) }; editingRoom = null } },
    ) { room ->
        val old = editingRoom?.name
        write(if (old == null) "${room.name} added" else null) { Chores.withRoom(it, room, old) }
        addingRoom = false; editingRoom = null
    }
    addingJob?.let { room ->
        JobSheet(room.name, onDismiss = { addingJob = null }) { c ->
            write("${c.name} added to ${room.name}") { Chores.editedIn(it, room.name, null, c.copy(done = today)) }
            addingJob = null
        }
    }
}

/** Add a room (or hold one to edit): name, icon, jobs one per line, how often. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoomSheet(room: ChoreRoom?, onDismiss: () -> Unit, onDelete: (() -> Unit)?, onSave: (ChoreRoom) -> Unit) {
    var name by remember { mutableStateOf(room?.name.orEmpty()) }
    var icon by remember { mutableStateOf(room?.emoji?.ifEmpty { null } ?: ROOM_ICONS.first()) }
    var jobs by remember { mutableStateOf(room?.chores?.joinToString("\n") { it.name }.orEmpty()) }
    var every by remember { mutableStateOf(room?.chores?.groupingBy { it.every }?.eachCount()?.maxByOrNull { it.value }?.key ?: 7) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Surface2) {
        Column(Modifier.padding(start = 18.dp, end = 18.dp, bottom = 28.dp)) {
            Text(if (room == null) "Add a room" else "Edit ${room.name}", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 6.dp))
            MiniLabel("Name"); CompactField(name, { name = it }, "Utility room")
            Spacer(Modifier.height(8.dp))
            MiniSection("Icon") { ROOM_ICONS.forEach { e -> MiniChip(e, icon == e) { icon = e } } }
            Spacer(Modifier.height(8.dp))
            MiniLabel("Jobs · one per line")
            OutlinedTextField(jobs, { jobs = it }, minLines = 3, colors = fieldColors(), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            MiniSection("How often") { HOW_OFTEN.forEach { (d, l) -> MiniChip(l, every == d) { every = d } } }
            if (room != null) Text("Jobs you keep stay as they were (when last done, how often). New ones use this.", fontSize = 11.5.sp, color = Muted, modifier = Modifier.padding(top = 6.dp))
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onDelete != null) RoomButton("Remove", false, Modifier.weight(1f), Warn, onDelete)
                else RoomButton("Cancel", false, Modifier.weight(1f), TextMain, onDismiss)
                RoomButton(if (room == null) "Add room" else "Save", true, Modifier.weight(1f)) {
                    val n = name.trim()
                    if (n.isEmpty()) return@RoomButton
                    val list = jobs.lines().map { it.trim().removePrefix("-").trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }.map { l ->
                        val (emoji, label) = Chores.splitEmoji(l)
                        room?.chores?.firstOrNull { it.name.equals(label, true) } ?: Chore(label, emoji, every, LocalDate.now())
                    }
                    onSave(ChoreRoom(n, icon, room?.reset, list))
                }
            }
        }
    }
}

/** ＋ Add job: a name and how often. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JobSheet(room: String, onDismiss: () -> Unit, onSave: (Chore) -> Unit) {
    var name by remember { mutableStateOf("") }
    var every by remember { mutableStateOf(7) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Surface2) {
        Column(Modifier.padding(start = 18.dp, end = 18.dp, bottom = 28.dp)) {
            Text("Add a job to $room", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 6.dp))
            MiniLabel("Job"); CompactField(name, { name = it }, "Clean the shower")
            MiniSection("How often") { HOW_OFTEN.forEach { (d, l) -> MiniChip(l, every == d) { every = d } } }
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RoomButton("Cancel", false, Modifier.weight(1f), TextMain, onDismiss)
                RoomButton("Add job", true, Modifier.weight(1f)) {
                    val (emoji, label) = Chores.splitEmoji(name.trim())
                    if (label.isNotEmpty()) onSave(Chore(label, emoji, every))
                }
            }
        }
    }
}

@Composable
private fun RoomButton(label: String, primary: Boolean, modifier: Modifier, colour: Color = TextMain, onClick: () -> Unit) {
    val shape = RoundedCornerShape(999.dp)
    Text(
        label, fontSize = 14.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, color = if (primary) Bg else colour,
        modifier = modifier.clip(shape).background(if (primary) Primary else Color.Transparent).border(1.dp, if (primary) Primary else Outline, shape)
            .clickable(onClick = onClick).padding(vertical = 12.dp)
    )
}
