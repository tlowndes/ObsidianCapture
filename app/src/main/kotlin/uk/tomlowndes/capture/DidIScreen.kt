package uk.tomlowndes.capture

import android.net.Uri
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val PassInk = Color(0xFF0D1630)
private val PassRule = Color(0xFFB7BFD2)
private val HM = DateTimeFormatter.ofPattern("HH:mm")

private enum class DidMode { PASS, FLOW, LOG }

/**
 * Did I? (hangar-phase1): a "Cleared to leave" pass you fill in one question at a time. Each yes stamps the
 * pass with the time in the vault, with Undo; the log answers "did I actually do it?" later.
 */
@Composable
fun DidIScreen(vault: Uri?, prefs: AppPrefs, snack: SnackbarHostState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var status by remember { mutableStateOf<List<DidStatus>>(emptyList()) }
    var stamps by remember { mutableStateOf<List<DidStamp>>(emptyList()) }
    var mode by remember { mutableStateOf(DidMode.PASS) }
    var skipped by remember { mutableStateOf(setOf<String>()) }
    var adding by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    val today = LocalDate.now()

    LaunchedEffect(vault, reload) {
        val uri = vault ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            runCatching { val v = appVault(ctx, uri); DidI.today(v, today) to DidI.loadStamps(v, today) }
        }.onSuccess { status = it.first; stamps = it.second }.onFailure { snack.showSnackbar("Couldn't read Did I?: ${it.message}") }
    }
    BackHandler { if (mode != DidMode.PASS) mode = DidMode.PASS else onBack() }

    fun yes(c: DidCheck) {
        val uri = vault ?: return
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        scope.launch {
            val s = withContext(Dispatchers.IO) { runCatching { DidI.stamp(appVault(ctx, uri), c.name) } }
                .getOrElse { snack.showSnackbar("Couldn't save: ${it.message}"); return@launch }
            status = status.map { if (it.check == c) it.copy(at = s.time) else it }
            stamps = stamps + s
            DidWidgets.changed(ctx)
            val r = snack.showSnackbar("${c.name} logged at ${s.time.format(HM)}", actionLabel = "Undo", duration = SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed) {
                withContext(Dispatchers.IO) { runCatching { DidI.undo(appVault(ctx, uri), today, s) } }
                reload++
                DidWidgets.changed(ctx)
            }
        }
    }

    val open = status.filter { it.at == null && it.check.name !in skipped }
    // The flow ends on its own once every check is stamped or skipped.
    LaunchedEffect(mode, open.isEmpty()) { if (mode == DidMode.FLOW && open.isEmpty()) { mode = DidMode.PASS; skipped = emptySet() } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (mode != DidMode.PASS) mode = DidMode.PASS else onBack() }) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = Muted)
            }
            Text(if (mode == DidMode.LOG) "Today's log" else "Did I?", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Green, modifier = Modifier.weight(1f))
            if (mode == DidMode.PASS) Text("Log", color = Primary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(CircleShape).clickable { mode = DidMode.LOG }.padding(horizontal = 12.dp, vertical = 8.dp))
        }
        when {
            vault == null -> Text("Pick your vault in Settings first.", color = Muted, modifier = Modifier.padding(top = 12.dp))
            mode == DidMode.FLOW -> open.firstOrNull()?.let { s ->
                Flow(s, done = status.count { it.at != null }, total = status.size,
                    onSkip = { skipped = skipped + s.check.name }, onYes = { yes(s.check) })
            }
            mode == DidMode.LOG -> Log(stamps)
            else -> {
                Pass(today, status) { yes(it) }
                val left = status.count { it.at == null }
                Spacer(Modifier.height(14.dp))
                if (left > 0) BigButton("Start checks · $left left", Green) { skipped = emptySet(); mode = DidMode.FLOW }
                else Text("Cleared. Every check is stamped for today.", color = Green, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp))
                Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("＋ Add a check", color = Primary, fontSize = 14.sp, modifier = Modifier.clickable { adding = true }.padding(vertical = 8.dp))
                    val tile = status.map { it.check.name }
                    if (tile.isNotEmpty()) {
                        val cur = tile.firstOrNull { it.equals(prefs.didTile, true) } ?: tile.first()
                        Text("Quick tile: $cur", color = Muted, fontSize = 13.sp, modifier = Modifier.clickable {
                            prefs.didTile = tile[(tile.indexOf(cur) + 1) % tile.size]; reload++
                        }.padding(vertical = 8.dp))
                    }
                }
                Text("Checks live in ${DidI.CHECKS}, and each day's log in ${DidI.LOG_DIR}.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp, bottom = 20.dp))
            }
        }
    }

    if (adding) AddCheckDialog(onDismiss = { adding = false }) { c ->
        adding = false
        val uri = vault ?: return@AddCheckDialog
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { DidI.saveCheck(appVault(ctx, uri), c) } }.onFailure { snack.showSnackbar("Couldn't save: ${it.message}") }
            reload++
            DidWidgets.changed(ctx)
        }
    }
}

/** The boarding pass: each check with its stamp time, or a dash. Tapping an open line stamps it. */
@Composable
private fun Pass(today: LocalDate, status: List<DidStatus>, onStamp: (DidCheck) -> Unit) {
    val cleared = status.isNotEmpty() && status.all { it.at != null }
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(18.dp)).background(TextMain)
            .then(if (cleared) Modifier.border(3.dp, Green, RoundedCornerShape(18.dp)) else Modifier).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (cleared) "CLEARED TO LEAVE" else "DID I?", color = PassInk, fontWeight = FontWeight.Bold, fontSize = 16.sp, letterSpacing = 0.6.sp, modifier = Modifier.weight(1f))
            Text(today.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)).uppercase(Locale.UK), color = PassInk, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
        Rule()
        if (status.isEmpty()) Text("No checks yet. Add one below.", color = PassInk, fontSize = 13.sp)
        status.forEach { s ->
            val done = s.at != null
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(enabled = !done) { onStamp(s.check) }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("${s.check.emoji}  ${s.check.name.uppercase(Locale.UK)}", color = PassInk.copy(alpha = if (done) 1f else 0.5f), fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                Text(if (done) "${s.at!!.format(HM)} ✓" else "— tap", color = PassInk.copy(alpha = if (done) 1f else 0.5f), fontSize = 14.sp,
                    fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            }
        }
        Rule()
        Text(DidI.progress(status).uppercase(Locale.UK), color = PassInk, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun Rule() = Box(Modifier.fillMaxWidth().height(2.dp).background(PassRule))

/** One question, full width, with Skip and Yes. */
@Composable
private fun Flow(s: DidStatus, done: Int, total: Int, onSkip: () -> Unit, onYes: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(total) { i -> Box(Modifier.weight(1f).height(5.dp).clip(CircleShape).background(if (i < done) Green else Outline)) }
        }
        Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(s.check.emoji, fontSize = 48.sp)
            Text(s.check.question, fontSize = 26.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text("Look at it now, then tap.", color = Muted, fontSize = 14.sp)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Skip", color = TextMain, fontSize = 15.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1f).clip(CircleShape)
                .border(1.dp, Outline, CircleShape).clickable(onClick = onSkip).padding(vertical = 16.dp))
            Text("Yes, done", color = OnPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.weight(1f)
                .clip(CircleShape).background(Green).clickable(onClick = onYes).padding(vertical = 16.dp))
        }
    }
}

@Composable
private fun Log(stamps: List<DidStamp>) {
    Text(if (stamps.isEmpty()) "Nothing logged yet today." else "Yes, you did.", fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
    Text(LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.UK)), color = Muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 12.dp))
    if (stamps.isNotEmpty()) ListCard {
        stamps.sortedByDescending { it.time }.forEachIndexed { i, s ->
            if (i > 0) HorizontalDivider(color = Outline)
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(s.time.format(HM), color = Muted, fontFamily = FontFamily.Monospace, fontSize = 13.sp, modifier = Modifier.padding(end = 14.dp))
                Text(s.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun BigButton(label: String, bg: Color, onClick: () -> Unit) =
    Text(label, color = OnPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().clip(CircleShape).background(bg).clickable(onClick = onClick).padding(vertical = 15.dp))

@Composable
private fun AddCheckDialog(onDismiss: () -> Unit, onAdd: (DidCheck) -> Unit) {
    var emoji by remember { mutableStateOf("✓") }
    var name by remember { mutableStateOf("") }
    var question by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Surface1,
        title = { Text("Add a check") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(emoji, { emoji = it.take(4) }, singleLine = true, colors = fieldColors(), modifier = Modifier.weight(0.3f))
                    OutlinedTextField(name, { name = it.replace("·", "") }, singleLine = true, colors = fieldColors(), placeholder = { Text("Name, e.g. Plugs") }, modifier = Modifier.weight(0.7f))
                }
                OutlinedTextField(question, { question = it.replace("·", "") }, singleLine = true, colors = fieldColors(), placeholder = { Text("Question (optional)") })
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                val n = name.trim()
                val e = emoji.trim().ifEmpty { "✓" }
                onAdd(if (question.isBlank()) DidCheck(e, n) else DidCheck(e, n, question.trim()))
            }) { Text("Add", color = Primary) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Muted) } }
    )
}
