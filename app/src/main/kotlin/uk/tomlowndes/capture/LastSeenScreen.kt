package uk.tomlowndes.capture

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.LocalDateTime

/** Where the phone is, with a short place name from OpenStreetMap. Call off the main thread. */
internal object PlaceHere {
    fun now(ctx: Context): Triple<Double, Double, String?>? {
        val (lat, lon) = WeatherFetch.location(ctx) ?: return null
        val name = runCatching {
            val c = URL("https://nominatim.openstreetmap.org/reverse?format=json&zoom=18&lat=$lat&lon=$lon").openConnection() as HttpURLConnection
            c.connectTimeout = 8_000; c.readTimeout = 10_000
            c.setRequestProperty("User-Agent", "FlightDeck/2.0 (personal Obsidian app)")
            val j = try { JSONObject(c.inputStream.bufferedReader().readText()) } finally { c.disconnect() }
            j.optString("name").takeIf { it.isNotBlank() }
                ?: j.optString("display_name").split(",").take(2).joinToString(",").trim().takeIf { it.isNotBlank() }
        }.getOrNull()
        return Triple(lat, lon, name)
    }
}

/**
 * Last Seen (hangar-phase2): snap where you put something, pick what it is, and it's saved with the time and
 * place. "Where's my…" finds it again; Found moves it down, and found things clear after 30 days.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LastSeenScreen(vault: Uri?, snack: SnackbarHostState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<Seen>?>(null) }
    val photos = remember { mutableStateMapOf<String, Any>() }
    var query by remember { mutableStateOf("") }
    var shot by remember { mutableStateOf<File?>(null) }
    var cameraFile by remember { mutableStateOf<File?>(null) }
    var open by remember { mutableStateOf<Seen?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(vault, reload) {
        val uri = vault ?: return@LaunchedEffect
        val known = photos.keys.toSet()
        withContext(Dispatchers.IO) {
            runCatching {
                val v = appVault(ctx, uri)
                val list = LastSeen.load(v)
                list to list.mapNotNull { it.photo }.filter { it !in known }
                    .mapNotNull { p -> runCatching { p to v.handle("${LastSeen.PHOTOS}/$p") }.getOrNull() }
            }
        }.onSuccess { (list, found) -> photos.putAll(found); items = list }.onFailure { snack.showSnackbar("Couldn't load Last Seen: ${it.message}") }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) shot = cameraFile }
    fun snap() {
        val dir = File(ctx.cacheDir, "camera").apply { mkdirs() }
        val f = File.createTempFile("seen", ".jpg", dir)
        cameraFile = f
        runCatching { camera.launch(FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)) }
            .onFailure { scope.launch { snack.showSnackbar("No camera app found") } }
    }
    BackHandler { when { shot != null -> shot = null; open != null -> open = null; else -> onBack() } }

    shot?.let { f ->
        SaveSheet(f, onCancel = { shot = null }) { emoji, what, note, place ->
            shot = null
            val uri = vault ?: return@SaveSheet
            scope.launch {
                withContext(Dispatchers.IO) {
                    runCatching {
                        val v = appVault(ctx, uri)
                        val at = LocalDateTime.now()
                        v.mkdirs(LastSeen.PHOTOS)
                        val path = v.create(LastSeen.PHOTOS, LastSeen.photoName(at).removeSuffix(".jpg"), "jpg")
                        v.writeBytes(path, shrunkJpeg(ctx, Uri.fromFile(f), 1600))
                        f.delete()
                        LastSeen.add(v, Seen(at, emoji, what, note, place?.third, place?.first, place?.second, nameOf(path)))
                    }
                }.onSuccess { reload++; snack.showSnackbar("$what saved") }.onFailure { snack.showSnackbar("Couldn't save: ${it.message}") }
            }
        }
        return
    }
    open?.let { s -> SeenDetail(s, s.photo?.let { photos[it] }) { open = null }; return }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = Muted) }
            Text("Last Seen", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Coral, modifier = Modifier.weight(1f))
            if (vault != null) Text("📷 Snap", color = Primary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(CircleShape).clickable(onClick = ::snap).padding(horizontal = 12.dp, vertical = 8.dp))
        }
        val all = items
        when {
            vault == null -> Text("Pick your vault in Settings first.", color = Muted, modifier = Modifier.padding(top = 12.dp))
            all == null -> Text("Loading…", color = Muted, modifier = Modifier.padding(top = 12.dp))
            all.isEmpty() -> Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Nothing saved yet.", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("Parked somewhere new, or put the spare keys away? Snap it, and it's saved with the time and place.", color = Muted, fontSize = 13.sp)
                Big("📷 Snap where it is", Coral, ::snap)
            }
            else -> {
                OutlinedTextField(query, { query = it }, singleLine = true, colors = fieldColors(), placeholder = { Text("🔍 Where's my…") },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp))
                val today = LocalDate.now()
                val shown = LastSeen.search(LastSeen.ordered(all, today), query)
                val now = LocalDateTime.now()
                listOf("Still out" to shown.filter { it.found == null }, "Found" to shown.filter { it.found != null }).forEach { (label, rows) ->
                    if (rows.isNotEmpty()) {
                        Box(Modifier.padding(top = 8.dp, bottom = 6.dp)) { GroupLabel(label) }
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            rows.forEach { s ->
                                SeenRow(s, s.photo?.let { photos[it] }, now, onOpen = { open = s }) {
                                    val uri = vault ?: return@SeenRow
                                    scope.launch {
                                        withContext(Dispatchers.IO) { runCatching { LastSeen.markFound(appVault(ctx, uri), s, today) } }
                                        reload++
                                        val r = snack.showSnackbar("${s.what} found", actionLabel = "Undo", duration = SnackbarDuration.Short)
                                        if (r == SnackbarResult.ActionPerformed) {
                                            withContext(Dispatchers.IO) { runCatching { LastSeen.unfound(appVault(ctx, uri), s.copy(found = today)) } }
                                            reload++
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (shown.isEmpty()) Text("Nothing matches \"$query\".", color = Muted, modifier = Modifier.padding(top = 8.dp))
                Text("Photos are saved in ${LastSeen.PHOTOS}.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 14.dp, bottom = 20.dp))
            }
        }
    }
}

@Composable
private fun SeenRow(s: Seen, photo: Any?, now: LocalDateTime, onOpen: () -> Unit, onFound: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Surface1).clickable(onClick = onOpen).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Thumb(s, photo, Modifier.size(48.dp))
        Column(Modifier.weight(1f)) {
            Text(listOf(s.what, s.note).filter { it.isNotBlank() }.joinToString(" · "), fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1,
                color = if (s.found != null) Muted else TextMain)
            Text(listOfNotNull(s.place, s.found?.let { "found " + if (it == now.toLocalDate()) "today" else LastSeen.ago(it.atStartOfDay(), now) } ?: LastSeen.ago(s.at, now)).joinToString(" · "),
                color = Muted, fontSize = 12.sp, maxLines = 1)
        }
        if (s.found == null) Text("Found", color = Coral, fontSize = 12.sp,
            modifier = Modifier.clip(CircleShape).border(1.dp, Coral.copy(alpha = 0.4f), CircleShape).clickable(onClick = onFound).padding(horizontal = 12.dp, vertical = 6.dp))
    }
}

@Composable
private fun Thumb(s: Seen, photo: Any?, modifier: Modifier) =
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(Surface2), contentAlignment = Alignment.Center) {
        if (photo != null) AsyncImage(photo, s.what, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Text(s.emoji, fontSize = 20.sp)
    }

@Composable
private fun SeenDetail(s: Seen, photo: Any?, onBack: () -> Unit) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = Muted) }
            Text("${s.emoji} ${s.what}", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Coral)
        }
        Thumb(s, photo, Modifier.fillMaxWidth().aspectRatio(0.8f))
        if (s.note.isNotBlank()) Text(s.note, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text(listOfNotNull(s.place, LastSeen.ago(s.at, LocalDateTime.now())).joinToString(" · "), color = Muted, fontSize = 14.sp)
        if (s.lat != null && s.lon != null) Big("Walk there", Coral) {
            val label = Uri.encode(s.what)
            val i = Intent(Intent.ACTION_VIEW, Uri.parse("geo:${s.lat},${s.lon}?q=${s.lat},${s.lon}($label)")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { ctx.startActivity(i) }
        }
        Text("Tap Found on the list once it turns up.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 20.dp))
    }
}

/** After the photo: what is it, an optional word ("Bay 41"), and the place, found while you choose. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SaveSheet(f: File, onCancel: () -> Unit, onSave: (String, String, String, Triple<Double, Double, String?>?) -> Unit) {
    val ctx = LocalContext.current
    var kind by remember { mutableStateOf<Pair<String, String>?>(null) }
    var other by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var place by remember { mutableStateOf<Triple<Double, Double, String?>?>(null) }
    var looking by remember { mutableStateOf(true) }
    LaunchedEffect(f) { place = withContext(Dispatchers.IO) { runCatching { PlaceHere.now(ctx) }.getOrNull() }; looking = false }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("✕", color = Muted, fontSize = 20.sp, modifier = Modifier.clip(CircleShape).clickable(onClick = onCancel).padding(8.dp))
            Text("Last Seen", color = Coral, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
        Box(Modifier.fillMaxWidth().aspectRatio(1.1f).clip(RoundedCornerShape(18.dp)).background(Surface2), contentAlignment = Alignment.BottomCenter) {
            AsyncImage(f, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Text(if (looking) "📍 Finding where you are…" else place?.third?.let { "📍 $it" } ?: "📍 No location", color = TextMain, fontSize = 12.sp,
                modifier = Modifier.padding(10.dp).clip(CircleShape).background(Bg.copy(alpha = 0.8f)).padding(horizontal = 10.dp, vertical = 5.dp))
        }
        Text("What is it?", color = Muted, fontSize = 13.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            LastSeen.KINDS.forEach { k -> AppChip(kind == k, { kind = k }, { Text("${k.first} ${k.second}") }) }
            AppChip(kind?.first == "•", { kind = "•" to other }, { Text("＋ Other") })
        }
        if (kind?.first == "•") OutlinedTextField(other, { other = it.replace("·", "") }, singleLine = true, colors = fieldColors(),
            placeholder = { Text("What is it?") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(note, { note = it.replace("·", "") }, singleLine = true, colors = fieldColors(),
            placeholder = { Text("A word to help, e.g. Bay 41 (optional)") }, modifier = Modifier.fillMaxWidth())
        val k = kind
        val what = if (k?.first == "•") other.trim() else k?.second.orEmpty()
        Text(if (what.isEmpty()) "Pick what it is" else "Save · $what${if (note.isNotBlank()) ", ${note.trim()}" else ""}",
            color = Bg, fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clip(CircleShape).background(if (what.isEmpty()) Outline else Coral)
                .clickable(enabled = what.isNotEmpty()) { onSave(k!!.first, what, note.trim(), place) }.padding(vertical = 15.dp))
    }
}
