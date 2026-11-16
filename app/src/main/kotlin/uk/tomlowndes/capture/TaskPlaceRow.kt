package uk.tomlowndes.capture

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The places set up in "99 - Meta/Places.md", for the Place row. */
@Composable
internal fun rememberPlaces(vault: Uri?): Pair<List<Place>, () -> Unit> {
    val ctx = LocalContext.current
    var places by remember(vault) { mutableStateOf<List<Place>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(vault, reload) {
        val uri = vault ?: return@LaunchedEffect
        withContext(Dispatchers.IO) { runCatching { Errands.places(appVault(ctx, uri)) }.getOrNull() }?.let { places = it }
    }
    return places to { reload++ }
}

/** "📍 B&Q" for the Capture toolbar: the first word of the first place on the task. */
internal fun placeChipLabel(text: String, places: List<Place>): String {
    val tag = Errands.tags(text).firstOrNull() ?: return "📍"
    val p = places.firstOrNull { it.tag == tag }
    return "📍 " + (p?.label?.substringBefore(' ')?.ifEmpty { null } ?: tag)
}

/**
 * The Place row on New task and Edit task (task-place-picker-v1). Tapping a place adds its @tag to [text], tapping
 * again takes it off. ＋ Place opens the Errands picker (search, map or here), saves the place, then tags the task.
 * An @tag typed by hand with no place yet shows muted with "set up".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlaceRow(vault: Uri?, text: String, onText: (String) -> Unit, label: Boolean = true) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val (places, reload) = rememberPlaces(vault)
    val on = Errands.tags(text).distinct()
    var adding by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<PlaceDraft?>(null) }
    var follow by remember { mutableStateOf(false) }
    var near by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    val hasFine = { ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED }
    // Somewhere to start the map: your first place, else the middle of the UK.
    fun blank(tag: String = "") = (places.firstOrNull()?.let { it.lat to it.lon } ?: (54.0 to -2.0)).let { (la, lo) -> PlaceDraft(null, "", tag, la, lo) }
    var setUpTag by remember { mutableStateOf("") }
    fun openMap() { searching = false; adding = false; follow = hasFine(); editing = blank(setUpTag) }
    val here = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) openMap() else Toast.makeText(ctx, "\"Here\" needs precise location", Toast.LENGTH_SHORT).show()
    }
    fun save(p: Place) {
        val uri = vault ?: return
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Errands.upsert(appVault(ctx, uri), p) } }
            r.onSuccess {
                editing = null; reload()
                if (p.tag !in Errands.tags(text)) onText(Errands.toggle(text, p.tag))
            }.onFailure { Toast.makeText(ctx, "Couldn't save: ${it.message}", Toast.LENGTH_SHORT).show() }
        }
    }

    Column(Modifier.padding(top = 14.dp)) {
        if (label) GroupLabel("Place")
        FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            places.forEach { p -> PlaceChip("📍 ${p.label}", p.tag in on) { onText(Errands.toggle(text, p.tag)) } }
            on.filter { t -> places.none { it.tag == t } }.forEach { t ->
                PlaceChip("$t · set up", false, muted = true) { setUpTag = t; adding = true }
            }
            PlaceChip("＋ Place", false, muted = true) { setUpTag = ""; adding = true }
        }
        Text("Nudges you when you get there · tap again to remove", fontSize = 12.5.sp, color = Muted, modifier = Modifier.padding(top = 8.dp))
    }

    if (adding) AddPlaceSheet(
        onSearch = { adding = false; searching = true },
        onMap = { openMap() },
        onHere = { adding = false; if (hasFine()) openMap() else here.launch(Manifest.permission.ACCESS_FINE_LOCATION) },
        onDismiss = { adding = false },
    )
    if (searching || editing != null) {
        val close = { searching = false; editing = null }
        Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            Box(Modifier.fillMaxSize().background(Bg)) {
                val d = editing
                if (d != null) androidx.compose.runtime.key(d) {
                    PlaceEditorScreen(d, places, uses = { 0 }, onSave = { save(it) }, onRemove = null, onCancel = close,
                        locate = { if (hasFine()) ErrandNudges.fix(ctx) else null }, follow = follow)
                } else {
                    LaunchedEffect(Unit) { if (hasFine()) near = ErrandNudges.fix(ctx) }
                    PlaceSearchScreen(near, onPick = { h ->
                        searching = false; follow = false
                        val known = places.map { it.tag } + on
                        editing = PlaceDraft(null, h.name, setUpTag.ifEmpty { PlaceSearch.tagFor(h.name, known) }, h.lat, h.lon)
                    }, onMap = { openMap() }, onBack = close)
                }
            }
        }
    }
}

@Composable
private fun PlaceChip(label: String, selected: Boolean, muted: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Text(
        label, fontSize = 14.sp, maxLines = 1,
        color = when { selected -> Green; muted -> Muted; else -> TextMain },
        modifier = Modifier.clip(shape).background(if (selected) TaskTint else Color.Transparent)
            .border(1.dp, if (selected) Green else Outline, shape)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp)
    )
}
