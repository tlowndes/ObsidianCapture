package uk.tomlowndes.capture

import android.Manifest
import android.app.TimePickerDialog
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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

/** Settings › Reminders: morning briefing, evening wrap-up, task due alerts and the weekly review. */
@Composable
internal fun RemindersSection(prefs: AppPrefs) {
    val ctx = LocalContext.current
    var morningOn by remember { mutableStateOf(prefs.morningOn) }
    var eveningOn by remember { mutableStateOf(prefs.eveningOn) }
    var morning by remember { mutableStateOf(prefs.morningMinute) }
    var evening by remember { mutableStateOf(prefs.eveningMinute) }
    var dueOn by remember { mutableStateOf(prefs.dueOn) }
    var due by remember { mutableStateOf(prefs.dueMinute) }
    var weeklyOn by remember { mutableStateOf(prefs.weeklyOn) }
    var weekly by remember { mutableStateOf(prefs.weeklyMinute) }

    val askNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { Reminders.scheduleAll(ctx, prefs) }
    fun apply() {
        if (Build.VERSION.SDK_INT >= 33 && !Reminders.canNotify(ctx) && (morningOn || eveningOn || dueOn || weeklyOn)) {
            askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else Reminders.scheduleAll(ctx, prefs)
    }

    Column(Modifier.fillMaxWidth()) {
        GroupLabel("Reminders")
        SettingRow("Morning briefing") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TimeChip(morning) { morning = it; prefs.morningMinute = it; apply() }
                AppSwitch(morningOn) { morningOn = it; prefs.morningOn = it; apply() }
            }
        }
        SettingRow("Evening wrap-up") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TimeChip(evening) { evening = it; prefs.eveningMinute = it; apply() }
                AppSwitch(eveningOn) { eveningOn = it; prefs.eveningOn = it; apply() }
            }
        }
        SettingRow("Task due alerts") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TimeChip(due) { due = it; prefs.dueMinute = it; apply() }
                AppSwitch(dueOn) { dueOn = it; prefs.dueOn = it; apply() }
            }
        }
        SettingRow("Weekly review", "Sunday") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TimeChip(weekly) { weekly = it; prefs.weeklyMinute = it; apply() }
                AppSwitch(weeklyOn) { weeklyOn = it; prefs.weeklyOn = it; apply() }
            }
        }
    }
}
