package uk.tomlowndes.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.zIndex
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Settings › Jarvis › Voice. Lists the server's voices (GET /voices); ▶ plays a voice's sample when the server has one.
 * [onPick] gets the id ("" for the server's default) and a label for the row.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun JarvisVoiceSheet(prefs: AppPrefs, onPick: (String, String) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val address = remember { prefs.jarvisAddress }
    val token = remember { SecretStore(ctx).jarvisToken.orEmpty() }
    var voices by remember { mutableStateOf<List<Jarvis.Voice>?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    var sampling by remember { mutableStateOf<String?>(null) }
    val player = remember { arrayOfNulls<MediaPlayer>(1) }
    DisposableEffect(Unit) { onDispose { player[0]?.release(); player[0] = null } }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { runCatching { Jarvis.voices(address, token) } }
            .onSuccess { voices = it }
            .onFailure { voices = emptyList(); failed = "Couldn't load the voices: ${it.message ?: "no answer"}" }
    }
    fun sample(v: Jarvis.Voice) {
        val url = v.sampleUrl ?: return
        sampling = v.id
        scope.launch {
            val f = File(ctx.cacheDir, "jarvis-sample.ogg")
            val ok = withContext(Dispatchers.IO) { runCatching { Jarvis.fetchAudio(address, token, url, f) }.isSuccess }
            if (ok) {
                player[0]?.release()
                player[0] = MediaPlayer().apply {
                    runCatching {
                        setDataSource(f.absolutePath); prepare(); start(); setSpeed(this, prefs.jarvisSpeed)
                        setOnCompletionListener { sampling = null }
                    }.onFailure { sampling = null }
                }
            } else {
                sampling = null
            }
        }
    }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Surface1) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text("${prefs.assistantName}'s voice", fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
            val list = voices
            if (list == null) Text("Loading…", fontSize = 13.sp, color = Muted, modifier = Modifier.padding(vertical = 12.dp))
            val rows = list.orEmpty() + Jarvis.Voice("", "Server default", "Whatever the server is set to", null)
            if (list != null) rows.forEach { v ->
                val on = v.id == prefs.jarvisVoice
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(v.id, if (v.id.isEmpty()) "" else v.label) }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    RadioButton(
                        selected = on, onClick = { onPick(v.id, if (v.id.isEmpty()) "" else v.label) },
                        colors = RadioButtonDefaults.colors(selectedColor = Primary, unselectedColor = Muted)
                    )
                    Column(Modifier.weight(1f)) {
                        Text(v.label, fontSize = 15.sp)
                        if (v.detail.isNotBlank()) Text(v.detail, fontSize = 12.sp, color = Muted)
                    }
                    if (v.sampleUrl != null) Box(
                        Modifier.size(30.dp).clip(CircleShape).background(PrimaryTint).clickable { sample(v) },
                        contentAlignment = Alignment.Center
                    ) { Text(if (sampling == v.id) "…" else "▶", fontSize = 11.sp, color = Primary) }
                }
            }
            failed?.let { Text(it, fontSize = 12.5.sp, color = Warn, modifier = Modifier.padding(top = 6.dp)) }
            if (list != null && list.isEmpty() && failed == null)
                Text("Your server doesn't list its voices yet, so it uses its own default.", fontSize = 12.5.sp, color = Muted, modifier = Modifier.padding(top = 6.dp))
            else Text("The list comes from your server. ▶ plays a short sample.", fontSize = 12.5.sp, color = Muted, modifier = Modifier.padding(top = 6.dp))
        }
    }
}
