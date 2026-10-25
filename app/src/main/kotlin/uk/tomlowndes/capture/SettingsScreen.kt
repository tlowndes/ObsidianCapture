package uk.tomlowndes.capture

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun SettingRow(title: String, sub: String? = null, trailing: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp)
            if (sub != null) Text(sub, fontSize = 12.sp, color = Muted)
        }
        trailing()
    }
}

@Composable
internal fun AppSwitch(checked: Boolean, onChange: (Boolean) -> Unit) = Switch(
    checked, onChange,
    colors = SwitchDefaults.colors(
        checkedTrackColor = Primary, checkedThumbColor = Color.White,
        uncheckedTrackColor = Outline, uncheckedThumbColor = Muted, uncheckedBorderColor = Outline
    )
)

@Composable
internal fun PathField(label: String, value: String, onChange: (String) -> Unit) {
    var v by remember(value) { mutableStateOf(value) }
    OutlinedTextField(
        v, { v = it; onChange(it) }, label = { Text(label) }, singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), colors = fieldColors()
    )
}

@Composable
fun SettingsScreen(vault: Uri?, prefs: AppPrefs, onPickVault: () -> Unit, onOpenCards: () -> Unit, onOpenHabits: () -> Unit, onOpenWeekly: () -> Unit) {
    val ctx = LocalContext.current
    var notesOpen by remember { mutableStateOf(false) }
    var cardsOn by remember { mutableStateOf<DeckLayout?>(null) }
    val secrets = remember { SecretStore(ctx) }
    var masked by remember { mutableStateOf(secrets.maskedKey()) }
    var editingKey by remember { mutableStateOf(false) }
    if (editingKey) {
        var draft by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { editingKey = false }, containerColor = Surface1,
            title = { Text("Claude API key") },
            text = {
                OutlinedTextField(draft, { draft = it }, singleLine = true, colors = fieldColors(),
                    visualTransformation = PasswordVisualTransformation(), placeholder = { Text("sk-ant-…") })
            },
            confirmButton = {
                TextButton(onClick = { secrets.claudeKey = draft; masked = secrets.maskedKey(); editingKey = false }, enabled = draft.isNotBlank()) {
                    Text("Save", color = Primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { if (masked != null) { secrets.claudeKey = null; masked = null }; editingKey = false }) {
                    Text(if (masked != null) "Remove" else "Cancel", color = Muted)
                }
            }
        )
    }
    LaunchedEffect(vault) {
        val uri = vault ?: return@LaunchedEffect
        cardsOn = withContext(Dispatchers.IO) { runCatching { DeckConfig.load(SafVault(ctx, uri)) }.getOrNull() }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp).verticalScroll(rememberScrollState())) {
        ScreenTitle("Settings")

        RemindersSection(prefs)

        Text("", Modifier.padding(top = 8.dp))
        CalendarSection(vault)

        Text("", Modifier.padding(top = 8.dp))
        GroupLabel("Flight Deck")
        SettingRow("Cards on home") {
            TextButton(onClick = onOpenCards) {
                Text(cardsOn?.let { "${it.onCount} of ${HomeCard.values().size} ›" } ?: "›", color = Muted)
            }
        }
        SettingRow("Weekly review") { TextButton(onClick = onOpenWeekly) { Text("›", color = Muted) } }
        SettingRow("Habits") { TextButton(onClick = onOpenHabits) { Text("›", color = Muted) } }

        Text("", Modifier.padding(top = 8.dp))
        GroupLabel("Brain dump AI")
        SettingRow("Claude API key", masked ?: "Not set") {
            TextButton(onClick = { editingKey = true }) { Text(if (masked == null) "Add" else "Change", color = Primary) }
        }
        SettingRow("Queued dumps") { Text("${prefs.captureQueue.size}", color = Muted, modifier = Modifier.padding(end = 12.dp)) }

        Text("", Modifier.padding(top = 8.dp))
        GroupLabel("Vault")
        SettingRow("Vault folder", vault?.let { Uri.decode(it.lastPathSegment ?: it.toString()) } ?: "Not selected") {
            TextButton(onClick = onPickVault) { Text(if (vault == null) "Choose" else "Change", color = Primary) }
        }
        SettingRow("Notes used") { TextButton(onClick = { notesOpen = !notesOpen }) { Text(if (notesOpen) "⌄" else "›", color = Muted) } }
        if (notesOpen) {
            PathField("Projects folder", prefs.ideasDir) { prefs.ideasDir = it }
            PathField("Images folder", prefs.imagesDir) { prefs.imagesDir = it }
            listOf(VaultTasks.QUICK_TASK, Jobs.DIR, FlightNotes.STUCK, FlightNotes.FOCUS_LOG, Habits.PATH, DeckConfig.PATH).forEach {
                Text(it, fontSize = 12.sp, color = Muted, modifier = Modifier.padding(vertical = 2.dp))
            }
        }
        Text("", Modifier.padding(bottom = 16.dp))
    }
}
