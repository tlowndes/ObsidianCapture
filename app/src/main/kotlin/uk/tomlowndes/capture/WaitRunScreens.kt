package uk.tomlowndes.capture

import android.Manifest
import android.app.PendingIntent
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon as AndroidIcon
import android.net.Uri
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime

/** Waiting Mode's Quick Settings tile (hangar-phase3): one tap opens Waiting Mode. */
class WaitingTile : TileService() {
    override fun onStartListening() {
        qsTile?.apply { state = Tile.STATE_INACTIVE; label = "Waiting Mode"; updateTile() }
    }

    override fun onClick() {
        val open = Intent(this, MainActivity::class.java).putExtra("tab", "WAITING")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 610, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(open)
        }
    }
}

@Composable
private fun RunTitle(title: String, color: Color, onBack: () -> Unit, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = Muted) }
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = color, modifier = Modifier.weight(1f))
        if (action != null) Text(action, color = Primary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(CircleShape).clickable(onClick = onAction).padding(horizontal = 12.dp, vertical = 8.dp))
    }
}

// ---- Waiting Mode ----

/**
 * Waiting Mode (hangar-phase3): in a queue or a waiting room, three things instead of scrolling: a 5-minute task,
 * a short read, or a minute of slow breathing. Shuffle swaps them.
 */
@Composable
fun WaitingScreen(vault: Uri?, snack: SnackbarHostState, onOpenArticle: (ReadRef) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val started = remember { LocalDateTime.now() }
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    var tasks by remember { mutableStateOf<List<TaskItem>?>(null) }
    var reads by remember { mutableStateOf<List<SavedArticle>>(emptyList()) }
    var turn by remember { mutableIntStateOf(0) }
    var breathing by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) { while (true) { delay(30_000); now = LocalDateTime.now() } }
    LaunchedEffect(vault, reload) {
        val uri = vault ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            runCatching { WaitingMode.quickTasks(VaultWriter(ctx, uri).listTasks()) }.onSuccess { tasks = it }
            runCatching { WaitingMode.shortReads(ReadStore(ctx, uri).inbox()) }.onSuccess { reads = it }
        }
        if (tasks == null) tasks = emptyList()
    }
    BackHandler { if (breathing) breathing = false else onBack() }

    if (breathing) { Breathe { breathing = false }; return }

    fun done(t: TaskItem) {
        val uri = vault ?: return
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        val prefs = AppPrefs(ctx)
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { VaultWriter(ctx, uri).completeTask(t, prefs.recurring) } }
            r.onFailure { snack.showSnackbar("Couldn't tick it: ${it.message}") }
            r.onSuccess { c ->
                reload++
                if (snack.showSnackbar("Done. Nice use of a queue.", "Undo", duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) {
                    withContext(Dispatchers.IO) { runCatching { VaultWriter(ctx, uri).restoreTask(t, c) } }
                    reload++
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("✕", color = Muted, fontSize = 20.sp, modifier = Modifier.clip(CircleShape).clickable(onClick = onBack).padding(12.dp))
            Text("waiting ${WaitingMode.waitedWords(started, now)}", color = Sand, fontSize = 13.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f), textAlign = TextAlign.End)
        }
        Text("Waiting? Pick one", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Sand, modifier = Modifier.padding(top = 8.dp, bottom = 12.dp))
        val task = tasks?.let { WaitingMode.pick(it, turn) }
        val read = WaitingMode.pick(reads, turn)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            WaitCard("5-MINUTE TASK", task?.let { TaskViews.plainText(it) } ?: if (tasks == null) "Looking…" else "None yet",
                task?.let { "from Tasks · tap when it's done" + (TaskRules.minutes(it.raw)?.let { m -> " · ⏱ ${m}m" } ?: "") }
                    ?: "Tag a task ⏱ 5m or #quick and it shows here", enabled = task != null) { task?.let(::done) }
            WaitCard(read?.let { "SHORT READ · ${WaitingMode.readMinutes(it.words)} MIN" } ?: "SHORT READ", read?.title ?: "Nothing short to read",
                read?.let { listOfNotNull("from Read It Later", ReadStore.host(it.url)).joinToString(" · ") } ?: "Articles under 5 minutes from Read It Later show here",
                enabled = read != null) { read?.let { onOpenArticle(it.ref) } }
            WaitCard("DO NOTHING, ON PURPOSE", "One minute of slow breathing", "in ${WaitingMode.BREATHE_IN}, out ${WaitingMode.BREATHE_OUT}", enabled = true) {
                breathing = true
            }
        }
        Text("Shuffle", color = Sand, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 14.dp).clip(CircleShape).border(1.dp, Sand.copy(alpha = 0.5f), CircleShape)
                .clickable { turn++ }.padding(vertical = 13.dp))
        if (Build.VERSION.SDK_INT >= 33) {
            Text("Add the Quick Settings tile", color = Primary, fontSize = 13.sp, modifier = Modifier.padding(top = 16.dp).clickable {
                val sbm = ctx.getSystemService(StatusBarManager::class.java)
                runCatching {
                    sbm.requestAddTileService(ComponentName(ctx, WaitingTile::class.java), "Waiting Mode",
                        AndroidIcon.createWithResource(ctx, R.drawable.ic_notify), ctx.mainExecutor) { }
                }
            }.padding(vertical = 6.dp))
        }
        Text("Open it from Quick Settings: pull down twice, tap the pencil and drag Waiting Mode in.",
            color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp, bottom = 20.dp))
    }
}

@Composable
private fun WaitCard(label: String, title: String, sub: String, enabled: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surface1).border(1.dp, if (enabled) Sand.copy(alpha = 0.35f) else Outline, RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, onClick = onClick).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(label, color = Sand, fontSize = 11.sp, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold)
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = if (enabled) TextMain else Muted, maxLines = 2)
        Text(sub, fontSize = 12.sp, color = Muted, maxLines = 2)
    }
}

/** One minute of breathing: the circle grows for 4 seconds and shrinks for 6. */
@Composable
private fun Breathe(onDone: () -> Unit) {
    var left by remember { mutableIntStateOf(WaitingMode.BREATHE_SECONDS) }
    LaunchedEffect(Unit) { while (left > 0) { delay(1000); left-- } }
    val cycle = (WaitingMode.BREATHE_IN + WaitingMode.BREATHE_OUT) * 1000
    val grow by rememberInfiniteTransition(label = "breathe").animateFloat(0.55f, 0.55f,
        infiniteRepeatable(keyframes { durationMillis = cycle; 0.55f at 0; 1f at WaitingMode.BREATHE_IN * 1000; 0.55f at cycle }, RepeatMode.Restart), label = "size")
    val inhale = (WaitingMode.BREATHE_SECONDS - left) % (cycle / 1000) < WaitingMode.BREATHE_IN
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(220.dp).scale(if (left > 0) grow else 0.55f).clip(CircleShape).background(Sand.copy(alpha = 0.25f)).border(2.dp, Sand, CircleShape))
            Text(if (left == 0) "Done" else if (inhale) "in" else "out", color = Sand, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(if (left > 0) "$left s" else "Nice. Back to waiting.", color = Muted, fontSize = 15.sp, modifier = Modifier.padding(top = 28.dp))
        Text(if (left > 0) "Stop" else "Back", color = Sand, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 20.dp).clip(CircleShape).border(1.dp, Sand.copy(alpha = 0.5f), CircleShape).clickable(onClick = onDone)
                .padding(horizontal = 32.dp, vertical = 12.dp))
    }
}

// ---- Errand Run ----

/**
 * Errand Run (hangar-phase3): every place with an errand waiting, as one run, nearest first. Ticking an item ticks its
 * task in the vault; a parcel ticks off as dropped in Return Radar. Start run hands the stops to the maps app.
 */
@Composable
fun ErrandRunScreen(vault: Uri?, snack: SnackbarHostState, onPlaces: () -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var stops by remember { mutableStateOf<List<RunStop>?>(null) }
    var here by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var ticked by remember { mutableStateOf(setOf<String>()) }
    var reload by remember { mutableIntStateOf(0) }
    val hasFine = { ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED }
    var located by remember { mutableStateOf(false) }
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) reload++ else scope.launch { snack.showSnackbar("Without location the run keeps your Errands order") }
    }

    LaunchedEffect(vault, reload) {
        val uri = vault ?: return@LaunchedEffect
        if (hasFine()) { here = ErrandNudges.fix(ctx); located = here != null }
        withContext(Dispatchers.IO) {
            runCatching {
                val v = appVault(ctx, uri)
                val places = Errands.places(v)
                val shopping = Recipes.shopping(v)?.let { Recipes.shoppingOpen(it) }.orEmpty()
                val errands = Errands.errands(VaultWriter(ctx, uri).listTasks(), shopping, places, ErrandNudges.shopTag(ctx))
                val returns = if (v.entry(Returns.NOTE) != null) Returns.load(v) else emptyList()
                ErrandRun.stops(errands, returns, places, here, LocalDate.now())
            }
        }.onSuccess { stops = it }.onFailure { snack.showSnackbar("Couldn't load errands: ${it.message}") }
    }
    BackHandler(onBack = onBack)

    fun tick(key: String, item: RunItem) {
        if (key in ticked) return
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        ticked = ticked + key
        val uri = vault ?: return
        val t = item.task
        val r = item.ret
        scope.launch {
            when {
                t != null -> {
                    val prefs = AppPrefs(ctx)
                    val res = withContext(Dispatchers.IO) { runCatching { VaultWriter(ctx, uri).completeTask(t, prefs.recurring) } }
                    res.onFailure { ticked = ticked - key; snack.showSnackbar("Couldn't tick it: ${it.message}") }
                    res.onSuccess { c ->
                        if (snack.showSnackbar("Ticked ${item.text}", "Undo", duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) {
                            withContext(Dispatchers.IO) { runCatching { VaultWriter(ctx, uri).restoreTask(t, c) } }
                            ticked = ticked - key
                        }
                    }
                }
                r != null -> withContext(Dispatchers.IO) {
                    runCatching { Returns.update(appVault(ctx, uri), r, r.copy(step = ReturnStep.DROPPED)) }
                }.onSuccess { HeadsAlarms.changed(ctx); snack.showSnackbar("${r.item} dropped off") }
                    .onFailure { ticked = ticked - key; snack.showSnackbar("Couldn't save: ${it.message}") }
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        RunTitle("Errand Run", Orange, onBack, "Places", onPlaces)
        val all = stops
        when {
            vault == null -> Text("Pick your vault in Settings first.", color = Muted, modifier = Modifier.padding(top = 12.dp))
            all == null -> Text("Loading…", color = Muted, modifier = Modifier.padding(top = 12.dp))
            all.isEmpty() -> Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("No errands waiting.", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("Add @tesco or @b&q to any task and it becomes a stop here. Packed Return Radar parcels add a Post Office stop.",
                    color = Muted, fontSize = 13.sp)
            }
            else -> {
                val (count, _) = ErrandRun.summary(all)
                Text("$count · " + if (located) "nearest first from here" else "in your Errands order", color = Muted, fontSize = 13.sp)
                if (!located && !hasFine()) Text("Use my location", color = Primary, fontSize = 13.sp,
                    modifier = Modifier.clickable { askLocation.launch(Manifest.permission.ACCESS_FINE_LOCATION) }.padding(vertical = 6.dp))
                Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    all.forEachIndexed { i, s ->
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Surface1).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Box(Modifier.size(26.dp).clip(CircleShape).background(if (s.place != null) Orange else Surface2), contentAlignment = Alignment.Center) {
                                    Text("${i + 1}", color = if (s.place != null) OnPrimary else Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                }
                                Text(s.title, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f), maxLines = 1)
                                Text(s.metres?.let(ErrandRun::distance) ?: if (s.place == null) "no place set" else "", color = Muted, fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace)
                            }
                            s.items.forEachIndexed { j, item ->
                                val key = "${s.tag}|$j|${item.text}"
                                val done = key in ticked
                                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(enabled = !done) { tick(key, item) }.padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Box(Modifier.size(20.dp).clip(RoundedCornerShape(5.dp)).background(if (done) Orange else Surface2)
                                        .border(1.dp, if (done) Orange else Outline, RoundedCornerShape(5.dp)), contentAlignment = Alignment.Center) {
                                        if (done) Text("✓", color = OnPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                    Text(item.text, fontSize = 14.sp, color = if (done) Muted else if (item.ret != null) Pink else TextMain,
                                        textDecoration = if (done) TextDecoration.LineThrough else null, modifier = Modifier.weight(1f))
                                }
                            }
                            if (s.place == null) Text("Add ${s.tag} in Places to put it on the map", color = Muted, fontSize = 12.sp,
                                modifier = Modifier.clickable(onClick = onPlaces))
                        }
                    }
                }
                ErrandRun.mapsUrl(all)?.let { url ->
                    Column(Modifier.padding(top = 14.dp)) {
                        Big("Start run · map", Orange) {
                            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                                .onFailure { scope.launch { snack.showSnackbar("No maps app to open the route") } }
                        }
                    }
                }
                Text("Stops come from your Places note. Ticking an item ticks its task; a parcel is marked dropped off in Return Radar. Shopping items tick here only.",
                    color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 14.dp, bottom = 20.dp))
            }
        }
    }
}
