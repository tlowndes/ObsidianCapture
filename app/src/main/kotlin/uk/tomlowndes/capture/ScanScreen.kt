package uk.tomlowndes.capture

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Reads the text on a photo, on the phone (ML Kit, no upload). Lines come top to bottom. */
private suspend fun readPage(ctx: Context, photo: Uri): String = suspendCancellableCoroutine { cont ->
    val recogniser = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    val image = runCatching { InputImage.fromFilePath(ctx, photo) }.getOrElse { cont.resumeWithException(it); return@suspendCancellableCoroutine }
    recogniser.process(image)
        .addOnSuccessListener { text ->
            val lines = text.textBlocks.flatMap { it.lines }.sortedBy { it.boundingBox?.top ?: 0 }.joinToString("\n") { it.text }
            recogniser.close()
            cont.resume(lines)
        }
        .addOnFailureListener { recogniser.close(); cont.resumeWithException(it) }
}

/**
 * Scan a page (paper-hybrid-v1): photograph a notebook page, read it on the phone, and sort its lines by their Bullet
 * Journal marks (• task, – note, ○ event, ★ priority; unmarked lines carry on the one above). You check and fix every
 * line before Save. Tasks go to Quick Task; notes and events go to the inbox note under a "📷 Scan" line that links the photo.
 */
@Composable
fun ScanScreen(vault: Uri?, prefs: AppPrefs, snack: SnackbarHostState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var photo by remember { mutableStateOf<Uri?>(null) }
    var reading by remember { mutableStateOf(false) }
    var read by remember { mutableStateOf(false) }
    val entries = remember { mutableStateListOf<LogEntry>() }
    var keep by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Int?>(null) }
    var cameraFile by remember { mutableStateOf<File?>(null) }

    fun use(uri: Uri) {
        photo = uri; reading = true; read = false; entries.clear()
        scope.launch {
            val r = runCatching { readPage(ctx, uri) }
            reading = false; read = true
            r.onSuccess { entries.addAll(RapidLog.page(it, LocalDate.now())) }
                .onFailure { snack.showSnackbar("Couldn't read the photo: ${it.message}") }
        }
    }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = cameraFile
        if (ok && f != null) use(Uri.fromFile(f))
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(::use) }
    fun takePhoto() {
        val dir = File(ctx.cacheDir, "camera").apply { mkdirs() }
        val f = File.createTempFile("scan", ".jpg", dir)
        cameraFile = f
        camera.launch(FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f))
    }

    fun save() {
        val uri = vault ?: run { scope.launch { snack.showSnackbar("Pick your vault folder in Settings first") }; return }
        val shot = photo ?: return
        saving = true
        val list = entries.toList()
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val w = VaultWriter(ctx, uri)
                    val now = LocalDateTime.now()
                    var link: String? = null
                    if (keep) {
                        val v = appVault(ctx, uri)
                        val bytes = ctx.contentResolver.openInputStream(shot)?.use { it.readBytes() } ?: error("Photo not found")
                        val path = v.create(prefs.imagesDir, "Scan " + now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HHmm")), "jpg")
                        v.writeBytes(path, bytes)
                        link = path.substringAfterLast('/')
                    }
                    list.filter { it.kind.isTask }.forEach { w.addTask(RapidLog.taskText(it), it.due?.toString()) }
                    val lines = list.filter { !it.kind.isTask }.map { "  " + RapidLog.inboxLine(it) }
                    if (lines.isNotEmpty() || link != null) {
                        val head = "- 📷 Scan " + now.format(DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.UK)) + (link?.let { " [[$it]]" } ?: "")
                        w.appendInbox(listOf(head) + lines)
                    }
                    list.count { it.kind.isTask } to lines.size
                }
            }
            saving = false
            r.onSuccess { (t, n) ->
                snack.showSnackbar(listOfNotNull(t.takeIf { it > 0 }?.let { "$it task" + if (it == 1) "" else "s" },
                    n.takeIf { it > 0 }?.let { "$it note" + if (it == 1) "" else "s" }).joinToString(" and ").ifEmpty { "Photo" } + " saved")
                onBack()
            }.onFailure { snack.showSnackbar("Failed: ${it.message}") }
        }
    }

    BackHandler { onBack() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = Muted) }
            Text("Scan a page", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        }
        Text("• task  – note  ○ event  ★ priority  × done", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Muted,
            modifier = Modifier.padding(bottom = 12.dp))

        val shot = photo
        if (shot == null) {
            Text("📷 Take a photo", color = OnPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clip(CircleShape).background(Primary).clickable { takePhoto() }.padding(vertical = 14.dp))
            Text("🖼 Pick from photos", color = Muted, fontSize = 14.sp, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 10.dp).fillMaxWidth().clip(CircleShape).border(1.dp, Outline, CircleShape)
                    .clickable { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }.padding(vertical = 13.dp))
            return@Column
        }

        AsyncImage(shot, "Scanned page", contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(16.dp)).border(1.dp, Outline, RoundedCornerShape(16.dp)))
        Box(Modifier.height(12.dp))
        when {
            reading -> Text("Reading the page…", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(vertical = 12.dp))
            read && entries.isEmpty() -> Text("No writing found. Try again in better light, flat on the table.", color = Muted, fontSize = 14.sp,
                modifier = Modifier.padding(vertical = 12.dp))
            entries.isNotEmpty() -> {
                GroupLabel("Found · ${entries.size}")
                ListCard(Modifier.padding(top = 6.dp)) {
                    entries.forEachIndexed { i, e ->
                        if (i > 0) HorizontalDivider(color = Outline)
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            // Tap the mark to change what the line is.
                            Box(
                                Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(Surface2).clickable {
                                    val ks = LogKind.values()
                                    entries[i] = e.copy(kind = ks[(e.kind.ordinal + 1) % ks.size])
                                },
                                contentAlignment = Alignment.Center
                            ) { Text(e.kind.mark, fontSize = 15.sp, color = kindColour(e.kind)) }
                            Column(Modifier.weight(1f).clickable { editing = i }) {
                                Text(e.text, fontSize = 14.sp)
                                val sub = listOfNotNull(
                                    e.kind.label + if (e.kind.isTask) " → Quick Task" else " → Inbox",
                                    e.due?.takeIf { e.kind.isTask }?.let { dueLabel(it) },
                                    e.minutes?.let { TaskRules.minutesLabel(it) },
                                ).joinToString(" · ")
                                Text(sub, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Muted, modifier = Modifier.padding(top = 2.dp))
                            }
                            Text("✕", color = Muted, fontSize = 14.sp, modifier = Modifier.clickable { entries.removeAt(i) }.padding(4.dp))
                        }
                    }
                }
                Text("Tap a mark to change it, or a line to fix the words.", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 2.dp))
            }
        }
        SettingRow("Keep the photo in the vault", "In ${prefs.imagesDir}, linked from the inbox note") { AppSwitch(keep) { keep = it } }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Retake", color = Muted, fontSize = 14.sp, textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).clip(CircleShape).border(1.dp, Outline, CircleShape).clickable(enabled = !reading && !saving) { takePhoto() }.padding(vertical = 13.dp))
            val can = !reading && !saving && (entries.isNotEmpty() || keep)
            Text(if (saving) "Saving…" else "Save", color = OnPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).clip(CircleShape).background(if (can) Primary else Primary.copy(alpha = 0.4f))
                    .clickable(enabled = can) { save() }.padding(vertical = 13.dp))
        }
    }

    editing?.let { i ->
        val e = entries.getOrNull(i) ?: run { editing = null; return@let }
        var t by remember(i) { mutableStateOf(e.text) }
        AlertDialog(
            onDismissRequest = { editing = null }, containerColor = Surface1,
            title = { Text("Fix the line") },
            text = { OutlinedTextField(t, { t = it }, minLines = 2, colors = fieldColors(), modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                TextButton(onClick = {
                    // Re-read it, so a date or estimate fixed by hand is picked up.
                    entries[i] = RapidLog.parse(t, LocalDate.now(), e.kind).let { p -> if (p.kind.isTask) p else p.copy(due = null) }
                    editing = null
                }, enabled = t.isNotBlank()) { Text("Done", color = Primary) }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel", color = Muted) } }
        )
    }
}

private fun kindColour(k: LogKind): Color = when (k) {
    LogKind.PRIORITY -> Amber
    LogKind.EVENT -> Teal
    LogKind.NOTE -> Muted
    LogKind.TASK -> TextMain
}
