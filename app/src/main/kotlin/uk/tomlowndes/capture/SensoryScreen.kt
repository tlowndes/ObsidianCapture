package uk.tomlowndes.capture

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Sensory Dial's colour: the amber-orange of the dial's ring at Low. */
internal val Ember = Color(0xFFE8963A)
private val EmberDeep = Color(0xFFD9622B)

/**
 * Talks to Home Assistant for Sensory Dial (sensory-dial-v1): sets the level, reads it back, and reads its history
 * for the vault log. Blocking: call off the main thread. On the home network HA usually speaks plain HTTP, so that is
 * written on a socket (as Piholegotchi does) rather than opening the whole app to cleartext; https goes the normal way.
 */
internal object SensoryHa {
    private const val TIMEOUT_MS = 5000

    class Setup(val address: String, val token: String)

    fun setup(ctx: Context): Setup? {
        val a = AppPrefs(ctx).haAddress.ifBlank { return null }
        val t = SecretStore(ctx).haToken?.ifBlank { null } ?: return null
        return Setup(a, t)
    }

    fun setLevel(s: Setup, level: SensoryLevel) {
        val (code, _) = call(s, "POST", Sensory.SELECT_PATH, Sensory.selectBody(level))
        if (code !in 200..299) error(why(code))
    }

    fun level(s: Setup): SensoryLevel? {
        val (code, body) = call(s, "GET", Sensory.STATE_PATH, null)
        if (code == 404) error("HA has no ${Sensory.ENTITY} yet. Add the Sensory Dial package first.")
        if (code !in 200..299) error(why(code))
        return Sensory.stateOf(body)
    }

    /** Reads HA's history since the last logged change and adds it to the vault log. Returns the whole log. */
    fun sync(s: Setup, v: Vault, now: LocalDateTime = LocalDateTime.now()): List<SensoryChange> {
        val logged = Sensory.load(v)
        val (code, body) = call(s, "GET", Sensory.historyPath(Sensory.since(logged, now)), null)
        if (code !in 200..299) error(why(code))
        return Sensory.save(v, Sensory.history(body))
    }

    /** For setup: "Connected · Normal" or why not. */
    fun test(s: Setup): String = runCatching {
        "Connected · " + (level(s)?.option ?: "level unknown")
    }.getOrElse { it.message ?: "Can't reach Home Assistant" }

    private fun why(code: Int) = when (code) {
        401, 403 -> "Home Assistant turned the token down. Make a new long-lived token in your HA profile."
        404 -> "Home Assistant doesn't know Sensory Dial yet. Add the package first."
        else -> "Home Assistant answered $code"
    }

    /** Returns the status code and body. */
    private fun call(s: Setup, method: String, path: String, json: String?): Pair<Int, String> {
        val ha = Sensory.ha(s.address) ?: error("Check the Home Assistant address")
        return runCatching { if (ha.https) https(ha, s.token, method, path, json) else plain(ha, s.token, method, path, json) }
            .getOrElse { e -> if (e is IllegalStateException) throw e else error("Can't reach Home Assistant. On home Wi-Fi?") }
    }

    private fun https(ha: Sensory.Ha, token: String, method: String, path: String, json: String?): Pair<Int, String> {
        val c = URL("https://${ha.host}:${ha.port}$path").openConnection() as HttpURLConnection
        try {
            c.requestMethod = method; c.connectTimeout = TIMEOUT_MS; c.readTimeout = TIMEOUT_MS
            c.setRequestProperty("Authorization", "Bearer $token")
            c.setRequestProperty("Content-Type", "application/json")
            if (json != null) { c.doOutput = true; c.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) } }
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            return code to body
        } finally { c.disconnect() }
    }

    /** HTTP/1.0 on a socket, so the answer comes whole and ends when the connection closes. */
    private fun plain(ha: Sensory.Ha, token: String, method: String, path: String, json: String?): Pair<Int, String> {
        val bytes = json?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        Socket().use { sock ->
            sock.connect(InetSocketAddress(ha.host, ha.port), TIMEOUT_MS)
            sock.soTimeout = TIMEOUT_MS
            val out = sock.getOutputStream()
            out.write(("$method $path HTTP/1.0\r\nHost: ${ha.host}:${ha.port}\r\nAuthorization: Bearer $token\r\n" +
                "Content-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
            out.write(bytes)
            out.flush()
            val all = ByteArrayOutputStream().also { sock.getInputStream().copyTo(it) }.toByteArray()
            val text = String(all, Charsets.UTF_8)
            val head = text.substringBefore("\r\n\r\n")
            val code = head.lineSequence().first().split(" ").getOrNull(1)?.toIntOrNull() ?: error("No answer")
            var body = text.substringAfter("\r\n\r\n", "")
            if (head.lines().any { it.lowercase().startsWith("transfer-encoding:") && "chunked" in it.lowercase() }) body = dechunk(body)
            return code to body
        }
    }

    private fun dechunk(s: String): String = buildString {
        var rest = s
        while (true) {
            val size = rest.substringBefore("\r\n").substringBefore(";").trim().toIntOrNull(16) ?: break
            if (size == 0) break
            rest = rest.substringAfter("\r\n")
            append(rest.take(size)); rest = rest.drop(size).removePrefix("\r\n")
        }
    }
}

/** Keeps the vault log current every 6 hours, so Weekly Wrap sees turns made at the desk when the app is shut. */
internal object SensorySync {
    private const val WORK = "sensory_sync"

    fun keep(ctx: Context, on: Boolean) {
        val wm = WorkManager.getInstance(ctx)
        if (!on) { wm.cancelUniqueWork(WORK); return }
        val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SensoryWorker>(6, TimeUnit.HOURS).setConstraints(net).build())
    }
}

class SensoryWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext
        val prefs = AppPrefs(app)
        if (Hangar.parse(prefs.hangarTools)?.contains(HangarTool.SENSORY) != true) return Result.success()
        val uri = prefs.vault ?: return Result.success()
        val s = SensoryHa.setup(app) ?: return Result.success()
        runCatching { SensoryHa.sync(s, appVault(app, uri)) }
        return Result.success()
    }
}

/** Sensory Dial's line in Hangar, from the vault log alone (no network). */
internal fun sensoryLine(changes: List<SensoryChange>, now: LocalDateTime = LocalDateTime.now()): Pair<String, String> {
    val level = Sensory.current(changes)
    val today = Sensory.downMinutes(changes, now.toLocalDate(), now)
    return (if (level.down) "${level.option} · ${Sensory.sinceWords(changes, now)}" else "Normal") to
        (if (today > 0) "$today min down today" else "")
}

/**
 * Sensory Dial (sensory-dial-v1): the same three levels as the knob on the desk, for when you're away from it, a week
 * of turned-down minutes, and the last few lines of the vault log. Home Assistant does the actual dimming.
 */
@Composable
fun SensoryScreen(vault: Uri?, prefs: AppPrefs, snack: SnackbarHostState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var setup by remember { mutableStateOf(SensoryHa.setup(ctx)) }
    var editing by remember { mutableStateOf(setup == null) }
    var log by remember { mutableStateOf<List<SensoryChange>?>(null) }
    var live by remember { mutableStateOf<SensoryLevel?>(null) }
    var busy by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    BackHandler(onBack = { if (editing && setup != null) editing = false else onBack() })

    // The log first (instant, from the vault), then HA's level and any history the log hasn't got yet.
    LaunchedEffect(vault, setup, reload) {
        val uri = vault ?: return@LaunchedEffect
        val v = withContext(Dispatchers.IO) { runCatching { appVault(ctx, uri) }.getOrNull() } ?: return@LaunchedEffect
        withContext(Dispatchers.IO) { runCatching { Sensory.load(v) } }.onSuccess { log = it }
        val s = setup ?: return@LaunchedEffect
        SensorySync.keep(ctx, true)
        withContext(Dispatchers.IO) { runCatching { SensoryHa.level(s) to SensoryHa.sync(s, v) } }
            .onSuccess { (l, all) -> live = l; log = all }
            .onFailure { snack.showSnackbar(it.message ?: "Couldn't reach Home Assistant") }
    }

    fun pick(level: SensoryLevel) {
        val s = setup ?: return
        if (busy) return
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        busy = true
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { SensoryHa.setLevel(s, level) } }
                .onSuccess { live = level; delay(1500); reload++ }
                .onFailure { snack.showSnackbar(it.message ?: "Couldn't change the level") }
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = Muted) }
            Text("Sensory Dial", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Ember, modifier = Modifier.weight(1f))
            if (setup != null && !editing) Text("Home Assistant", color = Primary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(CircleShape).clickable { editing = true }.padding(horizontal = 12.dp, vertical = 8.dp))
        }
        val now = LocalDateTime.now()
        when {
            vault == null -> Text("Pick your vault in Settings first.", color = Muted, modifier = Modifier.padding(top = 12.dp))
            editing -> HaSetup(prefs, setup, onSaved = { s -> setup = s; editing = false; reload++ }, onCancel = if (setup != null) ({ editing = false }) else null)
            else -> Column(Modifier.padding(top = 6.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val changes = log.orEmpty()
                val level = live ?: Sensory.current(changes)
                LevelCard(level, Sensory.sinceWords(changes, now).takeIf { changes.isNotEmpty() && Sensory.current(changes) == level }, busy, ::pick)
                WeekCard(Sensory.week(changes, now), now)
                LogCard(changes)
            }
        }
    }
}

@Composable
private fun LevelCard(level: SensoryLevel, since: String?, busy: Boolean, onPick: (SensoryLevel) -> Unit) {
    val edge = if (level.down) Ember else Outline
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Surface1).border(1.dp, edge, RoundedCornerShape(18.dp)).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("SENSORY · ${level.option.uppercase(Locale.UK)}", color = if (level.down) Ember else Muted, fontSize = 12.sp,
                letterSpacing = 1.4.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
            if (since != null) Text(since, color = Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
        Text(level.words, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, lineHeight = 26.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SensoryLevel.values().forEach { l ->
                val on = l == level
                val fill = when { !on -> Surface2; l == SensoryLevel.SHELTER -> EmberDeep; l == SensoryLevel.LOW -> Ember; else -> Primary }
                Box(Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).background(fill)
                    .border(1.dp, if (on) fill else Outline, RoundedCornerShape(12.dp)).clickable(enabled = !busy && !on) { onPick(l) },
                    contentAlignment = Alignment.Center) {
                    Text(l.option, color = if (on) Bg else TextMain, fontSize = 15.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium)
                }
            }
        }
        Text("Same three levels as the knob, for when you're away from the desk.", color = Muted, fontSize = 13.sp)
    }
}

@Composable
private fun WeekCard(week: List<Pair<java.time.LocalDate, Int>>, now: LocalDateTime) {
    val top = (week.maxOfOrNull { it.second } ?: 0).coerceAtLeast(30)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Surface1).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("THIS WEEK", color = Muted, fontSize = 12.sp, letterSpacing = 1.4.sp, fontFamily = FontFamily.Monospace)
        Row(Modifier.fillMaxWidth().height(96.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
            week.forEach { (_, m) ->
                val c = when { m == 0 -> Outline; m >= 60 -> EmberDeep; else -> Ember }
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Bottom, horizontalAlignment = Alignment.CenterHorizontally) {
                    if (m > 0) Text("$m", color = Muted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    Box(Modifier.fillMaxWidth().height((70f * m / top).coerceAtLeast(4f).dp).clip(RoundedCornerShape(4.dp)).background(c))
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            week.forEach { (d, _) ->
                Text(d.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.UK), color = if (d == now.toLocalDate()) TextMain else Muted,
                    fontSize = 11.sp, fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            }
        }
        Text("Minutes turned down each day. No judgement, just a pattern to notice.", fontSize = 14.sp, lineHeight = 19.sp)
    }
}

@Composable
private fun LogCard(changes: List<SensoryChange>) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Bg).border(1.dp, Outline, RoundedCornerShape(14.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(Sensory.LOG, color = Ember, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        if (changes.isEmpty()) Text("Nothing logged yet. Turn the dial and it shows up here.", color = Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        val tail = changes.takeLast(4)
        tail.forEachIndexed { i, c ->
            val until = tail.getOrNull(i + 1)?.at
            Text(Sensory.line(c, until), color = TextMain, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

/** Address and token, checked against HA before they're saved. */
@Composable
private fun HaSetup(prefs: AppPrefs, current: SensoryHa.Setup?, onSaved: (SensoryHa.Setup) -> Unit, onCancel: (() -> Unit)?) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf(current?.address ?: prefs.haAddress.ifBlank { "homeassistant.local:8123" }) }
    var token by remember { mutableStateOf(current?.token.orEmpty()) }
    var result by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    Column(Modifier.padding(top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Connect Home Assistant", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text("Sensory Dial sets input_select.sensory_level in HA, and HA does the lights, noise and Do Not Disturb. " +
            "Make a long-lived access token in your HA profile, at the bottom of the Security tab.", color = Muted, fontSize = 14.sp, lineHeight = 19.sp)
        OutlinedTextField(address, { address = it.trim() }, singleLine = true, colors = fieldColors(), label = { Text("Address") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(token, { token = it.trim() }, singleLine = true, colors = fieldColors(), label = { Text("Long-lived access token") },
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        result?.let { Text(it, color = if (it.startsWith("Connected")) Green else Warn, fontSize = 13.sp) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).background(if (testing) Surface2 else Ember)
                .clickable(enabled = !testing && address.isNotBlank() && token.isNotBlank()) {
                    testing = true
                    scope.launch {
                        val s = SensoryHa.Setup(address, token)
                        val r = withContext(Dispatchers.IO) { SensoryHa.test(s) }
                        result = r; testing = false
                        // A missing helper still saves: HA is reachable and the package can come after.
                        if (r.startsWith("Connected") || r.startsWith("HA has no")) {
                            prefs.haAddress = address; SecretStore(ctx).haToken = token; onSaved(s)
                        }
                    }
                }, contentAlignment = Alignment.Center) {
                Text(if (testing) "Checking…" else "Test and save", color = Bg, fontWeight = FontWeight.Bold)
            }
            if (onCancel != null) Box(Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, Outline, RoundedCornerShape(12.dp))
                .clickable(onClick = onCancel).padding(horizontal = 18.dp), contentAlignment = Alignment.Center) { Text("Cancel") }
        }
    }
}
