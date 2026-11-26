package uk.tomlowndes.capture

import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** The bar colour for a chore: green when fresh, blue filling, amber near due, red past due (home-life-v1). */
internal fun choreColour(fill: Double): Color = Tones.chore(fill).colour

/** A chore's fill bar. */
@Composable
internal fun ChoreBar(fill: Double, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(8.dp)).background(Outline)) {
        Box(Modifier.fillMaxWidth(fill.coerceIn(0.03, 1.0).toFloat()).height(8.dp).clip(RoundedCornerShape(8.dp)).background(choreColour(fill)))
    }
}

/** Home: chores that decay (home-life-v1). Tap = done today (with Undo), hold = edit, ＋ Add chore. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChoresScreen(vault: Uri?, snack: SnackbarHostState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var chores by remember { mutableStateOf<List<Chore>?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<Chore?>(null) }
    var adding by remember { mutableStateOf(false) }
    val today = LocalDate.now()

    LaunchedEffect(vault, refresh) {
        val uri = vault ?: return@LaunchedEffect
        chores = withContext(Dispatchers.IO) { runCatching { Chores.load(appVault(ctx, uri)) }.getOrDefault(emptyList()) }
    }
    fun act(f: (Vault) -> Unit) {
        val uri = vault ?: return
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { f(appVault(ctx, uri)) } }.onFailure { snack.showSnackbar("Failed: ${it.message}") }
            refresh++
        }
    }
    fun done(c: Chore) {
        val uri = vault ?: return
        chores = chores?.map { if (it.name == c.name) it.copy(done = today) else it }
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Chores.markDone(appVault(ctx, uri), c, today) } }
            refresh++
            r.onFailure { snack.showSnackbar("Failed: ${it.message}") }
            r.onSuccess { before ->
                if (snack.showSnackbar("${c.label} done", "Undo", duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) {
                    withContext(Dispatchers.IO) { runCatching { Chores.put(appVault(ctx, uri), c.name, before) } }
                    refresh++
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        Text("‹ Deck", color = Muted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clickable(onClick = onBack).padding(top = 10.dp))
        val all = chores
        val due = all?.let { Chores.due(it, today).size } ?: 0
        Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Home", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            SharedPill(vault)
            if (due > 0) Text("$due need doing", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Primary,
                modifier = Modifier.clip(RoundedCornerShape(99.dp)).background(PrimaryTint).padding(horizontal = 10.dp, vertical = 4.dp))
        }
        if (all == null) return@Column
        Chores.sorted(all, today).forEach { c ->
            val fill = c.fill(today)
            ListCard(Modifier.combinedClickable(onClick = { done(c) }, onLongClick = { editing = c })) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(c.label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text(c.ageLabel(today), fontSize = 12.sp, color = if (fill >= Chores.DUE) choreColour(fill) else Muted)
                    }
                    ChoreBar(fill, Modifier.padding(top = 8.dp))
                }
            }
        }
        Text("Tap = done today · hold = edit", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 2.dp))
        Text("＋ Add chore", color = Primary, fontSize = 14.sp, modifier = Modifier.clickable { adding = true }.padding(vertical = 10.dp))
    }

    if (adding || editing != null) {
        val c = editing
        var name by remember(c) { mutableStateOf(c?.label ?: "") }
        var every by remember(c) { mutableStateOf((c?.every ?: 7).toString()) }
        AlertDialog(
            onDismissRequest = { adding = false; editing = null }, containerColor = Surface1,
            title = { Text(if (c == null) "Add chore" else "Edit chore") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, singleLine = true, colors = fieldColors(), placeholder = { Text("🧹 Hoover downstairs") })
                    OutlinedTextField(every, { every = it.filter(Char::isDigit).take(3) }, singleLine = true, colors = fieldColors(),
                        label = { Text("Every how many days") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    if (c != null) Text("Remove chore", color = Warn, fontSize = 14.sp, modifier = Modifier.clickable {
                        editing = null; act { v -> Chores.remove(v, c) }
                    }.padding(top = 6.dp))
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    val (emoji, n) = Chores.splitEmoji(name)
                    val new = Chore(n, emoji, every.toIntOrNull()?.coerceIn(1, 365) ?: 7, c?.done ?: today)
                    adding = false; editing = null
                    act { v -> Chores.put(v, c?.name, new) }
                }) { Text("Save", color = Primary) }
            },
            dismissButton = { TextButton(onClick = { adding = false; editing = null }) { Text("Cancel", color = Muted) } }
        )
    }
}
