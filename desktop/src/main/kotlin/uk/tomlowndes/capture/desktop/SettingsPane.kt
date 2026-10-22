package uk.tomlowndes.capture.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg

@Composable
fun SettingsPane(state: AppState) {
    val p = state.prefs
    var recurring by remember { mutableStateOf(p.recurring) }
    var ideas by remember { mutableStateOf(p.ideasDir) }
    var images by remember { mutableStateOf(p.imagesDir) }
    var autostart by remember { mutableStateOf(Autostart.enabled()) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 26.dp)) {
        H1("Settings")
        Column(Modifier.widthIn(max = 720.dp).padding(top = 10.dp)) {
            Setting("Vault folder", p.vault?.absolutePath ?: "Not picked yet") { Chip("Change…", small = true) { pickVault(state) } }
            Setting("Quick capture shortcut",
                if (state.hotkeyFailed) "Another app is using this one, pick another" else "Opens the capture popup from any app") {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Hotkey.CHOICES.chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEach { c -> Chip(c.label, c.label == state.hotkeyLabel, small = true) { p.hotkey = c.label; state.hotkeyLabel = c.label } }
                        }
                    }
                }
            }
            Setting("Start with Windows", "Sits in the tray, ready for the shortcut") {
                Toggle(autostart) { on -> runCatching { Autostart.set(on) }.onSuccess { autostart = on }.onFailure { state.say("Couldn't change it: ${it.message}") } }
            }
            Setting("Repeat recurring tasks", "Ticking a 🔁 task adds its next one, like the Tasks plugin") {
                Toggle(recurring) { recurring = it; p.recurring = it }
            }
            Text("FOLDERS", fontSize = 12.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold, color = Muted, modifier = Modifier.padding(top = 26.dp, bottom = 4.dp))
            FolderSetting("Ideas", ideas, "03 - Projects") { ideas = it; p.ideasDir = it }
            FolderSetting("Images", images, "99 - Meta/Assets") { images = it; p.imagesDir = it }
            Sub("The same folders as the phone app.", Modifier.padding(top = 10.dp))
        }
    }
}

@Composable
private fun Setting(title: String, sub: String, control: @Composable () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 20.dp)) {
                Text(title, fontSize = 14.5.sp, color = TextMain)
                Text(sub, fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 2.dp))
            }
            control()
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Outline))
    }
}

@Composable
private fun FolderSetting(title: String, value: String, placeholder: String, onChange: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 14.sp, color = TextMain, modifier = Modifier.width(140.dp))
        Field(value, onChange, Modifier.weight(1f), placeholder)
    }
}

/** The per-user Run key, so the app starts (hidden, in the tray) when you sign in. */
object Autostart {
    private const val KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    private const val NAME = "Vault Capture"

    /** The installed launcher (set by the packaged app); null when run from Gradle. */
    private val exe: String? get() = System.getProperty("jpackage.app-path")

    fun enabled(): Boolean = runCatching { Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, KEY, NAME) }.getOrDefault(false)

    fun set(on: Boolean) {
        if (on) {
            val path = exe ?: error("only works in the installed app")
            Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, KEY, NAME, "\"$path\" --hidden")
        } else if (enabled()) {
            Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, KEY, NAME)
        }
    }
}
