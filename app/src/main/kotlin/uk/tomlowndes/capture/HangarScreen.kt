package uk.tomlowndes.capture

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Each Hangar tool keeps one colour in the hub, on its own screen and on its widget. */
internal fun HangarTool.colour(): Color = when (this) {
    HangarTool.DID_I -> Green
    HangarTool.LAUNCH_PAD -> Amber
    HangarTool.HEADS_UP -> Primary
}

internal val GreenTint = Color(0xFF0D2A1D)
private val HM = DateTimeFormatter.ofPattern("HH:mm")

/**
 * Hangar (hangar-phase1): the hub behind the one Hangar icon on Deck. The most urgent action sits on top, so
 * it can be done without opening a tool; the tools you've added are listed underneath with their live status.
 */
@Composable
fun HangarScreen(vault: Uri?, prefs: AppPrefs, snack: SnackbarHostState, onOpen: (HangarTool) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var tools by remember { mutableStateOf(Hangar.parse(prefs.hangarTools)) }
    var editing by remember { mutableStateOf(false) }
    var did by remember { mutableStateOf<List<DidStatus>?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    fun save(t: List<HangarTool>) { tools = t; prefs.hangarTools = Hangar.format(t) }

    LaunchedEffect(vault, tools, reload) {
        val uri = vault ?: return@LaunchedEffect
        if (tools?.contains(HangarTool.DID_I) == true)
            did = withContext(Dispatchers.IO) { runCatching { DidI.today(appVault(ctx, uri)) }.getOrNull() }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (editing) editing = false else onBack() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = Muted) }
            Text(if (editing) "Edit Hangar" else "Hangar", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (tools != null) Text(if (editing) "Done" else "Edit", color = Primary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(CircleShape).clickable { editing = !editing }.padding(horizontal = 12.dp, vertical = 8.dp))
        }
        val list = tools
        when {
            vault == null -> Text("Pick your vault in Settings first.", color = Muted, modifier = Modifier.padding(top = 12.dp))
            list == null -> FirstRun { t -> save(listOf(t)) }
            editing -> EditList(ctx, list, onSave = ::save)
            else -> {
                val next = did?.let { DidI.nextOpen(it) }
                Text(if (next != null) "1 thing needs you" else "All clear", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp, bottom = 12.dp))
                RightNow(next, onYes = { s ->
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    scope.launch {
                        val uri = vault ?: return@launch
                        val stamp = withContext(Dispatchers.IO) { runCatching { DidI.stamp(appVault(ctx, uri), s.check.name) } }
                            .getOrElse { snack.showSnackbar("Couldn't save: ${it.message}"); return@launch }
                        reload++
                        DidWidgets.changed(ctx)
                        val r = snack.showSnackbar("${s.check.name} logged at ${stamp.time.format(HM)}", actionLabel = "Undo", duration = SnackbarDuration.Short)
                        if (r == SnackbarResult.ActionPerformed) {
                            withContext(Dispatchers.IO) { runCatching { DidI.undo(appVault(ctx, uri), LocalDate.now(), stamp) } }
                            reload++
                            DidWidgets.changed(ctx)
                        }
                    }
                }, onOpen = { onOpen(HangarTool.DID_I) }, hasDid = HangarTool.DID_I in list)
                GroupLabel("Getting out")
                Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    list.forEach { t -> ToolTile(t, statusOf(t, did)) { if (t.ready) onOpen(t) } }
                }
                val more = Hangar.available(list)
                if (more.isNotEmpty()) Text("＋ Add a tool", color = Primary, fontSize = 14.sp,
                    modifier = Modifier.padding(vertical = 14.dp).clickable { editing = true })
            }
        }
    }
}

/** Status line and the right-hand label for a tool's tile. */
private fun statusOf(t: HangarTool, did: List<DidStatus>?): Pair<String, String> = when {
    !t.ready -> "Coming in the next update" to "soon"
    t == HangarTool.DID_I && did == null -> "Loading…" to ""
    t == HangarTool.DID_I -> {
        val s = did.orEmpty()
        val open = s.filter { it.at == null }.map { it.check.name }
        val done = s.filter { it.at != null }.joinToString(" · ") { "${it.check.name} ${it.at!!.format(HM)}" }
        (if (open.isEmpty()) done.ifEmpty { "No checks yet" } else "${open.joinToString(", ")} not checked yet") to DidI.progress(s)
    }
    else -> t.blurb to ""
}

@Composable
private fun RightNow(next: DidStatus?, hasDid: Boolean, onYes: (DidStatus) -> Unit, onOpen: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(bottom = 16.dp).clip(RoundedCornerShape(18.dp))
            .background(if (next != null) GreenTint else Surface1).border(1.dp, if (next != null) Green.copy(alpha = 0.35f) else Outline, RoundedCornerShape(18.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("RIGHT NOW", fontSize = 11.sp, letterSpacing = 1.2.sp, color = Muted, fontWeight = FontWeight.SemiBold)
        if (next == null) {
            Text(if (hasDid) "All clear" else "Nothing needs you", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(if (hasDid) "Every check is done today." else "Add Did I? to see your checks here.", color = Muted, fontSize = 13.sp)
        } else {
            Text("${next.check.emoji} ${next.check.question}", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Green)
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Open Did I?", color = TextMain, fontSize = 13.sp, modifier = Modifier.weight(1f).clip(CircleShape)
                    .border(1.dp, Outline, CircleShape).clickable(onClick = onOpen).padding(vertical = 10.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Text("Yes, done", color = OnPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).clip(CircleShape)
                    .background(Green).clickable { onYes(next) }.padding(vertical = 10.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}

@Composable
private fun ToolTile(t: HangarTool, status: Pair<String, String>, onClick: () -> Unit) {
    val c = if (t.ready) t.colour() else Muted
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Surface1).clickable(enabled = t.ready, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(4.dp).height(58.dp).background(c))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text("${t.icon} ${t.title}", color = c, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(status.first, color = Muted, fontSize = 12.5.sp, maxLines = 1)
        }
        if (status.second.isNotEmpty()) Text(status.second, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(end = 14.dp))
    }
}

@Composable
private fun FirstRun(onAdd: (HangarTool) -> Unit) {
    Text("Small tools for getting out the door. Pick the ones you want.", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(start = 4.dp, bottom = 14.dp))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HangarTool.values().forEach { t -> AddRow(t) { onAdd(t) } }
    }
    Text("You can add or hide tools at any time.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 14.dp))
}

@Composable
private fun AddRow(t: HangarTool, onAdd: () -> Unit) {
    val c = if (t.ready) t.colour() else Muted
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Surface1).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("${t.icon} ${t.title}", color = c, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(t.blurb, color = Muted, fontSize = 12.5.sp)
        }
        if (t.ready) Text("Add", color = OnPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp,
            modifier = Modifier.clip(CircleShape).background(Primary).clickable(onClick = onAdd).padding(horizontal = 14.dp, vertical = 7.dp))
        else Text("Soon", color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun EditList(ctx: Context, list: List<HangarTool>, onSave: (List<HangarTool>) -> Unit) {
    Text("Reorder, hide, or pin a tool to your home screen.", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp, bottom = 12.dp))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        list.forEachIndexed { i, t ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Surface1).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("↑", color = if (i > 0) TextMain else Outline, fontSize = 18.sp, modifier = Modifier.clip(CircleShape).clickable(enabled = i > 0) { onSave(Hangar.move(list, i, -1)) }.padding(8.dp))
                Text("↓", color = if (i < list.lastIndex) TextMain else Outline, fontSize = 18.sp, modifier = Modifier.clip(CircleShape).clickable(enabled = i < list.lastIndex) { onSave(Hangar.move(list, i, 1)) }.padding(8.dp))
                Text(t.title, fontSize = 15.sp, modifier = Modifier.weight(1f).padding(start = 4.dp))
                if (t.ready) Text("Pin", color = Primary, fontSize = 13.sp, modifier = Modifier.clip(CircleShape).clickable { pinShortcut(ctx, t) }.padding(8.dp))
                Text("Hide", color = Muted, fontSize = 13.sp, modifier = Modifier.clip(CircleShape).clickable { onSave(Hangar.remove(list, t)) }.padding(8.dp))
            }
        }
    }
    val more = Hangar.available(list)
    if (more.isNotEmpty()) {
        Box(Modifier.padding(top = 18.dp, bottom = 8.dp)) { GroupLabel("Add") }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { more.forEach { t -> AddRow(t) { onSave(Hangar.add(list, t)) } } }
    }
}

/** Asks the launcher to pin a home-screen icon that opens straight into [t]. It's still one app underneath. */
internal fun pinShortcut(ctx: Context, t: HangarTool) {
    val sm = ctx.getSystemService(ShortcutManager::class.java) ?: return
    if (!sm.isRequestPinShortcutSupported) return
    val intent = Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
        .putExtra("tab", if (t == HangarTool.DID_I) "DID_I" else "HANGAR")
    val info = ShortcutInfo.Builder(ctx, "hangar-${t.id}")
        .setShortLabel(t.title).setIcon(Icon.createWithResource(ctx, R.mipmap.ic_launcher)).setIntent(intent).build()
    runCatching { sm.requestPinShortcut(info, null) }
}
