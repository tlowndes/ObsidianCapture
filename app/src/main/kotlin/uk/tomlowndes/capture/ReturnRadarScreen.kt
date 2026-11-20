package uk.tomlowndes.capture

import android.app.DatePickerDialog
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)

/**
 * Return Radar (hangar-phase2): parcels to send back, soonest first, counted down in days. Three taps take a
 * return through Packed, Dropped off (with a photo of the receipt) and Refund in. Buzzes come from Heads Up's alarms.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ReturnRadarScreen(vault: Uri?, snack: SnackbarHostState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var returns by remember { mutableStateOf<List<Return>?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Return?>(null) }
    var dropping by remember { mutableStateOf<Return?>(null) }
    var cameraFile by remember { mutableStateOf<File?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(vault, reload) {
        val uri = vault ?: return@LaunchedEffect
        withContext(Dispatchers.IO) { runCatching { Returns.load(appVault(ctx, uri)) } }
            .onSuccess { returns = it }.onFailure { snack.showSnackbar("Couldn't load returns: ${it.message}") }
    }
    BackHandler(onBack = onBack)

    fun save(block: (Vault) -> Unit) {
        val uri = vault ?: return
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { block(appVault(ctx, uri)) } }
                .onSuccess { reload++; HeadsAlarms.changed(ctx) }.onFailure { snack.showSnackbar("Couldn't save: ${it.message}") }
        }
    }
    fun step(r: Return, to: ReturnStep, receipt: String? = r.receipt) {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        save { v -> Returns.update(v, r, r.copy(step = to, receipt = receipt)) }
    }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val r = dropping ?: return@rememberLauncherForActivityResult
        val f = cameraFile
        dropping = null
        if (!ok || f == null) { step(r, ReturnStep.DROPPED); return@rememberLauncherForActivityResult }
        save { v ->
            // The receipt sits with the Last Seen photos, linked from the return.
            v.mkdirs(LastSeen.PHOTOS)
            val path = v.create(LastSeen.PHOTOS, "Receipt " + LastSeen.photoName(LocalDateTime.now()).removeSuffix(".jpg"), "jpg")
            v.writeBytes(path, shrunkJpeg(ctx, Uri.fromFile(f), 1600))
            f.delete()
            Returns.update(v, r, r.copy(step = ReturnStep.DROPPED, receipt = nameOf(path)))
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = Muted) }
            Text("Return Radar", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Pink, modifier = Modifier.weight(1f))
            if (vault != null) Text("＋ Add", color = Primary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(CircleShape).clickable { adding = true }.padding(horizontal = 12.dp, vertical = 8.dp))
        }
        val all = returns
        val today = LocalDate.now()
        when {
            vault == null -> Text("Pick your vault in Settings first.", color = Muted, modifier = Modifier.padding(top = 12.dp))
            all == null -> Text("Loading…", color = Muted, modifier = Modifier.padding(top = 12.dp))
            Returns.ordered(all, today).isEmpty() -> Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Nothing to send back.", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("Add a parcel when it arrives and might go back. It counts down to the return date and buzzes 3 days before and on the morning.",
                    color = Muted, fontSize = 13.sp)
                Big("＋ Add a return", Pink) { adding = true }
            }
            else -> {
                Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Returns.ordered(all, today).forEach { r ->
                        ReturnCard(r, today, onLong = { deleting = r }) { to ->
                            if (to == ReturnStep.DROPPED) {
                                dropping = r
                                val dir = File(ctx.cacheDir, "camera").apply { mkdirs() }
                                val f = File.createTempFile("receipt", ".jpg", dir)
                                cameraFile = f
                                runCatching { camera.launch(FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)) }
                                    .onFailure { dropping = null; step(r, ReturnStep.DROPPED) }
                            } else step(r, to)
                        }
                    }
                }
                Text("Dropped off asks for a photo of the receipt (skip it by going back). Hold a return to delete it. They live in ${Returns.NOTE}.",
                    color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 14.dp, bottom = 20.dp))
            }
        }
    }

    deleting?.let { r ->
        AlertDialog(
            onDismissRequest = { deleting = null }, containerColor = Surface1,
            title = { Text("Delete ${r.item}?") },
            confirmButton = { TextButton(onClick = { deleting = null; save { v -> Returns.remove(v, r) } }) { Text("Delete", color = Coral) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel", color = Muted) } }
        )
    }
    if (adding) AddReturnDialog(onDismiss = { adding = false }) { r -> adding = false; save { v -> Returns.add(v, r) } }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReturnCard(r: Return, today: LocalDate, onLong: () -> Unit, onStep: (ReturnStep) -> Unit) {
    val left = Returns.daysLeft(r, today)
    val c = when {
        r.step == ReturnStep.REFUNDED -> Green
        r.step == ReturnStep.DROPPED -> Muted
        left <= Returns.SOON_DAYS -> Pink
        else -> Primary
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Surface1).combinedClickable(onClick = {}, onLongClick = onLong),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(4.dp).height(if (r.step == ReturnStep.REFUNDED) 52.dp else 112.dp).background(c))
        Column(Modifier.weight(1f).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(listOf(r.item, r.shop).filter { it.isNotBlank() }.joinToString(" · "), fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1,
                    modifier = Modifier.weight(1f), color = if (r.step == ReturnStep.REFUNDED) Muted else TextMain)
                Text(
                    when (r.step) { ReturnStep.REFUNDED -> "Refund in ✓"; ReturnStep.DROPPED -> "Sent back"; else -> Returns.countdown(r, today) },
                    color = c, fontSize = if (Returns.open(r)) 20.sp else 13.sp, fontWeight = FontWeight.Bold
                )
            }
            if (r.step != ReturnStep.REFUNDED) {
                Text("Return by ${r.by.format(DAY)}" + (r.receipt?.let { " · receipt saved" } ?: ""), color = Muted, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(ReturnStep.PACKED, ReturnStep.DROPPED, ReturnStep.REFUNDED).forEach { s ->
                        val done = r.step >= s
                        val next = r.step.ordinal + 1 == s.ordinal
                        Text((if (done) "✓ " else "") + s.label, fontSize = 11.sp, textAlign = TextAlign.Center, maxLines = 1,
                            color = if (done) Pink else if (next) TextMain else Muted,
                            modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                                .background(if (done) PinkTint else Surface2).border(1.dp, if (next) Pink.copy(alpha = 0.5f) else Outline, RoundedCornerShape(8.dp))
                                .clickable(enabled = next) { onStep(s) }.padding(vertical = 7.dp))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddReturnDialog(onDismiss: () -> Unit, onAdd: (Return) -> Unit) {
    val ctx = LocalContext.current
    var item by remember { mutableStateOf("") }
    var shop by remember { mutableStateOf("") }
    var arrived by remember { mutableStateOf(LocalDate.now()) }
    var window by remember { mutableStateOf<Int?>(30) }
    var exact by remember { mutableStateOf<LocalDate?>(null) }
    fun pick(d: LocalDate, set: (LocalDate) -> Unit) =
        DatePickerDialog(ctx, { _, y, m, day -> set(LocalDate.of(y, m + 1, day)) }, d.year, d.monthValue - 1, d.dayOfMonth).show()
    val by = exact ?: window?.let { Returns.byFor(arrived, it) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Surface1,
        title = { Text("What's going back?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(item, { item = it.replace("·", "") }, singleLine = true, colors = fieldColors(), placeholder = { Text("Item, e.g. Boots") })
                OutlinedTextField(shop, { shop = it.replace("·", "") }, singleLine = true, colors = fieldColors(), placeholder = { Text("Shop (optional)") })
                Text("Arrived ${if (arrived == LocalDate.now()) "today" else arrived.format(DAY)}  ✎", color = TextMain, fontSize = 14.sp,
                    modifier = Modifier.clickable { pick(arrived) { arrived = it; exact = null } }.padding(vertical = 6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Returns.WINDOWS.forEach { w -> AppChip(exact == null && window == w, { window = w; exact = null }, { Text("$w days") }) }
                    AppChip(exact != null, { pick(by ?: LocalDate.now()) { exact = it } }, { Text("Date…") })
                }
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Surface2).padding(12.dp)) {
                    Text("RETURN BY", color = Muted, fontSize = 11.sp, letterSpacing = 1.sp)
                    Text(by?.format(DAY) ?: "—", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Pink)
                    Text("Buzz 3 days before and on the morning", color = Muted, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = item.isNotBlank() && by != null, onClick = { onAdd(Return(item.trim(), shop.trim(), by!!)) }) { Text("Add return", color = Primary) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Muted) } }
    )
}
