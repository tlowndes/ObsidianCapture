package uk.tomlowndes.capture

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal val CAL_COLOURS = listOf("#7aa2ff", "#f5c06a", "#5fd39a", "#b39dff", "#ff8a80")

internal fun hexColour(hex: String): Color = runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Primary)

internal fun clockOf(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))

/** Settings › Proton Calendar: the linked calendars with on/off, sync time, and Add. */
@Composable
internal fun CalendarSection(vault: Uri?, startAdding: Boolean = false) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var sources by remember { mutableStateOf<List<CalendarSource>>(emptyList()) }
    var adding by remember { mutableStateOf(startAdding) }
    var removing by remember { mutableStateOf<CalendarSource?>(null) }
    var synced by remember { mutableStateOf(CalendarSync.lastSync(ctx)) }
    var broken by remember { mutableStateOf(CalendarSync.cached(ctx).any { !it.ok }) }

    suspend fun load() {
        val uri = vault ?: return
        sources = withContext(Dispatchers.IO) { runCatching { CalendarConfig.load(SafVault(ctx, uri)) }.getOrDefault(emptyList()) }
    }
    fun save(list: List<CalendarSource>) {
        sources = list
        val uri = vault ?: return
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching { CalendarConfig.save(SafVault(ctx, uri), list); CalendarSync.sync(ctx, uri) }
            }
            synced = CalendarSync.lastSync(ctx)
            broken = CalendarSync.cached(ctx).any { !it.ok }
        }
    }
    LaunchedEffect(vault) { load() }

    GroupLabel("Proton Calendar")
    SettingRow(
        if (sources.isEmpty()) "Not connected" else "${sources.size} calendar${if (sources.size == 1) "" else "s"}",
        when {
            sources.isEmpty() -> null
            broken -> "⚠ Reconnect · last ${if (synced > 0) clockOf(synced) else "never"}"
            synced > 0 -> "● synced ${clockOf(synced)} · next 7 days"
            else -> null
        }
    ) { TextButton(onClick = { adding = true }) { Text("Add", color = Primary) } }
    sources.forEach { s ->
        Row(
            Modifier.fillMaxWidth().clickable { removing = s }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(hexColour(s.colour)))
            Text(s.name, fontSize = 15.sp, modifier = Modifier.weight(1f))
            AppSwitch(s.enabled) { on -> save(sources.map { if (it.id == s.id) it.copy(enabled = on) else it }) }
        }
    }

    if (adding) AddCalendarSheet(onDismiss = { adding = false }) { s -> adding = false; save(sources + s) }
    removing?.let { s ->
        AlertDialog(
            onDismissRequest = { removing = null }, containerColor = Surface1,
            title = { Text(s.name) },
            confirmButton = { TextButton(onClick = { removing = null; save(sources.filter { it.id != s.id }) }) { Text("Remove", color = Warn) } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel", color = Muted) } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddCalendarSheet(onDismiss: () -> Unit, onAdd: (CalendarSource) -> Unit) {
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("Personal") }
    var custom by remember { mutableStateOf(false) }
    var colour by remember { mutableStateOf(CAL_COLOURS[0]) }
    var checking by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Surface1) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Add a Proton calendar", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            // The one place the spec allows steps text: it's needed to find the link in Proton.
            listOf(
                "In Proton Calendar on the web: Settings › Share › Share with anyone",
                "Pick Full view and copy the link",
                "Paste it here"
            ).forEachIndexed { i, step ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("${i + 1}", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Primary)
                    Text(step, fontSize = 13.sp, color = Muted)
                }
            }
            OutlinedTextField(
                url, { url = it; failed = false }, singleLine = true, colors = fieldColors(),
                placeholder = { Text("https://calendar.proton.me/…/calendar.ics") }, modifier = Modifier.fillMaxWidth(),
                isError = failed, shape = RoundedCornerShape(16.dp)
            )
            if (failed) Text("⚠ Link didn't load", color = Amber, fontSize = 12.sp)
            GroupLabel("Name")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Personal", "Work / interviews").forEach { n -> AppChip(!custom && name == n, { custom = false; name = n }, { Text(n) }) }
                AppChip(custom, { custom = true; name = "" }, { Text("＋ Custom") })
            }
            if (custom) OutlinedTextField(name, { name = it }, singleLine = true, colors = fieldColors(), modifier = Modifier.fillMaxWidth())
            GroupLabel("Colour")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CAL_COLOURS.forEach { c ->
                    Box(
                        Modifier.size(28.dp).clip(CircleShape).background(hexColour(c))
                            .then(if (c == colour) Modifier.border(2.dp, TextMain, CircleShape) else Modifier)
                            .clickable { colour = c }
                    )
                }
            }
            val ready = url.isNotBlank() && name.isNotBlank() && !checking
            Text(
                if (checking) "Checking…" else "Add calendar", textAlign = TextAlign.Center, fontWeight = FontWeight.Bold, color = OnPrimary,
                modifier = Modifier.padding(top = 6.dp).fillMaxWidth().clip(CircleShape)
                    .background(if (ready) Primary else Primary.copy(alpha = 0.4f))
                    .clickable(enabled = ready) {
                        checking = true
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) { CalendarSync.test(url) }
                            checking = false
                            if (ok) onAdd(CalendarSource(System.currentTimeMillis().toString(), name.trim(), url.trim(), colour)) else failed = true
                        }
                    }.padding(vertical = 13.dp)
            )
        }
    }
}
