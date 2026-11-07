package uk.tomlowndes.capture

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Stuck? Pick one.: one suggestion big with Start and Another, the rest of that length underneath. */
@Composable
fun StuckScreen(vault: Uri?, snack: SnackbarHostState, onBack: () -> Unit, onStart: (StuckItem) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf<Map<StuckLength, List<StuckItem>>>(emptyMap()) }
    var length by remember { mutableStateOf(StuckLength.QUICK) }
    var pick by remember { mutableIntStateOf(0) }
    var adding by remember { mutableStateOf(false) }

    suspend fun load() {
        val uri = vault ?: return
        menu = withContext(Dispatchers.IO) { runCatching { FlightNotes.loadStuck(appVault(ctx, uri)) }.getOrDefault(emptyMap()) }
    }
    LaunchedEffect(vault) { load() }

    val items = menu[length].orEmpty()
    val big = items.getOrNull(pick % items.size.coerceAtLeast(1))

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = Muted) }
            Text("Stuck? Pick one.", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Row(Modifier.padding(top = 8.dp, bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StuckLength.values().forEach { l -> AppChip(l == length, { length = l; pick = 0 }, { Text(l.title) }) }
        }
        if (big != null) {
            ListCard(border = NextBorder, bg = NextBg) {
                Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(big.emoji, fontSize = 40.sp)
                    Text(big.name, fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
                    Text("${big.minutes} min", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Muted, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Start", color = OnPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp, textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f).clip(CircleShape).background(Primary).clickable { onStart(big) }.padding(vertical = 10.dp))
                        Text("Another", color = Muted, fontSize = 13.sp, textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f).clip(CircleShape).border(1.dp, Outline, CircleShape)
                                .clickable(enabled = items.size > 1) { pick++ }.padding(vertical = 10.dp))
                    }
                }
            }
        }
        val rest = items.filter { it != big }
        if (rest.isNotEmpty()) {
            GroupLabel(length.title)
            ListCard(Modifier.padding(top = 6.dp)) {
                rest.forEachIndexed { i, it ->
                    if (i > 0) HorizontalDivider(color = Outline)
                    Row(Modifier.fillMaxWidth().clickable { pick = items.indexOf(it) }.padding(horizontal = 16.dp, vertical = 11.dp)) {
                        Text("${it.emoji} ${it.name}", fontSize = 13.5.sp, modifier = Modifier.weight(1f))
                        Text("${it.minutes} min", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Muted)
                    }
                }
            }
        }
        Text("＋ Add", color = Primary, fontSize = 14.sp, modifier = Modifier.clickable { adding = true }.padding(vertical = 10.dp))
    }

    if (adding) {
        var emoji by remember { mutableStateOf("✨") }
        var name by remember { mutableStateOf("") }
        var mins by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { adding = false }, containerColor = Surface1,
            title = { Text(length.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(emoji, { emoji = it.take(4) }, singleLine = true, colors = fieldColors(), modifier = Modifier.weight(0.3f))
                        OutlinedTextField(mins, { mins = it.filter(Char::isDigit).take(3) }, singleLine = true, colors = fieldColors(),
                            placeholder = { Text("min") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(0.7f))
                    }
                    OutlinedTextField(name, { name = it }, singleLine = true, colors = fieldColors(), placeholder = { Text("Name") })
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank() && mins.isNotBlank() && emoji.isNotBlank(), onClick = {
                    adding = false
                    val uri = vault ?: return@TextButton
                    val item = StuckItem(emoji.trim(), name.trim(), mins.toInt())
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            runCatching { VaultIo.edit(appVault(ctx, uri), FlightNotes.STUCK) { FlightNotes.addStuck(it, length, item) } }
                        }.onFailure { snack.showSnackbar("Failed: ${it.message}") }
                        load()
                    }
                }) { Text("Add", color = Primary) }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel", color = Muted) } }
        )
    }
}
