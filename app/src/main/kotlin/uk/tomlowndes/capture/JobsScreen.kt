package uk.tomlowndes.capture

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun shortDate(d: LocalDate): String =
    d.format(DateTimeFormatter.ofPattern(if (d.isBefore(LocalDate.now().plusDays(7))) "EEE d" else "MMM d", Locale.UK))

internal fun statusColour(status: String): Color = when (status.lowercase()) {
    "interview" -> Amber
    "offer" -> Green
    "applied" -> Primary
    "rejected", "withdrawn" -> Muted
    else -> Purple
}

/** Jobs: open applications by next date, closed ones underneath, + Add job. Reads the Job Hunt notes. */
@Composable
fun JobsScreen(vault: Uri?, snack: SnackbarHostState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var jobs by remember { mutableStateOf<List<Job>?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(vault, refresh) {
        val uri = vault ?: return@LaunchedEffect
        jobs = withContext(Dispatchers.IO) { runCatching { Jobs.list(appVault(ctx, uri)) }.getOrDefault(emptyList()) }
    }
    fun act(f: (Vault) -> Unit) {
        val uri = vault ?: return
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { f(appVault(ctx, uri)) } }.onFailure { snack.showSnackbar("Failed: ${it.message}") }
            refresh++
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = Muted) }
            Text("Jobs", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        val all = jobs ?: return@Column
        val (open, closed) = Jobs.sorted(all)
        Text(
            "${open.size} open" + (Jobs.nextDate(all)?.let { " · next ${shortDate(it)}" } ?: ""),
            fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Muted, modifier = Modifier.padding(bottom = 12.dp)
        )
        if (open.isNotEmpty()) ListCard {
            open.forEachIndexed { i, j ->
                if (i > 0) HorizontalDivider(color = Outline)
                JobRow(j) { editing = j }
            }
        }
        if (closed.isNotEmpty()) {
            Box(Modifier.height(8.dp))
            GroupLabel("Closed")
            ListCard(Modifier.padding(top = 6.dp)) {
                closed.forEachIndexed { i, j ->
                    if (i > 0) HorizontalDivider(color = Outline)
                    JobRow(j) { editing = j }
                }
            }
        }
        Text("＋ Add job", color = Primary, fontSize = 14.sp, modifier = Modifier.clickable { adding = true }.padding(vertical = 10.dp))
    }

    if (adding) {
        var company by remember { mutableStateOf("") }
        var role by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { adding = false }, containerColor = Surface1,
            title = { Text("Add job") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(company, { company = it }, singleLine = true, colors = fieldColors(), placeholder = { Text("Company") })
                    OutlinedTextField(role, { role = it }, singleLine = true, colors = fieldColors(), placeholder = { Text("Role") })
                }
            },
            confirmButton = {
                TextButton(enabled = company.isNotBlank() || role.isNotBlank(), onClick = {
                    adding = false
                    act { v -> Jobs.create(v, company, role) }
                }) { Text("Add", color = Primary) }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel", color = Muted) } }
        )
    }

    editing?.let { j ->
        var picking by remember(j) { mutableStateOf(false) }
        if (picking) {
            PickDateDialog(j.next, onPick = { d -> picking = false; editing = null; act { v -> Jobs.setNext(v, j.path, d) } }, onDismiss = { picking = false })
        } else AlertDialog(
            onDismissRequest = { editing = null }, containerColor = Surface1,
            title = { Text(listOf(j.company, j.role).filter { it.isNotBlank() }.joinToString(" · ")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Jobs.STATUSES.chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEach { s ->
                                AppChip(s.equals(j.status, true), { editing = null; act { v -> Jobs.setStatus(v, j.path, s) } }, { Text(s) })
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picking = true }) { Text(j.next?.let { "Next ${shortDate(it)}" } ?: "Next date", color = Primary) } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Close", color = Muted) } }
        )
    }
}

@Composable
private fun JobRow(j: Job, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(statusColour(j.status)))
        Column(Modifier.weight(1f)) {
            Text(listOf(j.company, j.role).filter { it.isNotBlank() }.joinToString(" · "), fontSize = 13.5.sp, color = if (j.closed) Muted else TextMain)
            Text(j.status, fontSize = 12.sp, color = Muted)
        }
        j.next?.takeIf { !j.closed }?.let { DuePill(it) }
    }
}
