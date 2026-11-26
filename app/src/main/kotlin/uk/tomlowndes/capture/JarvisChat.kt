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

// The pieces of the Jarvis chat (JarvisScreen.kt): bubbles, banners and cards. State stays in JarvisScreen.

private val DoneBg = Color(0xFF14271F)
private val DoneBorder = Color(0xFF24493A)
private val ErrBg = Color(0xFF2A1716)
private val ErrBorder = Color(0xFF4A2523)
private val AnswerShape = RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)

/** An answer's Markdown as styled text: bold, italic, code, link text, headings in bold, and "- " bullets as "•". */
internal fun chatMarkdown(md: String): AnnotatedString = buildAnnotatedString {
    fun inline(p: String) = appendInline(p, underline = false, tapLinks = false)
    md.trim().lines().forEachIndexed { i, raw ->
        if (i > 0) append("\n")
        val line = raw.trimEnd()
        val head = Regex("^#{1,6}\\s+(.*)").find(line)
        val bullet = Regex("^(\\s*)[-*+]\\s+(.*)").find(line)
        when {
            head != null -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { inline(head.groupValues[1]) }
            bullet != null -> { append(bullet.groupValues[1] + "• "); inline(bullet.groupValues[2]) }
            else -> inline(line)
        }
    }
}

/** One of Tom's messages: right-aligned, with how it was sent and when. */
@Composable
internal fun MineBubble(text: String, caption: String) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Column(
            Modifier.padding(start = 48.dp).clip(RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)).background(Primary)
                .padding(horizontal = 13.dp, vertical = 9.dp),
            horizontalAlignment = Alignment.End
        ) {
            Text(text, fontSize = 14.5.sp, lineHeight = 21.sp, color = OnPrimary)
            Text(caption, fontSize = 10.5.sp, color = OnPrimary.copy(alpha = 0.7f), modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** A route line, e.g. "Sent via the server", between a message and the server's answer. */
@Composable
internal fun JarvisRouteLine(text: String) {
    Text(
        text, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, color = Muted, lineHeight = 17.sp,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Surface2)
            .border(1.dp, Outline, RoundedCornerShape(12.dp)).padding(horizontal = 11.dp, vertical = 8.dp)
    )
}

/** Where one answer's server audio is: [here] when it is the one loaded in the player. */
internal data class JarvisAudio(val here: Boolean, val playing: Boolean, val pos: Float, val durMs: Long)

/**
 * One answer from [who]: Save as note, Try the server (Nano answers), and Read out or the audio player.
 * [idle] is false while something is sending. [reading] is true while the phone voice reads this one out.
 */
@Composable
internal fun JarvisAnswer(
    m: Jarvis.ChatMsg,
    who: String,
    idle: Boolean,
    phoneVoice: Boolean,
    reading: Boolean,
    audio: JarvisAudio?,
    speed: Float,
    onSaveNote: () -> Unit,
    onTryServer: () -> Unit,
    onReadOut: () -> Unit,
    onPlay: () -> Unit,
    onSpeed: () -> Unit,
) {
    Column(
        Modifier.padding(end = 36.dp).clip(AnswerShape).background(Surface1)
            .border(1.dp, Outline, AnswerShape).padding(horizontal = 13.dp, vertical = 10.dp)
    ) {
        val nano = m.via == NanoRoute.VIA
        Text(
            (m.via?.let { if (nano) "on this phone" else it } ?: who).uppercase(Locale.UK), fontSize = 10.sp, letterSpacing = 1.sp,
            fontFamily = FontFamily.Monospace, color = if (m.via != null && !nano) Amber else Primary
        )
        Text(remember(m.text) { chatMarkdown(m.text) }, fontSize = 14.5.sp, lineHeight = 21.sp, color = TextMain, modifier = Modifier.padding(top = 3.dp))
        // Tower notes: any reply can be kept as a note; it waits on Check and save like a ramble does.
        Text(
            "📝 Save as note", fontSize = 12.sp, color = Primary,
            modifier = Modifier.padding(top = 8.dp).clip(CircleShape).background(PrimaryTint).border(1.dp, NextBorder, CircleShape)
                .clickable(onClick = onSaveNote)
                .padding(horizontal = 10.dp, vertical = 4.dp)
        )
        if (nano) {
            Text("on phone · free", fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, color = Muted, modifier = Modifier.padding(top = 6.dp))
            Text(
                "Try the server", fontSize = 12.sp, color = if (idle) Primary else Muted,
                modifier = Modifier.padding(top = 8.dp).clip(CircleShape).background(PrimaryTint).border(1.dp, NextBorder, CircleShape)
                    .clickable(enabled = idle, onClick = onTryServer).padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
        if (m.audio == null && phoneVoice) {
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier.size(28.dp).clip(CircleShape).background(PrimaryTint).clickable(onClick = onReadOut),
                    contentAlignment = Alignment.Center
                ) { Text(if (reading) "■" else "▶", fontSize = 11.sp, color = Primary) }
                Text(if (reading) "Reading out…" else "Read out · ${Jarvis.speedLabel(speed)}", fontSize = 11.5.sp, color = Muted)
            }
        }
        if (m.audio != null && audio != null) {
            Row(
                Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    Modifier.size(28.dp).clip(CircleShape).background(PrimaryTint).clickable(onClick = onPlay),
                    contentAlignment = Alignment.Center
                ) { Text(if (audio.here && audio.playing) "❚❚" else "▶", fontSize = 11.sp, color = Primary) }
                if (audio.here) {
                    Box(Modifier.width(90.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Surface2)) {
                        Box(Modifier.fillMaxWidth(audio.pos.coerceIn(0f, 1f)).height(4.dp).background(Primary))
                    }
                    Text("${msClock((audio.durMs * audio.pos).toLong())} / ${msClock(audio.durMs)}", fontSize = 11.5.sp, color = Muted)
                    Text(
                        Jarvis.speedLabel(speed), fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Primary,
                        modifier = Modifier.clip(CircleShape).background(PrimaryTint).clickable(onClick = onSpeed)
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                } else Text("Play · ${Jarvis.speedLabel(speed)}", fontSize = 11.5.sp, color = Muted)
            }
        }
    }
}

/** The answer so far while it streams in, from Nano ([nano]) or Claude. */
@Composable
internal fun JarvisStreaming(text: String, nano: Boolean, who: String) {
    Column(
        Modifier.padding(end = 36.dp).clip(AnswerShape).background(Surface1)
            .border(1.dp, Outline, AnswerShape).padding(horizontal = 13.dp, vertical = 10.dp)
    ) {
        Text((if (nano) "on this phone" else who).uppercase(Locale.UK), fontSize = 10.sp, letterSpacing = 1.sp, fontFamily = FontFamily.Monospace, color = Primary)
        Text(remember(text) { chatMarkdown("$text ▍") }, fontSize = 14.5.sp, lineHeight = 21.sp, color = TextMain, modifier = Modifier.padding(top = 3.dp))
        Text(if (nano) "📱 on phone…" else "⚡ streaming…", fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, color = Muted, modifier = Modifier.padding(top = 6.dp))
    }
}

/** The typing dots while an answer is on its way. */
@Composable
internal fun JarvisTyping() {
    Text(
        "• • •", fontSize = 16.sp, color = Muted, letterSpacing = 3.sp,
        modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(Surface1).border(1.dp, Outline, RoundedCornerShape(18.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp)
    )
}

/** A send that failed: [msg], what's kept, Retry and Discard. */
@Composable
internal fun JarvisError(msg: String, textKept: Boolean, canRetry: Boolean, onRetry: () -> Unit, onDiscard: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 18.dp).clip(RoundedCornerShape(14.dp)).background(ErrBg)
            .border(1.dp, ErrBorder, RoundedCornerShape(14.dp)).padding(14.dp)
    ) {
        Text(msg, fontSize = 13.sp, color = Warn, fontWeight = FontWeight.SemiBold)
        Text(if (textKept) "Your message is kept." else "Your recording is kept.", fontSize = 13.sp, color = Muted)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onRetry, enabled = canRetry) { Text("Retry", color = Primary) }
            TextButton(onClick = onDiscard) { Text("Discard", color = Muted) }
        }
    }
}

/** What a ramble was sorted into, waiting behind Check and save. */
@Composable
internal fun JarvisFound(r: SortResult, onCheck: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 14.dp).clip(RoundedCornerShape(16.dp)).background(PrimaryTint)
            .border(1.dp, NextBorder, RoundedCornerShape(16.dp)).clickable(onClick = onCheck).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("✨", fontSize = 16.sp)
        Text(foundLabel(r), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text("Check and save", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = OnPrimary,
            modifier = Modifier.clip(CircleShape).background(Primary).padding(horizontal = 12.dp, vertical = 7.dp))
    }
}

/** A background job that finished, above the type box. */
@Composable
internal fun JarvisDone(d: Jarvis.Done, onOpen: () -> Unit, onClose: () -> Unit) {
    Row(
        Modifier.padding(bottom = 8.dp).fillMaxWidth()
            .clip(RoundedCornerShape(14.dp)).background(DoneBg).border(1.dp, DoneBorder, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(Modifier.size(24.dp).clip(CircleShape).background(Green), contentAlignment = Alignment.Center) {
            Text("✓", fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF0B2117))
        }
        Column(Modifier.weight(1f)) {
            Text(d.title, fontSize = 13.sp)
            if (d.detail.isNotBlank()) Text(d.detail, fontSize = 11.5.sp, color = Muted, maxLines = 1)
        }
        Text("Open", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = Green, modifier = Modifier.clickable(onClick = onOpen))
        Text("✕", fontSize = 13.sp, color = Muted, modifier = Modifier.clickable(onClick = onClose).padding(start = 4.dp))
    }
}

private fun foundLabel(r: SortResult): String = listOfNotNull(
    if (r.note != null) "1 note" else null,
    r.tasks.size.takeIf { it > 0 }?.let { if (it == 1) "1 task" else "$it tasks" },
    r.ideas.size.takeIf { it > 0 }?.let { if (it == 1) "1 idea" else "$it ideas" },
).joinToString(" · ")

private fun msClock(ms: Long): String = "%d:%02d".format(ms / 60_000, (ms / 1000) % 60)
