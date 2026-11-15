package uk.tomlowndes.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.max

/** An image shared into Flight Deck from another app, waiting for Snap anything to pick it up. */
internal object SnapInbox {
    @Volatile var pending: Uri? = null
}

/** [src] as a JPEG no bigger than [maxSide] pixels, turned upright. Blocking. */
internal fun shrunkJpeg(ctx: Context, src: Uri, maxSide: Int = Snap.MAX_SIDE): ByteArray {
    val cr = ctx.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    cr.openInputStream(src)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / sample > maxSide * 2) sample *= 2
    var bmp = cr.openInputStream(src)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
        ?: error("Can't read that image")
    val orientation = runCatching { cr.openInputStream(src)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) } }.getOrNull() ?: 1
    val m = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
    }
    val scale = maxSide.toFloat() / max(bmp.width, bmp.height)
    if (scale < 1f) m.postScale(scale, scale)
    if (!m.isIdentity) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
}

/**
 * Snap anything (snap-anything-v1): take or pick a photo, Claude Fast reads it, and you tick what to keep. Needs the
 * Claude key in Settings › Jarvis; without it, the Bullet Journal page scan ([onScanPage]) still works on the phone.
 */
@Composable
fun SnapScreen(vault: Uri?, prefs: AppPrefs, snack: SnackbarHostState, onScanPage: () -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val key = remember { SecretStore(ctx).claudeKey?.takeIf { it.isNotBlank() } }
    var mode by remember { mutableStateOf(SnapMode.AUTO) }
    var photo by remember { mutableStateOf<Uri?>(null) }
    var jpeg by remember { mutableStateOf<ByteArray?>(null) }
    var reading by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<SnapResult?>(null) }
    var steps by remember { mutableStateOf(false) }
    var parent by remember { mutableStateOf("") }
    var keepPhoto by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var cameraFile by remember { mutableStateOf<File?>(null) }

    fun use(uri: Uri) {
        val k = key ?: return
        photo = uri; reading = true; result = null
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = shrunkJpeg(ctx, uri)
                    jpeg = bytes
                    val tags = listOf("#work", "#personal")
                    val d = PhoneBrain.vision(k, Snap.system(LocalDate.now(), mode, tags), Base64.encodeToString(bytes, Base64.NO_WRAP), "image/jpeg", Snap.PROMPT)
                    prefs.brainSpend = prefs.brainSpend.add(PhoneBrain.costUsd(PhoneBrain.FAST, d.inTok, d.outTok))
                    Snap.parse(d.reply) ?: error("Couldn't make sense of that one")
                }
            }
            reading = false
            r.onSuccess { result = it; steps = it.steps; parent = it.what }
                .onFailure { snack.showSnackbar(PhoneBrain.failure(prefs.assistantName, it, viaServer = false)) }
        }
    }

    LaunchedEffect(Unit) { SnapInbox.pending?.let { SnapInbox.pending = null; use(it) } }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = cameraFile
        if (ok && f != null) use(Uri.fromFile(f))
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(::use) }
    fun takePhoto() {
        val dir = File(ctx.cacheDir, "camera").apply { mkdirs() }
        val f = File.createTempFile("snap", ".jpg", dir)
        cameraFile = f
        camera.launch(FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f))
    }

    fun save() {
        val uri = vault ?: run { scope.launch { snack.showSnackbar("Pick your vault folder in Settings first") }; return }
        val r = result ?: return
        saving = true
        scope.launch {
            val out = withContext(Dispatchers.IO) {
                runCatching {
                    val w = VaultWriter(ctx, uri)
                    val n = Snap.save(w, r, steps, parent)
                    if (r.receipt != null) runCatching { Snap.saveReceipt(appVault(ctx, uri), r) }
                    val bytes = jpeg
                    if (keepPhoto && bytes != null) {
                        val v = appVault(ctx, uri)
                        val path = v.create(prefs.imagesDir, "Snap " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HHmm")), "jpg")
                        v.writeBytes(path, bytes)
                        w.appendInbox(listOf("- 📷 " + r.what.ifEmpty { "Snap" } + " [[${path.substringAfterLast('/')}]]"))
                    }
                    n
                }
            }
            saving = false
            out.onSuccess { n -> snack.showSnackbar(if (n == 1) "Saved 1" else "Saved $n"); onBack() }
                .onFailure { snack.showSnackbar("Failed: ${it.message}") }
        }
    }

    BackHandler { if (result != null || photo != null) { result = null; photo = null } else onBack() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = Muted) }
            Text("Snap anything", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        }
        Text("A letter, receipt, whiteboard, list or screenshot", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(bottom = 12.dp))

        if (key == null) {
            ListCard {
                Text("Snap anything uses Claude. Add your Claude key in Settings › Jarvis › Advanced to turn it on (under 1p a photo).",
                    fontSize = 14.sp, modifier = Modifier.padding(14.dp))
            }
            Text("📓 Scan a notebook page instead (no Claude)", color = Primary, fontSize = 14.sp,
                modifier = Modifier.clickable(onClick = onScanPage).padding(vertical = 10.dp))
            return@Column
        }

        val shot = photo
        if (shot == null) {
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SnapMode.values().forEach { m -> AppChip(selected = mode == m, onClick = { mode = m }, label = { Text(m.label) }) }
            }
            Text("📷 Take a photo", color = OnPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clip(CircleShape).background(Primary).clickable { takePhoto() }.padding(vertical = 14.dp))
            Text("🖼 Photos and screenshots", color = Muted, fontSize = 14.sp, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 10.dp).fillMaxWidth().clip(CircleShape).border(1.dp, Outline, CircleShape)
                    .clickable { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }.padding(vertical = 13.dp))
            Text("📓 Scan a notebook page (Bullet Journal marks)", color = Muted, fontSize = 13.sp,
                modifier = Modifier.clickable(onClick = onScanPage).padding(vertical = 14.dp))
            return@Column
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AsyncImage(shot, null, contentScale = ContentScale.Crop, modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)))
            Column(Modifier.weight(1f)) {
                Text(if (reading) "Reading…" else result?.title ?: "Couldn't read it", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                result?.what?.takeIf { it.isNotEmpty() }?.let { Text(it, fontSize = 12.5.sp, color = Muted) }
            }
        }
        val r = result ?: run {
            if (!reading) Text("Try again", color = Primary, fontSize = 14.sp, modifier = Modifier.clickable { use(shot) }.padding(vertical = 12.dp))
            return@Column
        }
        SectionLabel("Keep")
        r.items.forEachIndexed { i, item ->
            ListCard(Modifier.clickable { result = r.copy(items = r.items.mapIndexed { j, x -> if (j == i) x.copy(keep = !x.keep) else x }) }) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TickBox(item.keep, onClick = { result = r.copy(items = r.items.mapIndexed { j, x -> if (j == i) x.copy(keep = !x.keep) else x }) })
                    Column(Modifier.weight(1f)) {
                        Text(item.kind.label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = when (item.kind) { SnapKind.BILL -> Amber; SnapKind.NOTE -> Purple; else -> Primary })
                        Text(item.text, fontSize = 14.5.sp, color = if (item.keep) TextMain else Muted)
                        Snap.detail(item).takeIf { it.isNotEmpty() }?.let { Text(it, fontSize = 12.5.sp, fontFamily = FontFamily.Monospace, color = Muted) }
                    }
                }
            }
        }
        val taskCount = r.kept.count { it.kind == SnapKind.TASK }
        if (taskCount > 1) {
            ListCard {
                Row(Modifier.fillMaxWidth().clickable { steps = !steps }.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Steps under one task", fontSize = 14.sp)
                        Text("Instead of $taskCount separate tasks", fontSize = 12.sp, color = Muted)
                    }
                    AppSwitch(steps) { steps = it }
                }
                if (steps) {
                    HorizontalDivider(color = Outline)
                    OutlinedTextField(parent, { parent = it }, singleLine = true, colors = fieldColors(), placeholder = { Text("Sort the garage") },
                        modifier = Modifier.fillMaxWidth().padding(10.dp))
                }
            }
        }
        ListCard {
            Row(Modifier.fillMaxWidth().clickable { keepPhoto = !keepPhoto }.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("🖼 Keep the photo", fontSize = 14.sp)
                    Text("Saves it to ${prefs.imagesDir} and links it in the inbox note", fontSize = 12.sp, color = Muted)
                }
                AppSwitch(keepPhoto) { keepPhoto = it }
            }
        }
        val n = r.kept.size
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DeckButton("Retake", primary = false, modifier = Modifier.weight(1f)) { photo = null; result = null }
            DeckButton(if (saving) "Saving…" else if (n == 0) "Nothing ticked" else "Save $n", primary = n > 0, modifier = Modifier.weight(1f)) {
                if (n > 0 && !saving) save()
            }
        }
        androidx.compose.foundation.layout.Spacer(Modifier.height(20.dp))
    }
}
