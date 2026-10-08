package uk.tomlowndes.capture

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences("capture", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.action == Intent.ACTION_SEND) {
            handleShare(intent)
            return
        }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        setContent {
            CaptureTheme {
                var vault by remember { mutableStateOf(prefs.getString("vault", null)?.let(Uri::parse)) }
                CaptureScreen(vault) { uri ->
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                    prefs.edit().putString("vault", uri.toString()).apply()
                    vault = uri
                }
            }
        }
    }

    /** Share-sheet entry: fetch the page, save it to Read It Later, then close. Shows a brief "Saving" screen. */
    private fun handleShare(i: Intent) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        setContent {
            CaptureTheme {
                Box(Modifier.fillMaxSize().background(Bg), contentAlignment = Alignment.Center) {
                    Text("Saving to Read It Later…", color = Muted, fontSize = 16.sp)
                }
            }
        }
        val text = i.getStringExtra(Intent.EXTRA_TEXT).orEmpty().trim()
        val url = Regex("https?://\\S+").find(text)?.value ?: text
        val title = i.getStringExtra(Intent.EXTRA_SUBJECT) ?: text.replace(url, "").trim().ifBlank { null }
        val vault = prefs.getString("vault", null)?.let(Uri::parse)
        lifecycleScope.launch {
            val msg = when {
                vault == null -> "Open Vault Capture once and pick your vault first"
                url.isBlank() -> "Nothing to save"
                else -> withContext(Dispatchers.IO) { runCatching { ReadLater.save(applicationContext, vault, url, title) } }
                    .fold({ if (it) "Saved page to Read It Later" else "Saved link only (couldn't fetch the page)" },
                        { "Failed: ${it.message}" })
            }
            Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
            finish()
        }
    }
}

// ---- design tokens (mirror design/mockup.html) ----
private val Bg = Color(0xFF13111A)
private val Surface1 = Color(0xFF1D1A28)
private val Surface2 = Color(0xFF262236)
private val Outline = Color(0xFF37314D)
private val Primary = Color(0xFF8B6CFF)
private val OnPrimary = Color(0xFF17102E)
private val TextMain = Color(0xFFECE9F6)
private val Muted = Color(0xFF9A94B3)
private val TaskTint = Color(0xFF16352C)
private val LinkTint = Color(0xFF142C40)

@Composable
fun CaptureTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Primary, onPrimary = OnPrimary,
            background = Bg, onBackground = TextMain,
            surface = Bg, onSurface = TextMain,
            surfaceVariant = Surface1, onSurfaceVariant = Muted,
            secondaryContainer = Surface2, onSecondaryContainer = Primary,
            outline = Outline, outlineVariant = Outline,
            inverseSurface = Color(0xFFE8E3FA), inverseOnSurface = Color(0xFF1B1630),
        ),
        content = content
    )
}

private data class Entry(val isLink: Boolean, val text: String, val sub: String)

private fun dueLabel(d: LocalDate): String {
    val today = LocalDate.now()
    return when (d) {
        today -> "today"
        today.plusDays(1) -> "tomorrow"
        else -> d.format(DateTimeFormatter.ofPattern("d MMM", Locale.UK))
    }
}

@Composable
private fun Logo() {
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(13.dp))
            .background(Brush.linearGradient(listOf(Color(0xFFA58BFF), Color(0xFF5B3FD6)))),
        contentAlignment = Alignment.Center
    ) {
        Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.requiredSize(70.dp))
    }
}

@Composable
private fun ModeSwitch(linkMode: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(CircleShape).background(Surface1).padding(4.dp)) {
        listOf("Task" to false, "Read later" to true).forEach { (label, isLink) ->
            val on = linkMode == isLink
            Box(
                Modifier.weight(1f).clip(CircleShape)
                    .background(if (on) Primary else Color.Transparent)
                    .clickable { onChange(isLink) }
                    .padding(vertical = 11.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(label, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = if (on) OnPrimary else Muted)
            }
        }
    }
}

@Composable
private fun HistoryItem(e: Entry) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Surface1)
    ) {
        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(if (e.isLink) LinkTint else TaskTint),
                contentAlignment = Alignment.Center) { Text(if (e.isLink) "🔗" else "✓") }
            Column {
                Text(e.text, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp)
                Text(e.sub, fontSize = 12.sp, color = Muted)
            }
        }
    }
}

@Composable
private fun TaskRow(t: TaskItem, onDone: () -> Unit, onRemove: () -> Unit) {
    val overdue = t.due != null && t.due.isBefore(LocalDate.now())
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Surface1)
    ) {
        Row(Modifier.padding(start = 13.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(26.dp).clip(CircleShape).border(2.dp, Primary, CircleShape).clickable(onClick = onDone)
            )
            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                Text(t.text, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                t.due?.let {
                    Text("Due ${dueLabel(it)}", fontSize = 12.sp, color = if (overdue) Color(0xFFFF8A80) else Muted)
                }
            }
            TextButton(onClick = onRemove) { Text("✕", color = Muted, fontSize = 16.sp) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureScreen(vault: Uri?, onVaultPicked: (Uri) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val history = remember { mutableStateListOf<Entry>() }
    val tasks = remember { mutableStateListOf<TaskItem>() }

    fun reload() {
        val uri = vault ?: return
        scope.launch {
            val list = withContext(Dispatchers.IO) { runCatching { VaultWriter(ctx, uri).listTasks() } }
            list.onSuccess { tasks.clear(); tasks.addAll(it) }
        }
    }
    LaunchedEffect(vault) { reload() }

    /** Tick off ([remove] = false) or delete a task in the note, with an Undo snackbar. */
    fun taskAction(item: TaskItem, remove: Boolean) {
        val uri = vault ?: return
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val w = VaultWriter(ctx, uri)
                    if (remove) { w.deleteTask(item.raw); null } else w.completeTask(item.raw)
                }
            }
            reload()
            result.onFailure { snack.showSnackbar("Failed: ${it.message}") }
            result.onSuccess { doneLine ->
                val r = snack.showSnackbar(
                    if (remove) "Task removed" else "Task completed",
                    actionLabel = "Undo", duration = SnackbarDuration.Short
                )
                if (r == SnackbarResult.ActionPerformed) {
                    withContext(Dispatchers.IO) { runCatching { VaultWriter(ctx, uri).restoreTask(item.raw, doneLine) } }
                    reload()
                }
            }
        }
    }

    var linkMode by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var due by remember { mutableStateOf<LocalDate?>(null) }
    var showPicker by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) onVaultPicked(uri)
    }

    fun submit() {
        val value = text.trim()
        val uri = vault
        if (value.isEmpty() || busy) return
        if (uri == null) { scope.launch { snack.showSnackbar("Pick your vault folder first") }; return }
        busy = true
        val wasLink = linkMode
        val wasDue = due
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (wasLink) {
                        val url = if (Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(value)) value
                        else "https://$value"
                        ReadLater.save(ctx, uri, url, null)
                    } else { VaultWriter(ctx, uri).addTask(value, wasDue?.toString()); true }
                }
            }
            busy = false
            result.onSuccess { fullPage ->
                if (wasLink) history.add(0, Entry(true, value, if (fullPage) "Read later · full page" else "Read later · link only"))
                else reload()
                text = ""; due = null
                snack.showSnackbar(
                    if (!wasLink) "Task added"
                    else if (fullPage) "Saved page to Read It Later" else "Saved link only (couldn't fetch the page)"
                )
            }.onFailure { snack.showSnackbar("Failed: ${it.message}") }
        }
    }

    Scaffold(
        containerColor = Bg,
        snackbarHost = { SnackbarHost(snack) }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 20.dp)) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Spacer(Modifier.height(8.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Logo()
                        Text("Capture", fontSize = 34.sp, fontWeight = FontWeight.Bold)
                    }
                    Text("Straight into your vault", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
                }
                Spacer(Modifier.height(2.dp))

                ModeSwitch(linkMode) { linkMode = it }

                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    label = { Text(if (linkMode) "Paste a link" else "What needs doing?") },
                    minLines = if (linkMode) 1 else 3,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp),
                    keyboardOptions = if (linkMode) KeyboardOptions(keyboardType = KeyboardType.Uri)
                    else KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Surface1, unfocusedContainerColor = Surface1,
                        focusedBorderColor = Primary, unfocusedBorderColor = Outline,
                        focusedLabelColor = Primary, unfocusedLabelColor = Muted, cursorColor = Primary
                    )
                )

                if (!linkMode) {
                    val today = LocalDate.now()
                    val custom = due != null && due != today && due != today.plusDays(1)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(due == today, { due = if (due == today) null else today },
                            { Text("Today") }, shape = RoundedCornerShape(12.dp))
                        FilterChip(due == today.plusDays(1),
                            { due = if (due == today.plusDays(1)) null else today.plusDays(1) },
                            { Text("Tomorrow") }, shape = RoundedCornerShape(12.dp))
                        FilterChip(custom, { showPicker = true },
                            { Text(if (custom) dueLabel(due!!) else "Pick date") }, shape = RoundedCornerShape(12.dp))
                    }
                }

                Button(
                    onClick = ::submit,
                    enabled = text.isNotBlank() && !busy,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = OnPrimary),
                    modifier = Modifier.fillMaxWidth().height(54.dp)
                ) { Text(if (linkMode) "Save link" else "Add task", fontWeight = FontWeight.Bold, fontSize = 16.sp) }

                if (!linkMode) {
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("TO DO · ${tasks.size}", fontSize = 12.sp, letterSpacing = 1.2.sp, color = Muted,
                            fontWeight = FontWeight.SemiBold)
                        TextButton(onClick = { reload() }) {
                            Text("Refresh", color = Primary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        tasks.forEach { t ->
                            TaskRow(t, onDone = { taskAction(t, false) }, onRemove = { taskAction(t, true) })
                        }
                    }
                } else if (history.isNotEmpty()) {
                    Text("JUST ADDED", fontSize = 12.sp, letterSpacing = 1.2.sp, color = Muted,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        history.take(5).forEach { HistoryItem(it) }
                    }
                }
            }

            HorizontalDivider(color = Outline)
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    vault?.let { "Vault: " + Uri.decode(it.lastPathSegment ?: it.toString()) } ?: "No vault selected",
                    fontSize = 12.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { picker.launch(null) }) {
                    Text(if (vault == null) "Choose" else "Change", color = Primary, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }

    if (showPicker) {
        val state = rememberDatePickerState()
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        due = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                    }
                    showPicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { due = null; showPicker = false }) { Text("No date") }
            }
        ) { DatePicker(state) }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF13111A, heightDp = 780)
@Composable
private fun CapturePreview() = CaptureTheme { CaptureScreen(null) {} }
