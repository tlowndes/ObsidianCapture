package uk.tomlowndes.capture

import android.content.Context
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.OvalShape
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** A place being added or edited (errand-places-v1). [original] is the saved place when editing. */
internal data class PlaceDraft(val original: Place?, val name: String, val tag: String, val lat: Double, val lon: Double, val radius: Int = 200)

private const val UA = "FlightDeck/2.0 (personal Obsidian app)"

/** Photon first, Nominatim if Photon fails. Blocking. */
internal fun searchPlaces(q: String, near: Pair<Double, Double>?): List<PlaceHit>? {
    fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8_000; c.readTimeout = 10_000
        c.setRequestProperty("User-Agent", UA)
        try { return c.inputStream.bufferedReader().readText() } finally { c.disconnect() }
    }
    val hits = runCatching { PlaceSearch.parsePhoton(get(PlaceSearch.photonUrl(q, near))) }.getOrNull()
        ?: runCatching { PlaceSearch.parseNominatim(get(PlaceSearch.nominatimUrl(q))) }.getOrNull()
        ?: return null
    return hits
}

/** Screen 1: Search, Pick on map, or Here. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddPlaceSheet(onSearch: () -> Unit, onMap: () -> Unit, onHere: () -> Unit, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Surface1) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            Text("Add a place", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 6.dp))
            listOf(
                Triple("🔍", "Search" to "Shop name, street or postcode", onSearch),
                Triple("🗺️", "Pick on map" to "Drop a pin anywhere", onMap),
                Triple("◎", "Here" to "Where you're standing now", onHere),
            ).forEachIndexed { i, (icon, words, go) ->
                if (i > 0) HorizontalDivider(color = Outline)
                Row(Modifier.fillMaxWidth().clickable { go() }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(PrimaryTint), contentAlignment = Alignment.Center) { Text(icon, fontSize = 15.sp) }
                    Column {
                        Text(words.first, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(words.second, fontSize = 12.5.sp, color = Muted)
                    }
                }
            }
        }
    }
}

/** Screen 2: results as you type, nearest first. */
@Composable
internal fun PlaceSearchScreen(near: Pair<Double, Double>?, onPick: (PlaceHit) -> Unit, onMap: () -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    var q by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<PlaceHit>?>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(q) {
        if (q.trim().length < PlaceSearch.MIN_CHARS) { hits = emptyList(); busy = false; return@LaunchedEffect }
        delay(400) // wait for a pause in typing; keeps us well inside the services' fair-use limits
        busy = true
        hits = withContext(Dispatchers.IO) { searchPlaces(q, near) }
        busy = false
    }
    Column(Modifier.fillMaxSize().imePadding().padding(horizontal = 18.dp)) {
        Row(Modifier.padding(top = 12.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("←", fontSize = 20.sp, color = Muted, modifier = Modifier.clickable(onClick = onBack).padding(6.dp))
            OutlinedTextField(q, { q = it }, singleLine = true, colors = fieldColors(), shape = RoundedCornerShape(14.dp),
                placeholder = { Text("Shop, street or postcode") }, leadingIcon = { Text("🔍") },
                trailingIcon = { if (busy) CircularProgressIndicator(color = Primary, strokeWidth = 2.dp, modifier = Modifier.size(18.dp)) else if (q.isNotEmpty()) Text("✕", color = Muted, modifier = Modifier.clickable { q = "" }.padding(8.dp)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.weight(1f).focusRequester(focus))
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            val list = hits?.let { PlaceSearch.sorted(it, near) }
            when {
                list == null -> Text("Couldn't search just now. Check your connection, or pick on the map.", fontSize = 13.sp, color = Muted, modifier = Modifier.padding(vertical = 10.dp))
                q.trim().length >= PlaceSearch.MIN_CHARS && list.isEmpty() && !busy -> Text("Nothing found for \"${q.trim()}\".", fontSize = 13.sp, color = Muted, modifier = Modifier.padding(vertical = 10.dp))
                list.isNotEmpty() -> {
                    GroupLabel(if (near != null) "Near you" else "Results")
                    list.forEachIndexed { i, h ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (i == 0 && near != null) PrimaryTint else Bg)
                            .clickable { onPick(h) }.padding(horizontal = 10.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("📍", fontSize = 15.sp)
                            Column(Modifier.weight(1f)) {
                                Text(h.name, fontSize = 14.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (h.address.isNotEmpty()) Text(h.address, fontSize = 12.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            h.metres?.let { Text(PlaceSearch.distance(it), fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Muted) }
                        }
                    }
                }
            }
            GroupLabel("Or")
            Text("🗺️  Pick on map instead", color = Primary, fontSize = 14.sp, modifier = Modifier.fillMaxWidth().clickable(onClick = onMap).padding(vertical = 12.dp))
        }
        Text(PlaceSearch.ATTRIBUTION, fontSize = 10.sp, color = Muted, modifier = Modifier.padding(vertical = 6.dp))
    }
}

private fun osmSetup(ctx: Context) {
    val c = Configuration.getInstance()
    c.userAgentValue = UA
    c.osmdroidBasePath = File(ctx.cacheDir, "osmdroid").apply { mkdirs() }
    c.osmdroidTileCache = File(c.osmdroidBasePath, "tiles").apply { mkdirs() }
}

/** Inverts the light OpenStreetMap tiles, then turns the hue back round so water stays blue and parks green. */
private fun darkTiles(): android.graphics.ColorMatrixColorFilter {
    val invert = android.graphics.ColorMatrix(floatArrayOf(-1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f))
    val hue = android.graphics.ColorMatrix(floatArrayOf(
        -0.574f, 1.430f, 0.144f, 0f, 0f,
        0.426f, 0.430f, 0.144f, 0f, 0f,
        0.426f, 1.430f, -0.856f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f))
    val dim = android.graphics.ColorMatrix().apply { setScale(0.8f, 0.85f, 0.95f, 1f) }
    return android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply { setConcat(dim, android.graphics.ColorMatrix().apply { setConcat(hue, invert) }) })
}

private fun dot(colour: Int) = ShapeDrawable(OvalShape()).apply { paint.color = colour; intrinsicWidth = 30; intrinsicHeight = 30 }

/**
 * Screens 3 and 4: drag the map under a fixed pin, set how close counts, name and tag it.
 * [others] show as amber dots so places don't overlap. [uses] counts the tasks already tagged.
 */
@Composable
internal fun PlaceEditorScreen(
    draft: PlaceDraft, others: List<Place>, uses: (String) -> Int,
    onSave: (Place) -> Unit, onRemove: (() -> Unit)?, onCancel: () -> Unit, locate: suspend () -> Pair<Double, Double>?,
    follow: Boolean = false,
) {
    BackHandler(onBack = onCancel)
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(draft.name) }
    var tag by remember { mutableStateOf(draft.tag) }
    var tagTouched by remember { mutableStateOf(draft.original != null) }
    var radius by remember { mutableIntStateOf(draft.radius) }
    var centre by remember { mutableStateOf(GeoPoint(draft.lat, draft.lon)) }
    val ring = remember { Polygon().apply {
        fillPaint.color = Primary.copy(alpha = 0.14f).toArgb()
        outlinePaint.color = Primary.toArgb(); outlinePaint.strokeWidth = 4f
        outlinePaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(18f, 14f), 0f)
        setOnClickListener { _, _, _ -> false }
    } }
    fun redraw(map: MapView?) { ring.setPoints(Polygon.pointsAsCircle(centre, radius.toDouble())); map?.invalidate() }
    val map = remember {
        osmSetup(ctx)
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            overlayManager.tilesOverlay.setColorFilter(darkTiles()) // dark map to match the app
            isTilesScaledToDpi = true
            controller.setZoom(if (draft.original != null || draft.name.isNotEmpty()) 17.0 else 16.0)
            controller.setCenter(centre)
            overlays.add(ring)
            others.filter { it.tag != draft.original?.tag }.forEach { p ->
                overlays.add(Marker(this).apply {
                    position = GeoPoint(p.lat, p.lon); icon = dot(Amber.toArgb()); setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    setOnMarkerClickListener { _, _ -> true }
                })
            }
            addMapListener(object : MapListener {
                override fun onScroll(e: ScrollEvent?): Boolean { centre = GeoPoint(mapCenter.latitude, mapCenter.longitude); redraw(this@apply); return false }
                override fun onZoom(e: ZoomEvent?): Boolean { centre = GeoPoint(mapCenter.latitude, mapCenter.longitude); redraw(this@apply); return false }
            })
            onResume()
        }
    }
    DisposableEffect(Unit) { redraw(map); onDispose { map.onPause(); map.onDetach() } }
    LaunchedEffect(radius) { redraw(map) }
    // "Here" and "Pick on map" start where you are once the phone has a fix.
    LaunchedEffect(Unit) { if (follow) locate()?.let { (la, lo) -> map.controller.setZoom(17.0); map.controller.animateTo(GeoPoint(la, lo)) } }

    Column(Modifier.fillMaxSize().imePadding()) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
            AndroidView({ map }, Modifier.fillMaxSize())
            Text("📍", fontSize = 34.sp, modifier = Modifier.align(Alignment.Center).padding(bottom = 40.dp)) // tip sits on the centre
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("←", fontSize = 20.sp, modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Surface2).clickable(onClick = onCancel).padding(horizontal = 12.dp, vertical = 6.dp))
                Text(draft.original?.let { "Edit ${it.tag}" } ?: "Drag the map to place the pin", fontSize = if (draft.original != null) 16.sp else 13.sp,
                    fontWeight = if (draft.original != null) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Surface2).padding(horizontal = 12.dp, vertical = 8.dp))
            }
            Text("◎", fontSize = 20.sp, color = Primary, modifier = Modifier.align(Alignment.BottomEnd).padding(14.dp).size(46.dp)
                .clip(RoundedCornerShape(14.dp)).background(Surface2).border(1.dp, Outline, RoundedCornerShape(14.dp))
                .clickable { scope.launch { locate()?.let { (la, lo) -> map.controller.animateTo(GeoPoint(la, lo)) } } }
                .padding(top = 9.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Text("© OpenStreetMap contributors", fontSize = 10.sp, color = Muted, modifier = Modifier.align(Alignment.BottomStart).padding(8.dp))
        }
        Column(Modifier.fillMaxWidth().background(Surface2).padding(horizontal = 18.dp, vertical = 14.dp)) {
            OutlinedTextField(name, { name = it; if (!tagTouched) tag = PlaceSearch.tagFor(it) }, singleLine = true, colors = fieldColors(),
                shape = RoundedCornerShape(12.dp), label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
            val clean = Errands.cleanTag(tag)
            val n = if (clean.length > 1) uses(clean) else 0
            val shop = clean == ErrandNudges.shopTag(ctx)
            val clash = others.any { it.tag == clean && it.tag != draft.original?.tag }
            OutlinedTextField(tag, { tag = it; tagTouched = true }, singleLine = true, colors = fieldColors(), shape = RoundedCornerShape(12.dp),
                label = { Text("Tag") }, supportingText = {
                    Text(listOfNotNull(if (clash) "replaces your saved $clean" else null, if (n > 0) "$n task${if (n == 1) "" else "s"} use this" else null, if (shop) "shopping list goes here" else null)
                        .joinToString(" · ").ifEmpty { "Add $clean to any task to do it here" }, fontSize = 12.sp, color = Muted)
                }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            Text("HOW CLOSE COUNTS", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Muted, modifier = Modifier.padding(top = 6.dp, bottom = 6.dp))
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(1.dp, Outline, RoundedCornerShape(12.dp))) {
                PlaceSearch.RADII.forEach { r ->
                    Text("$r m", fontSize = 13.sp, color = if (r == radius) Primary else Muted, fontWeight = if (r == radius) FontWeight.Bold else FontWeight.Normal,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.weight(1f).background(if (r == radius) PrimaryTint else Surface2).clickable { radius = r }.padding(vertical = 9.dp))
                }
            }
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onRemove != null) Text("Remove", fontSize = 13.sp, color = Warn, fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.weight(0.7f).clip(RoundedCornerShape(50)).border(1.dp, Outline, RoundedCornerShape(50)).clickable(onClick = onRemove).padding(vertical = 10.dp))
                else SheetButton("Cancel", Modifier.weight(1f), onClick = onCancel)
                val ok = clean.length > 1 && clean[1].isLetter()
                SheetButton(if (draft.original != null) "Save" else "Save place", Modifier.weight(1f), primary = true, enabled = ok) {
                    onSave(Place(clean, name.trim().ifEmpty { clean.removePrefix("@").replaceFirstChar { it.uppercase() } }, centre.latitude, centre.longitude, radius))
                }
            }
        }
    }
}
