package uk.tomlowndes.capture

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** Small add-task / save-link sheet launched from the capture widget, without opening the full app. */
class QuickCaptureActivity : ComponentActivity() {
    private var busy by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        val link = intent.getBooleanExtra(EXTRA_LINK, false)
        setContent { CaptureTheme { Sheet(link) } }
    }

    private fun save(link: Boolean, value: String, due: LocalDate?) {
        val vault = AppPrefs(this).vault
        if (vault == null) { done("Open Vault Capture once and pick your vault first"); return }
        busy = true
        lifecycleScope.launch {
            val msg = withContext(Dispatchers.IO) {
                runCatching {
                    if (link) {
                        val url = if (Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(value)) value else "https://$value"
                        if (ReadLater.save(applicationContext, vault, url, null)) "Saved page to Read It Later"
                        else "Saved link only (couldn't fetch the page)"
                    } else {
                        VaultWriter(applicationContext, vault).addTask(value, due?.toString())
                        "Task added" + (due?.let { " · due ${dueLabel(it)}" } ?: "")
                    }
                }.getOrElse { "Failed: ${it.message}" }
            }
            done(msg)
        }
    }

    private fun done(msg: String) {
        Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
        finish()
    }

    @androidx.compose.runtime.Composable
    private fun Sheet(link: Boolean) {
        var text by remember { mutableStateOf("") }
        var due by remember { mutableStateOf<LocalDate?>(null) }
        var showPicker by remember { mutableStateOf(false) }
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { focus.requestFocus() }
        fun submit() { if (text.isNotBlank() && !busy) save(link, text.trim(), due) }

        Box(
            Modifier.fillMaxSize().background(Color(0x99000000))
                .clickable(remember { MutableInteractionSource() }, null) { if (!busy) finish() }
                .imePadding(),
            contentAlignment = Alignment.BottomCenter
        ) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).background(Bg)
                    .clickable(remember { MutableInteractionSource() }, null) {}
                    .navigationBarsPadding().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(if (link) "Read later" else "New task", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextMain)
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus), shape = RoundedCornerShape(16.dp),
                    label = { Text(if (link) "Paste a link" else "What needs doing?") },
                    singleLine = link, maxLines = 4,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp),
                    keyboardOptions = if (link) KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done)
                    else KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    colors = fieldColors()
                )
                if (!link) {
                    val today = LocalDate.now()
                    val custom = due != null && due != today && due != today.plusDays(1)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppChip(due == today, { due = if (due == today) null else today }, { Text("Today") })
                        AppChip(due == today.plusDays(1), { due = if (due == today.plusDays(1)) null else today.plusDays(1) }, { Text("Tomorrow") })
                        AppChip(custom, { showPicker = true }, { Text(if (custom) dueLabel(due!!) else "Pick date") })
                    }
                }
                Button(
                    onClick = ::submit, enabled = text.isNotBlank() && !busy, shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = OnPrimary),
                    modifier = Modifier.fillMaxWidth().height(50.dp)
                ) {
                    Text(
                        when { busy && link -> "Saving…"; link -> "Save link"; else -> "Add task" },
                        fontWeight = FontWeight.Bold, fontSize = 16.sp
                    )
                }
            }
        }
        if (showPicker) PickDateDialog(due, onPick = { due = it; showPicker = false }, onDismiss = { showPicker = false })
    }

    companion object {
        const val EXTRA_LINK = "link"
    }
}
