package uk.tomlowndes.capture

import android.app.DatePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.os.PersistableBundle
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val Ink = Color(0xFF0D1630)

/** Puts [text] on the clipboard, marked sensitive so Android doesn't show it in the copy preview. */
internal fun copySensitive(ctx: Context, label: String, text: String) {
    val clip = ClipData.newPlainText(label, text)
    clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
}

/**
 * Asks for a fingerprint, face or the screen lock, then runs [onOk]. Uses the platform prompt, so it works from any
 * screen without a FragmentActivity.
 */
internal fun unlock(ctx: Context, title: String, onOk: () -> Unit, onFail: (String) -> Unit) {
    val b = BiometricPrompt.Builder(ctx).setTitle(title).setSubtitle("Pocket Pass")
    if (Build.VERSION.SDK_INT >= 30) b.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
    else @Suppress("DEPRECATION") b.setDeviceCredentialAllowed(true)
    runCatching {
        b.build().authenticate(CancellationSignal(), ctx.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) = onOk()
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                if (errorCode != BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED && errorCode != BiometricPrompt.BIOMETRIC_ERROR_CANCELED) onFail(errString?.toString() ?: "Couldn't check it's you")
            }
        })
    }.onFailure { onFail("This phone has no screen lock set up") }
}

/**
 * Pocket Pass (hangar-phase2): the codes you keep hunting for. Copy copies; tap a code to show it big for reading
 * out or showing someone; a Wi-Fi code shows a join QR. 🔒 codes stay dotted until tapped.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PocketPassScreen(vault: Uri?, prefs: AppPrefs, snack: SnackbarHostState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var codes by remember { mutableStateOf<List<PassCode>?>(null) }
    var shown by remember { mutableStateOf(setOf<PassCode>()) }
    var big by remember { mutableStateOf<PassCode?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<PassCode?>(null) }
    var lock by remember { mutableStateOf(prefs.passLock) }
    /** Shows a 🔒 code, after a fingerprint check when that's switched on. */
    fun reveal(c: PassCode) {
        if (!lock) { shown = shown + c; return }
        unlock(ctx, "Show ${c.name}", onOk = { shown = shown + c }) { msg -> scope.launch { snack.showSnackbar(msg) } }
    }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(vault, reload) {
        val uri = vault ?: return@LaunchedEffect
        withContext(Dispatchers.IO) { runCatching { val v = appVault(ctx, uri); PocketPass.tidy(v, LocalDate.now()); PocketPass.load(v) } }
            .onSuccess { codes = it }.onFailure { snack.showSnackbar("Couldn't load Pocket Pass: ${it.message}") }
    }
    BackHandler { if (big != null) big = null else onBack() }

    fun copy(c: PassCode) {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        copySensitive(ctx, c.name, c.value)
        scope.launch { snack.showSnackbar("${c.name} copied") }
    }

    big?.let { c -> BigCode(c, onCopy = { copy(c) }) { big = null }; return }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = Muted) }
            Text("Pocket Pass", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Teal, modifier = Modifier.weight(1f))
            if (vault != null) Text("＋ Add", color = Primary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(CircleShape).clickable { adding = true }.padding(horizontal = 12.dp, vertical = 8.dp))
        }
        val list = codes?.let { PocketPass.visible(it, LocalDate.now()) }
        when {
            vault == null -> Text("Pick your vault in Settings first.", color = Muted, modifier = Modifier.padding(top = 12.dp))
            list == null -> Text("Loading…", color = Muted, modifier = Modifier.padding(top = 12.dp))
            list.isEmpty() -> Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("No codes yet.", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("Add the ones you always hunt for: NHS number, car reg, the Wi-Fi password, a booking ref. They live in ${PocketPass.NOTE}, and never go to Claude or into search.",
                    color = Muted, fontSize = 13.sp)
            }
            else -> {
                list.groupBy { it.group }.forEach { (group, rows) ->
                    Box(Modifier.padding(top = 10.dp, bottom = 6.dp)) { GroupLabel(group) }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        rows.forEach { c ->
                            val hide = c.hidden && c !in shown
                            CodeRow(c, hide,
                                onTap = { if (hide) reveal(c) else big = c },
                                onLong = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); deleting = c },
                                onAction = { when { hide -> reveal(c); c.isWifi -> big = c; else -> copy(c) } })
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 14.dp).clip(RoundedCornerShape(14.dp)).background(Surface1).clickable {
                    lock = !lock; prefs.passLock = lock
                }.padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Fingerprint for 🔒 codes", fontSize = 14.sp)
                        Text("Or your screen lock, if there's no fingerprint", color = Muted, fontSize = 12.sp)
                    }
                    Switch(lock, { lock = it; prefs.passLock = it }, colors = SwitchDefaults.colors(checkedTrackColor = Teal, checkedThumbColor = Ink))
                }
                Text("Tap a code to show it big. Hold one to delete it.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp, bottom = 20.dp))
            }
        }
    }

    deleting?.let { c ->
        AlertDialog(
            onDismissRequest = { deleting = null }, containerColor = Surface1,
            title = { Text("Delete ${c.name}?") },
            text = { Text("It comes out of ${PocketPass.NOTE}.", color = Muted) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    val uri = vault ?: return@TextButton
                    scope.launch {
                        withContext(Dispatchers.IO) { runCatching { PocketPass.remove(appVault(ctx, uri), c) } }
                            .onSuccess { reload++ }.onFailure { snack.showSnackbar("Couldn't delete: ${it.message}") }
                    }
                }) { Text("Delete", color = Coral) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel", color = Muted) } }
        )
    }

    if (adding) AddCodeDialog(onDismiss = { adding = false }) { c ->
        adding = false
        val uri = vault ?: return@AddCodeDialog
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { PocketPass.add(appVault(ctx, uri), c) } }
                .onSuccess { reload++ }.onFailure { snack.showSnackbar("Couldn't save: ${it.message}") }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CodeRow(c: PassCode, hide: Boolean, onTap: () -> Unit, onLong: () -> Unit, onAction: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Surface1).combinedClickable(onClick = onTap, onLongClick = onLong)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(listOfNotNull(c.name, c.until?.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK))).joinToString(" · ").uppercase(Locale.UK),
                color = Muted, fontSize = 11.sp, letterSpacing = 0.6.sp, maxLines = 1)
            Text(if (!hide) c.value else if (c.isWifi) "•".repeat(10) else PocketPass.masked(c.value),
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, letterSpacing = 0.6.sp, maxLines = 1)
        }
        Text(when { hide -> "Show"; c.isWifi -> "QR"; else -> "Copy" }, color = Teal, fontSize = 12.sp,
            modifier = Modifier.clip(CircleShape).border(1.dp, Teal.copy(alpha = 0.4f), CircleShape).clickable(onClick = onAction).padding(horizontal = 12.dp, vertical = 6.dp))
    }
}

/** One code filling the screen, light on dark like a boarding pass, for reading out or turning round to show. */
@Composable
private fun BigCode(c: PassCode, onCopy: () -> Unit, onDone: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("✕", color = Muted, fontSize = 20.sp, modifier = Modifier.clip(CircleShape).clickable(onClick = onDone).padding(8.dp))
            Spacer(Modifier.weight(1f))
            Text(c.name, color = Muted, fontSize = 14.sp)
        }
        Column(
            Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(22.dp)).background(TextMain).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically)
        ) {
            Text((if (c.isWifi) c.ssid.ifEmpty { c.name } else c.name).uppercase(Locale.UK), color = Ink, fontSize = 13.sp, letterSpacing = 1.2.sp)
            if (c.isWifi) {
                QrCode(PocketPass.qr(PocketPass.wifiText(c.ssid.ifEmpty { c.name }, c.value)), Modifier.width(220.dp))
                Text("Guests scan this to join. No typing.", color = Ink.copy(alpha = 0.6f), fontSize = 13.sp, textAlign = TextAlign.Center)
            } else {
                Text(c.value, color = Ink, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 34.sp, letterSpacing = 2.sp, textAlign = TextAlign.Center)
                Text("Turn the phone round to show someone", color = Ink.copy(alpha = 0.6f), fontSize = 13.sp)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (c.isWifi) "Copy password" else "Copy", color = TextMain, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1f)
                .clip(CircleShape).border(1.dp, Outline, CircleShape).clickable(onClick = onCopy).padding(vertical = 14.dp))
            Text("Done", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.weight(1f)
                .clip(CircleShape).background(Teal).clickable(onClick = onDone).padding(vertical = 14.dp))
        }
    }
}

@Composable
private fun QrCode(m: Array<BooleanArray>, modifier: Modifier) =
    Canvas(modifier.aspectRatio(1f).background(Color.White).padding(10.dp)) {
        val n = m.size
        val cell = size.width / n
        for (y in 0 until n) for (x in 0 until n) if (m[y][x])
            drawRect(Ink, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
    }

@Composable
private fun AddCodeDialog(onDismiss: () -> Unit, onAdd: (PassCode) -> Unit) {
    val ctx = LocalContext.current
    var group by remember { mutableStateOf("Me") }
    var name by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    var hidden by remember { mutableStateOf(false) }
    var until by remember { mutableStateOf<LocalDate?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Surface1,
        title = { Text("Add a code") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PocketPass.GROUPS.forEach { g -> AppChip(group == g, { group = g }, { Text(g) }) }
                }
                OutlinedTextField(name, { name = it.replace("·", "") }, singleLine = true, colors = fieldColors(),
                    placeholder = { Text("Name, e.g. Car reg or Wi-Fi Home") })
                OutlinedTextField(value, { value = it.replace("·", "") }, singleLine = true, colors = fieldColors(), placeholder = { Text("The code") })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Hide until tapped", fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Switch(hidden, { hidden = it }, colors = SwitchDefaults.colors(checkedTrackColor = Teal, checkedThumbColor = Ink))
                }
                if (group == "Bookings") Text(until?.let { "Goes after ${it.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK))}" } ?: "＋ Add the day (it goes after that)",
                    color = Teal, fontSize = 14.sp, modifier = Modifier.clickable {
                        val d = until ?: LocalDate.now()
                        DatePickerDialog(ctx, { _, y, m, day -> until = LocalDate.of(y, m + 1, day) }, d.year, d.monthValue - 1, d.dayOfMonth).show()
                    }.padding(vertical = 6.dp))
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && value.isNotBlank(), onClick = {
                onAdd(PassCode(group, name.trim(), value.trim(), hidden, until.takeIf { group == "Bookings" }))
            }) { Text("Add", color = Primary) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Muted) } }
    )
}
