package uk.tomlowndes.capture

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Print from share (printables-v1, frame 2): "🧾 Print slip" in the Android share sheet. Shared text prints as
 * plain text, one tick box per line, or a Jarvis summary, on the printer saved in Daily Slip.
 */
class PrintShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
        val from = referrer?.host?.let { pkg ->
            runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrNull()
        }
        SlipText.load(this)
        setContent { CaptureTheme { PrintShareScreen(text, subject, from) { finish() } } }
    }
}

@Composable
private fun PrintShareScreen(text: String, subject: String?, from: String?, onDone: () -> Unit) {
    val snack = remember { SnackbarHostState() }
    var title by remember { mutableStateOf(PrintDocs.shareTitle(subject, text)) }
    var how by remember { mutableStateOf(ShareAs.TEXT) }
    var paper by remember { mutableStateOf<Bitmap?>(null) }
    val time = remember { LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm")) }
    LaunchedEffect(title, how) {
        if (how == ShareAs.SUMMARY || text.isBlank()) return@LaunchedEffect
        val d = PrintDocs.shared(title, text, how, from, time)
        paper = withContext(Dispatchers.Default) { runCatching { SlipRender.drawList(d) }.getOrNull() }
    }
    Scaffold(snackbarHost = { SnackbarHost(snack) }, containerColor = Bg) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).systemBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
            Text("‹ Back", color = Muted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 10.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onDone))
            Text("Print slip", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
            Text(from?.let { "Shared from $it" } ?: "Shared text", fontSize = 13.sp, color = Muted, modifier = Modifier.padding(bottom = 10.dp))
            if (text.isBlank()) { Text("Nothing to print: only text can be shared here.", color = Muted); return@Column }
            OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Title") },
                shape = RoundedCornerShape(14.dp), colors = fieldColors())
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ShareAs.values().forEach { a -> AppChip(how == a, { how = a }, { Text(a.label) }, RoundedCornerShape(99.dp)) }
            }
            if (how != ShareAs.SUMMARY) {
                Box(
                    Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = 120.dp).clip(RoundedCornerShape(6.dp)).background(Color.White).padding(vertical = 10.dp),
                    contentAlignment = Alignment.TopCenter
                ) {
                    val b = paper
                    if (b != null) Image(b.asImageBitmap(), "Preview of the slip", Modifier.fillMaxWidth(0.86f), contentScale = ContentScale.FillWidth, filterQuality = FilterQuality.Medium)
                }
                SlipPrintButton(paper, snack, Modifier.padding(top = 10.dp, bottom = 28.dp))
            }
        }
    }
    // Summary goes through Jarvis, the same as Print summary in the Vault reader.
    if (how == ShareAs.SUMMARY) PrintSummarySheet(SummarySource.Note(title, text), snack) { how = ShareAs.TEXT }
}
