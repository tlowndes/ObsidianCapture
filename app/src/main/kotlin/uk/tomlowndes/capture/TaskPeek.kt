package uk.tomlowndes.capture

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** Top-level open tasks due today or earlier: what the ✓ pill counts. */
internal suspend fun dueCount(ctx: android.content.Context, vault: Uri): Int = withContext(Dispatchers.IO) {
    runCatching {
        val open = VaultWriter(ctx, vault).listTaskLists().open
        val today = LocalDate.now()
        Subtasks.nest(open, open).top.count { it.due != null && !it.due!!.isAfter(today) }
    }.getOrDefault(0)
}

/** The ✓ count pill shown on screens with no tab bar (four-fixes-v1): tap to peek at tasks without leaving. */
@Composable
internal fun TaskPeekPill(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        if (count > 0) "✓ $count" else "✓", color = OnPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold,
        modifier = modifier.clip(CircleShape).background(Primary).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp)
    )
}

private enum class PeekView(val label: String) { TODAY("Today"), OVERDUE("Overdue"), ALL("All open") }

/**
 * Task peek (four-fixes-v1): today's and overdue tasks over whatever screen you're on. Tick in place, add one,
 * tap to edit (with its sub-tasks), or jump to the full Tasks tab.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TaskPeekSheet(vault: Uri?, snack: SnackbarHostState, onAll: () -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf<List<TaskItem>?>(null) }
    var view by remember { mutableStateOf(PeekView.TODAY) }
    var query by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf("") }
    var reload by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<TaskItem?>(null) }
    val gone = remember { mutableStateOf(setOf<String>()) }
    val tagData = rememberTagData(vault)
    val today = LocalDate.now()

    LaunchedEffect(vault, reload) {
        val uri = vault ?: return@LaunchedEffect
        open = withContext(Dispatchers.IO) { runCatching { VaultWriter(ctx, uri).listTaskLists().open }.getOrNull() }
    }
    val all = open.orEmpty()
    val top = remember(open) { Subtasks.nest(all, all).top }
    val shown = top.filter { t ->
        (when (view) {
            PeekView.TODAY -> t.due == today
            PeekView.OVERDUE -> t.due != null && t.due!!.isBefore(today)
            PeekView.ALL -> true
        }) && t.raw !in gone.value && (query.isBlank() || t.text.contains(query, ignoreCase = true))
    }.sortedBy { it.due ?: LocalDate.MAX }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Surface1) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Tasks", fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("All tasks ›", color = Primary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(CircleShape).clickable { onDismiss(); onAll() }.padding(horizontal = 10.dp, vertical = 6.dp))
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PeekView.values().forEach { v ->
                    val n = top.count { t -> when (v) { PeekView.TODAY -> t.due == today; PeekView.OVERDUE -> t.due != null && t.due!!.isBefore(today); PeekView.ALL -> true } }
                    AppChip(view == v, { view = v }, { Text("${v.label} $n") })
                }
            }
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true, shape = RoundedCornerShape(14.dp), colors = fieldColors(),
                placeholder = { Text("Search tasks") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            LazyColumn(Modifier.heightIn(max = 360.dp).padding(top = 6.dp)) {
                if (open == null) item { Text("Loading…", color = Muted, modifier = Modifier.padding(12.dp)) }
                else if (shown.isEmpty()) item { Text(if (view == PeekView.TODAY) "Nothing due today." else "Nothing here.", color = Muted, modifier = Modifier.padding(12.dp)) }
                items(shown, key = { it.path + it.raw }) { t ->
                    val subs = Subtasks.nest(all, all).children[t].orEmpty()
                    Row(Modifier.fillMaxWidth().clickable { editing = t }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        TickBox(false, {
                            val uri = vault ?: return@TickBox
                            gone.value = gone.value + t.raw
                            scope.launch {
                                val r = withContext(Dispatchers.IO) { runCatching { VaultWriter(ctx, uri).completeTask(t, AppPrefs(ctx).recurring) } }
                                r.onSuccess { c -> AppPrefs(ctx).logTick(c.doneLine, c.nextLine) }
                                r.onFailure { gone.value = gone.value - t.raw; snack.showSnackbar("Failed: ${it.message}") }
                                reload++
                            }
                        }, size = 22.dp)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(Tags.strip(t.text), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val meta = listOfNotNull(t.due?.let { dueLabel(it) }, subs.takeIf { it.isNotEmpty() }?.let { "${it.size} steps" }).joinToString(" · ")
                            if (meta.isNotEmpty()) Text(meta, fontSize = 12.sp, color = if (t.due?.isBefore(today) == true) Warn else Muted)
                        }
                    }
                }
            }
            OutlinedTextField(
                value = draft, onValueChange = { draft = it }, singleLine = true, shape = RoundedCornerShape(14.dp), colors = fieldColors(),
                placeholder = { Text("＋ Add task") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    val t = draft.trim(); val uri = vault
                    if (t.isNotBlank() && uri != null) {
                        draft = ""
                        scope.launch {
                            val r = withContext(Dispatchers.IO) { runCatching { VaultWriter(ctx, uri).addTask(t, if (view == PeekView.TODAY) today.toString() else null) } }
                            r.onFailure { snack.showSnackbar("Couldn't add: ${it.message}") }
                            reload++
                        }
                    }
                }),
            )
        }
    }
    editing?.let { t ->
        EditTaskSheet(
            t, tagData, vault,
            onSave = { text, due, rule, minutes, priority ->
                editing = null
                val uri = vault
                if (uri != null) scope.launch {
                    withContext(Dispatchers.IO) { runCatching { VaultWriter(ctx, uri).editTask(t, text, due, rule, minutes, priority) } }
                        .onFailure { snack.showSnackbar("Failed: ${it.message}") }
                    reload++
                }
            },
            onRemove = {
                editing = null
                val uri = vault
                if (uri != null) scope.launch {
                    withContext(Dispatchers.IO) { runCatching { VaultWriter(ctx, uri).deleteTask(t) } }.onFailure { snack.showSnackbar("Failed: ${it.message}") }
                    reload++
                }
            },
            onDismiss = { editing = null; reload++ },
            steps = vault?.let { StepOps(ctx, it, t) },
            onSnack = { m -> scope.launch { snack.showSnackbar(m) } },
        )
    }
}
