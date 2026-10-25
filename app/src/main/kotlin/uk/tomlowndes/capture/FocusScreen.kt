package uk.tomlowndes.capture

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Focus: countdown for the current session, Pause / Finish, and today's sessions underneath. */
@Composable
fun FocusScreen(vault: Uri?, prefs: AppPrefs, snack: SnackbarHostState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(Focus.state(ctx)) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var today by remember { mutableStateOf<List<FocusSession>>(emptyList()) }
    var asking by remember { mutableStateOf(false) }

    suspend fun loadToday() {
        val uri = vault ?: return
        today = withContext(Dispatchers.IO) { runCatching { FlightNotes.loadToday(SafVault(ctx, uri)) }.getOrDefault(emptyList()) }
    }
    LaunchedEffect(vault) { loadToday() }
    LaunchedEffect(state) { while (state?.running == true) { now = System.currentTimeMillis(); delay(500) } }

    /** Logs the session, ticks the task if [done], and clears the timer. */
    fun finish(done: Boolean) {
        val s = Focus.stop(ctx) ?: return
        state = null
        val uri = vault ?: return
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val started = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(s.startedAt), java.time.ZoneId.systemDefault())
                    FlightNotes.logFocus(SafVault(ctx, uri), FocusSession(started.withSecond(0).withNano(0), s.task, s.elapsedMinutes()))
                    if (done && s.raw != null && s.path != null) {
                        VaultWriter(ctx, uri).completeTask(TaskItem(s.raw, s.task, TaskRules.due(s.raw), TaskRules.recurrence(s.raw), s.path), prefs.recurring)
                    }
                }
            }
            r.onFailure { snack.showSnackbar("Failed: ${it.message}") }
            loadToday()
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = Muted) }
            Text("Focus", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        val s = state
        if (s != null) {
            val left = s.remaining(now)
            Text(s.task, fontSize = 15.sp, color = Muted, modifier = Modifier.padding(top = 8.dp))
            Text(
                "%02d:%02d".format(left / 60_000, (left / 1000) % 60), fontSize = 64.sp, fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 28.dp)
            )
            Box(Modifier.fillMaxWidth().padding(top = 16.dp).height(6.dp).clip(CircleShape).background(Surface2)) {
                Box(Modifier.fillMaxWidth(((s.totalMs - left).toFloat() / s.totalMs).coerceIn(0f, 1f)).height(6.dp).background(Primary))
            }
            Text("of ${s.totalMs / 60_000} min", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Muted,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            Row(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (s.running) "Pause" else "Resume", color = Muted, fontSize = 14.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).clip(CircleShape).border(1.dp, Outline, CircleShape).clickable {
                        if (s.running) Focus.pause(ctx) else Focus.resume(ctx)
                        state = Focus.state(ctx)
                    }.padding(vertical = 13.dp)
                )
                Text(
                    "Finish", color = OnPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).clip(CircleShape).background(Primary)
                        .clickable { if (s.raw != null) asking = true else finish(false) }.padding(vertical = 13.dp)
                )
            }
        }

        Row(Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            GroupLabel("Today")
            Text("${today.sumOf { it.minutes }} min", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Muted)
        }
        if (today.isNotEmpty() || s != null) ListCard {
            today.forEachIndexed { i, f ->
                if (i > 0) HorizontalDivider(color = Outline)
                SessionRow(f.at.format(DateTimeFormatter.ofPattern("HH:mm")), f.task, "${f.minutes}m")
            }
            if (s != null) {
                if (today.isNotEmpty()) HorizontalDivider(color = Outline)
                SessionRow(java.time.Instant.ofEpochMilli(s.startedAt).atZone(java.time.ZoneId.systemDefault()).toLocalTime()
                    .format(DateTimeFormatter.ofPattern("HH:mm")), s.task, "now")
            }
        }
    }

    if (asking) AlertDialog(
        onDismissRequest = { asking = false }, containerColor = Surface1,
        title = { Text(state?.task ?: "") },
        confirmButton = { TextButton(onClick = { asking = false; finish(true) }) { Text("Done", color = Primary) } },
        dismissButton = { TextButton(onClick = { asking = false; finish(false) }) { Text("Not yet", color = Muted) } }
    )
}

@Composable
private fun SessionRow(time: String, task: String, mins: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(time, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Muted)
        Text(task, fontSize = 13.5.sp, modifier = Modifier.weight(1f))
        Text(mins, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Muted)
    }
}
