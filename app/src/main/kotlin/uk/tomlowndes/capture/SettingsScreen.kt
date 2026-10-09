package uk.tomlowndes.capture

import android.Manifest
import android.app.TimePickerDialog
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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

private fun hhmm(minutes: Int) = "%02d:%02d".format(minutes / 60, minutes % 60)

@Composable
private fun TimeChip(minutes: Int, onPick: (Int) -> Unit) {
    val ctx = LocalContext.current
    Text(
        hhmm(minutes), fontWeight = FontWeight.Bold, fontSize = 14.sp,
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Surface2).clickable {
            TimePickerDialog(ctx, { _, h, m -> onPick(h * 60 + m) }, minutes / 60, minutes % 60, true).show()
        }.padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

@Composable
fun SettingsScreen(vault: Uri?, prefs: AppPrefs, onPickVault: () -> Unit) {
    val ctx = LocalContext.current
    var recurring by remember { mutableStateOf(prefs.recurring) }
    var morningOn by remember { mutableStateOf(prefs.morningOn) }
    var eveningOn by remember { mutableStateOf(prefs.eveningOn) }
    var morning by remember { mutableStateOf(prefs.morningMinute) }
    var evening by remember { mutableStateOf(prefs.eveningMinute) }
    var skip by remember { mutableStateOf(prefs.skipIfFilled) }

    val askNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { Reminders.scheduleAll(ctx, prefs) }
    fun apply() {
        if (Build.VERSION.SDK_INT >= 33 && !Reminders.canNotify(ctx) && (morningOn || eveningOn)) {
            askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else Reminders.scheduleAll(ctx, prefs)
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp).verticalScroll(rememberScrollState())) {
        ScreenTitle("Settings")

        GroupLabel("Reminders")
        SettingRow("Morning log", "Skipped if already filled in") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TimeChip(morning) { morning = it; prefs.morningMinute = it; apply() }
                AppSwitch(morningOn) { morningOn = it; prefs.morningOn = it; apply() }
            }
        }
        SettingRow("Evening log", "Skipped if already filled in") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TimeChip(evening) { evening = it; prefs.eveningMinute = it; apply() }
                AppSwitch(eveningOn) { eveningOn = it; prefs.eveningOn = it; apply() }
            }
        }
        SettingRow("Skip when already filled in", "No reminder if that part of today's log has content") {
            AppSwitch(skip) { skip = it; prefs.skipIfFilled = it }
        }

        Text("", Modifier.padding(top = 8.dp))
        GroupLabel("Tasks")
        SettingRow("Recurring tasks", "Ticking one creates the next occurrence") {
            AppSwitch(recurring) { recurring = it; prefs.recurring = it }
        }

        Text("", Modifier.padding(top = 8.dp))
        GroupLabel("Vault locations")
        SettingRow("Vault", vault?.let { Uri.decode(it.lastPathSegment ?: it.toString()) } ?: "Not selected") {
            TextButton(onClick = onPickVault) { Text(if (vault == null) "Choose" else "Change", color = Primary) }
        }
        PathField("Ideas folder", prefs.ideasDir) { prefs.ideasDir = it }
        PathField("Images folder", prefs.imagesDir) { prefs.imagesDir = it }
        PathField("Daily notes folder (blank = from Obsidian)", prefs.dailyDir) { prefs.dailyDir = it }
    }
}
