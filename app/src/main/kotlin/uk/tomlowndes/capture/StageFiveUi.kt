package uk.tomlowndes.capture

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Switch
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)

@Composable
private fun BackTitle(title: String, sub: String?, onBack: () -> Unit) {
    Text("‹ Deck", color = Muted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable(onClick = onBack).padding(top = 10.dp))
    Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
    sub?.let { Text(it, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Muted, modifier = Modifier.padding(bottom = 8.dp)) }
}

/** A one-field dialog for "＋ Gift idea", "＋ Note" and new names. */
@Composable
internal fun AskText(title: String, hint: String, onDone: (String?) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { onDone(null) }, containerColor = Surface1,
        title = { Text(title, fontSize = 17.sp) },
        text = { OutlinedTextField(text, { text = it }, placeholder = { Text(hint) }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onDone(text.trim().ifEmpty { null }) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text("Cancel") } },
    )
}

// ---- People and birthdays (body-people-errands-v1) ----

private fun openMessages(ctx: Context) = runCatching {
    ctx.startActivity(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@Composable
fun PeopleScreen(vault: Uri?, snack: SnackbarHostState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val today = LocalDate.now()
    var people by remember { mutableStateOf<List<Person>?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var open by remember { mutableStateOf<Person?>(null) }
    var asking by remember { mutableStateOf<Pair<String, (String) -> Unit>?>(null) }
    var picking by remember { mutableStateOf<Person?>(null) }
    LaunchedEffect(vault, refresh) {
        val uri = vault ?: return@LaunchedEffect
        people = withContext(Dispatchers.IO) { runCatching { People.list(appVault(ctx, uri)) }.getOrDefault(emptyList()) }
        open = open?.let { o -> people?.firstOrNull { it.path == o.path } }
    }
    fun edit(msg: String?, work: (Vault) -> Unit) {
        val uri = vault ?: return
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { work(appVault(ctx, uri)) } }
            refresh++
            r.onSuccess { msg?.let { snack.showSnackbar(it) } }.onFailure { snack.showSnackbar("Failed: ${it.message}") }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        val ps = people
        BackTitle("People", ps?.let { "${it.size}" }, onBack)
        if (ps == null) return@Column
        val up = People.comingUp(ps, today)
        if (up.isNotEmpty()) {
            GroupLabel("Coming up")
            ListCard(Modifier.padding(top = 6.dp, bottom = 8.dp)) {
                Column {
                    up.forEachIndexed { i, n ->
                        if (i > 0) HorizontalDivider(color = Outline)
                        Row(Modifier.fillMaxWidth().clickable { open = n.person }.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (n.birthday != null) "🎂" else "💬", fontSize = 16.sp, modifier = Modifier.width(28.dp))
                            Column(Modifier.weight(1f)) {
                                Text(n.person.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                Text(n.text, fontSize = 12.sp, color = if (n.birthday != null && n.days <= People.GIFT_DAYS) Amber else Muted)
                            }
                            if (n.birthday != null) Text("Gift", color = Primary, fontSize = 13.sp, modifier = Modifier.clickable {
                                asking = "Gift idea for ${n.person.name}" to { s -> edit("Gift idea saved") { v -> People.addGift(v, n.person, s) } }
                            }.padding(6.dp))
                            else Text("Message", color = Primary, fontSize = 13.sp, modifier = Modifier.clickable {
                                edit("Marked as spoken today") { v -> People.spoke(v, n.person) }; openMessages(ctx)
                            }.padding(6.dp))
                        }
                    }
                }
            }
        }
        GroupLabel("Everyone")
        ListCard(Modifier.padding(top = 6.dp)) {
            Column {
                if (ps.isEmpty()) Text("No one yet. Add family and friends, with birthdays.", fontSize = 13.sp, color = Muted, modifier = Modifier.padding(14.dp))
                ps.forEachIndexed { i, p ->
                    if (i > 0) HorizontalDivider(color = Outline)
                    QuietRow(null, p.name, p.birthday?.let { "🎂 ${p.nextBirthday(today)!!.format(DateTimeFormatter.ofPattern("d MMM", Locale.UK))}" }, onClick = { open = p })
                }
            }
        }
        Text("＋ Add someone", color = Primary, fontSize = 14.sp, modifier = Modifier.clickable {
            asking = "Name" to { s -> edit("Added $s") { v -> People.create(v, s) } }
        }.padding(vertical = 14.dp))
        Text("Each person is a note in People/. Capture understands \"gift idea for Mum: kneeler\".", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(bottom = 16.dp))
    }
    picking?.let { p ->
        BirthdaySheet(p, onDone = { v ->
            picking = null
            if (v != null) edit(if (v.isEmpty()) "Birthday removed" else "Birthday saved") { vt -> People.setBirthday(vt, p, v) }
        })
    }
    open?.takeIf { picking == null }?.let { p ->
        PersonSheet(p, onDone = { open = null },
            onGift = { asking = "Gift idea for ${p.name}" to { s -> edit("Gift idea saved") { v -> People.addGift(v, p, s) } } },
            onNote = { asking = "Note about ${p.name}" to { s -> edit("Note saved") { v -> People.addNote(v, p, s) } } },
            onBirthday = { picking = p },
            onEvery = { d -> edit(null) { v -> People.setEvery(v, p, d) } },
            onSpoke = { edit("Marked as spoken today") { v -> People.spoke(v, p) } })
    }
    asking?.let { (title, then) -> AskText(title, "") { s -> asking = null; s?.let(then) } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PersonSheet(p: Person, onDone: () -> Unit, onGift: () -> Unit, onNote: () -> Unit, onBirthday: () -> Unit, onEvery: (Int?) -> Unit, onSpoke: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val today = LocalDate.now()
    ModalBottomSheet(onDismissRequest = onDone, sheetState = sheet, containerColor = Surface1) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.name, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(p.birthday?.let { "🎂 " + p.nextBirthday(today)!!.format(DateTimeFormatter.ofPattern("d MMM", Locale.UK)) } ?: "＋ Birthday",
                    color = if (p.birthday == null) Primary else Muted, fontSize = 13.sp, modifier = Modifier.clickable(onClick = onBirthday).padding(6.dp))
            }
            p.lastSpoke?.let { Text("Last spoke ${it.format(DAY)}", fontSize = 12.sp, color = Muted) }
            GroupLabel("Gift ideas")
            if (p.gifts.isEmpty()) Text("None yet", fontSize = 13.sp, color = Muted)
            p.gifts.forEach { Text("• $it", fontSize = 14.sp, modifier = Modifier.padding(top = 3.dp)) }
            GroupLabel("Notes")
            if (p.notes.isEmpty()) Text("None yet", fontSize = 13.sp, color = Muted)
            p.notes.forEach { Text(it, fontSize = 14.sp, modifier = Modifier.padding(top = 3.dp)) }
            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SheetButton("＋ Gift idea", Modifier.weight(1f), onClick = onGift)
                SheetButton("＋ Note", Modifier.weight(1f), onClick = onNote)
                SheetButton("Spoke today", Modifier.weight(1f), onClick = onSpoke)
            }
            GroupLabel("Keep in touch")
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(null to "Off", 7 to "1 wk", 21 to "3 wks", 42 to "6 wks", 90 to "3 mo").forEach { (d, label) ->
                    AppChip(p.every == d, { onEvery(d) }, { Text(label) })
                }
            }
        }
    }
}

// ---- Birthday picker (birthday-picker-v1) ----

private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** Month grid, day grid, optional year. onDone gets the frontmatter value, "" to remove, or null to cancel. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BirthdaySheet(p: Person, onDone: (String?) -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val today = LocalDate.now()
    var month by remember { mutableIntStateOf(p.birthday?.monthValue ?: today.monthValue) }
    var day by remember { mutableIntStateOf(p.birthday?.dayOfMonth ?: today.dayOfMonth) }
    var knowYear by remember { mutableStateOf(p.born != null) }
    var year by remember { mutableIntStateOf(p.born ?: (today.year - 30)) }
    var typingYear by remember { mutableStateOf(false) }
    val y = year.takeIf { knowYear }
    val maxDay = People.pickerDays(month, y)
    val dayShown = day.coerceAtMost(maxDay)
    ModalBottomSheet(onDismissRequest = { onDone(null) }, sheetState = sheet, containerColor = Surface2) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 18.dp, end = 18.dp, bottom = 22.dp)) {
            Text("${p.name}'s birthday", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("Pick the month, then the day", fontSize = 12.5.sp, color = Muted, modifier = Modifier.padding(top = 2.dp))
            PickLabel("Month")
            MONTHS.chunked(6).forEachIndexed { r, row ->
                Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEachIndexed { c, m -> val n = r * 6 + c + 1; PickCell(m, n == month, true, Modifier.weight(1f)) { month = n } }
                }
            }
            PickLabel("Day")
            (1..31).chunked(7).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(bottom = 5.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    row.forEach { d -> PickCell("$d", d == dayShown, d <= maxDay, Modifier.weight(1f)) { day = d } }
                    repeat(7 - row.size) { Box(Modifier.weight(1f)) }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 14.dp).border(1.dp, Outline, RoundedCornerShape(14.dp)).clickable { knowYear = !knowYear }
                .padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("I know the year", fontSize = 14.5.sp)
                    Text("Optional. Shows their age.", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 2.dp))
                }
                Switch(knowYear, { knowYear = it }, colors = SwitchDefaults.colors(checkedTrackColor = Primary, uncheckedTrackColor = Outline))
            }
            if (knowYear) Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                fun bump(by: Int) { year = (year + by).coerceIn(1900, today.year) }
                StepCell("−10") { bump(-10) }; StepCell("−1") { bump(-1) }
                Text("$year", fontSize = 20.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).clickable { typingYear = true }.padding(vertical = 6.dp))
                StepCell("+1") { bump(1) }; StepCell("+10") { bump(10) }
            }
            Text(People.pickerPreview(month, dayShown, y, today), fontSize = 14.sp, color = Color(0xFFCDD8F5),
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp).background(PrimaryTint, RoundedCornerShape(14.dp)).padding(horizontal = 13.dp, vertical = 11.dp))
            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (p.birthday != null) SheetButton("Remove", Modifier.weight(1f)) { onDone("") }
                else SheetButton("Cancel", Modifier.weight(1f)) { onDone(null) }
                SheetButton("Save", Modifier.weight(1f), primary = true) { onDone(People.birthdayValue(month, dayShown, y)) }
            }
        }
    }
    if (typingYear) AskText("Year born", "e.g. 1958") { s ->
        typingYear = false
        s?.toIntOrNull()?.takeIf { it in 1900..today.year }?.let { year = it }
    }
}

@Composable
private fun PickLabel(text: String) =
    Text(text.uppercase(Locale.UK), fontSize = 11.sp, letterSpacing = 1.2.sp, fontFamily = FontFamily.Monospace, color = Muted, modifier = Modifier.padding(top = 14.dp, bottom = 7.dp))

@Composable
private fun PickCell(text: String, on: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Text(text, fontSize = 13.5.sp, textAlign = TextAlign.Center, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
        color = if (on) Primary else if (enabled) TextMain else Color(0xFF3A4152),
        modifier = modifier.clip(shape).then(if (on) Modifier.background(PrimaryTint) else Modifier)
            .border(1.dp, if (on) Primary else if (enabled) Outline else Color(0xFF1F2533), shape)
            .clickable(enabled = enabled, onClick = onClick).padding(vertical = 7.dp))
}

@Composable
private fun StepCell(text: String, onClick: () -> Unit) =
    Text(text, fontSize = 16.sp, modifier = Modifier.clip(RoundedCornerShape(10.dp)).border(1.dp, Outline, RoundedCornerShape(10.dp))
        .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp))

// ---- Errands by place ----

internal object ErrandNudges {
    private const val CHANNEL = "errands"
    private fun p(ctx: Context) = ctx.getSharedPreferences("errands", Context.MODE_PRIVATE)
    fun on(ctx: Context) = p(ctx).getBoolean("on", false)
    fun shopTag(ctx: Context) = p(ctx).getString("shop", Errands.DEFAULT_SHOP) ?: Errands.DEFAULT_SHOP

    fun canRun(ctx: Context) = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
        (Build.VERSION.SDK_INT < 29 || ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED)

    fun set(ctx: Context, on: Boolean) {
        p(ctx).edit().putBoolean("on", on).apply()
        val wm = WorkManager.getInstance(ctx)
        if (on) wm.enqueueUniquePeriodicWork("errands", ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<ErrandWorker>(15, TimeUnit.MINUTES).build())
        else { wm.cancelUniqueWork("errands"); p(ctx).edit().remove("inside").apply() }
    }

    fun inside(ctx: Context): Set<String> = p(ctx).getStringSet("inside", emptySet()).orEmpty()
    fun setInside(ctx: Context, s: Set<String>) = p(ctx).edit().putStringSet("inside", s).apply()

    /** A fresh fix where the phone can give one, else the newest known. */
    @SuppressLint("MissingPermission")
    suspend fun fix(ctx: Context): Pair<Double, Double>? {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        val lm = ctx.getSystemService(LocationManager::class.java)
        if (Build.VERSION.SDK_INT >= 30) {
            val provider = if (Build.VERSION.SDK_INT >= 31 && lm.hasProvider(LocationManager.FUSED_PROVIDER)) LocationManager.FUSED_PROVIDER else LocationManager.NETWORK_PROVIDER
            val l = withTimeoutOrNull(20_000) {
                suspendCancellableCoroutine<android.location.Location?> { c -> runCatching { lm.getCurrentLocation(provider, null, ctx.mainExecutor) { c.resume(it) } }.onFailure { c.resume(null) } }
            }
            if (l != null) return l.latitude to l.longitude
        }
        return runCatching { lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time } }.getOrNull()?.let { it.latitude to it.longitude }
    }

    fun notify(ctx: Context, e: Errand) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Errands nearby", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(ctx, 300, Intent(ctx, MainActivity::class.java).putExtra("tab", "ERRANDS").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(300 + (e.tag.hashCode() and 0xff), NotificationCompat.Builder(ctx, CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle(Errands.title(e)).setContentText(Errands.summary(e)).setContentIntent(open).setAutoCancel(true).build())
    }
}

/** Every 15 minutes while nudges are on: where am I, did I just arrive at a place with errands? Keeps no history. */
class ErrandWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (!ErrandNudges.on(ctx) || !ErrandNudges.canRun(ctx)) return Result.success()
        val uri = AppPrefs(ctx).vault ?: return Result.success()
        val v = appVault(ctx, uri)
        val places = runCatching { Errands.places(v) }.getOrDefault(emptyList()).takeIf { it.isNotEmpty() } ?: return Result.success()
        val (lat, lon) = ErrandNudges.fix(ctx) ?: return Result.success()
        val here = Errands.at(places, lat, lon, slack = 50.0)
        val tasks = runCatching { VaultWriter(ctx, uri).listTasks() }.getOrDefault(emptyList())
        val shopping = runCatching { Recipes.shopping(v)?.let { Recipes.shoppingOpen(it) } }.getOrNull().orEmpty()
        val (nudge, inside) = Errands.arrivals(here, ErrandNudges.inside(ctx), Errands.errands(tasks, shopping, places, ErrandNudges.shopTag(ctx)))
        nudge.forEach { ErrandNudges.notify(ctx, it) }
        ErrandNudges.setInside(ctx, inside)
        return Result.success()
    }
}

@Composable
fun ErrandsScreen(vault: Uri?, snack: SnackbarHostState, onRun: () -> Unit = {}, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var places by remember { mutableStateOf<List<Place>>(emptyList()) }
    var errands by remember { mutableStateOf<List<Errand>?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var nudges by remember { mutableStateOf(ErrandNudges.on(ctx) && ErrandNudges.canRun(ctx)) }
    var adding by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<PlaceDraft?>(null) }
    var follow by remember { mutableStateOf(false) }
    var near by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    LaunchedEffect(vault, refresh) {
        val uri = vault ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            runCatching {
                val v = appVault(ctx, uri)
                places = Errands.places(v)
                val shopping = Recipes.shopping(v)?.let { Recipes.shoppingOpen(it) }.orEmpty()
                errands = Errands.errands(VaultWriter(ctx, uri).listTasks(), shopping, places, ErrandNudges.shopTag(ctx))
            }
        }
    }
    val background = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        nudges = ok && ErrandNudges.canRun(ctx); ErrandNudges.set(ctx, nudges)
        if (!ok) scope.launch { snack.showSnackbar("Nudges need location \"All the time\" in Android's settings") }
    }
    val fine = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (!ok) scope.launch { snack.showSnackbar("Errands need precise location") }
        else if (Build.VERSION.SDK_INT >= 29 && !ErrandNudges.canRun(ctx)) background.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        else { nudges = true; ErrandNudges.set(ctx, true) }
    }
    val hasFine = { ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED }
    val known = { (places.map { it.tag } + errands.orEmpty().map { it.tag }).distinct() }
    // Somewhere to start the map before we know where you are: your first place, else the middle of the UK.
    fun blank() = (places.firstOrNull()?.let { it.lat to it.lon } ?: (54.0 to -2.0)).let { (la, lo) -> PlaceDraft(null, "", "", la, lo) }
    fun openMap() { searching = false; adding = false; follow = hasFine(); editing = blank() }
    val here = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) openMap() else scope.launch { snack.showSnackbar("\"Here\" needs precise location") }
    }
    fun save(oldTag: String?, p: Place) {
        val uri = vault ?: return
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Errands.replace(appVault(ctx, uri), oldTag, p) } }
            r.onSuccess { editing = null; refresh++; snack.showSnackbar("Saved ${p.tag} · ${p.label}") }.onFailure { snack.showSnackbar("Couldn't save: ${it.message}") }
        }
    }
    if (searching) {
        LaunchedEffect(Unit) { if (hasFine()) near = ErrandNudges.fix(ctx) }
        PlaceSearchScreen(near, onPick = { h ->
            searching = false; follow = false
            editing = PlaceDraft(null, h.name, PlaceSearch.tagFor(h.name, known()), h.lat, h.lon)
        }, onMap = { openMap() }, onBack = { searching = false })
        return
    }
    editing?.let { d -> androidx.compose.runtime.key(d) {
        PlaceEditorScreen(d, places, uses = { t -> errands?.firstOrNull { it.tag == t }?.tasks?.size ?: 0 },
            onSave = { save(d.original?.tag, it) },
            onRemove = d.original?.let { o -> {
                val uri = vault
                if (uri != null) scope.launch { withContext(Dispatchers.IO) { runCatching { Errands.remove(appVault(ctx, uri), o.tag) } }; editing = null; refresh++; snack.showSnackbar("Removed ${o.tag}") }
            } },
            onCancel = { editing = null }, locate = { if (hasFine()) ErrandNudges.fix(ctx) else null }, follow = follow)
        }
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        BackTitle("Errands", errands?.let { "${it.sumOf { e -> e.items.size }} to do at ${it.size} places" }, onBack)
        SharedPill(vault)
        errands?.let { es ->
            // Errand Run starts here now, not from Hangar (fold-doors-v1).
            val stops = es.count { it.place != null }
            if (stops >= 2) Row(Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(PrimaryTint)
                .border(1.dp, Primary, RoundedCornerShape(14.dp)).clickable(onClick = onRun).padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("⇢ Start a run · $stops stops", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Primary, modifier = Modifier.weight(1f))
                Text("nearest first ›", fontSize = 13.sp, color = Muted)
            }
            if (es.isEmpty()) Text("Add @tesco or @b&q to any task and it shows here.", fontSize = 14.sp, color = Muted, modifier = Modifier.padding(vertical = 12.dp))
            ListCard(Modifier.padding(top = 6.dp)) {
                Column {
                    es.forEachIndexed { i, e ->
                        if (i > 0) HorizontalDivider(color = Outline)
                        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(e.place?.let { "${e.tag} · ${it.label}" } ?: e.tag, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (e.place != null) TextMain else Muted)
                                Text(Errands.summary(e, 6), fontSize = 12.5.sp, color = Muted)
                            }
                            Text("${e.items.size}", fontSize = 14.sp, fontFamily = FontFamily.Monospace, color = Muted)
                        }
                    }
                }
            }
        }
        GroupLabel("Places")
        ListCard(Modifier.padding(top = 6.dp)) {
            Column {
                if (places.isEmpty()) Text("No places yet. Search for a shop, pick it on the map, or add where you are.", fontSize = 13.sp, color = Muted, modifier = Modifier.padding(14.dp))
                places.forEachIndexed { i, p ->
                    if (i > 0) HorizontalDivider(color = Outline)
                    QuietRow("📍", "${p.tag} · ${p.label}", "${p.radius} m · edit", onClick = {
                        follow = false; editing = PlaceDraft(p, p.label, p.tag, p.lat, p.lon, p.radius)
                    })
                }
            }
        }
        Text("＋ Add place", color = Primary, fontSize = 14.sp, modifier = Modifier.clickable { adding = true }.padding(vertical = 14.dp))
        DeckCard {
            Box(Modifier.padding(horizontal = 14.dp)) {
                SettingRow("Nudge me when I arrive", "Once per visit · no location history kept") {
                    AppSwitch(nudges) { want ->
                        if (!want) { nudges = false; ErrandNudges.set(ctx, false) }
                        else if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) fine.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                        else if (!ErrandNudges.canRun(ctx)) background.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                        else { nudges = true; ErrandNudges.set(ctx, true) }
                    }
                }
            }
        }
        Text("Shopping list items go under ${ErrandNudges.shopTag(ctx)}. Windows shows this list but doesn't nudge.", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(vertical = 14.dp))
    }
    if (adding) AddPlaceSheet(
        onSearch = { adding = false; searching = true },
        onMap = { openMap() },
        onHere = { adding = false; if (hasFine()) openMap() else here.launch(Manifest.permission.ACCESS_FINE_LOCATION) },
        onDismiss = { adding = false },
    )
}

// ---- Meal plan the week (meals-spend-v1) ----

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MealsScreen(vault: Uri?, snack: SnackbarHostState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val today = LocalDate.now()
    var week by remember { mutableStateOf(MealPlan.monday(today)) }
    var days by remember { mutableStateOf<List<MealDay>?>(null) }
    var recipes by remember { mutableStateOf<List<RecipeNote>>(emptyList()) }
    var picking by remember { mutableStateOf<LocalDate?>(null) }
    LaunchedEffect(vault, week) {
        val uri = vault ?: return@LaunchedEffect
        withContext(Dispatchers.IO) { runCatching { val v = appVault(ctx, uri); recipes = Recipes.list(v); days = MealPlan.load(v, week) } }
    }
    fun save(n: List<MealDay>) {
        days = n
        val uri = vault ?: return
        scope.launch { withContext(Dispatchers.IO) { runCatching { MealPlan.save(appVault(ctx, uri), n) } }.onFailure { snack.showSnackbar("Failed: ${it.message}") } }
    }
    val events = remember { CalendarSync.cached(ctx).flatMap { it.events } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        val ds = days
        BackTitle("This week", ds?.let { "${MealPlan.planned(it)} of 7 planned · week ${MealPlan.week(week)}" }, onBack)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("‹ Last week", color = Muted, fontSize = 13.sp, modifier = Modifier.clickable { week = week.minusWeeks(1) })
            Text("Next week ›", color = Muted, fontSize = 13.sp, modifier = Modifier.clickable { week = week.plusWeeks(1) })
        }
        if (ds == null) return@Column
        ListCard(Modifier.padding(top = 10.dp)) {
            Column {
                ds.forEachIndexed { i, d ->
                    if (i > 0) HorizontalDivider(color = Outline)
                    val event = events.firstOrNull { it.on(d.date) && !it.allDay && it.start.hour >= 11 }?.title
                    val mins = d.recipe?.let { r -> recipes.firstOrNull { it.title.equals(r, true) }?.minutes }
                    Row(Modifier.fillMaxWidth().clickable { picking = d.date }.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(d.date.format(DateTimeFormatter.ofPattern("EEE", Locale.UK)), fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                            color = if (d.date == today) Primary else Muted, modifier = Modifier.width(40.dp))
                        Column(Modifier.weight(1f)) {
                            if (d.empty) Text(event?.let { "$it (calendar)" } ?: "＋ Pick a recipe", fontSize = 14.sp, color = if (event != null) Muted else Primary)
                            else Text(d.label + (mins?.let { " · ${TaskRules.minutesLabel(it)}" } ?: ""), fontSize = 14.sp)
                        }
                        if (!d.empty && i < 6) Text("⇅", color = Muted, fontSize = 16.sp, modifier = Modifier.clickable { save(MealPlan.swap(ds, d.date, d.date.plusDays(1))) }.padding(6.dp))
                    }
                }
            }
        }
        val shop = MealPlan.shopping(ds, recipes)
        if (shop.isNotEmpty()) {
            GroupLabel("🛒 Shopping for the week · ${shop.size} items")
            ListCard(Modifier.padding(top = 6.dp)) {
                Column(Modifier.padding(14.dp)) {
                    MealPlan.mergedNote(shop)?.let { Text(it, fontSize = 12.sp, color = Muted) }
                    Text("Skips what you've ticked as \"have\" in each recipe.", fontSize = 12.sp, color = Muted)
                    shop.take(20).forEach { Text("• ${it.text}", fontSize = 14.sp, modifier = Modifier.padding(top = 3.dp)) }
                    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SheetButton("Add to ${ErrandNudges.shopTag(ctx)}", Modifier.weight(1f), primary = true) {
                            val uri = vault ?: return@SheetButton
                            scope.launch {
                                val n = withContext(Dispatchers.IO) { runCatching { Recipes.addToShopping(appVault(ctx, uri), shop.map { it.text }) } }
                                n.onSuccess { snack.showSnackbar("Added $it to the shopping list") }.onFailure { snack.showSnackbar("Failed: ${it.message}") }
                            }
                        }
                    }
                }
            }
        }
        Text("Saved in ${vault?.let { "Recipes/Week ${MealPlan.week(week)}.md" }}", fontSize = 11.sp, color = Muted, modifier = Modifier.padding(vertical = 14.dp))
    }
    picking?.let { date ->
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { picking = null }, sheetState = sheet, containerColor = Surface1) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
                Text(date.format(DAY), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MealPlan.QUICK.forEach { q -> AppChip(false, { days?.let { save(MealPlan.set(it, date, null, q)) }; picking = null }, { Text(q) }) }
                }
                GroupLabel("Recipes")
                if (recipes.isEmpty()) Text("Save a recipe first (share a recipe link to Flight Deck).", fontSize = 13.sp, color = Muted)
                recipes.forEach { r ->
                    QuietRow(null, r.title, r.minutes?.let { TaskRules.minutesLabel(it) }, onClick = { days?.let { save(MealPlan.set(it, date, r.title)) }; picking = null })
                }
                Text("Clear this day", color = Muted, fontSize = 13.sp, modifier = Modifier.clickable { days?.let { save(MealPlan.set(it, date, null, null)) }; picking = null }.padding(top = 12.dp))
            }
        }
    }
}

/** Deck bits for Stage 5: today's people, tonight's meal and errand count. Blocking. */
internal data class HomeBits(val people: List<PersonNudge>, val tonight: MealDay?, val planned: Int, val errands: Int)

internal fun loadHomeBits(ctx: Context, vault: Uri, prefs: AppPrefs, today: LocalDate): HomeBits {
    val v = appVault(ctx, vault)
    val people = runCatching { People.list(v) }.getOrDefault(emptyList())
    // Gift tasks a week ahead, checked once a day.
    if (prefs.giftsCheckedOn != today.toString()) {
        runCatching { People.addGiftTasks(v, VaultWriter(ctx, vault), people, today) }
        prefs.giftsCheckedOn = today.toString()
    }
    val plan = runCatching { MealPlan.load(v, today) }.getOrNull()
    val errands = runCatching {
        val tasks = VaultWriter(ctx, vault).listTasks()
        val shopping = Recipes.shopping(v)?.let { Recipes.shoppingOpen(it) }.orEmpty()
        Errands.errands(tasks, shopping, Errands.places(v), ErrandNudges.shopTag(ctx)).sumOf { it.items.size }
    }.getOrDefault(0)
    return HomeBits(People.comingUp(people, today).filter { it.birthday == null || it.days <= People.GIFT_DAYS }, plan?.let { MealPlan.tonight(it, today) },
        plan?.let { MealPlan.planned(it) } ?: 0, errands)
}
