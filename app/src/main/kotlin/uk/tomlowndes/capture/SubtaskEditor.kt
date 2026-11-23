package uk.tomlowndes.capture

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** The vault side of the Edit task sub-task list (four-fixes-v1): each call writes at once and returns what it learnt. */
internal class StepOps(private val ctx: Context, private val vault: Uri, private val parent: TaskItem) {
    private fun w() = VaultWriter(ctx, vault)

    private fun children(open: List<TaskItem>): List<TaskItem> {
        val up = Subtasks.parents(open)
        return open.filter { c -> up[c]?.let { it.raw == parent.raw && it.path == parent.path } == true }
    }

    suspend fun load(): List<TaskItem> = withContext(Dispatchers.IO) { children(w().listTaskLists().open) }

    /** Adds a step and returns the new child. */
    suspend fun add(text: String, known: List<TaskItem>): TaskItem? = withContext(Dispatchers.IO) {
        val w = w()
        w.addSubtasks(parent, listOf(Step(text, null)))
        children(w.listTaskLists().open).lastOrNull { c -> known.none { it.raw == c.raw } } ?: children(w.listTaskLists().open).lastOrNull()
    }

    suspend fun tick(c: TaskItem) = withContext(Dispatchers.IO) { w().completeTask(c, recurring = false); Unit }

    suspend fun rename(c: TaskItem, text: String): TaskItem = withContext(Dispatchers.IO) {
        val line = w().editTask(c, text, c.due, c.recurrence, Deck.minutes(c))
        c.copy(raw = line, text = text)
    }

    suspend fun remove(c: TaskItem) = withContext(Dispatchers.IO) { w().deleteTask(c); Unit }

    suspend fun move(c: TaskItem, target: TaskItem, after: Boolean) = withContext(Dispatchers.IO) { w().moveSubtask(parent, c, target, after) }
}

private class StepRow(val id: Int, val task: TaskItem, val done: Boolean = false) {
    fun with(task: TaskItem = this.task, done: Boolean = this.done) = StepRow(id, task, done)
}

/**
 * The Sub-tasks list on Edit task: tick, drag ≡ to reorder, rename, ✕ to remove, ＋ Add a step.
 * Edits write to the note as they happen, so the list never needs a Save.
 */
@Composable
internal fun SubtaskEditor(ops: StepOps, onSnack: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<StepRow>?>(null) }
    var nextId by remember { mutableStateOf(0) }
    var draft by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val rowH = 48.dp
    val rowPx = with(LocalDensity.current) { rowH.toPx() }

    LaunchedEffect(Unit) {
        runCatching { ops.load() }.onSuccess { l -> rows = l.map { StepRow(nextId++, it) } }.onFailure { rows = emptyList(); onSnack("Couldn't load sub-tasks: ${it.message}") }
    }
    fun fail(e: Throwable) = onSnack(e.message ?: "Couldn't save that")

    val list = rows
    Text("SUB-TASKS${list?.let { " · ${it.size}" } ?: ""}", fontSize = 11.sp, letterSpacing = 1.sp, color = Muted, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
    if (list == null) { Text("Loading…", color = Muted, fontSize = 13.sp); return }

    list.forEach { row -> androidx.compose.runtime.key(row.id) {
        val c = row.task
        var text by remember(row.id, c.raw) { mutableStateOf(c.text) }
        var drag by remember(row.id) { mutableStateOf(0f) }
        Row(
            Modifier.fillMaxWidth().height(rowH).offset { androidx.compose.ui.unit.IntOffset(0, drag.roundToInt()) },
            verticalAlignment = Alignment.CenterVertically
        ) {
            TickBox(row.done, {
                if (!row.done) scope.launch { runCatching { ops.tick(c) }.onSuccess { rows = rows?.map { if (it.id == row.id) it.with(done = true) else it } }.onFailure(::fail) }
            }, size = 22.dp)
            BasicTextField(
                value = text, onValueChange = { text = it }, singleLine = true, enabled = !row.done,
                textStyle = TextStyle(color = if (row.done) Muted else TextMain, fontSize = 15.sp, textDecoration = if (row.done) TextDecoration.LineThrough else null),
                cursorBrush = SolidColor(Primary),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    val t = text.trim()
                    if (t.isNotBlank() && t != c.text) scope.launch {
                        runCatching { ops.rename(c, t) }.onSuccess { n -> rows = rows?.map { if (it.id == row.id) it.with(task = n) else it } }.onFailure(::fail)
                    }
                }),
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp)
            )
            if (!row.done) {
                Text("✕", color = Muted, fontSize = 16.sp, modifier = Modifier.clip(CircleShape).clickable {
                    scope.launch { runCatching { ops.remove(c) }.onSuccess { rows = rows?.filter { it.id != row.id } }.onFailure(::fail) }
                }.padding(8.dp))
                Text("≡", color = Muted, fontSize = 20.sp, modifier = Modifier.padding(start = 2.dp).pointerInput(row.id, list.size) {
                    detectDragGesturesAfterLongPress(
                        onDrag = { change, amount -> change.consume(); drag += amount.y },
                        onDragCancel = { drag = 0f },
                        onDragEnd = {
                            val cur = rows.orEmpty().filter { !it.done }
                            val from = cur.indexOfFirst { it.id == row.id }
                            val to = (from + (drag / rowPx).roundToInt()).coerceIn(0, cur.lastIndex)
                            drag = 0f
                            if (from >= 0 && to != from) {
                                val target = cur[to]
                                scope.launch {
                                    runCatching { ops.move(c, target.task, after = to > from) }.onSuccess {
                                        rows = rows?.let { all ->
                                            val open = all.filter { !it.done }.toMutableList()
                                            open.add(to, open.removeAt(from))
                                            val it2 = open.iterator()
                                            all.map { r -> if (r.done) r else it2.next() }
                                        }
                                    }.onFailure(::fail)
                                }
                            }
                        }
                    )
                }.padding(8.dp))
            }
        }
    } }
    fun add() {
        val t = draft.trim()
        if (t.isBlank() || busy) return
        busy = true
        draft = ""
        scope.launch {
            runCatching { ops.add(t, rows.orEmpty().map { it.task }) }
                .onSuccess { n -> if (n != null) rows = rows.orEmpty() + StepRow(nextId++, n) }
                .onFailure(::fail)
            busy = false
        }
    }
    Row(Modifier.fillMaxWidth().height(rowH), verticalAlignment = Alignment.CenterVertically) {
        Text("＋", color = Primary, fontSize = 20.sp, modifier = Modifier.size(22.dp))
        BasicTextField(
            value = draft, onValueChange = { draft = it }, singleLine = true,
            textStyle = TextStyle(color = TextMain, fontSize = 15.sp), cursorBrush = SolidColor(Primary),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { add() }),
            decorationBox = { inner -> Box { if (draft.isEmpty()) Text("Add a step", color = Muted, fontSize = 15.sp); inner() } },
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp)
        )
    }
}
