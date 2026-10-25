package uk.tomlowndes.capture

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Tab(val label: String, val icon: String) {
    DECK("Deck", "◎"), CAPTURE("Capture", "＋"), TASKS("Tasks", "✓"), READ("Read", "📖"), PROJECTS("Projects", "💡")
}

class MainActivity : ComponentActivity() {
    private val prefs by lazy { AppPrefs(this) }

    /** Set by notification and widget taps (a tab, plus the article for Read) and read by [App]. */
    private var openRequest by mutableStateOf<Pair<Tab, String?>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.action == Intent.ACTION_SEND) {
            handleShare(intent)
            return
        }
        readOpenRequest(intent)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        setContent {
            CaptureTheme {
                App(prefs, openRequest) { openRequest = null }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readOpenRequest(intent)
    }

    private fun readOpenRequest(i: Intent?) {
        val tab = i?.getStringExtra("tab")?.let { name -> Tab.values().firstOrNull { it.name.equals(name, true) } } ?: return
        openRequest = tab to i.getStringExtra("slot")
    }

    /** Share-sheet entry: fetch the page, save it to Read It Later, then close. Shows a brief "Saving" screen. */
    private fun handleShare(i: Intent) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        setContent {
            CaptureTheme {
                Box(Modifier.fillMaxSize().background(Bg), contentAlignment = Alignment.Center) {
                    Text("Saving to Read It Later…", color = Muted, fontSize = 16.sp)
                }
            }
        }
        val text = i.getStringExtra(Intent.EXTRA_TEXT).orEmpty().trim()
        val url = Regex("https?://\\S+").find(text)?.value ?: text
        val title = i.getStringExtra(Intent.EXTRA_SUBJECT) ?: text.replace(url, "").trim().ifBlank { null }
        val vault = prefs.vault
        lifecycleScope.launch {
            val msg = when {
                vault == null -> "Open Flight Deck once and pick your vault first"
                url.isBlank() -> "Nothing to save"
                else -> withContext(Dispatchers.IO) { runCatching { ReadLater.save(applicationContext, vault, url, title) } }
                    .fold({ if (it) "Saved page to Read It Later" else "Saved link only (couldn't fetch the page)" },
                        { "Failed: ${it.message}" })
            }
            Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
            finish()
        }
    }
}

@Composable
fun App(prefs: AppPrefs, request: Pair<Tab, String?>?, onRequestHandled: () -> Unit) {
    val ctx = LocalContext.current
    var vault by remember { mutableStateOf(prefs.vault) }
    var tab by remember { mutableStateOf(request?.first ?: Tab.DECK) }
    var settings by remember { mutableStateOf(false) }
    var cards by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf(false) }
    var openIdea by remember { mutableStateOf<String?>(null) }
    var openArticle by remember { mutableStateOf<ReadRef?>(null) }
    val snack = remember { SnackbarHostState() }

    val askNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { Reminders.scheduleAll(ctx, prefs) }
    LaunchedEffect(Unit) {
        Reminders.scheduleAll(ctx, prefs)
        if (Build.VERSION.SDK_INT >= 33 && !Reminders.canNotify(ctx) && (prefs.morningOn || prefs.eveningOn) && !prefs.askedNotif) {
            prefs.askedNotif = true
            askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(request) {
        request?.let {
            tab = it.first; settings = false; cards = false; search = false; openIdea = null
            // From the Read widget the second part is the article's file name in the inbox.
            openArticle = if (it.first == Tab.READ) it.second?.let { f -> ReadRef(f, archived = false) } else null
            onRequestHandled()
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            ctx.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            prefs.vault = uri
            vault = uri
        }
    }

    BackHandler(enabled = cards || settings || search || openIdea != null || openArticle != null) {
        when {
            cards -> cards = false
            settings -> settings = false
            search -> search = false
            openArticle != null -> openArticle = null
            else -> openIdea = null
        }
    }

    val hideNav = (tab == Tab.PROJECTS && openIdea != null) || (tab == Tab.READ && openArticle != null)
    Scaffold(
        containerColor = Bg,
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = { if (!hideNav) NavBar(if (settings || search) null else tab) { tab = it; settings = false; cards = false; search = false } }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when {
                cards -> CardsScreen(vault, snack) { cards = false }
                settings -> SettingsScreen(vault, prefs, onPickVault = { picker.launch(null) }, onOpenCards = { cards = true })
                search -> SearchScreen(vault, snack) { search = false }
                tab == Tab.DECK -> DeckScreen(vault, prefs, snack, onOpenTab = { tab = it }, onOpenSearch = { search = true }) { settings = true }
                tab == Tab.CAPTURE -> CaptureScreen(vault, snack)
                tab == Tab.TASKS -> TasksScreen(vault, prefs, snack) { _, _ -> } // Focus lands in its own step
                tab == Tab.PROJECTS -> IdeasScreen(vault, prefs, snack, openIdea) { openIdea = it }
                else -> ReadScreen(vault, prefs, snack, openArticle) { openArticle = it }
            }
        }
    }
}

@Composable
private fun NavBar(selected: Tab?, onSelect: (Tab) -> Unit) {
    Column(Modifier.fillMaxWidth().background(NavBg)) {
        HorizontalDivider(color = Outline)
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().height(66.dp),
            horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically
        ) {
            Tab.values().forEach { t ->
                val on = t == selected
                Column(Modifier.clickable { onSelect(t) }.padding(horizontal = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.clip(CircleShape).background(if (on) PrimaryTint else Color.Transparent).padding(horizontal = 14.dp, vertical = 3.dp)) {
                        Text(t.icon, fontSize = 18.sp, color = if (on) Primary else Muted)
                    }
                    Text(t.label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (on) Primary else Muted)
                }
            }
        }
    }
}
