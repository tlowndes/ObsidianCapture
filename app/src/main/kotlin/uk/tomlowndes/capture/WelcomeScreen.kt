package uk.tomlowndes.capture

import android.net.Uri
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the welcome found in the picked folder (care-welcome-v1). */
private data class VaultCheck(val looksLikeVault: Boolean, val notes: Int, val openTasks: Int, val hasInbox: Boolean, val hasQuickTask: Boolean)

/**
 * The phone's first run (care-welcome-v1), like Windows' Welcome. With no [vault] it asks for the folder; once
 * picked it checks the folder and says what it found. [onDone] goes to Deck; [onPick] opens Android's folder picker.
 */
@Composable
fun WelcomeScreen(vault: Uri?, onPick: () -> Unit, onDone: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Bg).safeDrawingPadding().padding(horizontal = 22.dp)) {
        if (vault == null) Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.weight(1f))
            Text("◎", fontSize = 36.sp, color = Primary, textAlign = TextAlign.Center,
                modifier = Modifier.size(76.dp).clip(RoundedCornerShape(22.dp)).background(PrimaryTint)
                    .border(1.dp, Primary, RoundedCornerShape(22.dp)).padding(top = 12.dp))
            Text("Flight Deck", fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 22.dp).semantics { heading() })
            Text("Your Obsidian vault on the phone. Tasks, read-it-later and a calm home screen. Everything stays as plain notes.",
                fontSize = 14.sp, color = Muted, lineHeight = 21.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp).widthIn(max = 320.dp))
            Spacer(Modifier.weight(1.4f))
            BigButton("Pick your vault folder", onPick)
            Text("The folder with .obsidian in it", fontSize = 13.sp, color = Muted, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 28.dp))
        } else Checked(vault, onPick, onDone)
    }
}

@Composable
private fun Checked(vault: Uri, onPick: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var check by remember(vault) { mutableStateOf<VaultCheck?>(null) }
    LaunchedEffect(vault) {
        check = withContext(Dispatchers.IO) {
            runCatching {
                val v = appVault(ctx, vault)
                VaultCheck(
                    looksLikeVault = v.entry(".obsidian")?.isDir == true,
                    notes = runCatching { NoteIndex.paths(v).size }.getOrDefault(0),
                    openTasks = runCatching { VaultWriter(ctx, vault).listTasks().size }.getOrDefault(0),
                    hasInbox = v.entry(VaultWriter.INBOX)?.isDir == true,
                    hasQuickTask = v.entry("${VaultWriter.INBOX}/${VaultWriter.TASK_FILE}") != null,
                )
            }.getOrDefault(VaultCheck(false, 0, 0, false, false))
        }
    }
    val c = check
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Text(if (c == null) "Checking…" else if (c.looksLikeVault) "All set" else "Not quite",
                fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 30.dp).semantics { heading() })
            Text(Uri.decode(vault.lastPathSegment ?: "").substringAfterLast(':'), fontSize = 13.sp, color = Muted, modifier = Modifier.padding(top = 2.dp, bottom = 12.dp))
            if (c != null) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surface1).border(1.dp, Outline, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp)) {
                    Line(c.looksLikeVault, if (c.looksLikeVault) "Looks like a vault (.obsidian found)" else "No .obsidian folder here. Is this the vault itself?")
                    HorizontalDivider(color = Outline)
                    Line(c.notes > 0, "${"%,d".format(c.notes)} notes · ${c.openTasks} open tasks")
                    HorizontalDivider(color = Outline)
                    Line(c.hasInbox, if (c.hasInbox) "Can save to ${VaultWriter.INBOX}" else "No ${VaultWriter.INBOX} yet. One will be made.")
                    if (!c.hasQuickTask) { HorizontalDivider(color = Outline); Line(false, "No Quick Task note yet. One will be made.") }
                }
                Text("KEPT PRIVATE", fontSize = 10.5.sp, letterSpacing = 1.1.sp, color = Muted, modifier = Modifier.padding(top = 18.dp, bottom = 6.dp))
                Text("🔒 Pocket Pass, Refill, Did I? logs and Last Seen never reach Claude or search.", fontSize = 13.sp, color = Muted, lineHeight = 19.sp,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surface1).border(1.dp, Outline, RoundedCornerShape(16.dp)).padding(14.dp))
            }
        }
        if (c != null && !c.looksLikeVault) BigButton("Pick again", onPick)
        BigButton("Go to Deck", onDone, primary = c?.looksLikeVault == true)
        Text("Change it later in Settings › Vault", fontSize = 13.sp, color = Muted, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 28.dp))
    }
}

@Composable
private fun Line(ok: Boolean, text: String) {
    Row(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (ok) "✓" else "!", color = if (ok) Green else Amber, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Text(text, fontSize = 14.sp)
    }
}

@Composable
private fun BigButton(label: String, onClick: () -> Unit, primary: Boolean = true) {
    Text(label, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = if (primary) OnPrimary else TextMain, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).clip(CircleShape)
            .then(if (primary) Modifier.background(Primary) else Modifier.border(1.dp, Outline, CircleShape))
            .clickable(onClick = onClick).padding(vertical = 14.dp))
}
